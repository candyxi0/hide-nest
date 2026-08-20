package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionExecutionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V016 closeout capture_scope deletion bridge: counter-proof + rollback tests. */
class LocalV1V016CloseoutBridgeTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-14T00:00:00Z"), ZoneId.of("UTC"));
    private static final String HASH_HEX = "ab".repeat(32);
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static DeletionExecutionPort execution;
    private static Path payloadRoot;
    private static String jdbcUrl;
    private static String dbPassword;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        jdbcUrl = postgres.getJdbcUrl();
        dbPassword = password;
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(21, Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, password)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate()
                .migrationsExecuted);
        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        payloadRoot = Files.createTempDirectory("v016-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);
        DeletionPreviewPort previewAdapter = new JooqDeletionPreviewAdapter(dsl);
        DeletionFencePort fenceAdapter = new JooqDeletionFenceAdapter(dsl);
        preview = new LocalV1S3ADeletionPreviewCoordinator(previewAdapter, executor, CLOCK, fenceAdapter, payloadStore);
        confirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), governance, fenceAdapter, previewAdapter, executor, CLOCK);
        execution = new JooqDeletionExecutionAdapter(dsl);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (postgres != null) postgres.stop();
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    // ── 0. V016 migration: empty 16, V015→V016 upgrade 1, repeat 0 ────────────

    @Test
    @DisplayName("0. V016 empty 16, V015→V016 upgrade 1, repeat 0")
    void migrationCounts() throws Exception {
        assertEquals(21L, count("SELECT count(*) FROM public.flyway_schema_history WHERE success"));

        String upgradePassword = UUID.randomUUID().toString();
        PostgreSQLContainer<?> upgradeContainer = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(upgradePassword)
                .withStartupTimeout(Duration.ofSeconds(120));
        try {
            upgradeContainer.start();
            try (Connection conn = DriverManager.getConnection(upgradeContainer.getJdbcUrl(), USER, upgradePassword)) {
                conn.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
                conn.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
            }
            var fw15 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .target("15").load();
            assertEquals(15, fw15.migrate().migrationsExecuted);
            var fw16 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .load();
            assertEquals(6, fw16.migrate().migrationsExecuted);
            assertEquals(0, fw16.migrate().migrationsExecuted);
        } finally {
            upgradeContainer.stop();
        }
    }

    // ── 1. No marker: DELETE a frozen capture_scope_unit is rejected ─────────

    @Test
    @DisplayName("1. no erasure marker: DELETE frozen capture_scope_unit REJECTED")
    void noMarkerDeleteRejected() {
        Fixture mem = createMemory("no-marker");
        UUID scopeId = createFrozenScope(mem.sourceId(), List.of(mem.unitOne(), mem.unitTwo()));
        assertSqlStateContains("23514", () ->
                dsl.execute("DELETE FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId));
        assertEquals(2L, count("SELECT count(*) FROM runtime.capture_scope_unit WHERE scope_id=?", scopeId));
    }

    // ── 2. A-closure marker cannot delete B's scope unit ─────────────────────

    @Test
    @DisplayName("2. A-closure marker deleting B scope unit REJECTED")
    void crossClosureDeleteRejected() {
        Fixture a = createMemory("bridge-a");
        UUID closureA = createConfirmedClosure(a);
        insertMarker(closureA);
        try {
            Fixture b = createMemory("bridge-b");
            UUID scopeB = createFrozenScope(b.sourceId(), List.of(b.unitOne(), b.unitTwo()));
            assertSqlStateContains("23514", () ->
                    dsl.execute("DELETE FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeB));
            assertEquals(2L, count("SELECT count(*) FROM runtime.capture_scope_unit WHERE scope_id=?", scopeB));
        } finally {
            clearMarker(closureA);
        }
    }

    // ── 3. Marker + matching target_id but wrong member_kind REJECTED ─────────

    @Test
    @DisplayName("3. marker + member matching target but not SOURCE_UNIT+DELETE_CANDIDATE REJECTED")
    void wrongDispositionDeleteRejected() {
        Fixture mem = createMemory("wrong-kind");
        UUID scopeId = createFrozenScope(mem.sourceId(), List.of(mem.unitOne(), mem.unitTwo()));
        UUID closureId = insertPayloadMemberClosure(mem.unitOne());
        insertMarker(closureId);
        try {
            assertSqlStateContains("23514", () ->
                    dsl.execute("DELETE FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId));
            assertEquals(2L, count("SELECT count(*) FROM runtime.capture_scope_unit WHERE scope_id=?", scopeId));
        } finally {
            clearMarker(closureId);
        }
    }

    // ── 4. Injected failure after scope-unit delete → full transaction rollback ─

    @Test
    @DisplayName("4. injected failure after scope-unit delete rolls back capture_scope_unit/memory/evidence/pointer/payload task")
    void injectedFailureRollsBackEverything() throws Exception {
        Fixture mem = createMemory("rollback");
        UUID scopeId = createFrozenScope(mem.sourceId(), List.of(mem.unitOne(), mem.unitTwo()));
        UUID closureId = createConfirmedClosure(mem);

        long unitsBefore = count("SELECT count(*) FROM runtime.capture_scope_unit WHERE scope_id=?", scopeId);
        long memoryBefore = count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", mem.memoryId());
        long unitRowsBefore = count("SELECT count(*) FROM evidence.source_unit");
        long taskBefore = count("SELECT count(*) FROM runtime.deletion_payload_task");
        assertEquals(2L, unitsBefore);

        installTrigger(
                "CREATE OR REPLACE FUNCTION public.force_source_unit_delete_failure() RETURNS trigger AS $$ "
                        + "BEGIN RAISE EXCEPTION 'INJECTED_FAILURE: source_unit'; END; $$ LANGUAGE plpgsql",
                "CREATE TRIGGER injected_source_unit_failure BEFORE DELETE ON evidence.source_unit "
                        + "FOR EACH ROW EXECUTE FUNCTION public.force_source_unit_delete_failure()");
        try {
            assertThrows(RuntimeException.class, () ->
                    execution.executeDatabasePhase(UUID.randomUUID(), closureId, OffsetDateTime.now(CLOCK)));
        } finally {
            dropTrigger("injected_source_unit_failure", "evidence.source_unit");
        }

        // Everything rolled back to the pre-execution set.
        assertEquals(unitsBefore, count("SELECT count(*) FROM runtime.capture_scope_unit WHERE scope_id=?", scopeId));
        assertEquals(memoryBefore, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", mem.memoryId()));
        assertEquals(unitRowsBefore, count("SELECT count(*) FROM evidence.source_unit"));
        assertEquals(taskBefore, count("SELECT count(*) FROM runtime.deletion_payload_task"));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", closureId));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker WHERE closure_id=?", closureId));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private Fixture createMemory(String marker) {
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String body = "v016-" + marker + "-body";
        String key = "v016-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key,
                sha256(key),
                actor,
                "Interpretation",
                body,
                sha256(body),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitOne, actor, 1L, "unit-one-" + marker, OffsetDateTime.now(CLOCK), "evidence-one"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitTwo, actor, 2L, "unit-two-" + marker, OffsetDateTime.now(CLOCK), "evidence-two")),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key,
                sha256("confirm-" + key),
                prepared.proposalRevisionId(),
                prepared.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                new byte[32]));
        return new Fixture(memoryId, confirmed.currentRevisionId(), actor, prepared.sourceId(), unitOne, unitTwo);
    }

    /** Creates a frozen capture_scope with the given source units (like closeout Phase 2). */
    private UUID createFrozenScope(UUID sourceId, List<UUID> unitIds) {
        UUID scopeId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(jdbcUrl, USER, dbPassword)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute(
                        "INSERT INTO runtime.capture_scope (scope_id, source_id, from_ordinal, to_ordinal, "
                                + "rule_version, coverage_code, frozen_at, manifest_hash, created_at) "
                                + "VALUES ('" + scopeId + "'::uuid, '" + sourceId
                                + "'::uuid, 1, 2, 'LOCAL_V1_SYNTHETIC', 'SYNTHETIC_SELECTED', NULL, "
                                + "decode(repeat('ab',32),'hex'), clock_timestamp())");
                long ordinal = 1;
                for (UUID unitId : unitIds) {
                    s.execute(
                            "INSERT INTO runtime.capture_scope_unit (scope_id, source_unit_id, ordinal) "
                                    + "VALUES ('" + scopeId + "'::uuid, '" + unitId + "'::uuid, " + ordinal + ")");
                    ordinal = ordinal + 1;
                }
                s.execute("UPDATE runtime.capture_scope SET frozen_at = clock_timestamp() WHERE scope_id='"
                        + scopeId + "'::uuid");
            }
            c.commit();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        return scopeId;
    }

    private UUID createConfirmedClosure(Fixture mem) {
        byte[] requestHash = sha256("v016-preview-" + mem.memoryId());
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "v016-preview-" + mem.memoryId(), requestHash));
        confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                previewResult.previewId(), previewResult.previewRevision(), previewResult.manifestHash(),
                mem.actorId(), "v016-confirm-" + mem.memoryId()));
        return previewResult.previewId();
    }

    private void insertMarker(UUID closureId) {
        dsl.execute("INSERT INTO runtime.deletion_erasure_marker (closure_id, inserted_at) VALUES (?::uuid, clock_timestamp())",
                closureId);
    }

    private void clearMarker(UUID closureId) {
        dsl.execute("DELETE FROM runtime.deletion_erasure_marker WHERE closure_id=?::uuid", closureId);
    }

    /** Inserts a minimal PREVIEWED closure whose only member is a SOURCE_PAYLOAD for targetUnitId. */
    private UUID insertPayloadMemberClosure(UUID targetUnitId) {
        UUID closureId = UUID.randomUUID();
        dsl.transaction(config -> {
            DSLContext tx = DSL.using(config);
            tx.execute(
                    "INSERT INTO memory.deletion_closure (closure_id, root_memory_id, preview_revision, "
                            + "root_current_revision_id, root_revision_no, root_policy_id, root_policy_revision_no, "
                            + "request_idempotency_key, request_hash, manifest_hash, state, created_at, expires_at) "
                            + "VALUES (?::uuid, ?::uuid, 1, ?::uuid, 1, ?::uuid, 1, ?, "
                            + "decode(repeat('ab',32),'hex'), decode(repeat('cd',32),'hex'), 'PREVIEWED', "
                            + "clock_timestamp(), clock_timestamp() + interval '1 hour')",
                    closureId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "v016-key-" + closureId);
            tx.execute(
                    "INSERT INTO memory.deletion_closure_member (closure_id, ordinal, member_kind, target_id, "
                            + "target_revision_ref, disposition, size_bytes, content_hash) "
                            + "VALUES (?::uuid, 1, 'SOURCE_PAYLOAD', ?::uuid, NULL, 'DELETE_CANDIDATE', 1, "
                            + "decode(repeat('ef',32),'hex'))",
                    closureId, targetUnitId);
        });
        return closureId;
    }

    private static void installTrigger(String functionSql, String triggerSql) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute(functionSql);
            s.execute(triggerSql);
        }
    }

    private static void dropTrigger(String trigger, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            s.execute("DROP FUNCTION IF EXISTS public.force_source_unit_delete_failure()");
        }
    }

    private static long count(String sql, Object... binds) {
        return ((Number) dsl.fetchValue(sql, binds)).longValue();
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static void assertSqlStateContains(String expected, Runnable operation) {
        RuntimeException ex = assertThrows(RuntimeException.class, operation::run);
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().contains(expected)) {
                    return;
                }
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLSTATE containing " + expected + " but got: " + ex.getMessage(), ex);
    }

    private record Fixture(UUID memoryId, UUID revisionId, UUID actorId, UUID sourceId, UUID unitOne, UUID unitTwo) {}
}
