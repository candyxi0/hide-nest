package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.v2.adapter.JooqProjectionLedgerAdapter;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Binding;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Claim;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.EmbeddingManifest;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Result;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.ResultKind;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.SchemaManifest;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Settlement;
import io.github.candyxi0.hidenest.runtime.domain.ProjectionLedger.Target;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Synthetic bindings on a disposable PostgreSQL 18; no Worker or index is present. */
class NestV2ProjectionLedgerTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String WORLD = "world:synthetic-a1-a";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-24T00:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);
    private static final String MATERIAL = "sha256:" + "0".repeat(64);
    private static final String RECEIPT = "sha256:" + "1".repeat(64);

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext db;
    private static UUID formationTask;

    @BeforeAll
    static void database() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nest_v2_projection")
                .withUsername(USER)
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection c = connection()) {
            c.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            c.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Map<String, Integer> old = new HashMap<>();
        assertEquals(6, flyway("6").migrate().migrationsExecuted);
        try (Connection c = connection();
                var rows = c.createStatement()
                        .executeQuery("SELECT version,checksum FROM public.flyway_schema_history WHERE success")) {
            while (rows.next()) old.put(rows.getString(1), rows.getInt(2));
        }
        assertEquals(1, flyway(null).migrate().migrationsExecuted);
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        flyway(null).validate();
        try (Connection c = connection();
                var rows = c.createStatement()
                        .executeQuery(
                                "SELECT version,checksum FROM public.flyway_schema_history WHERE success AND version<>'007'")) {
            while (rows.next()) assertEquals(old.get(rows.getString(1)), rows.getInt(2));
        }
        db = DSL.using(
                new DriverManagerDataSource(postgres.getJdbcUrl(), USER, postgres.getPassword()), SQLDialect.POSTGRES);
    }

    @AfterAll
    static void close() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void fixture() {
        db.execute("TRUNCATE runtime.projection_attempt,runtime.projection_delivery,runtime.projection_target,"
                + "memory.projection_outbox,memory.revision,memory.record,runtime.formation_task,"
                + "runtime.source_progress,runtime.source_registration,memory.world_binding CASCADE");
        UUID source = UUID.randomUUID();
        formationTask = UUID.randomUUID();
        db.execute(
                "INSERT INTO memory.world_binding(singleton,world_ref,bound_at) VALUES (1,?,?::timestamptz)",
                WORLD,
                T0);
        db.execute(
                "INSERT INTO runtime.source_registration "
                        + "(source_id,source_kind,platform,external_ref,registered_at) "
                        + "VALUES (?::uuid,'SYNTHETIC','A1-A',?,?::timestamptz)",
                source,
                source.toString(),
                T0);
        db.execute(
                "INSERT INTO runtime.source_progress "
                        + "(source_id,last_notification_sequence,last_notification_hash,discovered_sequence,"
                        + "discovered_cursor,discovered_source_version,read_kind,read_ref,read_version,"
                        + "last_real_change_at,quiet_until,updated_at) "
                        + "VALUES (?::uuid,1,decode(repeat('00',32),'hex'),1,'cursor','v1',"
                        + "'STABLE_REREAD','synthetic','v1',?::timestamptz,?::timestamptz,?::timestamptz)",
                source,
                T0,
                T0.plusMinutes(90),
                T0);
        db.execute(
                "INSERT INTO runtime.formation_task "
                        + "(task_id,source_id,state,to_sequence,to_cursor,to_source_version,read_kind,read_ref,"
                        + "read_version,ready_at,created_at,updated_at) "
                        + "VALUES (?::uuid,?::uuid,'PENDING',1,'cursor','v1','STABLE_REREAD','synthetic','v1',"
                        + "?::timestamptz,?::timestamptz,?::timestamptz)",
                formationTask,
                source,
                T0,
                T0,
                T0);
    }

    @Test
    void emptyDatabaseMigratesAllSevenWithoutSeedAndRepeatIsNoOp() throws Exception {
        try (Connection c = connection()) {
            c.createStatement().execute("CREATE DATABASE nest_v2_projection_empty");
        }
        String emptyUrl = postgres.getJdbcUrl().replace("/nest_v2_projection?", "/nest_v2_projection_empty?");
        Flyway empty = Flyway.configure()
                .dataSource(emptyUrl, USER, postgres.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/v2/migration")
                .cleanDisabled(true)
                .load();
        assertEquals(7, empty.migrate().migrationsExecuted);
        assertEquals(0, empty.migrate().migrationsExecuted);
        empty.validate();
        try (Connection c = DriverManager.getConnection(emptyUrl, USER, postgres.getPassword());
                var rows = c.createStatement().executeQuery("SELECT count(*) FROM runtime.projection_target")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void invalidPolicyIsRejectedBeforeDatabaseWork() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JooqProjectionLedgerAdapter(db, Clock.systemUTC(), 0, Duration.ofSeconds(1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JooqProjectionLedgerAdapter(db, Clock.systemUTC(), 2, Duration.ZERO));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JooqProjectionLedgerAdapter(db, Clock.systemUTC(), 2, Duration.ofDays(8)));
    }

    @Test
    void targetIdentityAndCommittedVisibility() throws Exception {
        var ledger = ledger(T0, 3);
        ledger.register(target());
        ledger.register(target());
        assertThrows(
                IllegalStateException.class,
                () -> ledger.register(
                        new Target(WORLD, 1, new SchemaManifest("index-schema:other", "v1"), target().embedding())));
        assertThrows(
                IllegalArgumentException.class,
                () -> ledger.register(new Target("world:other", 1, target().schema(), target().embedding())));
        UUID event = UUID.randomUUID();
        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            insertEvent(writer, event, T0);
            assertTrue(ledger.discover(WORLD, 1, 10).isEmpty());
            assertTrue(ledger.claim(WORLD, 1, event, "owner", LEASE, MATERIAL).isEmpty());
            writer.commit();
        }
        assertEquals(event, ledger.discover(WORLD, 1, 10).getFirst().eventId());
        assertEquals(1, count("SELECT count(*) FROM runtime.projection_delivery"));
        ledger.discover(WORLD, 1, 10);
        assertEquals(1, count("SELECT count(*) FROM runtime.projection_delivery"));
        assertTrue(ledger.claim(WORLD, 1, event, "owner", LEASE, MATERIAL).isPresent());
    }

    @Test
    void earlierOpenTransactionCannotBeSkippedAfterLaterCommit() throws Exception {
        var ledger = ledger(T0.plusHours(1), 3);
        ledger.register(target());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        try (Connection earlier = connection()) {
            earlier.setAutoCommit(false);
            insertEvent(earlier, a, T0);
            try (Connection later = connection()) {
                later.setAutoCommit(false);
                insertEvent(later, b, T0.plusSeconds(1));
                later.commit();
            }
            assertEquals(b, ledger.discover(WORLD, 1, 10).getFirst().eventId());
            Claim bClaim = ledger.claim(WORLD, 1, b, "b", LEASE, MATERIAL).orElseThrow();
            assertEquals(Settlement.APPLIED, ledger.settle(applied(bClaim)));
            assertEquals(0, count("SELECT count(*) FROM runtime.projection_delivery WHERE event_id='" + a + "'"));
            earlier.commit();
        }
        assertEquals(a, ledger.discover(WORLD, 1, 10).getFirst().eventId());
        Claim aClaim = ledger.claim(WORLD, 1, a, "a", LEASE, MATERIAL).orElseThrow();
        assertEquals(Settlement.APPLIED, ledger.settle(applied(aClaim)));
        assertEquals(2, count("SELECT count(*) FROM runtime.projection_delivery WHERE state='APPLIED'"));
    }

    @Test
    void unrelatedEventsCanHoldLeasesConcurrently() throws Exception {
        var ledger = ledger(T0.plusHours(1), 2);
        ledger.register(target());
        UUID a = event(T0);
        UUID b = event(T0.plusSeconds(1));
        ledger.discover(WORLD, 1, 10);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Claim> one = pool.submit(() -> {
                start.await();
                return ledger.claim(WORLD, 1, a, "a", LEASE, MATERIAL).orElseThrow();
            });
            Future<Claim> two = pool.submit(() -> {
                start.await();
                return ledger.claim(WORLD, 1, b, "b", LEASE, MATERIAL).orElseThrow();
            });
            start.countDown();
            assertNotEquals(one.get().attemptId(), two.get().attemptId());
            assertEquals(2, count("SELECT count(*) FROM runtime.projection_attempt WHERE state='RUNNING'"));
        }
    }

    @Test
    void concurrentClaimLeaseTakeoverAndCompleteBindingFence() throws Exception {
        var startLedger = ledger(T0.plusHours(1), 3);
        startLedger.register(target());
        UUID event = event(T0);
        startLedger.discover(WORLD, 1, 10);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Claim> one = pool.submit(() -> {
                start.await();
                return startLedger
                        .claim(WORLD, 1, event, "one", LEASE, MATERIAL)
                        .orElse(null);
            });
            Future<Claim> two = pool.submit(() -> {
                start.await();
                return startLedger
                        .claim(WORLD, 1, event, "two", LEASE, MATERIAL)
                        .orElse(null);
            });
            start.countDown();
            Claim first = one.get() == null ? two.get() : one.get();
            assertEquals(1, count("SELECT count(*) FROM runtime.projection_attempt"));
            assertFalse(startLedger.renew(
                    new Claim(first.attemptId(), "wrong", first.leaseUntil(), first.binding()), LEASE));
            assertFalse(startLedger.renew(
                    new Claim(
                            first.attemptId(),
                            first.ownerRef(),
                            first.leaseUntil(),
                            withGeneration(first.binding(), 9)),
                    LEASE));
            assertTrue(startLedger.renew(first, LEASE));
            assertTrue(ledger(T0.plusHours(1).plusMinutes(4), 3)
                    .claim(WORLD, 1, event, "other", LEASE, MATERIAL)
                    .isEmpty());
            assertFalse(ledger(T0.plusHours(1).plusMinutes(6), 3).renew(first, LEASE));
            assertEquals(
                    Settlement.REJECTED,
                    ledger(T0.plusHours(1).plusMinutes(6), 3).settle(applied(first)));
            Claim second = ledger(T0.plusHours(1).plusMinutes(6), 3)
                    .claim(WORLD, 1, event, "second", LEASE, MATERIAL)
                    .orElseThrow();
            assertNotEquals(first.attemptId(), second.attemptId());
            assertEquals(2, second.binding().attemptGeneration());
            var settle = ledger(T0.plusHours(1).plusMinutes(7), 3);
            assertEquals(Settlement.REJECTED, settle.settle(applied(first)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            "wrong",
                            second.binding(),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withGeneration(second.binding(), 7),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withTask(second.binding()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withEvent(second.binding(), UUID.randomUUID()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withWorld(second.binding(), "world:other"),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withProjection(second.binding(), 2),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withSchema(second.binding()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withEmbedding(second.binding()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withDigest(second.binding()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            withRevision(second.binding()),
                            ResultKind.APPLIED,
                            RECEIPT,
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            second.binding(),
                            ResultKind.APPLIED,
                            RECEIPT,
                            UUID.randomUUID(),
                            null)));
            assertEquals(0, count("SELECT count(*) FROM runtime.projection_delivery WHERE state='APPLIED'"));
            assertEquals(Settlement.APPLIED, settle.settle(applied(second)));
            String deliveryVersion = db.fetchOne(
                            "SELECT xmin::text FROM runtime.projection_delivery WHERE event_id=?::uuid", event)
                    .get(0, String.class);
            String attemptVersion = db.fetchOne(
                            "SELECT xmin::text FROM runtime.projection_attempt WHERE attempt_id=?::uuid",
                            second.attemptId())
                    .get(0, String.class);
            assertEquals(Settlement.APPLIED, settle.settle(applied(second)));
            assertEquals(
                    deliveryVersion,
                    db.fetchOne("SELECT xmin::text FROM runtime.projection_delivery WHERE event_id=?::uuid", event)
                            .get(0, String.class));
            assertEquals(
                    attemptVersion,
                    db.fetchOne(
                                    "SELECT xmin::text FROM runtime.projection_attempt WHERE attempt_id=?::uuid",
                                    second.attemptId())
                            .get(0, String.class));
            assertEquals(
                    Settlement.REJECTED,
                    settle.settle(new Result(
                            second.attemptId(),
                            second.ownerRef(),
                            second.binding(),
                            ResultKind.APPLIED,
                            "sha256:" + "2".repeat(64),
                            second.binding().expectedRevisionId(),
                            null)));
            assertEquals(1, count("SELECT count(*) FROM runtime.projection_attempt WHERE state='APPLIED'"));
        }
    }

    @Test
    void retryAttentionRecoveryAndUnrelatedEvent() throws Exception {
        var first = ledger(T0.plusHours(1), 2);
        first.register(target());
        UUID bad = event(T0);
        UUID good = event(T0.plusSeconds(1));
        first.discover(WORLD, 1, 10);
        Claim a = first.claim(WORLD, 1, bad, "a", LEASE, MATERIAL).orElseThrow();
        assertEquals(
                Settlement.RETRY_WAIT,
                first.settle(failed(a, ResultKind.TEMPORARY_FAILURE, "INDEX_WRITE_UNAVAILABLE")));
        assertEquals(1, first.discover(WORLD, 1, 10).size());
        assertEquals(good, first.discover(WORLD, 1, 10).getFirst().eventId());
        assertTrue(first.claim(WORLD, 1, bad, "hot", LEASE, MATERIAL).isEmpty());
        Claim b = ledger(T0.plusHours(1).plusSeconds(1), 2)
                .claim(WORLD, 1, bad, "b", LEASE, MATERIAL)
                .orElseThrow();
        assertEquals(
                Settlement.ATTENTION_REQUIRED,
                ledger(T0.plusHours(1).plusSeconds(1), 2)
                        .settle(failed(b, ResultKind.TEMPORARY_FAILURE, "INDEX_WRITE_UNAVAILABLE")));
        assertEquals(1, first.attention(WORLD, 1, 10).size());
        assertEquals(1, first.attention(WORLD, 1, 10).size());
        Claim independent = first.claim(WORLD, 1, good, "good", LEASE, MATERIAL).orElseThrow();
        assertEquals(Settlement.APPLIED, first.settle(applied(independent)));
        assertFalse(first.recover(WORLD, 1, bad, "PENDING"));
        assertFalse(first.recover(WORLD, 1, good, "ATTENTION_REQUIRED"));
        assertFalse(first.recover("world:other", 1, bad, "ATTENTION_REQUIRED"));
        assertTrue(first.recover(WORLD, 1, bad, "ATTENTION_REQUIRED"));
        assertTrue(first.attention(WORLD, 1, 10).isEmpty());
        Claim c = first.claim(WORLD, 1, bad, "c", LEASE, MATERIAL).orElseThrow();
        assertEquals(3, c.binding().attemptGeneration());
        assertEquals(3, count("SELECT count(*) FROM runtime.projection_attempt WHERE event_id='" + bad + "'"));
        assertEquals(Settlement.APPLIED, first.settle(applied(c)));
        assertEquals(2, count("SELECT count(*) FROM runtime.projection_delivery WHERE state='APPLIED'"));
        assertEquals(2, count("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(0, count("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
        assertEquals(1, count("SELECT count(*) FROM runtime.formation_task WHERE state='PENDING'"));
    }

    @Test
    void permanentFailureAndExpiredBudgetArePerEvent() throws Exception {
        var first = ledger(T0.plusHours(1), 1);
        first.register(target());
        UUID permanent = event(T0);
        UUID expired = event(T0.plusSeconds(1));
        first.discover(WORLD, 1, 10);
        Claim a = first.claim(WORLD, 1, permanent, "a", LEASE, MATERIAL).orElseThrow();
        assertEquals(
                Settlement.ATTENTION_REQUIRED,
                first.settle(failed(a, ResultKind.PERMANENT_FAILURE, "MATERIAL_INVALID")));
        Claim b = first.claim(WORLD, 1, expired, "b", LEASE, MATERIAL).orElseThrow();
        assertTrue(ledger(T0.plusHours(1).plusMinutes(6), 1)
                .claim(WORLD, 1, expired, "takeover", LEASE, MATERIAL)
                .isEmpty());
        assertEquals(
                Settlement.REJECTED, ledger(T0.plusHours(1).plusMinutes(6), 1).settle(applied(b)));
        assertEquals(2, first.attention(WORLD, 1, 10).size());
    }

    @Test
    void injectedDatabaseFailuresRollBackClaimRenewSettleAndFail() throws Exception {
        var ledger = ledger(T0.plusHours(1), 2);
        ledger.register(target());
        UUID event = event(T0);
        ledger.discover(WORLD, 1, 10);
        db.execute("CREATE FUNCTION public.projection_injected_failure() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected projection failure'; END $$");
        try {
            trigger("projection_attempt", "INSERT");
            assertThrows(RuntimeException.class, () -> ledger.claim(WORLD, 1, event, "owner", LEASE, MATERIAL));
            assertEquals(0, count("SELECT count(*) FROM runtime.projection_attempt"));
            assertEquals("PENDING", state(event));
            dropTrigger("projection_attempt");

            Claim claim =
                    ledger.claim(WORLD, 1, event, "owner", LEASE, MATERIAL).orElseThrow();
            OffsetDateTime until = db.fetchOne(
                            "SELECT lease_until FROM runtime.projection_attempt WHERE attempt_id=?::uuid",
                            claim.attemptId())
                    .get(0, OffsetDateTime.class);
            trigger("projection_attempt", "UPDATE");
            assertThrows(RuntimeException.class, () -> ledger.renew(claim, LEASE));
            assertEquals(
                    until,
                    db.fetchOne(
                                    "SELECT lease_until FROM runtime.projection_attempt WHERE attempt_id=?::uuid",
                                    claim.attemptId())
                            .get(0, OffsetDateTime.class));
            dropTrigger("projection_attempt");

            trigger("projection_delivery", "UPDATE");
            assertThrows(RuntimeException.class, () -> ledger.settle(applied(claim)));
            assertEquals("RUNNING", attemptState(claim));
            assertEquals("ATTEMPTING", state(event));
            assertThrows(
                    RuntimeException.class,
                    () -> ledger.settle(failed(claim, ResultKind.TEMPORARY_FAILURE, "INDEX_WRITE_UNAVAILABLE")));
            assertEquals("RUNNING", attemptState(claim));
            assertEquals("ATTEMPTING", state(event));
            dropTrigger("projection_delivery");
            assertEquals(Settlement.APPLIED, ledger.settle(applied(claim)));
            assertEquals(1, count("SELECT count(*) FROM memory.projection_outbox"));
            assertEquals(1, count("SELECT count(*) FROM memory.record"));
            assertEquals(1, count("SELECT count(*) FROM memory.revision"));
            assertEquals(1, count("SELECT count(*) FROM runtime.formation_task WHERE state='PENDING'"));
            assertEquals(0, count("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
        } finally {
            db.execute("DROP TRIGGER IF EXISTS projection_injected ON runtime.projection_attempt");
            db.execute("DROP TRIGGER IF EXISTS projection_injected ON runtime.projection_delivery");
            db.execute("DROP FUNCTION public.projection_injected_failure()");
        }
    }

    @Test
    void rolesCannotReadOrWriteLedger() throws Exception {
        var ledger = ledger(T0, 2);
        ledger.register(target());
        for (String table : new String[] {"projection_target", "projection_delivery", "projection_attempt"}) {
            for (String role : new String[] {"hide_nest_worker", "PUBLIC"}) {
                for (String privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                    assertFalse(privilege(role, table, privilege));
                }
            }
            for (String privilege : new String[] {"DELETE", "TRUNCATE", "TRIGGER", "REFERENCES"}) {
                assertFalse(privilege("hide_nest_api", table, privilege));
            }
            for (String privilege : new String[] {"SELECT", "INSERT"}) {
                assertTrue(privilege("hide_nest_api", table, privilege));
            }
        }
        assertFalse(privilege("hide_nest_api", "projection_target", "UPDATE"));
        assertTrue(privilege("hide_nest_api", "projection_delivery", "UPDATE"));
        assertTrue(privilege("hide_nest_api", "projection_attempt", "UPDATE"));
        try (Connection worker = connection()) {
            worker.createStatement().execute("SET ROLE hide_nest_worker");
            for (String table : new String[] {"projection_target", "projection_delivery", "projection_attempt"}) {
                assertThrows(SQLException.class, () -> worker.createStatement()
                        .executeQuery("SELECT * FROM runtime." + table));
                assertThrows(SQLException.class, () -> worker.createStatement()
                        .execute("INSERT INTO runtime." + table + " DEFAULT VALUES"));
                assertThrows(SQLException.class, () -> worker.createStatement()
                        .execute("UPDATE runtime." + table + " SET "
                                + ("projection_target".equals(table) ? "created_at=now()" : "state=state")));
                assertThrows(
                        SQLException.class, () -> worker.createStatement().execute("DELETE FROM runtime." + table));
            }
        }
        try (Connection publicOnly = connection()) {
            publicOnly.createStatement().execute("CREATE ROLE projection_unprivileged NOLOGIN");
            publicOnly.createStatement().execute("SET ROLE projection_unprivileged");
            for (String table : new String[] {"projection_target", "projection_delivery", "projection_attempt"}) {
                assertThrows(
                        SQLException.class,
                        () -> publicOnly.createStatement().executeQuery("SELECT * FROM runtime." + table));
                assertThrows(
                        SQLException.class,
                        () -> publicOnly.createStatement().execute("INSERT INTO runtime." + table + " DEFAULT VALUES"));
                assertThrows(SQLException.class, () -> publicOnly
                        .createStatement()
                        .execute("UPDATE runtime." + table + " SET "
                                + ("projection_target".equals(table) ? "created_at=now()" : "state=state")));
                assertThrows(
                        SQLException.class, () -> publicOnly.createStatement().execute("DELETE FROM runtime." + table));
            }
        }
        try (Connection api = connection()) {
            api.createStatement().execute("SET ROLE hide_nest_api");
            assertThrows(SQLException.class, () -> api.createStatement()
                    .execute("ALTER TABLE runtime.projection_target ADD COLUMN forbidden integer"));
            assertThrows(
                    SQLException.class, () -> api.createStatement().execute("TRUNCATE runtime.projection_delivery"));
        }
    }

    private static boolean privilege(String role, String table, String privilege) {
        if ("PUBLIC".equals(role)) {
            return Boolean.TRUE.equals(db.fetchOne(
                            "SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                                    + "CROSS JOIN LATERAL aclexplode(coalesce(c.relacl,acldefault('r',c.relowner))) a "
                                    + "WHERE n.nspname='runtime' AND c.relname=? AND a.grantee=0 "
                                    + "AND a.privilege_type=?)",
                            table,
                            privilege)
                    .get(0, Boolean.class));
        }
        return Boolean.TRUE.equals(
                db.fetchOne("SELECT has_table_privilege(?,? ,?)", role, "runtime." + table, privilege)
                        .get(0, Boolean.class));
    }

    private static void trigger(String table, String operation) {
        db.execute("CREATE TRIGGER projection_injected BEFORE " + operation + " ON runtime." + table
                + " FOR EACH ROW EXECUTE FUNCTION public.projection_injected_failure()");
    }

    private static void dropTrigger(String table) {
        db.execute("DROP TRIGGER projection_injected ON runtime." + table);
    }

    private static String state(UUID event) {
        return db.fetchOne("SELECT state FROM runtime.projection_delivery WHERE event_id=?::uuid", event)
                .get(0, String.class);
    }

    private static String attemptState(Claim claim) {
        return db.fetchOne("SELECT state FROM runtime.projection_attempt WHERE attempt_id=?::uuid", claim.attemptId())
                .get(0, String.class);
    }

    private static JooqProjectionLedgerAdapter ledger(OffsetDateTime time, int budget) {
        return new JooqProjectionLedgerAdapter(
                db, Clock.fixed(time.toInstant(), ZoneOffset.UTC), budget, Duration.ofSeconds(1));
    }

    private static Target target() {
        return new Target(
                WORLD,
                1,
                new SchemaManifest("index-schema:synthetic", "v1"),
                new EmbeddingManifest("embedding:synthetic", 8, "v1"));
    }

    private static Result applied(Claim claim) {
        return new Result(
                claim.attemptId(),
                claim.ownerRef(),
                claim.binding(),
                ResultKind.APPLIED,
                RECEIPT,
                claim.binding().expectedRevisionId(),
                null);
    }

    private static Result failed(Claim claim, ResultKind kind, String code) {
        return new Result(claim.attemptId(), claim.ownerRef(), claim.binding(), kind, RECEIPT, null, code);
    }

    private static Binding changed(
            Binding b,
            String world,
            UUID event,
            int projection,
            int attempt,
            SchemaManifest schema,
            EmbeddingManifest embedding,
            String digest) {
        return new Binding(
                b.taskId(), world, event, projection, attempt, schema, embedding, digest, b.expectedRevisionId());
    }

    private static Binding withGeneration(Binding b, int generation) {
        return changed(
                b,
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                generation,
                b.schema(),
                b.embedding(),
                b.materialDigest());
    }

    private static Binding withTask(Binding b) {
        return new Binding(
                UUID.randomUUID(),
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                b.materialDigest(),
                b.expectedRevisionId());
    }

    private static Binding withRevision(Binding b) {
        return new Binding(
                b.taskId(),
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                b.materialDigest(),
                UUID.randomUUID());
    }

    private static Binding withEvent(Binding b, UUID event) {
        return changed(
                b,
                b.worldRef(),
                event,
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                b.materialDigest());
    }

    private static Binding withWorld(Binding b, String world) {
        return changed(
                b,
                world,
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                b.materialDigest());
    }

    private static Binding withProjection(Binding b, int generation) {
        return changed(
                b,
                b.worldRef(),
                b.eventId(),
                generation,
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                b.materialDigest());
    }

    private static Binding withSchema(Binding b) {
        return changed(
                b,
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                new SchemaManifest("index-schema:wrong", "v1"),
                b.embedding(),
                b.materialDigest());
    }

    private static Binding withEmbedding(Binding b) {
        return changed(
                b,
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                new EmbeddingManifest("embedding:wrong", 8, "v1"),
                b.materialDigest());
    }

    private static Binding withDigest(Binding b) {
        return changed(
                b,
                b.worldRef(),
                b.eventId(),
                b.projectionGeneration(),
                b.attemptGeneration(),
                b.schema(),
                b.embedding(),
                "sha256:" + "3".repeat(64));
    }

    private static UUID event(OffsetDateTime createdAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertEvent(c, id, createdAt);
            c.commit();
        }
        return id;
    }

    private static void insertEvent(Connection c, UUID event, OffsetDateTime createdAt) throws SQLException {
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO memory.record(record_id,type,current_revision_id) " + "VALUES (?::uuid,'CLAIM',?::uuid)",
                record,
                revision);
        execute(
                c,
                "INSERT INTO memory.revision "
                        + "(revision_id,record_id,revision_no,content,subject,scope,perspective,formation_ref,task_id,created_at) "
                        + "VALUES (?::uuid,?::uuid,1,'synthetic','synthetic','synthetic','synthetic',"
                        + "'synthetic',?::uuid,?::timestamptz)",
                revision,
                record,
                formationTask,
                createdAt);
        execute(
                c,
                "INSERT INTO memory.projection_outbox "
                        + "(event_id,event_kind,record_id,revision_id,request_hash,created_at) "
                        + "VALUES (?::uuid,'MEMORY_CREATED',?::uuid,?::uuid,decode(repeat('00',32),'hex'),?::timestamptz)",
                event,
                record,
                revision,
                createdAt);
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    private static int count(String sql) {
        return db.fetchOne(sql).get(0, Integer.class);
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, postgres.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/v2/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) config.target(target);
        return config.load();
    }
}
