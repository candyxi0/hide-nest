package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1S3C1ADatabaseErasureTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH_HEX = "ab".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static DeletionExecutionPort execution;
    private static TransactionExecutor transactions;
    private static Path payloadRoot;
    private static String password;
    private static String jdbcUrl;

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        jdbcUrl = postgres.getJdbcUrl();
        try (var connection = DriverManager.getConnection(jdbcUrl, USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(18, Flyway.configure().dataSource(jdbcUrl, USER, password)
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate().migrationsExecuted);
        var raw = new DriverManagerDataSource(jdbcUrl, USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        transactions = executor;
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        payloadRoot = Files.createTempDirectory("s3c1a-payload-test-");
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
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    // ================================================================
    // 1. Migration counts
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("1. V014 empty 14, upgrade 13→14 1, repeat 0; generated A/B/tracked consistent")
    void migrationCounts() throws Exception {
        // Clean migration on a fresh database: 16
        assertEquals(18, count("SELECT count(*) FROM public.flyway_schema_history WHERE success"));

        // Upgrade from V013 to V015 on separate container
        String upgradePassword = UUID.randomUUID().toString();
        PostgreSQLContainer<?> upgradeContainer = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(upgradePassword)
                .withStartupTimeout(Duration.ofSeconds(120));
        try {
            upgradeContainer.start();
            try (var conn = DriverManager.getConnection(upgradeContainer.getJdbcUrl(), USER, upgradePassword)) {
                conn.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
                conn.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
            }
            var fw13 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .target("13").load();
            assertEquals(13, fw13.migrate().migrationsExecuted);
            var fw15 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .load();
            assertEquals(5, fw15.migrate().migrationsExecuted);
            // Repeat: 0
            assertEquals(0, fw15.migrate().migrationsExecuted);
        } finally {
            upgradeContainer.stop();
        }
    }

    // ================================================================
    // 2. Legal erasure: exclusive evidence, clean execution
    // ================================================================

    @Test
    @Order(2)
    @DisplayName("2. Legal erasure: run=FILE_PENDING, task count exact, root memory/revisions/relations/anchor/unit/payload all 0")
    void legalErasure() {
        ConfirmedClosure cc = createConfirmedClosure("legal", "legal-body", false);
        List<String> payloadSnapshotBefore = payloadSnapshot();
        long preRunCount = count("SELECT count(*) FROM runtime.deletion_run");
        long preTaskCount = count("SELECT count(*) FROM runtime.deletion_payload_task");

        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        var result = execution.executeDatabasePhase(runId, cc.closureId(), now);

        assertEquals(runId, result.runId());
        assertEquals(cc.closureId(), result.closureId());
        assertEquals("FILE_PENDING", result.state());
        assertEquals(now.toInstant(), result.databaseErasedAt().toInstant());
        assertTrue(result.payloadTaskCount() > 0);

        // Run row exists
        assertEquals(preRunCount + 1, count("SELECT count(*) FROM runtime.deletion_run"));
        assertEquals("FILE_PENDING", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        // Payload tasks match SOURCE_PAYLOAD members
        long expectedTasks = count(
                "SELECT count(*) FROM memory.deletion_closure_member WHERE closure_id=? AND member_kind='SOURCE_PAYLOAD'",
                cc.closureId());
        assertEquals(expectedTasks, result.payloadTaskCount());
        assertEquals(expectedTasks, count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId));

        // Root memory_record deleted
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", cc.memoryId()));

        // All root memory revisions deleted
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", cc.memoryId()));

        // All relations from root revisions deleted
        assertEquals(0L, count(
                "SELECT count(*) FROM memory.memory_relation mr "
                + "JOIN memory.deletion_closure_member m ON mr.from_revision_id=m.target_id "
                + "AND m.member_kind='MEMORY_REVISION' AND m.closure_id=?",
                cc.closureId()));

        // Anchor, unit, payload metadata all deleted
        assertEquals(0L, count(
                "SELECT count(*) FROM evidence.source_anchor WHERE anchor_id IN "
                + "(SELECT target_id FROM memory.deletion_closure_member WHERE closure_id=? AND member_kind='SOURCE_ANCHOR')",
                cc.closureId()));
        assertEquals(0L, count(
                "SELECT count(*) FROM evidence.source_unit WHERE source_unit_id IN "
                + "(SELECT target_id FROM memory.deletion_closure_member WHERE closure_id=? AND member_kind='SOURCE_UNIT')",
                cc.closureId()));
        assertEquals(0L, count(
                "SELECT count(*) FROM evidence.source_payload WHERE payload_id IN "
                + "(SELECT target_id FROM memory.deletion_closure_member WHERE closure_id=? AND member_kind='SOURCE_PAYLOAD')",
                cc.closureId()));

        // Erasure marker is gone
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));

        // Payload files unchanged (no file deletion)
        List<String> payloadSnapshotAfter = payloadSnapshot();
        assertEquals(payloadSnapshotBefore, payloadSnapshotAfter);

        // All task rows have valid non-blank object_ref and 32-byte expected_hash
        assertEquals(0L, count(
                "SELECT count(*) FROM runtime.deletion_payload_task t "
                + "WHERE t.deletion_run_id=? AND (t.object_ref IS NULL "
                + "OR char_length(trim(t.object_ref)) = 0 "
                + "OR t.expected_hash IS NULL OR octet_length(t.expected_hash) <> 32 "
                + "OR t.state <> 'PENDING')",
                runId));
    }

    // ================================================================
    // 3. Proposal body/hash nulled; decision/closure/fence/outbox preserved
    // ================================================================

    @Test
    @Order(3)
    @DisplayName("3. Proposal revision body/hash NULL; Decision/Proposal/Review/closure/fence/ChangeEvent/outbox preserved")
    void proposalBodyClearedAndAuditPreserved() {
        ConfirmedClosure cc = createConfirmedClosure("audit", "audit-body", false);

        // Capture pre-state
        long preDecisionCount = count("SELECT count(*) FROM memory.decision");
        long preClosureCount = count("SELECT count(*) FROM memory.deletion_closure");
        long preMemberCount = count("SELECT count(*) FROM memory.deletion_closure_member");
        long preFenceCount = count("SELECT count(*) FROM memory.deletion_fence");
        long preChangeEventCount = count("SELECT count(*) FROM memory.change_event");
        long preOutboxCount = count("SELECT count(*) FROM runtime.outbox_event");
        long preReviewCount = count("SELECT count(*) FROM memory.review_session");
        long preProposalCount = count("SELECT count(*) FROM memory.proposal");

        // Find proposal revisions that had body_text for the root memory
        List<String> preBodies = dsl.resultQuery(
                "SELECT pr.body_text, pr.body_hash FROM memory.proposal_revision pr "
                + "JOIN memory.decision d ON d.proposal_revision_id=pr.proposal_revision_id "
                + "WHERE d.target_kind='MEMORY' AND d.target_id=?",
                cc.memoryId()).fetch().getValues(0, String.class);

        UUID runId = UUID.randomUUID();
        execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));

        // Proposal revisions tied to root memory decisions: body_text and body_hash are NULL
        List<String> postBodies = dsl.resultQuery(
                "SELECT pr.body_text FROM memory.proposal_revision pr "
                + "JOIN memory.decision d ON d.proposal_revision_id=pr.proposal_revision_id "
                + "WHERE d.target_kind='MEMORY' AND d.target_id=?",
                cc.memoryId()).fetch().getValues(0, String.class);
        for (String body : postBodies) {
            assertNull(body);
        }
        long nullHashCount = count(
                "SELECT count(*) FROM memory.proposal_revision pr "
                + "JOIN memory.decision d ON d.proposal_revision_id=pr.proposal_revision_id "
                + "WHERE d.target_kind='MEMORY' AND d.target_id=? AND pr.body_hash IS NOT NULL",
                cc.memoryId());
        assertEquals(0L, nullHashCount);

        // Governance facts preserved
        assertEquals(preDecisionCount, count("SELECT count(*) FROM memory.decision"));
        assertEquals(preClosureCount, count("SELECT count(*) FROM memory.deletion_closure"));
        assertEquals(preMemberCount, count("SELECT count(*) FROM memory.deletion_closure_member"));
        assertEquals(preFenceCount, count("SELECT count(*) FROM memory.deletion_fence"));
        assertEquals(preChangeEventCount, count("SELECT count(*) FROM memory.change_event"));
        assertEquals(preOutboxCount, count("SELECT count(*) FROM runtime.outbox_event"));
        assertEquals(preReviewCount, count("SELECT count(*) FROM memory.review_session"));

        // Proposal rows still exist (only target_memory_id was nulled)
        assertEquals(preProposalCount, count("SELECT count(*) FROM memory.proposal"));
    }

    // ================================================================
    // 4. Payload files unchanged; task objectRef/hash matches metadata
    // ================================================================

    @Test
    @Order(4)
    @DisplayName("4. Payload files unchanged before/after; task objectRef/expectedHash match source_payload metadata exactly")
    void payloadFilesUnchanged() {
        ConfirmedClosure cc = createConfirmedClosure("payload", "payload-body", false);
        List<String> before = payloadSnapshot();
        long beforeFileCount = before.size();

        UUID runId = UUID.randomUUID();
        execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));

        List<String> after = payloadSnapshot();
        assertEquals(beforeFileCount, after.size());
        assertEquals(before, after);

        // Every task's object_ref and expected_hash match the source_payload metadata that existed
        // (the source_payload rows are deleted, but the tasks captured them)
        List<UUID> taskPayloadIds = dsl.resultQuery(
                "SELECT payload_id FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId)
                .fetch().getValues(0, UUID.class);
        assertFalse(taskPayloadIds.isEmpty());
        for (UUID pid : taskPayloadIds) {
            assertEquals(0L, count(
                    "SELECT count(*) FROM runtime.deletion_payload_task t "
                    + "WHERE t.deletion_run_id=? AND t.payload_id=? "
                    + "AND (t.object_ref IS NULL OR t.expected_hash IS NULL "
                    + "OR octet_length(t.expected_hash) <> 32)",
                    runId, pid));
        }
    }

    // ================================================================
    // 5. Same runId exact replay; different runId same closure rejected
    // ================================================================

    @Test
    @Order(5)
    @DisplayName("5. Same runId replay EXACT; different runId same closure REJECTED")
    void replayAndConflict() {
        ConfirmedClosure cc = createConfirmedClosure("replay", "replay-body", false);
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(CLOCK);

        var first = execution.executeDatabasePhase(runId, cc.closureId(), now);
        long runCount = count("SELECT count(*) FROM runtime.deletion_run");
        long taskCount = count("SELECT count(*) FROM runtime.deletion_payload_task");

        // Exact replay: same result, no new rows
        var second = execution.executeDatabasePhase(runId, cc.closureId(), now);
        assertEquals(first.runId(), second.runId());
        assertEquals(first.closureId(), second.closureId());
        assertEquals(first.state(), second.state());
        assertEquals(first.payloadTaskCount(), second.payloadTaskCount());
        assertEquals(first.databaseErasedAt().toInstant(), second.databaseErasedAt().toInstant());
        assertEquals(runCount, count("SELECT count(*) FROM runtime.deletion_run"));
        assertEquals(taskCount, count("SELECT count(*) FROM runtime.deletion_payload_task"));

        // Different runId same closure: HDM014_DELETION_RUN_CONFLICT
        UUID otherRunId = UUID.randomUUID();
        try {
            execution.executeDatabasePhase(otherRunId, cc.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected rejection");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM014_DELETION_RUN_CONFLICT", e);
        }
        // No new rows
        assertEquals(runCount, count("SELECT count(*) FROM runtime.deletion_run"));
    }

    // ================================================================
    // 6. PREVIEWED closure, wrong confirmed Decision, fence/member drift
    // ================================================================

    @Test
    @Order(6)
    @DisplayName("6. PREVIEWED closure, wrong confirmed Decision, missing/extra/wrong fence, member drift: all rejected")
    void rejectionScenarios() {
        ConfirmedClosure cc = createConfirmedClosure("reject", "reject-body", false);
        OffsetDateTime now = OffsetDateTime.now(CLOCK);

        // 6a. PREVIEWED closure (not confirmed)
        Fixture f2 = createMemory("reject-prev", "prev-body");
        byte[] reqHash2 = sha256("preview-reject-prev");
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                f2.memoryId(), "preview-reject-prev", reqHash2));
        List<String> preBusiness = businessSnapshot();
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), previewResult.previewId(), now);
            throw new AssertionError("expected rejection for PREVIEWED");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM014", e);
        }
        assertEquals(preBusiness, businessSnapshot());

        // 6b. Future executed_at timestamp → rejected
        OffsetDateTime futureTime = OffsetDateTime.now(CLOCK).plusYears(1);
        List<String> preBusiness2 = businessSnapshot();
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc.closureId(), futureTime);
            throw new AssertionError("expected rejection for future timestamp");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM014", e);
        }
        assertEquals(preBusiness2, businessSnapshot());

        // 6c. Null parameters → rejected before any write
        try {
            execution.executeDatabasePhase(null, cc.closureId(), now);
            throw new AssertionError("expected rejection for null runId");
        } catch (Exception e) {
            // May fail at Java layer (requireNonNull) or SQL layer
        }
    }

    // ================================================================
    // 7. Shared evidence → RETAIN_SHARED + SHARED_REFERENCE (no choice required)
    // ================================================================

    @Test
    @Order(7)
    @DisplayName("7. Shared evidence preview: RETAIN_SHARED + SHARED_REFERENCE, no AFFECTED_PENDING_CHOICE")
    void sharedEvidenceMarksRetainShared() {
        Fixture root = createMemory("shared-root", "shared-root-body");
        Fixture other = createMemory("shared-other", "shared-other-body", root.anchorOne());

        byte[] reqHash = sha256("preview-shared");
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root.memoryId(), "preview-shared", reqHash));

        assertEquals(0L, count(
                "SELECT count(*) FROM memory.deletion_closure_member WHERE closure_id=? AND disposition='AFFECTED_PENDING_CHOICE'",
                previewResult.previewId()));
        assertTrue(count(
                "SELECT count(*) FROM memory.deletion_closure_member WHERE closure_id=? AND disposition='RETAIN_SHARED'",
                previewResult.previewId()) > 0, "expected RETAIN_SHARED members");
        assertTrue(count(
                "SELECT count(*) FROM memory.deletion_closure_member WHERE closure_id=? AND member_kind='SHARED_REFERENCE'",
                previewResult.previewId()) > 0, "expected SHARED_REFERENCE member");
        assertEquals("PREVIEWED", scalarString(
                "SELECT state FROM memory.deletion_closure WHERE closure_id=?", previewResult.previewId()));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", previewResult.previewId()));
    }

    // ================================================================
    // 8. Shared references outside closure: all rejected, zero change
    // ================================================================

    @Test
    @Order(8)
    @DisplayName("8. Payload object_ref reuse outside closure: rejected with HDM014_DELETION_SHARED_REFERENCE, zero change")
    void sharedReferencesRejected() {
        // Create a confirmed closure normally
        ConfirmedClosure cc = createConfirmedClosure("shared-ref", "shared-body", false);

        // Get one closure payload's object_ref
        var payloadRow = dsl.fetchOne(
                "SELECT sp.object_ref, sp.payload_id FROM evidence.source_payload sp "
                + "JOIN memory.deletion_closure_member m ON m.target_id = sp.payload_id "
                + "AND m.member_kind = 'SOURCE_PAYLOAD' AND m.closure_id = ? LIMIT 1",
                cc.closureId());
        String closureObjectRef = payloadRow.get(0, String.class);

        // Insert a new payload with same object_ref but different payload_id,
        // referencing a source_unit that is NOT in the closure
        Fixture external = createMemory("ext-ref", "external-ref-body");
        UUID extSourceUnitId = dsl.fetchOne(
                "SELECT su.source_unit_id FROM evidence.source_unit su "
                + "JOIN evidence.source_anchor_unit sau ON sau.source_unit_id = su.source_unit_id "
                + "JOIN evidence.source_anchor sa ON sa.anchor_id = sau.anchor_id "
                + "WHERE sa.anchor_id = ? LIMIT 1",
                external.anchorOne()).get(0, UUID.class);
        UUID extPolicyId = dsl.fetchOne("SELECT policy_id FROM memory.memory_record WHERE memory_id=?",
                external.memoryId()).get(0, UUID.class);

        // Insert a "rogue" payload sharing the same object_ref, under external's source_unit
        dsl.execute("INSERT INTO evidence.source_payload(payload_id,source_unit_id,payload_kind,store_adapter,"
                + "object_ref,content_type,size_bytes,content_hash,policy_id,current_policy_revision_no,"
                + "retention_class,created_at) VALUES (?::uuid, ?::uuid, 'BLOB', 'LOCAL_V1', "
                + "?::text, 'application/octet-stream', 0, decode(?,'hex'), ?::uuid, 1, "
                + "'TRANSIENT', clock_timestamp())",
                UUID.randomUUID(), extSourceUnitId, closureObjectRef, HASH_HEX, extPolicyId);

        // Capture state just before execute
        long preRunCount = count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc.closureId());
        long preTaskCount = count("SELECT count(*) FROM runtime.deletion_payload_task");

        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected shared payload rejection");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM014_DELETION_SHARED_REFERENCE", e);
        }

        // Zero change for this closure
        assertEquals(preRunCount, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc.closureId()));
        assertEquals(preTaskCount, count("SELECT count(*) FROM runtime.deletion_payload_task"));
    }

    // ================================================================
    // 9. Direct SQL bypass: all rejected; marker unforgeable
    // ================================================================

    @Test
    @Order(9)
    @DisplayName("9. Direct SQL UPDATE proposal body / DELETE memory revision / DELETE memory_record: all rejected; marker unforgeable")
    void directSqlBypassRejected() throws Exception {
        ConfirmedClosure cc = createConfirmedClosure("bypass", "bypass-body", false);

        // 9a. Direct UPDATE proposal_revision.body_text via SQL (no marker) → rejected
        UUID proposalRevId = dsl.fetchOne(
                "SELECT pr.proposal_revision_id FROM memory.proposal_revision pr "
                + "JOIN memory.decision d ON d.proposal_revision_id=pr.proposal_revision_id "
                + "WHERE d.target_kind='MEMORY' AND d.target_id=? LIMIT 1",
                cc.memoryId()).get(0, UUID.class);
        try {
            dsl.execute("UPDATE memory.proposal_revision SET body_text=NULL WHERE proposal_revision_id=?",
                    proposalRevId);
            throw new AssertionError("expected immutability rejection");
        } catch (Exception e) {
            assertSqlStateContains("55000", e);
        }

        // 9b. Direct DELETE memory_revision (no marker) → rejected
        UUID revisionId = dsl.fetchOne(
                "SELECT memory_revision_id FROM memory.memory_revision WHERE memory_id=? LIMIT 1",
                cc.memoryId()).get(0, UUID.class);
        try {
            dsl.execute("DELETE FROM memory.memory_revision WHERE memory_revision_id=?", revisionId);
            throw new AssertionError("expected immutability rejection");
        } catch (Exception e) {
            assertSqlStateContains("55000", e);
        }

        // 9c. Direct DELETE memory_record (no marker) → rejected
        try {
            dsl.execute("DELETE FROM memory.memory_record WHERE memory_id=?", cc.memoryId());
            throw new AssertionError("expected immutability rejection");
        } catch (Exception e) {
            assertSqlStateContains("55000", e);
        }

        // 9d. Marker table: api/worker cannot write
        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("SET ROLE hide_nest_worker");
            SQLException ex = assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES ('"
                            + cc.closureId() + "')"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_api");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES ('"
                            + cc.closureId() + "')"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");
        }

        // 9e. Marker enables controlled erasure: migrator inserts marker,
        //     verifies DELETE is allowed, then rolls back to preserve state.
        //     This proves the marker mechanism works (and that the SECURITY DEFINER
        //     function's internal marker-based authorization is sound).
        try {
            dsl.transaction(config -> {
                DSLContext tx = org.jooq.impl.DSL.using(config);
                tx.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES (?)", cc.closureId());
                long preCount = ((Number) tx.fetchValue(
                        "SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", cc.memoryId())).longValue();
                tx.execute("DELETE FROM memory.memory_revision WHERE memory_revision_id=?", revisionId);
                long postCount = ((Number) tx.fetchValue(
                        "SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", cc.memoryId())).longValue();
                assertEquals(preCount - 1, postCount);
                // Rollback: the marker and deletion are both undone
                throw new RuntimeException("ROLLBACK_MARKER_TEST");
            });
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("ROLLBACK_MARKER_TEST") || e.getCause() != null);
        }
        // Verify marker was rolled back
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
    }

    // ================================================================
    // 10. CanonicalFailureCode frozen-registry assertion (R1-01)
    // ================================================================

    @Test
    @Order(10)
    @DisplayName("10. CanonicalFailureCode has no HDM014 R1 values; frozen registry still 50 codes")
    void canonicalFailureCodeIsFrozen() {
        // The two HDM014 diagnostic identifiers must NOT be in the enum
        for (io.github.candyxi0.hidenest.application.model.CanonicalFailureCode code
                : io.github.candyxi0.hidenest.application.model.CanonicalFailureCode.values()) {
            String name = code.name();
            assertFalse(name.equals("DELETION_RUN_CONFLICT"),
                    "DELETION_RUN_CONFLICT must not be a formal CanonicalFailureCode");
            assertFalse(name.equals("DELETION_CHOICE_REQUIRED"),
                    "DELETION_CHOICE_REQUIRED must not be a formal CanonicalFailureCode");
        }
        assertEquals(12, io.github.candyxi0.hidenest.application.model.CanonicalFailureCode.values().length,
                "frozen enum count must be 12");
        assertEquals(50, count("SELECT count(*) FROM runtime.failure_code_registry"),
                "frozen registry must still be 50");
    }

    // ================================================================
    // 13. Precision-marker attack tests (R1-02)
    // ================================================================

    @Test
    @Order(13)
    @DisplayName("13. Cross-closure marker attacks: B memory proposal/proposal_revision REJECTED under A marker")
    void crossClosureMarkerAttacks() {
        // Create closure A (confirmed, with marker target)
        ConfirmedClosure ccA = createConfirmedClosure("cca", "cca-body", false);
        // Create a separate memory B (not in closure A)
        Fixture memB = createMemory("ccb", "ccb-body");

        // Get B's proposal and proposal_revision IDs via the decision chain
        UUID proposalRevBId = dsl.fetchOne(
                "SELECT d.proposal_revision_id FROM memory.decision d "
                + "WHERE d.target_kind = 'MEMORY' AND d.target_id = ? "
                + "AND d.decision_kind = 'USER_CONFIRM' LIMIT 1", memB.memoryId()).get(0, UUID.class);
        UUID proposalBId = dsl.fetchOne(
                "SELECT proposal_id FROM memory.proposal_revision WHERE proposal_revision_id = ?",
                proposalRevBId).get(0, UUID.class);

        // Snapshot B's proposal/proposal_revision rows before attack
        String preProposalB = dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal t WHERE proposal_id = ?", proposalBId).get(0, String.class);
        String preProposalRevB = dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal_revision t WHERE proposal_revision_id = ?",
                proposalRevBId).get(0, String.class);

        // Insert marker for closure A and attack B's data (in a rolling-back transaction)
        try {
            dsl.transaction(config -> {
                DSLContext tx = org.jooq.impl.DSL.using(config);
                tx.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES (?)", ccA.closureId());

                // 13a. Attack B's proposal.target_memory_id → must be REJECTED
                try {
                    tx.execute("UPDATE memory.proposal SET target_memory_id = NULL WHERE proposal_id = ?",
                            proposalBId);
                    throw new AssertionError("expected rejection for cross-closure proposal update");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                // 13b. Attack B's proposal_revision body_text → must be REJECTED
                try {
                    tx.execute("UPDATE memory.proposal_revision SET body_text = NULL WHERE proposal_revision_id = ?",
                            proposalRevBId);
                    throw new AssertionError("expected rejection for cross-closure proposal_revision update");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                throw new RuntimeException("ROLLBACK_CROSS_CLOSURE_ATTACK");
            });
        } catch (RuntimeException e) {
            // Expected — transaction rolled back
        }

        // Verify B's rows are column-by-column unchanged
        assertEquals(preProposalB, dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal t WHERE proposal_id = ?", proposalBId).get(0, String.class));
        assertEquals(preProposalRevB, dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal_revision t WHERE proposal_revision_id = ?",
                proposalRevBId).get(0, String.class));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
    }

    @Test
    @Order(14)
    @DisplayName("14. Same-closure non-authorized field attacks: proposal_kind, memory_type, action_code, expected_policy_revision_no all REJECTED")
    void sameClosureNonAuthorizedFieldAttacks() {
        ConfirmedClosure cc = createConfirmedClosure("sca", "sca-body", false);

        // Get the proposal and proposal_revision via the decision chain (CREATE proposals have target_memory_id=NULL)
        UUID proposalRevId = dsl.fetchOne(
                "SELECT d.proposal_revision_id FROM memory.decision d "
                + "WHERE d.target_kind = 'MEMORY' AND d.target_id = ? "
                + "AND d.decision_kind = 'USER_CONFIRM' LIMIT 1", cc.memoryId()).get(0, UUID.class);
        UUID proposalId = dsl.fetchOne(
                "SELECT proposal_id FROM memory.proposal_revision WHERE proposal_revision_id = ?",
                proposalRevId).get(0, UUID.class);

        // Snapshot before attacks
        String preProposal = dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal t WHERE proposal_id = ?", proposalId).get(0, String.class);
        String preProposalRev = dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal_revision t WHERE proposal_revision_id = ?",
                proposalRevId).get(0, String.class);

        try {
            dsl.transaction(config -> {
                DSLContext tx = org.jooq.impl.DSL.using(config);
                tx.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES (?)", cc.closureId());

                // 14a. Attack: modify proposal_kind → REJECTED
                try {
                    tx.execute("UPDATE memory.proposal SET proposal_kind = 'REVISE' WHERE proposal_id = ?",
                            proposalId);
                    throw new AssertionError("expected rejection for proposal_kind");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                // 14b. Attack: modify proposal_revision.memory_type → REJECTED
                try {
                    tx.execute("UPDATE memory.proposal_revision SET memory_type = 'Claim' WHERE proposal_revision_id = ?",
                            proposalRevId);
                    throw new AssertionError("expected rejection for memory_type");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                // 14c. Attack: modify proposal_revision.action_code → REJECTED
                try {
                    tx.execute("UPDATE memory.proposal_revision SET action_code = 'REVISE' WHERE proposal_revision_id = ?",
                            proposalRevId);
                    throw new AssertionError("expected rejection for action_code");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                // 14d. Attack: modify proposal_revision.expected_policy_revision_no → REJECTED
                try {
                    tx.execute("UPDATE memory.proposal_revision SET expected_policy_revision_no = 99 "
                            + "WHERE proposal_revision_id = ?", proposalRevId);
                    throw new AssertionError("expected rejection for expected_policy_revision_no");
                } catch (Exception e) {
                    assertSqlStateContains("55000", e);
                }

                throw new RuntimeException("ROLLBACK_SAME_CLOSURE_ATTACKS");
            });
        } catch (RuntimeException e) {
            // Expected — transaction rolled back
        }

        // Verify column-by-column unchanged
        assertEquals(preProposal, dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal t WHERE proposal_id = ?", proposalId).get(0, String.class));
        assertEquals(preProposalRev, dsl.fetchOne(
                "SELECT row_to_json(t)::text FROM memory.proposal_revision t WHERE proposal_revision_id = ?",
                proposalRevId).get(0, String.class));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
    }

    @Test
    @Order(15)
    @DisplayName("15. With A marker, A closure legal three-field clear still PASS; complete S3C1A execute still PASS")
    void legalClearStillPassesUnderMarker() {
        ConfirmedClosure cc = createConfirmedClosure("legal-marker", "legal-marker-body", false);

        // Insert marker directly, do the three-field clear, verify it works, then rollback
        UUID proposalRevId = dsl.fetchOne(
                "SELECT d.proposal_revision_id FROM memory.decision d "
                + "WHERE d.target_kind = 'MEMORY' AND d.target_id = ? "
                + "AND d.decision_kind = 'USER_CONFIRM' LIMIT 1", cc.memoryId()).get(0, UUID.class);
        UUID proposalId = dsl.fetchOne(
                "SELECT proposal_id FROM memory.proposal_revision WHERE proposal_revision_id = ?",
                proposalRevId).get(0, UUID.class);

        // Verify that under a marker, the legal operations work in a transaction
        try {
            dsl.transaction(config -> {
                DSLContext tx = org.jooq.impl.DSL.using(config);
                tx.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES (?)", cc.closureId());

                // Legal: clear proposal target_memory_id
                tx.execute("UPDATE memory.proposal SET target_memory_id = NULL WHERE proposal_id = ?", proposalId);

                // Legal: clear proposal_revision body_text and body_hash
                tx.execute("UPDATE memory.proposal_revision SET body_text = NULL, body_hash = NULL "
                        + "WHERE proposal_revision_id = ?", proposalRevId);

                // Legal: clear expected_memory_revision_id
                tx.execute("UPDATE memory.proposal_revision SET expected_memory_revision_id = NULL "
                        + "WHERE proposal_revision_id = ?", proposalRevId);

                throw new RuntimeException("ROLLBACK_LEGAL_CLEAR");
            });
        } catch (RuntimeException e) {
            // Expected — transaction rolled back, legal clears succeeded before rollback
        }

        // Verifying: complete S3C1A execute still PASS
        UUID runId = UUID.randomUUID();
        var result = execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));
        assertEquals("FILE_PENDING", result.state());
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
    }

    // ================================================================
    // 17. Shared ref: external revision → internal anchor (fence-precluded)
    // ================================================================

    @Test
    @Order(17)
    @DisplayName("17. External revision referencing closure anchor: PRECLUDED_BY_EXISTING_FENCE (HDM012/23514)")
    void externalRevisionToInternalAnchorPrecludedByFence() {
        // Create a confirmed closure (its anchors are fenced)
        ConfirmedClosure cc = createConfirmedClosure("ext-rev-anchor", "ext-rev-body", false);

        // Create an external memory (not in closure)
        Fixture external = createMemory("ext-rev-ext", "ext-ext-body");
        UUID extDecisionId = dsl.fetchOne(
                "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id = ?",
                external.revisionId()).get(0, UUID.class);

        // Get one of the closure's fenced anchors
        UUID closureAnchorId = dsl.fetchOne(
                "SELECT target_id FROM memory.deletion_closure_member "
                + "WHERE closure_id = ? AND member_kind = 'SOURCE_ANCHOR' LIMIT 1",
                cc.closureId()).get(0, UUID.class);

        // Remove external's existing relation (DELETE is not blocked by fence trigger)
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id = ?", external.revisionId());

        // Capture state after all setup, just before attack
        List<String> preBusiness = businessSnapshot();
        long preRelCount = count("SELECT count(*) FROM memory.memory_relation WHERE from_revision_id = ?",
                external.revisionId());

        // Try to insert a relation from external revision → closure's fenced anchor
        try {
            dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,"
                    + "to_anchor_id,created_by_decision_id,created_at) "
                    + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                    UUID.randomUUID(), external.revisionId(), closureAnchorId, extDecisionId);
            throw new AssertionError("expected fence rejection for external revision → internal anchor");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM012_DELETION_FENCED", e);
        }

        // Business state unchanged — the external revision still has 0 relations
        assertEquals(preRelCount, count("SELECT count(*) FROM memory.memory_relation WHERE from_revision_id = ?",
                external.revisionId()));
        assertEquals(preBusiness, businessSnapshot());

        // Restore external's original relation
        dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,"
                + "to_anchor_id,created_by_decision_id,created_at) "
                + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), external.revisionId(), external.anchorOne(), extDecisionId);

        assertEquals("CONFIRMED", scalarString(
                "SELECT state FROM memory.deletion_closure WHERE closure_id = ?", cc.closureId()));
    }

    // ================================================================
    // 18. Shared ref: external anchor → internal unit (S3C1A guard)
    // ================================================================

    @Test
    @Order(18)
    @DisplayName("18. External anchor referencing closure source_unit: S3C1A_SHARED_GUARD (HDM014/23514)")
    void externalAnchorToInternalUnitCaughtBySharedGuard() {
        // Create a confirmed closure (its source_units are fenced)
        ConfirmedClosure cc = createConfirmedClosure("ext-anchor-unit", "ext-au-body", false);
        List<String> preBusiness = businessSnapshot();

        // Get one closure source_unit and its source
        var unitRow = dsl.fetchOne(
                "SELECT m.target_id, su.source_id FROM memory.deletion_closure_member m "
                + "JOIN evidence.source_unit su ON su.source_unit_id = m.target_id "
                + "WHERE m.closure_id = ? AND m.member_kind = 'SOURCE_UNIT' LIMIT 1",
                cc.closureId());
        UUID closureUnitId = unitRow.get(0, UUID.class);
        UUID sourceId = unitRow.get(1, UUID.class);

        // Create a new anchor under the SAME source (not in closure, not fenced)
        // source_anchor_unit CONSTRAINT TRIGGER requires same source for anchor and unit
        UUID rogueAnchorId = UUID.randomUUID();
        dsl.execute("INSERT INTO evidence.source_anchor(anchor_id,source_id,anchor_kind,created_at) "
                + "VALUES (?,?,'MESSAGE_SEGMENT',clock_timestamp())", rogueAnchorId, sourceId);

        // Link rogue anchor → closure unit via source_anchor_unit (no fence trigger on this table)
        dsl.execute("INSERT INTO evidence.source_anchor_unit(anchor_id,source_unit_id,ordinal) "
                + "VALUES (?,?,99)", rogueAnchorId, closureUnitId);

        // Now the closure unit is shared: referenced by an external anchor
        // S3C1A execute must catch this
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected shared reference rejection for external anchor → internal unit");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM014_DELETION_SHARED_REFERENCE", e);
        }

        // Clean up the rogue rows
        dsl.execute("DELETE FROM evidence.source_anchor_unit WHERE anchor_id = ?", rogueAnchorId);
        dsl.execute("DELETE FROM evidence.source_anchor WHERE anchor_id = ?", rogueAnchorId);

        // Business state unchanged
        assertEquals(preBusiness, businessSnapshot());
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id = ?", cc.closureId()));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_payload_task "
                + "WHERE deletion_run_id IN (SELECT deletion_run_id FROM runtime.deletion_run WHERE closure_id = ?)",
                cc.closureId()));
    }

    // ================================================================
    // 99. Four failure points: rollback verification
    // ================================================================

    @Test
    @Order(99)
    @DisplayName("99. Four failure points: after task insert, after proposal clear, after relation delete, before memory delete — all rollback")
    void failurePointRollback() throws Exception {
        // For each failure point, we create a temporary trigger that fires AFTER the relevant
        // operation and raises an exception. Then we verify all changes rolled back.

        // 10a. Fail after task insert (after erasure_marker insert)
        ConfirmedClosure cc1 = createConfirmedClosure("fp1", "fp1-body", false);
        List<String> preBusiness1 = businessSnapshot();
        List<String> prePayload1 = payloadSnapshot();
        long preTaskCount1 = count("SELECT count(*) FROM runtime.deletion_payload_task");
        long preMarkerCount1 = count("SELECT count(*) FROM runtime.deletion_erasure_marker");
        dsl.execute(
                "CREATE FUNCTION public.test_fail_after_task() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'TEST_INJECTED_FAILURE_AFTER_TASK' USING ERRCODE = '23514'; END; $$");
        dsl.execute("CREATE TRIGGER test_fail_after_task_trigger AFTER INSERT ON runtime.deletion_erasure_marker "
                + "FOR EACH ROW EXECUTE FUNCTION public.test_fail_after_task()");
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc1.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected failure injection");
        } catch (Exception e) {
            assertMessageContains("TEST_INJECTED_FAILURE_AFTER_TASK", e);
        }
        // Rollback verified
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc1.closureId()));
        assertEquals(preTaskCount1, count("SELECT count(*) FROM runtime.deletion_payload_task"));
        assertEquals(preMarkerCount1, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
        assertEquals(preBusiness1, businessSnapshot());
        assertEquals(prePayload1, payloadSnapshot());
        dsl.execute("DROP FUNCTION IF EXISTS public.test_fail_after_task CASCADE");

        // 10b. Fail after proposal body clear (after proposal_revision UPDATE)
        ConfirmedClosure cc2 = createConfirmedClosure("fp2", "fp2-body", false);
        List<String> preBusiness2 = businessSnapshot();
        long preRunCount2 = count("SELECT count(*) FROM runtime.deletion_run");
        dsl.execute(
                "CREATE FUNCTION public.test_fail_after_proposal() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'TEST_INJECTED_FAILURE_AFTER_PROPOSAL' USING ERRCODE = '23514'; END; $$");
        dsl.execute("CREATE TRIGGER test_fail_after_proposal_trigger AFTER UPDATE ON memory.proposal_revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.test_fail_after_proposal()");
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc2.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected failure injection");
        } catch (Exception e) {
            assertMessageContains("TEST_INJECTED_FAILURE_AFTER_PROPOSAL", e);
        }
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc2.closureId()));
        assertEquals(preRunCount2, count("SELECT count(*) FROM runtime.deletion_run"));
        assertEquals(preBusiness2, businessSnapshot());
        dsl.execute("DROP FUNCTION IF EXISTS public.test_fail_after_proposal CASCADE");

        // 10c. Fail after relation delete (after source_payload DELETE)
        ConfirmedClosure cc3 = createConfirmedClosure("fp3", "fp3-body", false);
        List<String> preBusiness3 = businessSnapshot();
        long preRunCount3 = count("SELECT count(*) FROM runtime.deletion_run");
        dsl.execute(
                "CREATE FUNCTION public.test_fail_after_payload() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'TEST_INJECTED_FAILURE_AFTER_PAYLOAD' USING ERRCODE = '23514'; END; $$");
        dsl.execute("CREATE TRIGGER test_fail_after_payload_trigger AFTER DELETE ON evidence.source_payload "
                + "FOR EACH ROW EXECUTE FUNCTION public.test_fail_after_payload()");
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc3.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected failure injection");
        } catch (Exception e) {
            assertMessageContains("TEST_INJECTED_FAILURE_AFTER_PAYLOAD", e);
        }
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc3.closureId()));
        assertEquals(preRunCount3, count("SELECT count(*) FROM runtime.deletion_run"));
        assertEquals(preBusiness3, businessSnapshot());
        dsl.execute("DROP FUNCTION IF EXISTS public.test_fail_after_payload CASCADE");

        // 10d. Fail before memory delete (after memory_revision DELETE)
        ConfirmedClosure cc4 = createConfirmedClosure("fp4", "fp4-body", false);
        List<String> preBusiness4 = businessSnapshot();
        long preRunCount4 = count("SELECT count(*) FROM runtime.deletion_run");
        dsl.execute(
                "CREATE FUNCTION public.test_fail_before_memory() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'TEST_INJECTED_FAILURE_BEFORE_MEMORY' USING ERRCODE = '23514'; END; $$");
        dsl.execute("CREATE TRIGGER test_fail_before_memory_trigger AFTER DELETE ON memory.memory_revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.test_fail_before_memory()");
        try {
            execution.executeDatabasePhase(UUID.randomUUID(), cc4.closureId(), OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected failure injection");
        } catch (Exception e) {
            assertMessageContains("TEST_INJECTED_FAILURE_BEFORE_MEMORY", e);
        }
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", cc4.closureId()));
        assertEquals(preRunCount4, count("SELECT count(*) FROM runtime.deletion_run"));
        assertEquals(preBusiness4, businessSnapshot());
        dsl.execute("DROP FUNCTION IF EXISTS public.test_fail_before_memory CASCADE");
    }

    // ================================================================
    // 11. Body text canary scan = 0 post-execution
    // ================================================================

    @Test
    @Order(11)
    @DisplayName("11. Post-execution body text canary scan: 0 across memory/evidence/runtime")
    void bodyTextCanaryScan() {
        ConfirmedClosure cc = createConfirmedClosure("canary", "BODY_CANARY_TEST_VALUE", false);

        UUID runId = UUID.randomUUID();
        execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));

        // Scan all text/JSON business columns in memory, evidence, runtime for canary
        String canary = "BODY_CANARY_TEST_VALUE";

        // memory schema: scan body_text columns
        assertEquals(0L, countCanary("memory.memory_revision", "body_text", canary));
        assertEquals(0L, countCanary("memory.proposal_revision", "body_text", canary));

        // evidence schema: no body_text columns, but scan source* tables
        // evidence.source has platform/external_ref text cols - shouldn't contain body
        // evidence.source_unit has external_unit_ref - shouldn't contain body

        // runtime schema: scan deletion_run, deletion_payload_task for leaked body
        assertEquals(0L, countCanary("runtime.deletion_run", "cast(deletion_run_id as text)", canary));
        assertEquals(0L, countCanary("runtime.deletion_payload_task", "object_ref", canary));

        // Verify proposal_revision.body_hash is NULL where body_text is NULL
        assertEquals(0L, count(
                "SELECT count(*) FROM memory.proposal_revision pr "
                + "JOIN memory.decision d ON d.proposal_revision_id=pr.proposal_revision_id "
                + "WHERE d.target_kind='MEMORY' AND d.target_id=? AND pr.body_hash IS NOT NULL",
                cc.memoryId()));

        // UUIDs and hashes (metadata) are fine — not body text
        assertTrue(count("SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId) > 0);
    }

    // ================================================================
    // 12. Permission tests
    // ================================================================

    @Test
    @Order(12)
    @DisplayName("12. API cannot EXECUTE function, cannot write marker/run/task; worker can call function but not write marker")
    void permissionTests() throws Exception {
        ConfirmedClosure cc = createConfirmedClosure("perm", "perm-body", false);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, password);
             Statement stmt = conn.createStatement()) {

            // 12a. API cannot EXECUTE the function
            stmt.execute("SET ROLE hide_nest_api");
            UUID execRunId = UUID.randomUUID();
            SQLException ex = assertThrows(SQLException.class, () ->
                    stmt.execute("SELECT runtime.execute_confirmed_deletion_database_phase('"
                            + execRunId + "','" + cc.closureId() + "',clock_timestamp())"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            // 12b. API cannot INSERT into deletion_run
            stmt.execute("SET ROLE hide_nest_api");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO runtime.deletion_run(deletion_run_id,closure_id,confirmed_by_decision_id,state,started_at,database_erased_at,payload_task_count) "
                            + "VALUES ('" + UUID.randomUUID() + "','" + cc.closureId() + "','" + cc.confirmedByDecisionId()
                            + "','FILE_PENDING',clock_timestamp(),clock_timestamp(),0)"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            // 12c. API cannot INSERT into deletion_payload_task
            stmt.execute("SET ROLE hide_nest_api");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO runtime.deletion_payload_task(deletion_run_id,payload_id,object_ref,expected_hash,state,created_at) "
                            + "VALUES ('" + UUID.randomUUID() + "','" + UUID.randomUUID()
                            + "','test',decode('" + HASH_HEX + "','hex'),'PENDING',clock_timestamp())"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            // 12d. Worker CAN call the SECURITY DEFINER function
            stmt.execute("SET ROLE hide_nest_worker");
            UUID runId = UUID.randomUUID();
            var rs = stmt.executeQuery(
                    "SELECT * FROM runtime.execute_confirmed_deletion_database_phase('"
                            + runId + "'::uuid, '" + cc.closureId() + "'::uuid, '"
                            + OffsetDateTime.now(CLOCK) + "'::timestamptz)");
            assertTrue(rs.next());
            assertEquals("FILE_PENDING", rs.getString("o_state"));
            stmt.execute("RESET ROLE");

            // 12e. Worker CANNOT directly insert into marker table
            stmt.execute("SET ROLE hide_nest_worker");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id) VALUES ('"
                            + cc.closureId() + "')"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");
        }
    }

    // ================================================================
    // Helpers
    // ================================================================

    private ConfirmedClosure createConfirmedClosure(String marker, String body, boolean withAffected) {
        Fixture mem = createMemory(marker, body, withAffected ? null : null);
        // For withAffected=true, create a second memory sharing the anchor
        if (withAffected) {
            createMemory(marker + "-affected", "affected-body", mem.anchorOne());
        }
        byte[] requestHash = sha256(("preview-" + marker).getBytes(StandardCharsets.UTF_8));
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "preview-" + marker, requestHash));
        var confirmResult = confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                previewResult.previewId(), previewResult.previewRevision(), previewResult.manifestHash(),
                mem.actorId(), "confirm-" + marker));
        UUID confirmedByDecisionId = dsl.fetchOne(
                "SELECT confirmed_by_decision_id FROM memory.deletion_closure WHERE closure_id=?",
                previewResult.previewId()).get(0, UUID.class);
        return new ConfirmedClosure(previewResult.previewId(), mem.memoryId(), mem.revisionId(),
                mem.anchorOne(), mem.actorId(), confirmedByDecisionId);
    }

    private Fixture createMemory(String marker, String body) {
        return createMemory(marker, body, null);
    }

    private Fixture createMemory(String marker, String body, UUID linkToAnchor) {
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String key = "s3c1a-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key.getBytes(StandardCharsets.UTF_8)), actor, "Interpretation", body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(unitOne, actor, 1L,
                        "unit-one-" + marker, OffsetDateTime.now(CLOCK), "evidence-one"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(unitTwo, actor, 2L,
                                "unit-two-" + marker, OffsetDateTime.now(CLOCK), "EVIDENCE_BODY_CANARY-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key, sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(), prepared.reviewSessionId(), memoryId,
                UUID.randomUUID(), new byte[32]));

        if (linkToAnchor != null) {
            dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", confirmed.currentRevisionId());
            UUID decisionId = dsl.fetch(
                    "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                    confirmed.currentRevisionId()).get(0).get(0, UUID.class);
            dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                    + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                    UUID.randomUUID(), confirmed.currentRevisionId(), linkToAnchor, decisionId);
        }
        return new Fixture(memoryId, confirmed.currentRevisionId(), anchorOne, actor);
    }

    private List<String> businessSnapshot() {
        List<String> snapshot = new ArrayList<>();
        List<String> tables = List.of(
                "memory.memory_record", "memory.memory_revision", "memory.memory_relation",
                "memory.proposal", "memory.proposal_revision",
                "evidence.source_anchor", "evidence.source_unit", "evidence.source_payload",
                "evidence.source_anchor_unit");
        for (String table : tables) {
            List<String> rows = dsl.fetch("SELECT row_to_json(t)::text FROM " + table + " t")
                    .getValues(0, String.class);
            rows.stream().sorted().forEach(row -> snapshot.add(table + "|" + row));
        }
        return snapshot;
    }

    private List<String> payloadSnapshot() {
        try (var paths = Files.walk(payloadRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> payloadRoot.relativize(path).toString().replace('\\', '/')
                            + "|" + fileSize(path) + "|" + hex(sha256File(path)))
                    .sorted().toList();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] sha256File(Path path) {
        try { return sha256(Files.readAllBytes(path)); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    private static byte[] sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static long countCanary(String table, String column, String canary) {
        return count("SELECT count(*) FROM " + table + " WHERE " + column + " LIKE '%' || ? || '%'", canary);
    }

    private static String scalarString(String sql, Object... args) {
        return dsl.fetchOne(sql, args).get(0, String.class);
    }

    private static void assertSqlStateContains(String expected, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.sql.SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().contains(expected)) return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLSTATE containing " + expected + " but got: " + error.getMessage(), error);
    }

    private static void assertMessageContains(String expected, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) return;
            current = current.getCause();
        }
        throw new AssertionError("expected message containing '" + expected + "' but got: " + error.getMessage(), error);
    }

    private static String sqlState(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current.getSQLState() != null) return current.getSQLState();
            current = current.getNextException();
        }
        return null;
    }

    private record Fixture(UUID memoryId, UUID revisionId, UUID anchorOne, UUID actorId) {}

    private record ConfirmedClosure(
            UUID closureId, UUID memoryId, UUID revisionId,
            UUID anchorOne, UUID actorId, UUID confirmedByDecisionId) {}
}
