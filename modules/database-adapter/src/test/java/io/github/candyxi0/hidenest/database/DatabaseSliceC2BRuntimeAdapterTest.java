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
class DatabaseSliceC2BRuntimeAdapterTest {

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

    // Cached prerequisite IDs
    private static UUID cachedPolicyId;
    private static UUID cachedSourceId;
    private static UUID cachedSourceUnitId1;
    private static UUID cachedSourceUnitId2;
    private static UUID cachedOutboxEventId;

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
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .load();
        assertEquals(21, flyway.migrate().migrationsExecuted);

        var rds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
        DataSource pds = new TransactionAwareDataSourceProxy(rds);
        var cfg = new DefaultConfiguration();
        cfg.setSQLDialect(SQLDialect.POSTGRES);
        cfg.setDataSource(pds);
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

    // ── helpers ────────────────────────────────────────────────────────────

    private static void execute(String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement()) {
            s.execute(sql);
        }
    }

    private static void withinTransaction(Consumer<Connection> operation) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
            c.setAutoCommit(false);
            operation.accept(c);
            c.commit();
        }
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(CLOCK);
    }

    private static byte[] hash32() {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(UUID.randomUUID().toString().getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static UUID newId() {
        return UUID.randomUUID();
    }

    private static String uniqueKey() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private static void insertPrerequisites() throws SQLException {
        withinTransaction(c -> {
            try {
                // Actor ref (prerequisite for decision)
                UUID actorId = newId();
                execute(c, ("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,"
                        + "display_label,created_at) VALUES "
                        + "('" + actorId + "','SYNTHETIC','prereq-actor','Prereq Actor',clock_timestamp())"));

                // Decision (prerequisite for governed outbox)
                UUID decisionId = newId();
                execute(c, ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                        + "target_kind,target_id,target_revision_ref,"
                        + "authorization_ref,idempotency_key,created_at) VALUES "
                        + "('" + decisionId + "','HIDE_SELECT','" + actorId + "','USER',"
                        + "'ACCESS_POLICY','" + newId() + "',1,"
                        + "'prereq-proof','" + uniqueKey() + "',clock_timestamp())"));

                // Access policy + revision
                cachedPolicyId = newId();
                execute(c, ("INSERT INTO memory.access_policy(policy_id,owner_kind,owner_id,"
                        + "current_revision_no,created_at) VALUES "
                        + "('" + cachedPolicyId + "','MEMORY','" + cachedPolicyId + "',1,clock_timestamp())"));
                execute(c, ("INSERT INTO memory.access_policy_revision(policy_id,revision_no,"
                        + "companion_allowed,maintenance_allowed,export_allowed,external_provider_allowed,"
                        + "isolated,created_by_decision_id,created_at) VALUES "
                        + "('" + cachedPolicyId + "',1,true,true,false,false,false,'"
                        + decisionId + "',clock_timestamp())"));

                // Governed outbox for ACCESS_POLICY (required by trigger)
                String manifestHex = "ab".repeat(32);
                UUID changeId = newId();
                execute(c, ("INSERT INTO memory.change_event(change_event_id,event_type,actor_id,"
                        + "target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES "
                        + "('" + changeId + "','memory.policy-changed.v1','" + actorId + "',"
                        + "'ACCESS_POLICY','" + cachedPolicyId + "',1,'" + decisionId + "',clock_timestamp())"));
                String payload = "{\"aggregateId\":\"" + cachedPolicyId
                        + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                        + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + manifestHex + "\"}";
                execute(c, ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,"
                        + "event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,"
                        + "purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,"
                        + "state,available_at,attempt_count,max_attempts,created_at) VALUES "
                        + "('" + newId() + "','" + uniqueKey() + "','GOVERNED',"
                        + "'memory.policy-changed.v1','ACCESS_POLICY','" + cachedPolicyId
                        + "',1,'pink.event.v1','DATABASE_TEST',0,decode('" + manifestHex + "','hex'),"
                        + "'" + payload + "'::jsonb,'" + changeId + "','READY',"
                        + "clock_timestamp(),0,8,clock_timestamp())"));

                // Source for FK references
                cachedSourceId = newId();
                execute(c, ("INSERT INTO evidence.source(source_id,source_kind,platform,external_ref,"
                        + "observed_accessible,compressed_observed,policy_id,created_at,ingested_at) VALUES "
                        + "('" + cachedSourceId + "','CODEX','TEST_PLATFORM','TEST_REF',"
                        + "true,false,'" + cachedPolicyId + "',clock_timestamp(),clock_timestamp())"));

                // Source units for capture_scope_unit FK
                cachedSourceUnitId1 = newId();
                cachedSourceUnitId2 = newId();
                execute(c, ("INSERT INTO evidence.source_unit(source_unit_id,source_id,external_unit_ref,"
                        + "source_version,ordinal,occurred_at,created_at) VALUES "
                        + "('" + cachedSourceUnitId1 + "','" + cachedSourceId + "','ext-u-1','v1',1,"
                        + "clock_timestamp(),clock_timestamp())"));
                execute(c, ("INSERT INTO evidence.source_unit(source_unit_id,source_id,external_unit_ref,"
                        + "source_version,ordinal,occurred_at,created_at) VALUES "
                        + "('" + cachedSourceUnitId2 + "','" + cachedSourceId + "','ext-u-2','v1',2,"
                        + "clock_timestamp(),clock_timestamp())"));

                // Outbox event for consumer_effect FK (OPERATIONAL to avoid change_event FK)
                cachedOutboxEventId = newId();
                String opPayload = "{\"aggregateId\":\"" + cachedSourceId
                        + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                        + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + manifestHex + "\"}";
                execute(c, ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,"
                        + "event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,"
                        + "purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,"
                        + "state,available_at,attempt_count,max_attempts,created_at) VALUES "
                        + "('" + cachedOutboxEventId + "','" + uniqueKey() + "','OPERATIONAL',"
                        + "'closeout.received.v1','RUN','" + cachedSourceId + "',1,'pink.event.v1',"
                        + "'DATABASE_TEST',0,decode('" + manifestHex + "','hex'),"
                        + "'" + opPayload + "'::jsonb,NULL,'READY',"
                        + "clock_timestamp(),0,8,clock_timestamp())"));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. CaptureScope + units same transaction assemble→freeze→commit, ordinal round-trip
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    @DisplayName("CaptureScope + units assemble→freeze→commit with ordinal round-trip")
    void captureScopeWithUnitsAssembleFreezeCommitAndOrdinalRoundTrip() {
        UUID scopeId = newId();
        OffsetDateTime ts = now();
        byte[] manifestHash = hash32();

        // Assemble within transaction: insert scope (frozen_at=null), insert units, freeze
        dsl.transaction(config -> {
            var txDsl = config.dsl();
            var tx = new JooqRuntimeTransactionAdapter(txDsl);
            tx.insertCaptureScope(new CaptureScope(scopeId, cachedSourceId, 0L, 5L,
                    "V1", "FULL_COVERAGE", null, manifestHash, ts));
            tx.insertCaptureScopeUnits(List.of(
                    new CaptureScopeUnit(scopeId, cachedSourceUnitId2, 20L, "EXCLUDED"),
                    new CaptureScopeUnit(scopeId, cachedSourceUnitId1, 10L, null)));
            assertTrue(tx.freezeCaptureScope(scopeId, ts.plusMinutes(1)));
        });

        // Verify round-trip
        CaptureScope scope = queryPort.findCaptureScopeById(scopeId);
        assertNotNull(scope);
        assertEquals(scopeId, scope.scopeId());
        assertEquals(cachedSourceId, scope.sourceId());
        assertEquals(0L, scope.fromOrdinal());
        assertEquals(5L, scope.toOrdinal());
        assertEquals("V1", scope.ruleVersion());
        assertEquals("FULL_COVERAGE", scope.coverageCode());
        assertNotNull(scope.frozenAt());
        assertArrayEquals(manifestHash, scope.manifestHash());

        // Unit round-trip by ordinal ASC
        List<CaptureScopeUnit> units = queryPort.findCaptureScopeUnitsByScopeId(scopeId);
        assertEquals(2, units.size());
        assertEquals(cachedSourceUnitId1, units.get(0).sourceUnitId());
        assertEquals(10L, units.get(0).ordinal());
        assertNull(units.get(0).exclusionReason());
        assertEquals(cachedSourceUnitId2, units.get(1).sourceUnitId());
        assertEquals(20L, units.get(1).ordinal());
        assertEquals("EXCLUDED", units.get(1).exclusionReason());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Not-frozen commit truly rejected; cross-source unit truly rejected
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    @DisplayName("Not-frozen commit rejected; cross-source unit rejected")
    void notFrozenCommitRejectedAndCrossSourceUnitRejected() throws Exception {
        // Not-frozen rejection: commit transaction without freeze → DEFERRABLE trigger fires
        UUID scopeId = newId();
        assertThrows(Exception.class, () -> {
            dsl.transaction(config -> {
                var txDsl = config.dsl();
                var tx = new JooqRuntimeTransactionAdapter(txDsl);
                tx.insertCaptureScope(new CaptureScope(scopeId, cachedSourceId, 0L, 1L,
                        "V1", "FULL_COVERAGE", null, hash32(), now()));
                // No freeze → commit trigger rejects
            });
        });

        // Verify scope was NOT persisted (rolled back)
        assertNull(queryPort.findCaptureScopeById(scopeId));

        // Cross-source unit: create a second source with a unit (reuse cached policy)
        UUID srcB = newId();
        UUID unitB = newId();
        withinTransaction(c -> {
            try {
                execute(c, "INSERT INTO evidence.source(source_id,source_kind,platform,external_ref,"
                        + "observed_accessible,compressed_observed,policy_id,created_at,ingested_at) VALUES "
                        + "('" + srcB + "','CODEX','CROSS-PLAT','CROSS-REF',true,false,'"
                        + cachedPolicyId + "',clock_timestamp(),clock_timestamp())");
                execute(c, "INSERT INTO evidence.source_unit(source_unit_id,source_id,external_unit_ref,"
                        + "source_version,ordinal,occurred_at,created_at) VALUES "
                        + "('" + unitB + "','" + srcB + "','cross-unit','v1',1,"
                        + "clock_timestamp(),clock_timestamp())");
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        // Cross-source: try to add unitB (from srcB) to scope (from cachedSourceId)
        // Should be rejected by deferred same-source trigger at commit
        UUID scope2 = newId();
        assertThrows(Exception.class, () -> {
            dsl.transaction(config -> {
                var txDsl = config.dsl();
                var tx = new JooqRuntimeTransactionAdapter(txDsl);
                tx.insertCaptureScope(new CaptureScope(scope2, cachedSourceId, 0L, 1L,
                        "V1", "FULL_COVERAGE", null, hash32(), now()));
                tx.insertCaptureScopeUnits(List.of(
                        new CaptureScopeUnit(scope2, unitB, 1L, null)));
                tx.freezeCaptureScope(scope2, now());
            });
        });
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. freezeCaptureScope CAS: first true, duplicate false, non-existent false
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(3)
    @DisplayName("freezeCaptureScope CAS: first true, duplicate false, non-existent false")
    void freezeCaptureScopeCas() {
        UUID scopeId = newId();
        OffsetDateTime ts = now();
        OffsetDateTime frozenAt = ts.plusMinutes(1);

        dsl.transaction(config -> {
            var txDsl = config.dsl();
            var tx = new JooqRuntimeTransactionAdapter(txDsl);
            tx.insertCaptureScope(new CaptureScope(scopeId, cachedSourceId, 0L, 1L,
                    "V1", "FULL_COVERAGE", null, hash32(), ts));
            tx.insertCaptureScopeUnits(List.of(
                    new CaptureScopeUnit(scopeId, cachedSourceUnitId1, 1L, null)));
            assertTrue(tx.freezeCaptureScope(scopeId, frozenAt));
            // Duplicate freeze → false (already frozen)
            assertFalse(tx.freezeCaptureScope(scopeId, frozenAt.plusMinutes(1)));
        });

        // Non-existent scope → false
        assertFalse(txPort.freezeCaptureScope(UUID.randomUUID(), frozenAt));

        // Verify scope is frozen
        CaptureScope found = queryPort.findCaptureScopeById(scopeId);
        assertNotNull(found);
        assertNotNull(found.frozenAt());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. CloseoutRun insert/find/list + legal state transition; wrong expected false; illegal DB throw
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(4)
    @DisplayName("CloseoutRun insert/find/list, CAS transitions, wrong expected false, illegal throw")
    void closeoutRunCrudAndCas() {
        // Prerequisite: frozen capture scope
        UUID scopeId = newId();
        dsl.transaction(config -> {
            var txDsl = config.dsl();
            var tx = new JooqRuntimeTransactionAdapter(txDsl);
            tx.insertCaptureScope(new CaptureScope(scopeId, cachedSourceId, 0L, 1L,
                    "V1", "FULL_COVERAGE", null, hash32(), now()));
            tx.insertCaptureScopeUnits(List.of(
                    new CaptureScopeUnit(scopeId, cachedSourceUnitId1, 1L, null)));
            tx.freezeCaptureScope(scopeId, now());
        });

        UUID runId = newId();
        OffsetDateTime ts = now();

        // Insert closeout run in READY state
        txPort.insertCloseoutRun(new CloseoutRun(runId, scopeId, "READY", null, null,
                null, null, null, ts));

        // Find by id
        CloseoutRun found = queryPort.findCloseoutRunById(runId);
        assertNotNull(found);
        assertEquals(runId, found.runId());
        assertEquals(scopeId, found.scopeId());
        assertEquals("READY", found.state());

        // List by scope (ordered by created_at ASC, run_id ASC)
        List<CloseoutRun> list = queryPort.findCloseoutRunsByScopeId(scopeId);
        assertEquals(1, list.size());
        assertEquals(runId, list.get(0).runId());

        // Legal CAS: READY → RUNNING
        OffsetDateTime startedAt = ts.plusSeconds(1);
        assertTrue(txPort.transitionCloseoutRun(runId, "READY", "RUNNING",
                startedAt, null, null));

        // Verify transition
        CloseoutRun running = queryPort.findCloseoutRunById(runId);
        assertEquals("RUNNING", running.state());
        assertTrue(startedAt.isEqual(running.startedAt()));

        // Legal CAS: RUNNING → COMPLETED
        OffsetDateTime terminalAt = ts.plusSeconds(10);
        assertTrue(txPort.transitionCloseoutRun(runId, "RUNNING", "COMPLETED",
                startedAt, terminalAt, null));

        // Wrong expected state → false
        assertFalse(txPort.transitionCloseoutRun(runId, "READY", "CANCELLED",
                null, now(), null));

        // Illegal transition (COMPLETED → RUNNING) must throw from DB trigger
        assertThrows(Exception.class, () ->
                txPort.transitionCloseoutRun(runId, "COMPLETED", "RUNNING",
                        startedAt, null, null));

        // Non-existent → false (not throw)
        assertFalse(txPort.transitionCloseoutRun(UUID.randomUUID(), "READY", "RUNNING",
                startedAt, null, null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Checkpoint insert/find + sequence DESC ordering
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(5)
    @DisplayName("Checkpoint insert/find and sequence_no DESC ordering")
    void checkpointInsertFindAndOrdering() {
        UUID runId = newId();
        OffsetDateTime ts = now();
        byte[] hash1 = hash32();
        byte[] hash2 = hash32();
        byte[] hash3 = hash32();

        UUID cp1 = newId();
        UUID cp2 = newId();
        UUID cp3 = newId();

        // Insert in non-sequence order
        txPort.insertCheckpoint(new Checkpoint(cp2, "CLOSEOUT", runId, 200L, hash2, "obj-2", ts));
        txPort.insertCheckpoint(new Checkpoint(cp1, "CLOSEOUT", runId, 100L, hash1, "obj-1", ts));
        txPort.insertCheckpoint(new Checkpoint(cp3, "CLOSEOUT", runId, 300L, hash3, null, ts));

        // Find by id
        Checkpoint found = queryPort.findCheckpointById(cp1);
        assertNotNull(found);
        assertEquals(cp1, found.checkpointId());
        assertEquals("CLOSEOUT", found.runKind());
        assertEquals(runId, found.runId());
        assertEquals(100L, found.sequenceNo());
        assertArrayEquals(hash1, found.manifestHash());
        assertEquals("obj-1", found.objectRef());

        // Find by run kind + run id → must be sequence_no DESC
        List<Checkpoint> list = queryPort.findCheckpointsByRunKindAndRunId("CLOSEOUT", runId);
        assertEquals(3, list.size());
        assertEquals(300L, list.get(0).sequenceNo());
        assertEquals(200L, list.get(1).sequenceNo());
        assertEquals(100L, list.get(2).sequenceNo());

        // null object_ref round-trip
        assertEquals(cp3, list.get(0).checkpointId());
        assertNull(list.get(0).objectRef());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. WorkArtifact insert/find/list
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(6)
    @DisplayName("WorkArtifact insert/find/list with ordering")
    void workArtifactInsertFindList() {
        OffsetDateTime ts = now();
        OffsetDateTime future = ts.plusDays(1);
        byte[] hash1 = hash32();
        byte[] hash2 = hash32();

        UUID art1 = newId();
        UUID art2 = newId();

        OffsetDateTime t1 = ts.plusSeconds(1);
        OffsetDateTime t2 = ts.plusSeconds(2);

        // WorkArtifact with null run_id (no FK to closeout_run needed)
        txPort.insertWorkArtifact(new WorkArtifact(art1, null, "SNAPSHOT", "obj-a", hash1, future, t1));
        txPort.insertWorkArtifact(new WorkArtifact(art2, null, "DIFF", "obj-b", hash2, future, t2));

        // Find by id
        WorkArtifact found = queryPort.findWorkArtifactById(art1);
        assertNotNull(found);
        assertEquals(art1, found.artifactId());
        assertNull(found.runId());
        assertEquals("SNAPSHOT", found.artifactKind());
        assertEquals("obj-a", found.objectRef());
        assertArrayEquals(hash1, found.contentHash());

        // List by run_id for null → empty
        assertTrue(queryPort.findWorkArtifactsByRunId(newId()).isEmpty());

        // WorkArtifact with null run_id
        UUID art3 = newId();
        txPort.insertWorkArtifact(new WorkArtifact(art3, null, "EPHEMERAL", "obj-c", hash32(), future, t2));
        WorkArtifact found3 = queryPort.findWorkArtifactById(art3);
        assertNotNull(found3);
        assertNull(found3.runId());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. ModelRun insert/find, legal CAS true, wrong expected false, illegal transition throw
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(7)
    @DisplayName("ModelRun insert/find, CAS transitions, wrong expected false, illegal throw")
    void modelRunCrudAndCas() {
        UUID modelRunId = newId();
        OffsetDateTime ts = now();
        byte[] inputHash = hash32();
        byte[] outputHash = hash32();

        // Insert in RUNNING state
        txPort.insertModelRun(new ModelRun(modelRunId, "REASONER", "pm-1", "RUNNING",
                inputHash, null, null, ts, null, null));

        // Find
        ModelRun found = queryPort.findModelRunById(modelRunId);
        assertNotNull(found);
        assertEquals(modelRunId, found.modelRunId());
        assertEquals("REASONER", found.roleCode());
        assertEquals("RUNNING", found.state());
        assertArrayEquals(inputHash, found.inputManifestHash());
        assertNull(found.outputManifestHash());

        // Legal CAS: RUNNING → SUCCEEDED
        OffsetDateTime terminalAt = ts.plusSeconds(30);
        assertTrue(txPort.transitionModelRun(modelRunId, "RUNNING", "SUCCEEDED",
                outputHash, terminalAt, null));

        ModelRun succeeded = queryPort.findModelRunById(modelRunId);
        assertEquals("SUCCEEDED", succeeded.state());
        assertArrayEquals(outputHash, succeeded.outputManifestHash());
        assertTrue(terminalAt.isEqual(succeeded.terminalAt()));

        // Wrong expected state → false
        assertFalse(txPort.transitionModelRun(modelRunId, "RUNNING", "FAILED",
                null, now(), "ACCESS_DENIED"));

        // Illegal transition (SUCCEEDED → RUNNING) must throw from DB trigger
        assertThrows(Exception.class, () ->
                txPort.transitionModelRun(modelRunId, "SUCCEEDED", "RUNNING",
                        null, null, null));

        // Non-existent → false
        assertFalse(txPort.transitionModelRun(UUID.randomUUID(), "RUNNING", "SUCCEEDED",
                outputHash, now(), null));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. RetrievalTrace UUID[]: non-empty, empty, null round-trip; returned list unmodifiable
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(8)
    @DisplayName("RetrievalTrace UUID[] round-trip: non-empty, empty, null; list unmodifiable")
    void retrievalTraceUuidArrayRoundTrip() {
        UUID traceId = newId();
        OffsetDateTime ts = now();
        OffsetDateTime future = ts.plusMinutes(5);
        byte[] policyHash = hash32();

        UUID reqId = newId();
        UUID threadId = newId();
        UUID turnId = newId();

        // Non-empty arrays
        List<UUID> considered = List.of(newId(), newId(), newId());
        List<UUID> delivered = List.of(newId(), newId());
        txPort.insertRetrievalTrace(new RetrievalTrace(traceId, reqId, threadId, turnId,
                "RETRIEVAL", "SUCCEEDED", policyHash, considered, delivered, ts, future));

        RetrievalTrace found = queryPort.findRetrievalTraceById(traceId);
        assertNotNull(found);
        assertEquals(considered, found.consideredIds());
        assertEquals(delivered, found.deliveredIds());

        // Returned lists must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> found.consideredIds().add(newId()));
        assertThrows(UnsupportedOperationException.class, () -> found.deliveredIds().clear());

        // Empty arrays
        UUID traceId2 = newId();
        txPort.insertRetrievalTrace(new RetrievalTrace(traceId2, reqId, threadId, turnId,
                "RETRIEVAL", "NO_RELEVANT_RESULT", policyHash,
                List.of(), List.of(), ts, future));
        RetrievalTrace found2 = queryPort.findRetrievalTraceById(traceId2);
        assertNotNull(found2);
        assertTrue(found2.consideredIds().isEmpty());
        assertTrue(found2.deliveredIds().isEmpty());

        // Null arrays
        UUID traceId3 = newId();
        txPort.insertRetrievalTrace(new RetrievalTrace(traceId3, reqId, threadId, turnId,
                "RETRIEVAL", "DENIED", policyHash, null, null, ts, future));
        RetrievalTrace found3 = queryPort.findRetrievalTraceById(traceId3);
        assertNotNull(found3);
        assertNull(found3.consideredIds());
        assertNull(found3.deliveredIds());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. ContextDelivery insert/find, first invalidation true, repeat false
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(9)
    @DisplayName("ContextDelivery insert/find, first invalidation true, repeat false")
    void contextDeliveryInsertFindAndInvalidation() {
        UUID deliveryId = newId();
        OffsetDateTime ts = now();
        OffsetDateTime deliveredAt = ts;
        OffsetDateTime expiresAt = ts.plusMinutes(5);
        byte[] policyHash = hash32();
        byte[] manifestHash = hash32();

        UUID reqId = newId();
        UUID threadId = newId();
        UUID turnId = newId();

        // Insert
        txPort.insertContextDelivery(new ContextDelivery(deliveryId, reqId, threadId, turnId,
                "CONTEXT", policyHash, manifestHash, deliveredAt, expiresAt, null, null));

        // Find
        ContextDelivery found = queryPort.findContextDeliveryById(deliveryId);
        assertNotNull(found);
        assertEquals(deliveryId, found.deliveryId());
        assertNull(found.invalidatedAt());
        assertNull(found.invalidationReason());

        // First invalidation → true
        OffsetDateTime invalidatedAt = ts.plusMinutes(1);
        assertTrue(txPort.invalidateContextDelivery(deliveryId, invalidatedAt, "EXPIRED"));

        // Verify
        ContextDelivery invalidated = queryPort.findContextDeliveryById(deliveryId);
        assertTrue(invalidatedAt.isEqual(invalidated.invalidatedAt()));
        assertEquals("EXPIRED", invalidated.invalidationReason());

        // Repeat invalidation → false (already invalidated)
        assertFalse(txPort.invalidateContextDelivery(deliveryId, ts.plusMinutes(2), "REVOKED"));

        // Non-existent → false
        assertFalse(txPort.invalidateContextDelivery(UUID.randomUUID(), ts, "EXPIRED"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 10. ConsumerEffect insert/exists/find; same triple duplicate rejected
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(10)
    @DisplayName("ConsumerEffect insert/exists/find; triple duplicate rejected")
    void consumerEffectInsertExistsFindAndDuplicate() {
        String consumerCode = "EFFECT_TEST";
        UUID eventId = cachedOutboxEventId;
        String effectKey = "effect-1";
        OffsetDateTime ts = now();

        // Initially does not exist
        assertFalse(queryPort.existsConsumerEffect(consumerCode, eventId, effectKey));
        assertNull(queryPort.findConsumerEffectByKey(consumerCode, eventId, effectKey));

        // Insert
        txPort.insertConsumerEffect(new ConsumerEffect(consumerCode, eventId, effectKey, ts));

        // Now exists
        assertTrue(queryPort.existsConsumerEffect(consumerCode, eventId, effectKey));

        // Find
        ConsumerEffect found = queryPort.findConsumerEffectByKey(consumerCode, eventId, effectKey);
        assertNotNull(found);
        assertEquals(consumerCode, found.consumerCode());
        assertEquals(eventId, found.eventId());
        assertEquals(effectKey, found.effectKey());

        // Same triple duplicate → rejected by PK constraint
        assertThrows(Exception.class, () ->
                txPort.insertConsumerEffect(new ConsumerEffect(consumerCode, eventId, effectKey, ts)));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 11. All single-object not-found=null, collection not-found=empty
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(11)
    @DisplayName("Not-found returns null for single-object, empty list for collections")
    void notFoundReturnsNullOrEmpty() {
        UUID nonexistent = UUID.randomUUID();

        // Single-object queries → null
        assertNull(queryPort.findCaptureScopeById(nonexistent));
        assertNull(queryPort.findCloseoutRunById(nonexistent));
        assertNull(queryPort.findCheckpointById(nonexistent));
        assertNull(queryPort.findWorkArtifactById(nonexistent));
        assertNull(queryPort.findModelRunById(nonexistent));
        assertNull(queryPort.findRetrievalTraceById(nonexistent));
        assertNull(queryPort.findContextDeliveryById(nonexistent));
        assertNull(queryPort.findConsumerEffectByKey("NONEXISTENT", nonexistent, "never"));

        // Collection queries → empty (not null)
        assertTrue(queryPort.findCaptureScopeUnitsByScopeId(nonexistent).isEmpty());
        assertTrue(queryPort.findCloseoutRunsByScopeId(nonexistent).isEmpty());
        assertTrue(queryPort.findCheckpointsByRunKindAndRunId("NONE", nonexistent).isEmpty());
        assertTrue(queryPort.findWorkArtifactsByRunId(nonexistent).isEmpty());

        // Boolean → false
        assertFalse(queryPort.existsConsumerEffect("NONEXISTENT", nonexistent, "never"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 12. Database exceptions not swallowed
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(12)
    @DisplayName("Database exceptions propagate, not swallowed")
    void databaseExceptionsNotSwallowed() {
        // FK violation: insert CaptureScope with non-existent source_id
        UUID badScopeId = newId();
        assertThrows(Exception.class, () ->
                txPort.insertCaptureScope(new CaptureScope(badScopeId, UUID.randomUUID(), 0L, 1L,
                        "V1", "FULL_COVERAGE", null, hash32(), now())));

        // FK violation: insert CaptureScopeUnit with non-existent scope_id
        assertThrows(Exception.class, () ->
                txPort.insertCaptureScopeUnits(List.of(
                        new CaptureScopeUnit(UUID.randomUUID(), cachedSourceUnitId1, 1L, null))));

        // FK violation: insert CloseoutRun with non-existent scope_id
        assertThrows(Exception.class, () ->
                txPort.insertCloseoutRun(new CloseoutRun(newId(), UUID.randomUUID(), "READY",
                        null, null, null, null, null, now())));

        // FK violation: insert WorkArtifact with non-existent run_id
        assertThrows(Exception.class, () ->
                txPort.insertWorkArtifact(new WorkArtifact(newId(), UUID.randomUUID(),
                        "SNAPSHOT", "bad-obj", hash32(), now().plusDays(1), now())));

        // Check constraint: manifest_hash wrong length
        assertThrows(Exception.class, () ->
                txPort.insertCheckpoint(new Checkpoint(newId(), "CLOSEOUT", newId(), 1L,
                        new byte[16], "obj", now())));

        // FK violation: ConsumerEffect with non-existent event_id
        assertThrows(Exception.class, () ->
                txPort.insertConsumerEffect(new ConsumerEffect("BAD_CONS", UUID.randomUUID(),
                        "bad-key", now())));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 13. Body / secret canary scan: 0 hits in adapter query/return objects
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(13)
    @DisplayName("Body text canary: adapter returns no bodyText/prompt/answer/chainOfThought/rawPayload")
    void bodyTextCanaryHitZero() {
        // Create a frozen scope + run to verify round-trip objects
        UUID scopeId = newId();
        dsl.transaction(config -> {
            var txDsl = config.dsl();
            var tx = new JooqRuntimeTransactionAdapter(txDsl);
            tx.insertCaptureScope(new CaptureScope(scopeId, cachedSourceId, 0L, 1L,
                    "V1", "FULL_COVERAGE", null, hash32(), now()));
            tx.insertCaptureScopeUnits(List.of(
                    new CaptureScopeUnit(scopeId, cachedSourceUnitId1, 1L, null)));
            tx.freezeCaptureScope(scopeId, now());
        });

        // Verify all domain objects from adapter queries
        Set<String> forbidden = Set.of("bodyText", "prompt", "answer", "chainOfThought", "rawPayload");

        // CaptureScope
        CaptureScope scope = queryPort.findCaptureScopeById(scopeId);
        for (String f : forbidden) assertFalse(scope.toString().contains(f), "CaptureScope leaked " + f);

        // CaptureScopeUnit
        List<CaptureScopeUnit> units = queryPort.findCaptureScopeUnitsByScopeId(scopeId);
        for (CaptureScopeUnit u : units)
            for (String f : forbidden) assertFalse(u.toString().contains(f), "CaptureScopeUnit leaked " + f);

        // CloseoutRun
        UUID runId = newId();
        txPort.insertCloseoutRun(new CloseoutRun(runId, scopeId, "READY", null, null,
                null, null, null, now()));
        CloseoutRun run = queryPort.findCloseoutRunById(runId);
        for (String f : forbidden) assertFalse(run.toString().contains(f), "CloseoutRun leaked " + f);

        // Checkpoint
        UUID cpId = newId();
        txPort.insertCheckpoint(new Checkpoint(cpId, "CLOSEOUT", runId, 1L, hash32(), "obj", now()));
        Checkpoint cp = queryPort.findCheckpointById(cpId);
        for (String f : forbidden) assertFalse(cp.toString().contains(f), "Checkpoint leaked " + f);

        // WorkArtifact
        UUID artId = newId();
        txPort.insertWorkArtifact(new WorkArtifact(artId, runId, "SNAPSHOT", "obj", hash32(), now().plusDays(1), now()));
        WorkArtifact art = queryPort.findWorkArtifactById(artId);
        for (String f : forbidden) assertFalse(art.toString().contains(f), "WorkArtifact leaked " + f);

        // ModelRun
        UUID mrId = newId();
        txPort.insertModelRun(new ModelRun(mrId, "REASONER", "pm-1", "RUNNING",
                hash32(), null, null, now(), null, null));
        ModelRun mr = queryPort.findModelRunById(mrId);
        for (String f : forbidden) assertFalse(mr.toString().contains(f), "ModelRun leaked " + f);

        // RetrievalTrace
        UUID traceId = newId();
        txPort.insertRetrievalTrace(new RetrievalTrace(traceId, newId(), newId(), newId(),
                "RETRIEVAL", "SUCCEEDED", hash32(), List.of(), null, now(), now().plusMinutes(5)));
        RetrievalTrace trace = queryPort.findRetrievalTraceById(traceId);
        for (String f : forbidden) assertFalse(trace.toString().contains(f), "RetrievalTrace leaked " + f);

        // ContextDelivery
        UUID cdId = newId();
        txPort.insertContextDelivery(new ContextDelivery(cdId, newId(), newId(), newId(),
                "CONTEXT", hash32(), hash32(), now(), now().plusMinutes(5), null, null));
        ContextDelivery cd = queryPort.findContextDeliveryById(cdId);
        for (String f : forbidden) assertFalse(cd.toString().contains(f), "ContextDelivery leaked " + f);

        // ConsumerEffect
        ConsumerEffect ce = queryPort.findConsumerEffectByKey("EFFECT_TEST", cachedOutboxEventId, "effect-1");
        if (ce != null)
            for (String f : forbidden) assertFalse(ce.toString().contains(f), "ConsumerEffect leaked " + f);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 14. Formal adapter override sets exactly match port abstract methods
    // ═══════════════════════════════════════════════════════════════════════
    @Test
    @Order(14)
    @DisplayName("Adapter @Override sets exactly match port abstract methods")
    void adapterOverridesExactlyMatchPortAbstractMethods() {
        // Transaction adapter: 17 port abstract methods
        java.util.Set<String> txPortAbstract = abstractMethodKeys(RuntimeTransactionPort.class);
        java.util.Set<String> txAdapterPublic = allPublicMethodKeys(JooqRuntimeTransactionAdapter.class);
        assertEquals(txPortAbstract, txAdapterPublic,
                "JooqRuntimeTransactionAdapter override mismatch. Missing: "
                        + diff(txPortAbstract, txAdapterPublic) + " Extra: "
                        + diff(txAdapterPublic, txPortAbstract));

        // Query adapter: 13 port abstract methods
        java.util.Set<String> qPortAbstract = abstractMethodKeys(RuntimeQueryPort.class);
        java.util.Set<String> qAdapterPublic = allPublicMethodKeys(JooqRuntimeQueryAdapter.class);
        assertEquals(qPortAbstract, qAdapterPublic,
                "JooqRuntimeQueryAdapter override mismatch. Missing: "
                        + diff(qPortAbstract, qAdapterPublic) + " Extra: "
                        + diff(qAdapterPublic, qPortAbstract));
    }

    private static java.util.Set<String> abstractMethodKeys(Class<?> clazz) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var m : clazz.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
                keys.add(methodKey(m));
            }
        }
        return keys;
    }

    private static java.util.Set<String> allPublicMethodKeys(Class<?> clazz) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (var m : clazz.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                keys.add(methodKey(m));
            }
        }
        return keys;
    }

    private static String methodKey(java.lang.reflect.Method m) {
        var sb = new StringBuilder();
        sb.append(m.getName()).append('(');
        var params = m.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static java.util.Set<String> diff(java.util.Set<String> a, java.util.Set<String> b) {
        java.util.Set<String> d = new java.util.HashSet<>(a);
        d.removeAll(b);
        return d;
    }
}
