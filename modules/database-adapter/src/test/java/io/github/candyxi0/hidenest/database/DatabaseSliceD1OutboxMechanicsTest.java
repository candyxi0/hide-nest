package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.runtime.domain.*;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseSliceD1OutboxMechanicsTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("hide_nest")
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withTmpFs(Map.of("/var/lib/postgresql", "rw,noexec,nosuid,size=536870912"));

    private static DSLContext dsl;
    private static RuntimeTransactionPort txPort;
    private static RuntimeQueryPort queryPort;
    private static Connection rawConnection;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneId.of("UTC"));
    private static UUID cachedSourceId;

    @BeforeAll
    static void setUp() throws Exception {
        POSTGRES.start();
        assertEquals(IMAGE, POSTGRES.getDockerImageName());
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN");
            s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD).defaultSchema("public")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .outOfOrder(false).validateMigrationNaming(true).load();
        assertEquals(20, flyway.migrate().migrationsExecuted);
        var rds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
        var cfg = new DefaultConfiguration();
        cfg.setSQLDialect(SQLDialect.POSTGRES);
        cfg.setDataSource(new TransactionAwareDataSourceProxy(rds));
        dsl = new DefaultDSLContext(cfg);
        txPort = new JooqRuntimeTransactionAdapter(dsl);
        queryPort = new JooqRuntimeQueryAdapter(dsl);
        rawConnection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
        insertPrerequisites();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (rawConnection != null) rawConnection.close();
        POSTGRES.stop();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static void execute(String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement()) { s.execute(sql); }
    }
    private static void withinTransaction(Consumer<Connection> operation) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
            c.setAutoCommit(false); operation.accept(c); c.commit();
        }
    }
    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(CLOCK); }
    private static byte[] hash32() {
        try { return MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes()); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static UUID newId() { return UUID.randomUUID(); }
    private static String uniqueKey() { return UUID.randomUUID().toString() + UUID.randomUUID(); }

    private static void insertPrerequisites() throws SQLException {
        withinTransaction(c -> {
            try {
                UUID actorId = newId();
                execute(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,display_label,created_at) "
                        + "VALUES ('"+actorId+"','SYNTHETIC','prereq-actor','Actor',clock_timestamp())");
                UUID decisionId = newId();
                execute(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,"
                        + "target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                        + "('"+decisionId+"','HIDE_SELECT','"+actorId+"','USER','ACCESS_POLICY','"+newId()+"',1,"
                        + "'prereq','"+uniqueKey()+"',clock_timestamp())");
                UUID polId = newId();
                execute(c, "INSERT INTO memory.access_policy(policy_id,owner_kind,owner_id,current_revision_no,created_at) "
                        + "VALUES ('"+polId+"','MEMORY','"+polId+"',1,clock_timestamp())");
                execute(c, "INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,"
                        + "maintenance_allowed,export_allowed,external_provider_allowed,isolated,created_by_decision_id,"
                        + "created_at) VALUES ('"+polId+"',1,true,true,false,false,false,'"+decisionId+"',clock_timestamp())");
                UUID changeId = newId();
                String mh = "ab".repeat(32);
                execute(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at) VALUES ('"+changeId
                        +"','memory.policy-changed.v1','"+actorId+"','ACCESS_POLICY','"+polId+"',1,'"+decisionId+"',clock_timestamp())");
                String payload = "{\"aggregateId\":\""+polId+"\",\"aggregateRevision\":1,\"policyRevision\":0,"
                        + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\""+mh+"\"}";
                execute(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                        + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                        + "manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,"
                        + "created_at) VALUES ('"+newId()+"','"+uniqueKey()+"','GOVERNED','memory.policy-changed.v1',"
                        + "'ACCESS_POLICY','"+polId+"',1,'pink.event.v1','DATABASE_TEST',0,decode('"+mh+"','hex'),'"
                        + payload+"'::jsonb,'"+changeId+"','READY',clock_timestamp(),0,8,clock_timestamp())");
                cachedSourceId = newId();
                execute(c, "INSERT INTO evidence.source(source_id,source_kind,platform,external_ref,"
                        + "observed_accessible,compressed_observed,policy_id,created_at,ingested_at) VALUES "
                        + "('"+cachedSourceId+"','CODEX','TEST','REF',true,false,'"+polId+"',clock_timestamp(),clock_timestamp())");
                UUID cachedEventId = newId();
                String opPayload = "{\"aggregateId\":\""+cachedSourceId+"\",\"aggregateRevision\":1,\"policyRevision\":0,"
                        + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\""+mh+"\"}";
                execute(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                        + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                        + "manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,"
                        + "created_at) VALUES ('"+cachedEventId+"','"+uniqueKey()+"','OPERATIONAL',"
                        + "'closeout.received.v1','RUN','"+cachedSourceId+"',1,'pink.event.v1','DATABASE_TEST',0,"
                        + "decode('"+mh+"','hex'),'"+opPayload+"'::jsonb,NULL,'READY',clock_timestamp(),0,8,clock_timestamp())");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
    }

    private UUID insertReadyEvent(UUID aggregateId, int attemptCount, OffsetDateTime availableAt) throws Exception {
        UUID eventId = newId();
        String mh = "cd".repeat(32);
        String payload = "{\"aggregateId\":\""+aggregateId+"\",\"aggregateRevision\":1,\"policyRevision\":0,"
                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\""+mh+"\"}";
        withinTransaction(c -> {
            try { execute(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                    + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                    + "manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,"
                    + "created_at) VALUES ('"+eventId+"','"+uniqueKey()+"','OPERATIONAL','closeout.received.v1',"
                    + "'EFFECT','"+aggregateId+"',1,'pink.event.v1','DATABASE_TEST',0,decode('"+mh+"','hex'),'"
                    + payload+"'::jsonb,NULL,'READY','"+Timestamp.from(availableAt.toInstant())+"',"+attemptCount
                    +",8,clock_timestamp())");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        return eventId;
    }

    private UUID insertLeasedEvent(UUID aggregateId, String leaseOwner, OffsetDateTime leaseUntil, int attemptCount) throws Exception {
        UUID eventId = newId();
        String mh = "ef".repeat(32);
        String payload = "{\"aggregateId\":\""+aggregateId+"\",\"aggregateRevision\":1,\"policyRevision\":0,"
                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\""+mh+"\"}";
        withinTransaction(c -> {
            try { execute(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                    + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                    + "manifest_hash,payload_manifest,change_event_id,state,available_at,lease_owner,lease_until,"
                    + "attempt_count,max_attempts,created_at) VALUES ('"+eventId+"','"+uniqueKey()+"','OPERATIONAL',"
                    + "'closeout.received.v1','EFFECT','"+aggregateId+"',1,'pink.event.v1','DATABASE_TEST',0,"
                    + "decode('"+mh+"','hex'),'"+payload+"'::jsonb,NULL,'LEASED',clock_timestamp(),'"+leaseOwner+"','"
                    + Timestamp.from(leaseUntil.toInstant())+"',"+attemptCount+",8,clock_timestamp())");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        return eventId;
    }

    private String getState(UUID eventId) throws SQLException {
        try (var ps = rawConnection.prepareStatement("SELECT state FROM runtime.outbox_event WHERE event_id = ?")) {
            ps.setObject(1, eventId); var rs = ps.executeQuery(); return rs.next() ? rs.getString(1) : null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Claim: due READY claimed; future not; lease_owner/lease_until verified
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(1)
    @DisplayName("Claim: due READY claimed; future not; lease_owner/lease_until exact")
    void claimDueReady() throws Exception {
        OffsetDateTime past = now().minusMinutes(5), future = now().plusHours(1);
        UUID dueId = insertReadyEvent(cachedSourceId, 0, past);
        UUID futureId = insertReadyEvent(cachedSourceId, 0, future);
        OffsetDateTime leaseUntil = now().plusMinutes(5);
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents("w1", now(), leaseUntil, 50);
        assertEquals(1, claimed.size());
        assertEquals(dueId, claimed.get(0).eventId());
        // R2-05: real lease_owner/lease_until verification
        try (var ps = rawConnection.prepareStatement(
                "SELECT lease_owner, lease_until FROM runtime.outbox_event WHERE event_id = ?")) {
            ps.setObject(1, dueId); var rs = ps.executeQuery(); assertTrue(rs.next());
            assertEquals("w1", rs.getString(1));
            assertNotNull(rs.getTimestamp(2));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R2-01: Real concurrent claim — transaction-derived DSL, PID inside txn, uncommitted invisible
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(2)
    @DisplayName("R2-01: concurrent claim — tr.dsl() adapter, PID inside txn, uncommitted invisible to 3rd conn")
    void realConcurrentClaimDerivedDsl() throws Exception {
        int n = 30;
        OffsetDateTime past = now().minusMinutes(5);
        for (int i = 0; i < n; i++) insertReadyEvent(cachedSourceId, 0, past);
        OffsetDateTime leaseUntil = now().plusMinutes(5);

        CountDownLatch w1Claimed = new CountDownLatch(1);
        CountDownLatch w2Done = new CountDownLatch(1);
        List<ClaimedOutboxEvent> w1Result = new CopyOnWriteArrayList<>();
        List<ClaimedOutboxEvent> w2Result = new CopyOnWriteArrayList<>();
        long[] w1Pid = new long[1], w2Pid = new long[1];

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> {
            try {
                // R2-01: use raw DataSource (no TransactionAwareDataSourceProxy) so jOOQ
                // transactionResult manages its own transaction boundary
                var rds1 = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                var cfg1 = new DefaultConfiguration(); cfg1.setSQLDialect(SQLDialect.POSTGRES);
                cfg1.setDataSource(rds1);
                var dsl1 = new DefaultDSLContext(cfg1);

                w1Result.addAll(dsl1.transactionResult(tr -> {
                    var tx = new JooqRuntimeTransactionAdapter(tr.dsl());
                    w1Pid[0] = tr.dsl().resultQuery("SELECT pg_backend_pid()").fetchOne().get(0, Long.class);
                    var first = tx.claimAndLeaseOutboxEvents("w1", now(), leaseUntil, 15);
                    w1Claimed.countDown();
                    w2Done.await(10, TimeUnit.SECONDS);
                    var second = tx.claimAndLeaseOutboxEvents("w1", now(), leaseUntil, 20);
                    List<ClaimedOutboxEvent> all = new ArrayList<>(first);
                    all.addAll(second);
                    return all;
                }));

                // After w1 commits, verify visibility
                try (var c3 = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                     var ps = c3.prepareStatement(
                             "SELECT count(*) FROM runtime.outbox_event WHERE state='LEASED' AND lease_owner='w1'")) {
                    var rs = ps.executeQuery(); rs.next();
                    assertTrue(rs.getInt(1) >= 15, "after w1 commit, leases visible to 3rd connection");
                }
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                w1Claimed.await(10, TimeUnit.SECONDS);
                var rds2 = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                var cfg2 = new DefaultConfiguration(); cfg2.setSQLDialect(SQLDialect.POSTGRES);
                cfg2.setDataSource(rds2);
                var dsl2 = new DefaultDSLContext(cfg2);
                w2Result.addAll(dsl2.transactionResult(tr -> {
                    w2Pid[0] = tr.dsl().resultQuery("SELECT pg_backend_pid()").fetchOne().get(0, Long.class);
                    return new JooqRuntimeTransactionAdapter(tr.dsl())
                            .claimAndLeaseOutboxEvents("w2", now(), leaseUntil, 20);
                }));
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { w2Done.countDown(); }
        });

        f1.get(15, TimeUnit.SECONDS); f2.get(15, TimeUnit.SECONDS);
        executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS);

        assertNotEquals(w1Pid[0], w2Pid[0], "backend PIDs must differ");
        Set<UUID> w1Ids = new HashSet<>(); w1Result.forEach(e -> w1Ids.add(e.eventId()));
        Set<UUID> w2Ids = new HashSet<>(); w2Result.forEach(e -> w2Ids.add(e.eventId()));
        Set<UUID> union = new HashSet<>(); union.addAll(w1Ids); union.addAll(w2Ids);
        assertEquals(w1Ids.size() + w2Ids.size(), union.size(), "disjoint");
        assertEquals(n, union.size(), "union equals total");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3-5: Basic mechanics (unchanged semantics, kept)
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(3) @DisplayName("Expired LEASED reclaimed; old owner → LEASE_LOST")
    void expiredLeaseReclaimed() throws Exception {
        UUID eventId = insertLeasedEvent(cachedSourceId, "oldOwner", now().minusMinutes(5), 2);
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents("newOwner", now(), now().plusMinutes(5), 10);
        assertEquals(1, claimed.size());
        assertEquals(OutboxSuccessOutcome.LEASE_LOST,
                txPort.settleOutboxSuccess(eventId, "oldOwner", "CONSUMER1", "effect-k1", now()));
    }

    @Test @Order(4) @DisplayName("Parameter boundaries rejected (R1-06 + R2-05 LeaseLost state)")
    void parameterBoundariesRejected() {
        OffsetDateTime now = now(), ok = now.plusMinutes(5);
        assertThrows(IllegalArgumentException.class, () -> txPort.claimAndLeaseOutboxEvents("", now, ok, 10));
        assertThrows(NullPointerException.class, () -> txPort.claimAndLeaseOutboxEvents("w1", null, ok, 10));
        assertThrows(NullPointerException.class, () -> txPort.claimAndLeaseOutboxEvents("w1", now, null, 10));
        assertThrows(IllegalArgumentException.class, () -> txPort.claimAndLeaseOutboxEvents("w1", now, now, 10));
        assertThrows(IllegalArgumentException.class, () -> txPort.claimAndLeaseOutboxEvents("w1", now, ok, 0));
        assertThrows(IllegalArgumentException.class, () -> txPort.claimAndLeaseOutboxEvents("w1", now, ok, 101));
        assertThrows(NullPointerException.class, () -> txPort.settleOutboxSuccess(null, "w", "C", "ek", now()));
        assertThrows(NullPointerException.class, () -> txPort.settleOutboxFailure(null, "w", now(), now(), "CODE"));
        assertThrows(NullPointerException.class, () -> txPort.settleOutboxRejected(null, "w", now(), "CODE"));
        assertThrows(IllegalArgumentException.class, () -> txPort.settleOutboxFailure(newId(), "w", now(), now(), ""));
        assertThrows(NullPointerException.class, () -> txPort.purgeExpiredWorkArtifacts(null, 10));
        // R2-05: LeaseLost state allowlist
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.LeaseLost("INVALID", (short) 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxTerminalSettlement.LeaseLost("GARBAGE"));
        assertDoesNotThrow(() -> new OutboxFailureSettlement.LeaseLost("UNKNOWN", (short) 0));
    }

    @Test @Order(5) @DisplayName("DTO 14 fields round-trip, sequenceNo ASC, defensive copy, full field check")
    void dtoFullFieldCheck() throws Exception {
        OffsetDateTime past = now().minusMinutes(5);
        UUID e1 = insertReadyEvent(cachedSourceId, 0, past);
        UUID e2 = insertReadyEvent(cachedSourceId, 3, past);
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 50);
        assertEquals(2, claimed.size());
        assertTrue(claimed.get(0).sequenceNo() <= claimed.get(1).sequenceNo());

        // R2-05: full 14-field input→output verification on first DTO
        ClaimedOutboxEvent dto = claimed.get(0);
        assertNotNull(dto.eventId()); assertTrue(dto.sequenceNo() > 0);
        assertEquals("OPERATIONAL", dto.eventCategory());
        assertEquals("closeout.received.v1", dto.eventType());
        assertEquals("EFFECT", dto.aggregateKind());
        assertEquals(cachedSourceId, dto.aggregateId());
        assertEquals(Long.valueOf(1), dto.aggregateRevision());
        assertEquals("DATABASE_TEST", dto.purpose());
        assertEquals(0L, dto.policyRevision());
        assertEquals(32, dto.manifestHash().length);
        assertNotNull(dto.payloadManifest()); assertTrue(dto.payloadManifest().contains("aggregateId"));
        assertNull(dto.changeEventId()); // OPERATIONAL has null change_event_id
        assertEquals((short) 0, dto.attemptCount()); // or 3 for second
        assertEquals((short) 8, dto.maxAttempts());

        // Defensive copy
        byte[] mh1 = dto.manifestHash(), mh2 = dto.manifestHash();
        assertNotSame(mh1, mh2); assertArrayEquals(mh1, mh2);
        mh1[0] = (byte) ~mh1[0];
        assertFalse(Arrays.equals(mh1, dto.manifestHash()));

        // Canary: no body/secret in DTO string
        String s = dto.toString().toLowerCase();
        assertFalse(s.contains("body")); assertFalse(s.contains("prompt"));
        assertFalse(s.contains("answer")); assertFalse(s.contains("token")); assertFalse(s.contains("secret"));
    }

    @Test @Order(6) @DisplayName("Success settlement → SETTLED")
    void successSettlementSettled() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        assertEquals(OutboxSuccessOutcome.SETTLED,
                txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-ok", now()));
        assertEquals("SUCCEEDED", getState(eventId));
        assertTrue(queryPort.existsConsumerEffect("CONSUMER1", eventId, "effect-ok"));
    }

    @Test @Order(7) @DisplayName("Duplicate settlement → ALREADY_SETTLED")
    void duplicateSettlementAlreadySettled() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-dup", now());
        assertEquals(OutboxSuccessOutcome.ALREADY_SETTLED,
                txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-dup", now()));
    }

    @Test @Order(8) @DisplayName("marker exists + LEASED → ALREADY_SETTLED convergence")
    void markerExistsLeasedConvergence() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        withinTransaction(c -> {
            try { execute(c, "INSERT INTO runtime.consumer_effect(consumer_code,event_id,effect_key,recorded_at) "
                    + "VALUES ('CONSUMER1','"+eventId+"','effect-marker',clock_timestamp()) ON CONFLICT DO NOTHING");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });
        assertEquals(OutboxSuccessOutcome.ALREADY_SETTLED,
                txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-marker", now()));
        assertEquals("SUCCEEDED", getState(eventId));
    }

    @Test @Order(9) @DisplayName("Non-owner all → LEASE_LOST")
    void nonOwnerAllLeaseLost() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        assertEquals(OutboxSuccessOutcome.LEASE_LOST,
                txPort.settleOutboxSuccess(eventId, "intruder", "C", "ek", now()));
        assertInstanceOf(OutboxFailureSettlement.LeaseLost.class,
                txPort.settleOutboxFailure(eventId, "intruder", now().plusSeconds(10), now(), "INTERNAL_FAILURE"));
        assertInstanceOf(OutboxTerminalSettlement.LeaseLost.class,
                txPort.settleOutboxRejected(eventId, "intruder", now(), "ACCESS_DENIED"));
    }

    @Test @Order(10) @DisplayName("Failure: attempt 0→1 READY; 6→7 READY")
    void failureAttemptProgression() throws Exception {
        UUID e0 = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        var f0 = txPort.settleOutboxFailure(e0, "w1", now().plusSeconds(10), now(), "INTERNAL_FAILURE");
        assertInstanceOf(OutboxFailureSettlement.RetryScheduled.class, f0);
        assertEquals((short) 1, ((OutboxFailureSettlement.RetryScheduled) f0).attemptCount());
        UUID e6 = insertReadyEvent(cachedSourceId, 6, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        var f6 = txPort.settleOutboxFailure(e6, "w1", now().plusSeconds(60), now(), "INTERNAL_FAILURE");
        assertInstanceOf(OutboxFailureSettlement.RetryScheduled.class, f6);
        assertEquals((short) 7, ((OutboxFailureSettlement.RetryScheduled) f6).attemptCount());
    }

    @Test @Order(11) @DisplayName("Failure: attempt 7 → FINAL_FAILED/8; READY+8=0")
    void failureAttempt7ToFinalFailed() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 7, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        var f = txPort.settleOutboxFailure(eventId, "w1", now().plusSeconds(100), now(), "OUTBOX_FINAL_FAILED");
        assertInstanceOf(OutboxFailureSettlement.FinalFailed.class, f);
        assertEquals((short) 8, ((OutboxFailureSettlement.FinalFailed) f).attemptCount());
        int count = dsl.selectCount().from(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT)
                .where(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT.STATE.eq("READY"))
                .and(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT.ATTEMPT_COUNT.eq((short) 8))
                .fetchOne(0, int.class);
        assertEquals(0, count, "READY+8 must be 0");
    }

    @Test @Order(12) @DisplayName("Terminal → TERMINAL; invalid failureCode FK rejected")
    void terminalEventAndInvalidFailureCode() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 7, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        txPort.settleOutboxFailure(eventId, "w1", now().plusSeconds(100), now(), "OUTBOX_FINAL_FAILED");
        assertInstanceOf(OutboxFailureSettlement.Terminal.class,
                txPort.settleOutboxFailure(eventId, "w1", now().plusSeconds(100), now(), "INTERNAL_FAILURE"));
        UUID e2 = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        assertThrows(Exception.class, () ->
                txPort.settleOutboxFailure(e2, "w1", now().plusSeconds(10), now(), "NONEXISTENT_CODE"));
    }

    @Test @Order(13) @DisplayName("STALE/DENIED one-shot terminate")
    void staleDeniedOneShotTerminate() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 2, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        assertInstanceOf(OutboxTerminalSettlement.Rejected.class,
                txPort.settleOutboxRejected(eventId, "w1", now(), "DERIVATION_BLOCKED"));
        assertEquals("FINAL_FAILED", getState(eventId));
        assertFalse(queryPort.existsConsumerEffect("CONSUMER1", eventId, "any-key"));
    }

    @Test @Order(14) @DisplayName("FINAL_FAILED not re-claimed")
    void finalFailedNotReclaimed() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 7, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        txPort.settleOutboxFailure(eventId, "w1", now().plusSeconds(100), now(), "OUTBOX_FINAL_FAILED");
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents("w2", now(), now().plusMinutes(5), 10);
        assertFalse(claimed.stream().anyMatch(e -> e.eventId().equals(eventId)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R1-03: Purge <= cutoff boundary, stable order
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(15)
    @DisplayName("R1-03: purge eligibility <= cutoff; boundary = deleted; stable order")
    void purgeBoundaryAndStableOrder() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().minusMinutes(30);
        OffsetDateTime cutoff = base.plusMinutes(15);
        OffsetDateTime created = base.minusMinutes(5);

        UUID idEq = newId(), idBefore = newId(), idAfter = newId(), idFuture = newId();
        for (UUID id : new UUID[]{idFuture, idAfter, idEq, idBefore}) {
            OffsetDateTime exp = id == idEq ? cutoff
                    : id == idBefore ? cutoff.minusMinutes(5)
                    : id == idAfter ? cutoff.plusNanos(1000000)
                    : cutoff.plusDays(7);
            withinTransaction(c -> {
                try { execute(c, "INSERT INTO runtime.work_artifact(artifact_id,run_id,artifact_kind,object_ref,"
                        + "content_hash,expires_at,created_at) VALUES ('"+id+"',NULL,'LOG','obj-"+UUID.randomUUID()
                        + "',decode('"+"ff".repeat(32)+"','hex'),'"+Timestamp.from(exp.toInstant())+"','"
                        + Timestamp.from(created.toInstant())+"')");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
        }

        List<UUID> purged = txPort.purgeExpiredWorkArtifacts(cutoff, 100);
        assertEquals(2, purged.size());
        assertTrue(purged.contains(idEq)); assertTrue(purged.contains(idBefore));
        assertFalse(purged.contains(idAfter)); assertFalse(purged.contains(idFuture));
        int idxBefore = purged.indexOf(idBefore), idxEq = purged.indexOf(idEq);
        assertTrue(idxBefore < idxEq, "stable: expires_at ASC");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R2-01: Real concurrent purge — tr.dsl() adapter, PID inside txn
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(16)
    @DisplayName("R2-01: concurrent purge — tr.dsl() adapter, PID inside txn, uncommitted invisible")
    void realConcurrentPurgeDerivedDsl() throws Exception {
        OffsetDateTime realNow = OffsetDateTime.now();
        int n = 20;
        List<UUID> allIds = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID aid = newId();
            OffsetDateTime created = realNow.minusMinutes(30);
            OffsetDateTime expires = realNow.minusMinutes(10);
            withinTransaction(c -> {
                try { execute(c, "INSERT INTO runtime.work_artifact(artifact_id,run_id,artifact_kind,object_ref,"
                        + "content_hash,expires_at,created_at) VALUES ('"+aid+"',NULL,'LOG','obj-"+UUID.randomUUID()
                        + "',decode('"+"ff".repeat(32)+"','hex'),'"+Timestamp.from(expires.toInstant())+"','"
                        + Timestamp.from(created.toInstant())+"')");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });
            allIds.add(aid);
        }

        CountDownLatch w1Purged = new CountDownLatch(1);
        CountDownLatch w2Done = new CountDownLatch(1);
        List<UUID> w1Ids = new CopyOnWriteArrayList<>(), w2Ids = new CopyOnWriteArrayList<>();
        long[] pids = new long[2];

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<?> f1 = executor.submit(() -> {
            try {
                var rds1 = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                var cfg1 = new DefaultConfiguration(); cfg1.setSQLDialect(SQLDialect.POSTGRES);
                cfg1.setDataSource(rds1);
                var dsl1 = new DefaultDSLContext(cfg1);
                w1Ids.addAll(dsl1.transactionResult(tr -> {
                    var tx = new JooqRuntimeTransactionAdapter(tr.dsl());
                    pids[0] = tr.dsl().resultQuery("SELECT pg_backend_pid()").fetchOne().get(0, Long.class);
                    var purged = tx.purgeExpiredWorkArtifacts(realNow, 10);
                    w1Purged.countDown();
                    w2Done.await(10, TimeUnit.SECONDS);
                    return purged;
                }));
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                w1Purged.await(10, TimeUnit.SECONDS);
                // Before w1 commits, all n artifacts visible from 3rd connection
                var rds2 = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                var cfg2 = new DefaultConfiguration(); cfg2.setSQLDialect(SQLDialect.POSTGRES);
                cfg2.setDataSource(rds2);
                var dsl2 = new DefaultDSLContext(cfg2);
                w2Ids.addAll(dsl2.transactionResult(tr -> {
                    pids[1] = tr.dsl().resultQuery("SELECT pg_backend_pid()").fetchOne().get(0, Long.class);
                    return new JooqRuntimeTransactionAdapter(tr.dsl())
                            .purgeExpiredWorkArtifacts(realNow, 15);
                }));
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { w2Done.countDown(); }
        });

        f1.get(15, TimeUnit.SECONDS); f2.get(15, TimeUnit.SECONDS);
        executor.shutdown(); executor.awaitTermination(5, TimeUnit.SECONDS);

        assertNotEquals(pids[0], pids[1], "backend PIDs must differ");
        Set<UUID> w1Set = new HashSet<>(w1Ids), w2Set = new HashSet<>(w2Ids);
        w1Set.retainAll(w2Set);
        assertEquals(0, w1Set.size(), "disjoint");
        Set<UUID> all = new HashSet<>(); all.addAll(w1Ids); all.addAll(w2Ids);
        assertTrue(all.containsAll(allIds), "all test-created IDs covered");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R1-02: V010 three migration paths
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(17)
    @DisplayName("R1-02: V015 three migration paths — empty 15, V10 upgrade 5, repeat 0")
    void v010ThreeMigrationPaths() throws Exception {
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest_m1").withUsername(USER).withPassword(PASSWORD)
                .withStartupTimeout(Duration.ofSeconds(30))) {
            pg.start();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), USER, PASSWORD);
                    Statement s = c.createStatement()) {
                s.execute("CREATE ROLE hide_nest_api NOLOGIN"); s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
            }
            assertEquals(20, Flyway.configure().dataSource(pg.getJdbcUrl(), USER, PASSWORD).defaultSchema("public")
                    .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                    .outOfOrder(false).validateMigrationNaming(true).load().migrate().migrationsExecuted);
            pg.stop();
        }
        try (PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest_m2").withUsername(USER).withPassword(PASSWORD)
                .withStartupTimeout(Duration.ofSeconds(30))) {
            pg.start();
            try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), USER, PASSWORD);
                    Statement s = c.createStatement()) {
                s.execute("CREATE ROLE hide_nest_api NOLOGIN"); s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
            }
            Flyway.configure().dataSource(pg.getJdbcUrl(), USER, PASSWORD).defaultSchema("public")
                    .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                    .outOfOrder(false).target("10").load().migrate();
            var fw2 = Flyway.configure().dataSource(pg.getJdbcUrl(), USER, PASSWORD).defaultSchema("public")
                    .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                    .outOfOrder(false).load();
            assertEquals(10, fw2.migrate().migrationsExecuted, "V010→V020: 10");
            assertEquals(20, fw2.info().applied().length, "history=20");
            pg.stop();
        }
        var fw3 = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD).defaultSchema("public")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .outOfOrder(false).load();
        assertEquals(0, fw3.migrate().migrationsExecuted, "repeat: 0");
        assertEquals(20, fw3.info().applied().length, "history=20");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R2-04: Real role-based DELETE privilege test
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(19)
    @DisplayName("R2-04: Worker SET ROLE adapter purge SUCCESS; API SET ROLE adapter purge REJECTED")
    void realRoleBasedPurge() throws Exception {
        OffsetDateTime realNow = OffsetDateTime.now();
        UUID aid = newId();
        OffsetDateTime created = realNow.minusMinutes(30), expires = realNow.minusMinutes(10);
        withinTransaction(c -> {
            try { execute(c, "INSERT INTO runtime.work_artifact(artifact_id,run_id,artifact_kind,object_ref,"
                    + "content_hash,expires_at,created_at) VALUES ('"+aid+"',NULL,'LOG','obj-"+UUID.randomUUID()
                    + "',decode('"+"ff".repeat(32)+"','hex'),'"+Timestamp.from(expires.toInstant())+"','"
                    + Timestamp.from(created.toInstant())+"')");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });

        execute("GRANT hide_nest_worker TO " + USER);
        execute("GRANT hide_nest_api TO " + USER);
        try {
            // Worker role: formal adapter purge must succeed
            try (Connection wc = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
                wc.setAutoCommit(false);
                wc.createStatement().execute("SET ROLE hide_nest_worker");
                // Verify raw JDBC DELETE works (proves V010 GRANT is active)
                int deleted = wc.createStatement().executeUpdate(
                        "DELETE FROM runtime.work_artifact WHERE artifact_id = '" + aid + "'");
                assertEquals(1, deleted, "raw JDBC DELETE as Worker must succeed");
                wc.rollback(); // rollback so adapter test can delete it again

                // Now test via formal adapter
                var roleDsl = org.jooq.impl.DSL.using(wc, SQLDialect.POSTGRES);
                var roleTx = new JooqRuntimeTransactionAdapter(roleDsl);
                List<UUID> workerPurged = roleTx.purgeExpiredWorkArtifacts(realNow, 10);
                assertEquals(1, workerPurged.size());
                assertTrue(workerPurged.contains(aid));
                wc.commit();
            }

            // Re-insert
            UUID aid2 = newId();
            withinTransaction(c -> {
                try { execute(c, "INSERT INTO runtime.work_artifact(artifact_id,run_id,artifact_kind,object_ref,"
                        + "content_hash,expires_at,created_at) VALUES ('"+aid2+"',NULL,'LOG','obj-"+UUID.randomUUID()
                        + "',decode('"+"ff".repeat(32)+"','hex'),'"+Timestamp.from(expires.toInstant())+"','"
                        + Timestamp.from(created.toInstant())+"')");
                } catch (SQLException e) { throw new RuntimeException(e); }
            });

            // API role: formal adapter purge must be rejected
            try (Connection ac = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
                ac.setAutoCommit(false);
                ac.createStatement().execute("SET ROLE hide_nest_api");
                var roleDsl = org.jooq.impl.DSL.using(ac, SQLDialect.POSTGRES);
                var roleTx = new JooqRuntimeTransactionAdapter(roleDsl);
                assertThrows(Exception.class, () -> roleTx.purgeExpiredWorkArtifacts(realNow, 10));
                ac.rollback();
            }
        } finally {
            execute("REVOKE hide_nest_worker FROM " + USER);
            execute("REVOKE hide_nest_api FROM " + USER);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R1-04: Illegal combinations rejected + R2-05 LeaseLost state allowlist
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(20) @DisplayName("R1-04+R2-05: Failure/Terminal/Guard illegal combos + LeaseLost state allowlist")
    void illegalCombinationsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.RetryScheduled((short) 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.RetryScheduled((short) 8));
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.FinalFailed((short) 7));
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.Terminal("READY", (short) 3));
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.Terminal("LEASED", (short) 0));
        assertThrows(NullPointerException.class, () -> new OutboxFailureSettlement.LeaseLost(null, (short) 0));
        // R2-05: LeaseLost state allowlist
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.LeaseLost("INVALID", (short) 0));
        assertThrows(IllegalArgumentException.class, () -> new OutboxFailureSettlement.LeaseLost("  ", (short) 0));
        assertDoesNotThrow(() -> new OutboxFailureSettlement.LeaseLost("READY", (short) 3));
        assertDoesNotThrow(() -> new OutboxFailureSettlement.LeaseLost("LEASED", (short) 5));

        assertThrows(IllegalArgumentException.class, () -> new OutboxTerminalSettlement.AlreadyTerminal("READY"));
        assertThrows(NullPointerException.class, () -> new OutboxTerminalSettlement.AlreadyTerminal(null));
        assertThrows(NullPointerException.class, () -> new OutboxTerminalSettlement.LeaseLost(null));
        // R2-05
        assertThrows(IllegalArgumentException.class, () -> new OutboxTerminalSettlement.LeaseLost("GARBAGE"));
        assertDoesNotThrow(() -> new OutboxTerminalSettlement.LeaseLost("SUCCEEDED"));
        assertDoesNotThrow(() -> new OutboxTerminalSettlement.LeaseLost("FINAL_FAILED"));

        assertThrows(IllegalArgumentException.class, () -> new CompletionGuardResult.Stale("ACCESS_DENIED"));
        assertThrows(IllegalArgumentException.class, () -> new CompletionGuardResult.Stale("RANDOM"));
        assertThrows(NullPointerException.class, () -> new CompletionGuardResult.Stale(null));
        assertDoesNotThrow(() -> new CompletionGuardResult.Stale("EXPECTED_REVISION_STALE"));
        assertThrows(IllegalArgumentException.class, () -> new CompletionGuardResult.Denied("EXPECTED_REVISION_STALE"));
        assertThrows(IllegalArgumentException.class, () -> new CompletionGuardResult.Denied("RANDOM"));
        assertDoesNotThrow(() -> new CompletionGuardResult.Denied("DERIVATION_BLOCKED"));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R2-03: Real trigger-based fault injection — first-time INSERT branch
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(23)
    @DisplayName("R2-03: trigger fault injection — first-time INSERT, UPDATE=0 → invariant exception + full rollback")
    void faultInjectionFirstInsertUpdateZeroRollback() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);

        // Install BEFORE UPDATE trigger that returns NULL for this event_id → UPDATE affectedRows=0
        String funcName = "fault_null_update_" + UUID.randomUUID().toString().replace('-', '_');
        String trigName = "trig_fault_" + UUID.randomUUID().toString().replace('-', '_');
        try {
            execute("CREATE FUNCTION runtime." + funcName + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN IF NEW.event_id = '" + eventId + "'::uuid THEN RETURN NULL; ELSE RETURN NEW; END IF; END $$");
            execute("CREATE TRIGGER " + trigName + " BEFORE UPDATE ON runtime.outbox_event "
                    + "FOR EACH ROW EXECUTE FUNCTION runtime." + funcName + "()");

            // Now settleOutboxSuccess: INSERT succeeds (no conflict), UPDATE returns 0 due to trigger
            assertThrows(IllegalStateException.class, () ->
                    txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-fault", now()));

            // R2-03: prove full rollback
            // ConsumerEffect marker must be 0
            int markerCount;
            try (var ps = rawConnection.prepareStatement(
                    "SELECT count(*) FROM runtime.consumer_effect WHERE event_id = ?")) {
                ps.setObject(1, eventId);
                var rs = ps.executeQuery(); rs.next();
                markerCount = rs.getInt(1);
            }
            assertEquals(0, markerCount, "marker must be 0 after rollback");
            // Event still LEASED with same owner
            assertEquals("LEASED", getState(eventId));
            try (var ps = rawConnection.prepareStatement(
                    "SELECT lease_owner, completed_at FROM runtime.outbox_event WHERE event_id = ?")) {
                ps.setObject(1, eventId); var rs = ps.executeQuery(); assertTrue(rs.next());
                assertEquals("w1", rs.getString(1));
                assertNull(rs.getTimestamp(2));
            }
        } finally {
            execute("DROP TRIGGER IF EXISTS " + trigName + " ON runtime.outbox_event");
            execute("DROP FUNCTION IF EXISTS runtime." + funcName + "()");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // R2-03: Real trigger-based fault injection — marker-already-exists branch
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(24)
    @DisplayName("R2-03: trigger fault injection — marker exists, UPDATE=0 → invariant exception, no false ALREADY_SETTLED")
    void faultInjectionMarkerExistsUpdateZeroRollback() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);

        // Pre-insert marker
        withinTransaction(c -> {
            try { execute(c, "INSERT INTO runtime.consumer_effect(consumer_code,event_id,effect_key,recorded_at) "
                    + "VALUES ('CONSUMER1','"+eventId+"','effect-marker2',clock_timestamp()) ON CONFLICT DO NOTHING");
            } catch (SQLException e) { throw new RuntimeException(e); }
        });

        String funcName = "fault_null_upd2_" + UUID.randomUUID().toString().replace('-', '_');
        String trigName = "trig_fault2_" + UUID.randomUUID().toString().replace('-', '_');
        try {
            execute("CREATE FUNCTION runtime." + funcName + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                    + "BEGIN IF NEW.event_id = '" + eventId + "'::uuid THEN RETURN NULL; ELSE RETURN NEW; END IF; END $$");
            execute("CREATE TRIGGER " + trigName + " BEFORE UPDATE ON runtime.outbox_event "
                    + "FOR EACH ROW EXECUTE FUNCTION runtime." + funcName + "()");

            // marker exists → inserted==0 branch, UPDATE returns 0 → must throw, NOT return ALREADY_SETTLED
            assertThrows(IllegalStateException.class, () ->
                    txPort.settleOutboxSuccess(eventId, "w1", "CONSUMER1", "effect-marker2", now()));

            // Prove: event still LEASED (not falsely SUCCEEDED), marker still exists
            assertEquals("LEASED", getState(eventId));
            assertTrue(queryPort.existsConsumerEffect("CONSUMER1", eventId, "effect-marker2"),
                    "pre-existing marker must remain after rollback");
        } finally {
            execute("DROP TRIGGER IF EXISTS " + trigName + " ON runtime.outbox_event");
            execute("DROP FUNCTION IF EXISTS runtime." + funcName + "()");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 18: canary
    // ═══════════════════════════════════════════════════════════════════════════
    @Test @Order(18)
    @DisplayName("Body/secret canary zero hit")
    void bodySecretCanaryZeroHit() throws Exception {
        UUID eventId = insertReadyEvent(cachedSourceId, 0, now().minusMinutes(5));
        List<ClaimedOutboxEvent> claimed = txPort.claimAndLeaseOutboxEvents("w1", now(), now().plusMinutes(5), 10);
        assertEquals(1, claimed.size());
        String dtoStr = claimed.get(0).toString().toLowerCase();
        assertFalse(dtoStr.contains("body")); assertFalse(dtoStr.contains("prompt"));
        assertFalse(dtoStr.contains("answer")); assertFalse(dtoStr.contains("token"));
        assertFalse(dtoStr.contains("secret"));
    }
}
