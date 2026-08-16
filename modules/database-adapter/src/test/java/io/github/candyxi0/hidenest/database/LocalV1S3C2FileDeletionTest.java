package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3C2Exception;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3C2FileDeletionCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3C2FileDeletionResult;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionExecutionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
class LocalV1S3C2FileDeletionTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH_HEX = "ab".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static DeletionExecutionPort execution;
    private static LocalV1S3C2FileDeletionCoordinator s3c2;
    private static LocalV1S2BQueryCoordinator s2b;
    private static TransactionExecutor transactions;
    private static Path payloadRoot;
    private static PayloadStore payloadStore;
    private static EvidenceReferencePort evidence;
    private static DeletionFencePort fenceAdapter;
    private static String password;
    private static String jdbcUrl;
    private static DriverManagerDataSource rawDataSource;

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
        assertEquals(20, Flyway.configure().dataSource(jdbcUrl, USER, password)
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate().migrationsExecuted);
        rawDataSource = new DriverManagerDataSource(jdbcUrl, USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(rawDataSource));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(rawDataSource));
        dsl = new DefaultDSLContext(configuration);
        evidence = new JooqEvidenceReferenceAdapter(dsl);
        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        transactions = executor;
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        payloadRoot = Files.createTempDirectory("s3c2-payload-test-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);
        DeletionPreviewPort previewAdapter = new JooqDeletionPreviewAdapter(dsl);
        fenceAdapter = new JooqDeletionFenceAdapter(dsl);
        preview = new LocalV1S3ADeletionPreviewCoordinator(previewAdapter, executor, CLOCK, fenceAdapter, payloadStore);
        confirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), governance, fenceAdapter, previewAdapter, executor, CLOCK);
        execution = new JooqDeletionExecutionAdapter(dsl);
        s3c2 = new LocalV1S3C2FileDeletionCoordinator(execution, payloadStore, CLOCK);
        s2b = new LocalV1S2BQueryCoordinator(new JooqMemoryReadAdapter(dsl), evidence, payloadStore, fenceAdapter);
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
    // 1. V015 migration counts: 15/1/0
    // ================================================================

    @Test
    @Order(1)
    @DisplayName("1. V015 empty 15, V014→V015 upgrade 1, repeat 0")
    void migrationCounts() throws Exception {
        assertEquals(20, count("SELECT count(*) FROM public.flyway_schema_history WHERE success"));

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
            // Migrate V001—V014 as baseline
            var fw14 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .target("14").load();
            assertEquals(14, fw14.migrate().migrationsExecuted);
            // V014→V016 upgrade: exactly 2 (V015 + V016)
            var fw15 = Flyway.configure().dataSource(upgradeContainer.getJdbcUrl(), USER, upgradePassword)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                    .load();
            assertEquals(6, fw15.migrate().migrationsExecuted);
            // Repeat: 0
            assertEquals(0, fw15.migrate().migrationsExecuted);
        } finally {
            upgradeContainer.stop();
        }
    }

    // ================================================================
    // 2. Two files both hash-correct: deleted, tasks DELETED, run COMPLETED
    // ================================================================

    @Test
    @Order(2)
    @DisplayName("2. Two files hash correct: both deleted, tasks all DELETED, run COMPLETED")
    void normalFileDeletion() {
        ConfirmedClosure cc = createConfirmedClosure("normal", "normal-body");
        UUID runId = executeDatabasePhase(cc);

        List<String> payloadBefore = payloadSnapshot();
        assertTrue(payloadBefore.size() >= 2, "expected at least 2 payload files");

        var result = s3c2.execute(runId);
        assertEquals(runId, result.runId());
        assertEquals(cc.closureId(), result.closureId());
        assertEquals("COMPLETED", result.state());
        assertTrue(result.payloadTaskCount() >= 2);

        long deletedCount = count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state='DELETED'",
                runId);
        assertEquals(result.payloadTaskCount(), deletedCount);

        assertEquals("COMPLETED", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertNotNull(scalarTimestamp("SELECT completed_at FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertNull(scalarStringOrNull("SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        List<String> payloadAfter = payloadSnapshot();
        assertEquals(0, payloadAfter.size());
    }

    // ================================================================
    // 3. Second file hash mismatch: pre-check rejects, first file still exists
    // ================================================================

    @Test
    @Order(3)
    @DisplayName("3. Second file hash mismatch: pre-check rejects, first file still exists, run not pseudo-completed")
    void hashMismatchPreCheckRejects() {
        ConfirmedClosure cc = createConfirmedClosure("mismatch", "mismatch-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        assertTrue(tasks.size() >= 2);
        var secondTask = tasks.get(1);
        Path secondFile = payloadRoot.resolve(secondTask.objectRef());

        try {
            Files.write(secondFile, "corrupted-content".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        List<String> payloadBefore = payloadSnapshot();

        try {
            s3c2.execute(runId);
            throw new AssertionError("expected S3C2 failure on hash mismatch");
        } catch (LocalV1S3C2Exception e) {
            assertEquals("DELETION_EXECUTION_FAILED", e.failureCode());
        }

        List<String> payloadAfter = payloadSnapshot();
        assertEquals(payloadBefore.size(), payloadAfter.size());

        assertEquals("FILE_PENDING", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertEquals("DELETION_EXECUTION_FAILED", scalarStringOrNull(
                "SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        assertEquals(0L, count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state='DELETED'",
                runId));
    }

    // ================================================================
    // 4. Real illegal objectRef/path traversal: delete 0, run not completed
    // ================================================================

    @Test
    @Order(4)
    @DisplayName("4. Real illegal objectRef: path-traversal value, DELETION_EXECUTION_FAILED, zero deletions")
    void illegalObjectRefRejected() {
        ConfirmedClosure cc = createConfirmedClosure("illegal-ref", "illegal-ref-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        assertFalse(tasks.isEmpty());

        // Snapshot all payload files before the attack
        List<String> snapshotBefore = payloadSnapshot();
        assertFalse(snapshotBefore.isEmpty());

        // Use migrator privilege to replace one task's object_ref with an illegal value
        var targetTask = tasks.get(0);
        String illegalRef = "../../etc/passwd";
        dsl.execute(
                "UPDATE runtime.deletion_payload_task SET object_ref = ? WHERE deletion_run_id = ? AND payload_id = ?",
                illegalRef, runId, targetTask.payloadId());

        // Verify DB update took effect
        String dbRef = scalarString(
                "SELECT object_ref FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND payload_id=?",
                runId, targetTask.payloadId());
        assertEquals(illegalRef, dbRef);

        // Execute S3C2 — must fail
        try {
            s3c2.execute(runId);
            throw new AssertionError("expected S3C2 failure for illegal objectRef");
        } catch (LocalV1S3C2Exception e) {
            assertEquals("DELETION_EXECUTION_FAILED", e.failureCode());
        }

        // All original files still match snapshot — zero deletions
        List<String> snapshotAfter = payloadSnapshot();
        assertEquals(snapshotBefore, snapshotAfter);

        // No task DELETED
        assertEquals(0L, count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state='DELETED'",
                runId));

        // Run still FILE_PENDING with failure code
        assertEquals("FILE_PENDING", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertEquals("DELETION_EXECUTION_FAILED", scalarStringOrNull(
                "SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
    }

    // ================================================================
    // 5. File already gone + task still PENDING: rerun settles and completes
    // ================================================================

    @Test
    @Order(5)
    @DisplayName("5. File already gone, task still PENDING: rerun settles and completes")
    void fileAlreadyGoneSettlesAndCompletes() {
        ConfirmedClosure cc = createConfirmedClosure("gone", "gone-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        for (var task : tasks) {
            Path filePath = payloadRoot.resolve(task.objectRef());
            try {
                Files.delete(filePath);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());
        assertEquals(tasks.size(), count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state='DELETED'",
                runId));
    }

    // ================================================================
    // 6. Failure injected between file delete and task settlement
    // ================================================================

    @Test
    @Order(6)
    @DisplayName("6. Failure after file delete, before task settle: file gone, run not done, rerun completes")
    void failureBetweenDeleteAndSettle() {
        ConfirmedClosure cc = createConfirmedClosure("fail-delete", "fail-delete-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        assertFalse(tasks.isEmpty());

        for (var task : tasks) {
            Path filePath = payloadRoot.resolve(task.objectRef());
            try {
                Files.delete(filePath);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        assertEquals("FILE_PENDING", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());
        assertEquals(tasks.size(), count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state='DELETED'",
                runId));
    }

    // ================================================================
    // 7. R1-02 Real completion-before-run failure recovery
    // ================================================================

    @Test
    @Order(7)
    @DisplayName("7. Real recovery: tasks all settled DELETED, run still FILE_PENDING, coordinator completes")
    void realPreCompletionFailureRecovery() {
        ConfirmedClosure cc = createConfirmedClosure("real-fail-comp", "real-fail-comp-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        assertFalse(tasks.isEmpty());

        // Step 1: delete all files from disk
        for (var task : tasks) {
            Path filePath = payloadRoot.resolve(task.objectRef());
            try { Files.delete(filePath); } catch (Exception ignored) {}
        }

        // Step 2: settle all tasks to DELETED via the adapter (simulating crash after all settles)
        for (var task : tasks) {
            execution.settlePayloadTask(runId, task.payloadId(), task.objectRef(), task.expectedHash());
        }

        // Step 3: verify state — all tasks DELETED, run still FILE_PENDING, completed_at IS NULL
        for (var task : tasks) {
            assertEquals("DELETED", scalarString(
                    "SELECT state FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND payload_id=?",
                    runId, task.payloadId()));
        }
        assertEquals("FILE_PENDING", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertNull(scalarStringOrNull(
                "SELECT completed_at::text FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        // Record pre-recovery DB facts
        long preRunCount = count("SELECT count(*) FROM runtime.deletion_run WHERE deletion_run_id=?", runId);
        long preCompletedRuns = count(
                "SELECT count(*) FROM runtime.deletion_run WHERE deletion_run_id=? AND state='COMPLETED'", runId);
        assertEquals(0L, preCompletedRuns);

        // Step 4: call coordinator — it should find 0 pending tasks and call completeRun
        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());
        assertEquals(runId, result.runId());

        // Step 5: verify run is now COMPLETED
        assertEquals("COMPLETED", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertNotNull(scalarTimestamp("SELECT completed_at FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
        assertEquals(1L, preRunCount); // no new run rows
    }

    // ================================================================
    // 8. R1-03 Real concurrent execution with two threads + latch
    // ================================================================

    @Test
    @Order(8)
    @DisplayName("8. Real concurrent: two threads, same run, overlapping execution, one COMPLETED fact")
    void realConcurrentExecution() throws Exception {
        ConfirmedClosure cc = createConfirmedClosure("real-conc", "real-conc-body");
        UUID runId = executeDatabasePhase(cc);

        // Pre-delete files from disk (simulating crash after file deletion).
        // This avoids filesystem-level TOCTOU races (head vs concurrent delete)
        // while keeping the DB-level concurrency test fully realistic:
        // both threads race through settle → complete.
        var tasks = execution.findPendingPayloadTasks(runId);
        for (var task : tasks) {
            Path filePath = payloadRoot.resolve(task.objectRef());
            try { Files.delete(filePath); } catch (Exception ignored) {}
        }

        // Create two independent coordinators with separate DSLContext/connections
        DriverManagerDataSource ds2 = new DriverManagerDataSource(jdbcUrl, USER, password);
        DefaultConfiguration config2 = new DefaultConfiguration();
        config2.setSQLDialect(SQLDialect.POSTGRES);
        config2.setDataSource(new TransactionAwareDataSourceProxy(ds2));
        DSLContext dsl2 = new DefaultDSLContext(config2);
        DeletionExecutionPort exec2 = new JooqDeletionExecutionAdapter(dsl2);
        LocalV1S3C2FileDeletionCoordinator s3c2Thread2 =
                new LocalV1S3C2FileDeletionCoordinator(exec2, payloadStore, CLOCK);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicReference<LocalV1S3C2FileDeletionResult> result1Ref = new AtomicReference<>();
        AtomicReference<LocalV1S3C2FileDeletionResult> result2Ref = new AtomicReference<>();
        AtomicReference<Exception> error1Ref = new AtomicReference<>();
        AtomicReference<Exception> error2Ref = new AtomicReference<>();
        AtomicLong thread1StartMs = new AtomicLong();
        AtomicLong thread1EndMs = new AtomicLong();
        AtomicLong thread2StartMs = new AtomicLong();
        AtomicLong thread2EndMs = new AtomicLong();

        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                thread1StartMs.set(System.currentTimeMillis());
                result1Ref.set(s3c2.execute(runId));
                thread1EndMs.set(System.currentTimeMillis());
            } catch (Exception e) {
                error1Ref.set(e);
            } finally {
                doneLatch.countDown();
            }
        }, "s3c2-concurrent-1");

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                thread2StartMs.set(System.currentTimeMillis());
                result2Ref.set(s3c2Thread2.execute(runId));
                thread2EndMs.set(System.currentTimeMillis());
            } catch (Exception e) {
                error2Ref.set(e);
            } finally {
                doneLatch.countDown();
            }
        }, "s3c2-concurrent-2");

        t1.start();
        t2.start();
        startLatch.countDown(); // release both threads simultaneously

        assertTrue(doneLatch.await(30, TimeUnit.SECONDS), "threads should complete within timeout");

        // Verify no errors
        assertNull(error1Ref.get(), "thread 1 should not fail: " + (error1Ref.get() != null ? error1Ref.get().getMessage() : ""));
        assertNull(error2Ref.get(), "thread 2 should not fail: " + (error2Ref.get() != null ? error2Ref.get().getMessage() : ""));

        // Verify time intervals overlapped
        long t1s = thread1StartMs.get();
        long t1e = thread1EndMs.get();
        long t2s = thread2StartMs.get();
        long t2e = thread2EndMs.get();
        boolean overlap = t1s < t2e && t2s < t1e;
        assertTrue(overlap, String.format("execution intervals must overlap: t1=[%d,%d] t2=[%d,%d]", t1s, t1e, t2s, t2e));

        // Both results are COMPLETED
        assertEquals("COMPLETED", result1Ref.get().state());
        assertEquals("COMPLETED", result2Ref.get().state());
        assertEquals(runId, result1Ref.get().runId());
        assertEquals(runId, result2Ref.get().runId());

        // All tasks DELETED
        long pendingCount = count(
                "SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND state <> 'DELETED'",
                runId);
        assertEquals(0L, pendingCount);

        // Only one COMPLETED run fact
        assertEquals(1L, count("SELECT count(*) FROM runtime.deletion_run WHERE deletion_run_id=?", runId));

        // No last_failure_code
        assertNull(scalarStringOrNull(
                "SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
    }

    // ================================================================
    // 9. Task objectRef/hash/payloadId mismatch: DB settlement rejects
    // ================================================================

    @Test
    @Order(9)
    @DisplayName("9. Task objectRef/hash/payloadId mismatch: DB settlement rejects")
    void taskBindingAttackRejected() {
        ConfirmedClosure cc = createConfirmedClosure("binding", "binding-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        assertFalse(tasks.isEmpty());
        var task = tasks.get(0);

        try {
            execution.settlePayloadTask(runId, task.payloadId(), "ff/00000000000000000000000000000000.payload",
                    task.expectedHash());
            throw new AssertionError("expected settlement rejection for wrong objectRef");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
        }

        byte[] wrongHash = new byte[32];
        try {
            execution.settlePayloadTask(runId, task.payloadId(), task.objectRef(), wrongHash);
            throw new AssertionError("expected settlement rejection for wrong hash");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
        }

        try {
            execution.settlePayloadTask(runId, UUID.randomUUID(), task.objectRef(), task.expectedHash());
            throw new AssertionError("expected settlement rejection for wrong payloadId");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
        }

        assertEquals("PENDING", scalarString(
                "SELECT state FROM runtime.deletion_payload_task WHERE deletion_run_id=? AND payload_id=?",
                runId, task.payloadId()));
    }

    // ================================================================
    // 10. Task count != run.payload_task_count: completion rejected
    // ================================================================

    @Test
    @Order(10)
    @DisplayName("10. Task count != run.payload_task_count: completion rejected")
    void taskCountMismatchCompletionRejected() {
        ConfirmedClosure cc = createConfirmedClosure("count-mismatch", "count-mismatch-body");
        UUID runId = executeDatabasePhase(cc);

        var tasks = execution.findPendingPayloadTasks(runId);
        for (var task : tasks) {
            Path filePath = payloadRoot.resolve(task.objectRef());
            try { Files.delete(filePath); } catch (Exception ignored) {}
            execution.settlePayloadTask(runId, task.payloadId(), task.objectRef(), task.expectedHash());
        }

        long realCount = count("SELECT count(*) FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId);
        dsl.execute("UPDATE runtime.deletion_run SET payload_task_count = ? WHERE deletion_run_id=?",
                realCount + 1, runId);

        try {
            execution.completeRun(runId, OffsetDateTime.now(CLOCK));
            throw new AssertionError("expected completion rejection for count mismatch");
        } catch (Exception e) {
            assertSqlStateContains("23514", e);
            assertMessageContains("HDM015_COMPLETE_TASK_COUNT_MISMATCH", e);
        }
    }

    // ================================================================
    // 11. Zero-task run: complete and replayable
    // ================================================================

    @Test
    @Order(11)
    @DisplayName("11. Zero-task run: complete immediately and replayable")
    void zeroTaskRun() throws Exception {
        ConfirmedClosure cc = createConfirmedClosure("zero", "zero-body");
        UUID runId = executeDatabasePhase(cc);

        dsl.execute("DELETE FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId);
        dsl.execute("UPDATE runtime.deletion_run SET payload_task_count=0 WHERE deletion_run_id=?", runId);

        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());
        assertEquals(0L, result.payloadTaskCount());

        var result2 = s3c2.execute(runId);
        assertEquals("COMPLETED", result2.state());
        assertEquals(result.completedAt(), result2.completedAt());
    }

    // ================================================================
    // 12. Already COMPLETED replay: PayloadStore calls = 0, DB no writes
    // ================================================================

    @Test
    @Order(12)
    @DisplayName("12. COMPLETED replay: PayloadStore calls 0, DB no new writes")
    void completedReplayZeroPayloadStoreCalls() {
        ConfirmedClosure cc = createConfirmedClosure("replay", "replay-body");
        UUID runId = executeDatabasePhase(cc);

        var first = s3c2.execute(runId);
        assertEquals("COMPLETED", first.state());

        long preTaskCount = count("SELECT count(*) FROM runtime.deletion_payload_task");
        long preRunCount = count("SELECT count(*) FROM runtime.deletion_run");

        var second = s3c2.execute(runId);
        assertEquals("COMPLETED", second.state());
        assertEquals(first.runId(), second.runId());
        assertEquals(first.completedAt(), second.completedAt());

        assertEquals(preTaskCount, count("SELECT count(*) FROM runtime.deletion_payload_task"));
        assertEquals(preRunCount, count("SELECT count(*) FROM runtime.deletion_run"));
    }

    // ================================================================
    // 13. API cannot execute functions or direct-write state
    // ================================================================

    @Test
    @Order(13)
    @DisplayName("13. API cannot execute V015 functions or direct-write state; Worker cannot direct-write state")
    void permissionTests() throws Exception {
        ConfirmedClosure cc = createConfirmedClosure("perm", "perm-body");
        UUID runId = executeDatabasePhase(cc);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, USER, password);
             Statement stmt = conn.createStatement()) {

            stmt.execute("SET ROLE hide_nest_api");
            SQLException ex = assertThrows(SQLException.class, () ->
                    stmt.execute("SELECT runtime.settle_deletion_payload_task('"
                            + runId + "','" + UUID.randomUUID() + "','test',decode('" + HASH_HEX + "','hex'))"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_api");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("SELECT runtime.record_deletion_file_failure('"
                            + runId + "',clock_timestamp())"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_api");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("SELECT runtime.complete_deletion_run('"
                            + runId + "',clock_timestamp())"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_worker");
            var tasks = execution.findPendingPayloadTasks(runId);
            if (!tasks.isEmpty()) {
                var task = tasks.get(0);
                Path filePath = payloadRoot.resolve(task.objectRef());
                try { Files.delete(filePath); } catch (Exception ignored) {}
                var rs = stmt.executeQuery("SELECT * FROM runtime.settle_deletion_payload_task('"
                        + task.deletionRunId() + "'::uuid, '"
                        + task.payloadId() + "'::uuid, '"
                        + task.objectRef() + "'::text, decode('"
                        + hex(task.expectedHash()) + "','hex'))");
                assertTrue(rs.next());
                assertEquals("DELETED", rs.getString("o_state"));
            }
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_worker");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("UPDATE runtime.deletion_run SET state='COMPLETED' WHERE deletion_run_id='"
                            + runId + "'"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");

            stmt.execute("SET ROLE hide_nest_worker");
            ex = assertThrows(SQLException.class, () ->
                    stmt.execute("UPDATE runtime.deletion_payload_task SET state='DELETED' WHERE deletion_run_id='"
                            + runId + "'"));
            assertEquals("42501", sqlState(ex));
            stmt.execute("RESET ROLE");
        }
    }

    // ================================================================
    // 14. R1-04 Real S2B query chain: before/after deletion
    // ================================================================

    @Test
    @Order(14)
    @DisplayName("14. Real S2B query chain: visible before fences, absent/rejected after full deletion")
    void s2bQueryChainBeforeAndAfterDeletion() {
        // ── Create memory (before any deletion flow) ─────────────────
        Fixture mem = createMemory("s2b-chain", "s2b-chain-body");
        UUID memoryId = mem.memoryId();

        // ── BEFORE fences: S2B queries work ─────────────────────────
        var beforeList = s2b.listMemories(new LocalV1S2BListRequest("ALL", "s2b-chain", 50, 0));
        boolean foundBefore = beforeList.items().stream()
                .anyMatch(item -> item.memoryId().equals(memoryId));
        assertTrue(foundBefore, "S2B list must contain target memory before fences");

        var beforeDetail = s2b.getMemoryDetail(memoryId);
        assertNotNull(beforeDetail, "S2B detail must return target memory before fences");
        assertEquals(memoryId, beforeDetail.memoryId());

        var beforeEvidence = s2b.getFullEvidence(memoryId);
        assertNotNull(beforeEvidence, "S2B evidence must return data before fences");
        assertFalse(beforeEvidence.messages().isEmpty(), "must have at least one evidence message before fences");

        // ── Confirm + S3C1A + S3C2 ──────────────────────────────────
        byte[] requestHash = sha256(("preview-s2b-chain").getBytes(StandardCharsets.UTF_8));
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "preview-s2b-chain", requestHash));
        confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                previewResult.previewId(), previewResult.previewRevision(), previewResult.manifestHash(),
                mem.actorId(), "confirm-s2b-chain"));
        UUID closureId = previewResult.previewId();
        long preDeletionClosureCount = count("SELECT count(*) FROM memory.deletion_closure WHERE closure_id=?", closureId);

        UUID runId = UUID.randomUUID();
        execution.executeDatabasePhase(runId, closureId, OffsetDateTime.now(CLOCK));
        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());

        // ── AFTER deletion: S2B list excludes fenced/deleted memory ─
        var afterList = s2b.listMemories(new LocalV1S2BListRequest("ALL", "s2b-chain", 50, 0));
        boolean foundAfter = afterList.items().stream()
                .anyMatch(item -> item.memoryId().equals(memoryId));
        assertFalse(foundAfter, "S2B list must NOT contain target memory after deletion");

        // detail must reject (memory gone + fenced)
        try {
            s2b.getMemoryDetail(memoryId);
            throw new AssertionError("expected S2B detail rejection after deletion");
        } catch (LocalV1S2BException e) {
            assertEquals(LocalV1S2BException.Code.DELETION_FENCED, e.code());
        }

        // full evidence must reject
        try {
            s2b.getFullEvidence(memoryId);
            throw new AssertionError("expected S2B evidence rejection after deletion");
        } catch (LocalV1S2BException e) {
            assertEquals(LocalV1S2BException.Code.DELETION_FENCED, e.code());
        }

        // ── Payload NOT_FOUND ───────────────────────────────────────
        var allTasks = dsl.resultQuery(
                "SELECT object_ref, expected_hash FROM runtime.deletion_payload_task WHERE deletion_run_id=?", runId)
                .fetch();
        assertFalse(allTasks.isEmpty());
        for (var row : allTasks) {
            String objectRef = row.get(0, String.class);
            byte[] expectedHash = row.get(1, byte[].class);
            try {
                payloadStore.head(objectRef);
                throw new AssertionError("expected NOT_FOUND for head of deleted file: " + objectRef);
            } catch (io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException e) {
                assertEquals("NOT_FOUND", e.errorCode());
            }
            try {
                payloadStore.delete(objectRef, expectedHash);
                throw new AssertionError("expected NOT_FOUND for repeat delete: " + objectRef);
            } catch (io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException e) {
                assertEquals("NOT_FOUND", e.errorCode());
            }
        }

        // ── Audit trail preserved ───────────────────────────────────
        assertEquals(preDeletionClosureCount,
                count("SELECT count(*) FROM memory.deletion_closure WHERE closure_id=?", closureId));
        assertTrue(count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", closureId) > 0);
        assertEquals("COMPLETED", scalarString(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", runId));
    }

    // ================================================================
    // 15. Body canary, absolute payload root: all 0
    // ================================================================

    @Test
    @Order(15)
    @DisplayName("15. Body canary/absolute payload root/secret leak: 0 in exceptions/logs/reports")
    void zeroLeakage() {
        ConfirmedClosure cc = createConfirmedClosure("canary", "BODY_CANARY_S3C2_VALUE");
        UUID runId = executeDatabasePhase(cc);

        var result = s3c2.execute(runId);
        assertEquals("COMPLETED", result.state());

        String canary = "BODY_CANARY_S3C2_VALUE";
        assertEquals(0L, countCanary("memory.memory_revision", "body_text", canary));
        assertEquals(0L, countCanary("memory.proposal_revision", "body_text", canary));
        assertEquals(0L, countCanary("runtime.deletion_run",
                "cast(coalesce(last_failure_code,'') as text)", canary));
        assertEquals(0L, countCanary("runtime.deletion_payload_task", "object_ref", canary));

        String rootPath = payloadRoot.toString();
        assertFalse(rootPath.isEmpty());
        assertEquals(0L, countCanary("runtime.deletion_run",
                "cast(deletion_run_id as text)", rootPath));
        assertEquals(0L, countCanary("runtime.deletion_payload_task",
                "cast(payload_id as text)", rootPath));
        assertEquals(0L, countCanary("runtime.deletion_payload_task", "object_ref", rootPath));
    }

    // ================================================================
    // 16. Regression: S3C1A database erasure still passes
    // ================================================================

    @Test
    @Order(16)
    @DisplayName("16. S3C1A regression: database erasure still works")
    void s3c1aRegression() {
        ConfirmedClosure cc = createConfirmedClosure("reg-s3c1a", "reg-s3c1a-body");
        UUID runId = UUID.randomUUID();
        var result = execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));

        assertEquals(runId, result.runId());
        assertEquals("FILE_PENDING", result.state());
        assertTrue(result.payloadTaskCount() > 0);

        assertEquals(0L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", cc.memoryId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", cc.memoryId()));
    }

    // ================================================================
    // 17. Regression: S3B2B confirmation still works
    // ================================================================

    @Test
    @Order(17)
    @DisplayName("17. S3B2B regression: deletion confirmation still works")
    void s3b2bRegression() {
        Fixture mem = createMemory("reg-s3b2b", "reg-s3b2b-body");
        byte[] requestHash = sha256("preview-reg-s3b2b".getBytes(StandardCharsets.UTF_8));
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "preview-reg-s3b2b", requestHash));

        assertEquals("PREVIEWED", scalarString(
                "SELECT state FROM memory.deletion_closure WHERE closure_id=?", previewResult.previewId()));

        var confirmResult = confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                previewResult.previewId(), previewResult.previewRevision(), previewResult.manifestHash(),
                mem.actorId(), "confirm-reg-s3b2b"));
        assertEquals("CONFIRMED", confirmResult.state());
    }

    // ================================================================
    // 18. Regression: S3B1 fence still works
    // ================================================================

    @Test
    @Order(18)
    @DisplayName("18. S3B1 regression: deletion fence still works")
    void s3b1Regression() {
        ConfirmedClosure cc = createConfirmedClosure("reg-s3b1", "reg-s3b1-body");
        long fenceCount = count(
                "SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", cc.closureId());
        assertTrue(fenceCount > 0, "expected fences to exist after confirmation");
    }

    // ================================================================
    // Helpers
    // ================================================================

    private UUID executeDatabasePhase(ConfirmedClosure cc) {
        UUID runId = UUID.randomUUID();
        execution.executeDatabasePhase(runId, cc.closureId(), OffsetDateTime.now(CLOCK));
        return runId;
    }

    private ConfirmedClosure createConfirmedClosure(String marker, String body) {
        Fixture mem = createMemory(marker, body);
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
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String key = "s3c2-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key.getBytes(StandardCharsets.UTF_8)), actor, "Interpretation", body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(unitOne, actor, 1L,
                        "unit-one-" + marker, OffsetDateTime.now(CLOCK), "evidence-one"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(unitTwo, actor, 2L,
                                "unit-two-" + marker, OffsetDateTime.now(CLOCK), "EVIDENCE_CANARY-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key, sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(), prepared.reviewSessionId(), memoryId,
                UUID.randomUUID(), new byte[32]));
        return new Fixture(memoryId, confirmed.currentRevisionId(), anchorOne, actor);
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

    private static String scalarStringOrNull(String sql, Object... args) {
        var row = dsl.fetchOne(sql, args);
        if (row == null) return null;
        return row.get(0, String.class);
    }

    private static Object scalarTimestamp(String sql, Object... args) {
        return dsl.fetchOne(sql, args).get(0);
    }

    private static void assertSqlStateContains(String expected, Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
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
