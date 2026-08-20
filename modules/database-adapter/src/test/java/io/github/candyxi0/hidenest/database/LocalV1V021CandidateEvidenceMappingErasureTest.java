package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetBatchCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCreateProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3C2FileDeletionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorSpec;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.Candidate;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.FinalConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.database.adapter.JooqCandidateSetGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionBindingAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionExecutionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
import io.github.candyxi0.hidenest.memory.port.DeletionBindingPort;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
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
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V021 narrow contract for CandidateSet evidence-mapping erasure and NO_RUN replay recovery. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1V021CandidateEvidenceMappingErasureTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA =
            HexFormat.of().parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-20T00:00:00Z"), ZoneId.of("UTC"));
    private static final String DENIED = "HDM021_CANDIDATE_EVIDENCE_MAPPING_ERASURE_DENIED";

    private static PostgreSQLContainer<?> postgres;
    private static String password;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static Flyway flyway;
    private static int emptyMigrations;

    private static LocalV1CandidateSetBatchCoordinator batch;
    private static LocalV1CandidateSetCreateProjectionCoordinator projection;
    private static DeletionBindingPort binding;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static DeletionExecutionPort execution;
    private static LocalV1DeletionWriteCoordinator deletion;
    private static TransactionExecutor transactions;

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
        postgres = container("v021_main", password);
        postgres.start();
        createRoles(postgres, password);

        flyway = flyway(postgres, null);
        emptyMigrations = flyway.migrate().migrationsExecuted;

        DriverManagerDataSource raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        transactions = new SpringTransactionExecutor(tx);

        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        CandidateSetGovernancePort candidateSets = new JooqCandidateSetGovernanceAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, transactions, CLOCK);

        payloadRoot = Files.createTempDirectory("v021-mapping-erasure-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        batch = new LocalV1CandidateSetBatchCoordinator(
                evidence, governance, runtime, candidateSets, transactions, payloadStore, CLOCK);

        JooqMemoryReadAdapter memoryRead = new JooqMemoryReadAdapter(dsl);
        MemoryVectorStorePort vectorStore = new JooqVectorStoreAdapter(dsl);
        ModelFingerprint fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);
        LocalV1VectorCoordinator vector = new LocalV1VectorCoordinator(
                new FakeEmbeddingProvider(), vectorStore, governance, transactions, fingerprint, CLOCK);
        projection = new LocalV1CandidateSetCreateProjectionCoordinator(
                candidateSets, governance, memoryRead, evidence, publisher, vector, vectorStore,
                fingerprint, runtime);

        JooqDeletionPreviewAdapter previewPort = new JooqDeletionPreviewAdapter(dsl);
        JooqDeletionFenceAdapter fencePort = new JooqDeletionFenceAdapter(dsl);
        JooqDeletionExecutionAdapter executionPort = new JooqDeletionExecutionAdapter(dsl);
        binding = new JooqDeletionBindingAdapter(dsl);
        preview = new LocalV1S3ADeletionPreviewCoordinator(
                previewPort, transactions, CLOCK, fencePort, payloadStore);
        confirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), governance, fencePort,
                previewPort, transactions, CLOCK);
        execution = executionPort;
        deletion = new LocalV1DeletionWriteCoordinator(
                binding, previewPort, governance, preview, confirm, executionPort,
                new LocalV1S3C2FileDeletionCoordinator(executionPort, payloadStore, CLOCK), CLOCK);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (postgres != null) {
            postgres.stop();
        }
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best-effort test cleanup
                    }
                });
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("V021 empty/V020 upgrade/repeat = 21/1/0; table count and narrow privileges unchanged")
    void migrationAndPrivilegeContract() throws Exception {
        assertEquals(21, emptyMigrations);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        String upgradePassword = UUID.randomUUID().toString();
        try (PostgreSQLContainer<?> upgrade = container("v021_upgrade", upgradePassword)) {
            upgrade.start();
            createRoles(upgrade, upgradePassword);
            Flyway v20 = flyway(upgrade, "20");
            assertEquals(20, v20.migrate().migrationsExecuted);
            long tablesBefore = tableCount(upgrade, upgradePassword);
            Flyway v21 = flyway(upgrade, null);
            assertEquals(1, v21.migrate().migrationsExecuted);
            assertEquals(0, v21.migrate().migrationsExecuted);
            assertEquals(tablesBefore, tableCount(upgrade, upgradePassword));
        }

        assertFalse(bool("SELECT has_function_privilege('hide_nest_api', "
                + "'memory.enforce_candidate_evidence_mapping_immutable_or_erasure()', 'EXECUTE')"));
        assertFalse(bool("SELECT has_function_privilege('hide_nest_worker', "
                + "'memory.enforce_candidate_evidence_mapping_immutable_or_erasure()', 'EXECUTE')"));
        assertFalse(bool("SELECT has_table_privilege('hide_nest_api', "
                + "'memory.candidate_evidence_mapping', 'DELETE')"));
        assertFalse(bool("SELECT has_table_privilege('hide_nest_worker', "
                + "'memory.candidate_evidence_mapping', 'DELETE')"));
        assertTrue(bool("SELECT has_function_privilege('hide_nest_worker', "
                + "'runtime.execute_confirmed_deletion_database_phase(uuid,uuid,timestamp with time zone)', "
                + "'EXECUTE')"));
    }

    @Test
    @Order(2)
    @DisplayName("exclusive mapping erases; CONFIRMED+fence+NO_RUN replay creates one COMPLETED run and EXACT replay adds zero facts")
    void exclusiveNoRunReplayRecovery() {
        Projected projected = project(1, "exclusive");
        ConfirmedNoRun confirmed = confirmWithoutRun(projected, 0, "exclusive-no-run");

        assertEquals("CONFIRMED", text("SELECT state FROM memory.deletion_closure WHERE closure_id=?", confirmed.closureId()));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", confirmed.closureId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()));

        FactCounts beforeWrong = factCounts(confirmed.closureId());
        String wrongManifest = flipHex(confirmed.manifestHex());
        LocalV1DeletionException mismatch = assertThrows(LocalV1DeletionException.class, () ->
                deletion.confirm(
                        confirmed.closureId(), confirmed.memoryId(), 1, 1, confirmed.requestHashHex(),
                        confirmed.closureId(), confirmed.previewRevision(), wrongManifest, confirmed.confirmKey()));
        assertEquals(LocalV1DeletionException.Code.DELETION_CLOSURE_MISMATCH, mismatch.code());
        assertEquals(beforeWrong, factCounts(confirmed.closureId()));

        LocalV1RunStatus recovered = replayConfirmed(confirmed);
        assertEquals("CANONICAL_COMMITTED", recovered.phase());
        assertFalse(recovered.retryable());
        assertEquals("COMPLETED", text(
                "SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?", recovered.runId()));

        assertEquals(0L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", confirmed.memoryId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", confirmed.memoryId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding e "
                + "JOIN memory.memory_revision r ON r.memory_revision_id=e.memory_revision_id WHERE r.memory_id=?",
                confirmed.memoryId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()));
        assertEquals(0L, count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id=?", projected.anchorId()));
        assertEquals(0L, count("SELECT count(*) FROM evidence.source_unit WHERE source_unit_id=?", projected.unitId()));
        assertEquals(0L, count("SELECT count(*) FROM evidence.source_payload WHERE payload_id=?", projected.payloadId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id=?", projected.setId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.candidate_set_member WHERE candidate_set_id=?", projected.setId()));

        FactCounts afterRecovery = factCounts(confirmed.closureId());
        assertEquals(1L, afterRecovery.runs());
        assertEquals(1L, afterRecovery.decisions());
        assertTrue(afterRecovery.fences() > 0);
        LocalV1RunStatus exact = replayConfirmed(confirmed);
        assertEquals(recovered.runId(), exact.runId());
        assertEquals(afterRecovery, factCounts(confirmed.closureId()));
    }

    @Test
    @Order(3)
    @DisplayName("shared anchor keeps all mappings after first deletion; last reference deletes anchor and all mappings")
    void sharedMappingRetainedUntilLastReference() {
        Projected projected = project(2, "shared");
        assertEquals(2L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()));

        ConfirmedNoRun first = confirmWithoutRun(projected, 0, "shared-first");
        assertEquals("RETAIN_SHARED", text(
                "SELECT disposition FROM memory.deletion_closure_member "
                        + "WHERE closure_id=? AND member_kind='SOURCE_ANCHOR' AND target_id=?",
                first.closureId(), projected.anchorId()));
        LocalV1RunStatus firstRun = replayConfirmed(first);
        assertEquals("CANONICAL_COMMITTED", firstRun.phase());
        assertEquals(2L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()));
        assertEquals(1L, count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id=?", projected.anchorId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", projected.memoryIds().get(0)));
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", projected.memoryIds().get(1)));

        ConfirmedNoRun last = confirmWithoutRun(projected, 1, "shared-last");
        assertEquals("DELETE_CANDIDATE", text(
                "SELECT disposition FROM memory.deletion_closure_member "
                        + "WHERE closure_id=? AND member_kind='SOURCE_ANCHOR' AND target_id=?",
                last.closureId(), projected.anchorId()));
        LocalV1RunStatus lastRun = replayConfirmed(last);
        assertEquals("CANONICAL_COMMITTED", lastRun.phase());
        assertEquals(0L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()));
        assertEquals(0L, count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id=?", projected.anchorId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id=?", projected.setId()));
        assertEquals(2L, count("SELECT count(*) FROM memory.candidate_set_member WHERE candidate_set_id=?", projected.setId()));
    }

    @Test
    @Order(4)
    @DisplayName("no marker, UPDATE, fake marker, wrong closure/anchor and RETAIN_SHARED marker are all 23514/HDM021 rejected")
    void markerAndMutationAttacksRejected() {
        Projected target = project(1, "attack-target");
        ConfirmedNoRun targetClosure = confirmWithoutRun(target, 0, "attack-target");

        assertRejected(() -> dsl.execute(
                "DELETE FROM memory.candidate_evidence_mapping WHERE anchor_id=?", target.anchorId()));
        assertRejected(() -> dsl.execute(
                "UPDATE memory.candidate_evidence_mapping SET anchor_id=anchor_id WHERE anchor_id=?",
                target.anchorId()));

        assertRejected(() -> transactions.executeInTransaction(() -> {
            dsl.execute("INSERT INTO runtime.deletion_erasure_marker(closure_id,inserted_at) "
                            + "VALUES (?,?::timestamptz)",
                    targetClosure.closureId(), OffsetDateTime.now(CLOCK));
            dsl.execute("DELETE FROM memory.candidate_evidence_mapping WHERE anchor_id=?", target.anchorId());
            return null;
        }));

        Projected wrong = project(1, "attack-wrong");
        ConfirmedNoRun wrongClosure = confirmWithoutRun(wrong, 0, "attack-wrong");
        assertRejected(() -> withRunMarker(wrongClosure, () -> dsl.execute(
                "DELETE FROM memory.candidate_evidence_mapping WHERE anchor_id=?", target.anchorId())));

        Projected shared = project(2, "attack-retained");
        ConfirmedNoRun retainedClosure = confirmWithoutRun(shared, 0, "attack-retained");
        assertEquals("RETAIN_SHARED", text(
                "SELECT disposition FROM memory.deletion_closure_member "
                        + "WHERE closure_id=? AND member_kind='SOURCE_ANCHOR' AND target_id=?",
                retainedClosure.closureId(), shared.anchorId()));
        assertRejected(() -> withRunMarker(retainedClosure, () -> dsl.execute(
                "DELETE FROM memory.candidate_evidence_mapping WHERE anchor_id=?", shared.anchorId())));

        assertEquals(1L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", target.anchorId()));
        assertEquals(1L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", wrong.anchorId()));
        assertEquals(2L, count(
                "SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", shared.anchorId()));
        assertEquals(0L, count("SELECT count(*) FROM runtime.deletion_erasure_marker"));
    }

    @Test
    @Order(5)
    @DisplayName("mapping, anchor, revision and memory failure injections roll back mapping/anchor/memory/revision/vector/run/task exactly")
    void injectedFailuresRollbackEverything() {
        assertInjectedRollback(
                "memory.candidate_evidence_mapping", "mapping", "HDMTEST_V021_AFTER_MAPPING");
        assertInjectedRollback("evidence.source_anchor", "anchor", "HDMTEST_V021_AFTER_ANCHOR");
        assertInjectedRollback("memory.memory_revision", "revision", "HDMTEST_V021_AFTER_REVISION");
        assertInjectedRollback("memory.memory_record", "memory", "HDMTEST_V021_AFTER_MEMORY");
    }

    @Test
    @Order(6)
    @DisplayName("jOOQ generation A/B/tracked trees are byte-identical under the V021 Testcontainers schema")
    void jooqABTrackedMatch() throws Exception {
        Path module = moduleRoot();
        Path repository = module.getParent().getParent();
        Path temporary = Files.createTempDirectory("v021-jooq-ab-");
        try {
            Path generationA = temporary.resolve("a");
            Path generationB = temporary.resolve("b");
            runJooqGeneration(repository, generationA, temporary.resolve("a.log"));
            runJooqGeneration(repository, generationB, temporary.resolve("b.log"));
            Map<String, String> a = treeManifest(generationA);
            Map<String, String> b = treeManifest(generationB);
            Map<String, String> tracked = treeManifest(module.resolve("src/generated/java"));
            assertFalse(a.isEmpty());
            assertEquals(a, b, "jOOQ A/B mismatch");
            assertEquals(a, tracked, "jOOQ generated/tracked mismatch");
        } finally {
            deleteTree(temporary);
        }
    }

    private void assertInjectedRollback(String table, String suffix, String identifier) {
        Projected projected = project(1, "rollback-" + suffix);
        ConfirmedNoRun closure = confirmWithoutRun(projected, 0, "rollback-" + suffix);
        ErasureSnapshot before = erasureSnapshot(projected, closure.closureId());

        String function = "public.v021_fail_" + suffix;
        String trigger = "v021_fail_" + suffix;
        dsl.execute("CREATE FUNCTION " + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION '" + identifier + "' USING ERRCODE='23514'; END $$");
        dsl.execute("CREATE TRIGGER " + trigger + " AFTER DELETE ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION " + function + "()");
        try {
            Exception failure = assertThrows(Exception.class, () ->
                    execution.executeDatabasePhase(
                            UUID.randomUUID(), closure.closureId(), OffsetDateTime.now(CLOCK)));
            assertSqlFailure(failure, "23514", identifier);
            assertEquals(before, erasureSnapshot(projected, closure.closureId()));
        } finally {
            dsl.execute("DROP TRIGGER " + trigger + " ON " + table);
            dsl.execute("DROP FUNCTION " + function + "()");
        }
    }

    private static Projected project(int acceptedCount, String marker) {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "V021 evidence " + marker;
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 1; i <= acceptedCount; i++) {
            UUID candidateId = UUID.randomUUID();
            candidates.add(new Candidate(
                    candidateId, i, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                    "memory " + marker + " " + i, "Claim", actor, List.of(anchor),
                    null, null, null, null, "reason-" + candidateId));
        }
        LocalV1CandidateSetRequest draft = new LocalV1CandidateSetRequest(
                setId, "key-" + setId, new byte[32], UUID.randomUUID(), "scope", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(new EvidenceMessage(
                                unit, actor, "XIAOLIN", 1, "msg-" + unit,
                                OffsetDateTime.now(CLOCK), body, sha(body))),
                        List.of(new AnchorSpec(
                                anchor, List.of(new AnchorUnit(unit, 0L, (long) body.length(), 1L))))),
                candidates);
        LocalV1CandidateSetRequest sealed = new LocalV1CandidateSetRequest(
                setId, "key-" + setId, LocalV1CandidateSetCanonicalizer.requestHash(draft),
                draft.threadId(), draft.scopeRef(), draft.setVersion(),
                new FinalConfirmation(
                        "CONFIRM_SET", 1, LocalV1CandidateSetCanonicalizer.confirmationHash(draft)),
                draft.evidencePool(), candidates);
        batch.submit(sealed);
        projection.project(setId);

        List<UUID> memories = dsl.fetch(
                        "SELECT future_memory_id FROM memory.candidate_set_member "
                                + "WHERE candidate_set_id=? ORDER BY ordinal", setId)
                .getValues(0, UUID.class);
        UUID persistedAnchor = dsl.fetchOne(
                        "SELECT anchor_id FROM memory.candidate_evidence_mapping "
                                + "WHERE candidate_set_id=? ORDER BY candidate_id,ordinal LIMIT 1",
                        setId)
                .get(0, UUID.class);
        UUID persistedUnit = dsl.fetchOne(
                        "SELECT source_unit_id FROM evidence.source_anchor_unit "
                                + "WHERE anchor_id=? ORDER BY ordinal LIMIT 1",
                        persistedAnchor)
                .get(0, UUID.class);
        UUID payload = dsl.fetchOne(
                        "SELECT payload_id FROM evidence.source_payload "
                                + "WHERE source_unit_id=? ORDER BY payload_id LIMIT 1",
                        persistedUnit)
                .get(0, UUID.class);
        return new Projected(setId, actor, persistedAnchor, persistedUnit, payload, memories);
    }

    private static ConfirmedNoRun confirmWithoutRun(Projected projected, int memoryIndex, String marker) {
        UUID memoryId = projected.memoryIds().get(memoryIndex);
        DeletionBindingPort.MemoryFacts facts = binding.readMemoryFacts(memoryId);
        assertNotNull(facts);
        byte[] requestHash = LocalV1DeletionCanonicalizer.requestHash(
                memoryId, facts.revisionNo(), facts.policyRevisionNo());
        LocalV1S3ADeletionPreviewResult result = preview.preview(
                new LocalV1S3ADeletionPreviewRequest(
                        memoryId, "preview-" + marker + "-" + UUID.randomUUID(), requestHash));
        String confirmKey = "confirm-" + marker + "-" + UUID.randomUUID();
        confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                result.previewId(), result.previewRevision(), result.manifestHash(),
                projected.actorId(), confirmKey));
        assertEquals(0L, count(
                "SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", result.previewId()));
        return new ConfirmedNoRun(
                result.previewId(), memoryId, result.previewRevision(),
                HexFormat.of().formatHex(requestHash), HexFormat.of().formatHex(result.manifestHash()),
                confirmKey);
    }

    private static LocalV1RunStatus replayConfirmed(ConfirmedNoRun confirmed) {
        return deletion.confirm(
                confirmed.closureId(), confirmed.memoryId(), 1, 1, confirmed.requestHashHex(),
                confirmed.closureId(), confirmed.previewRevision(), confirmed.manifestHex(),
                confirmed.confirmKey());
    }

    private static void withRunMarker(ConfirmedNoRun closure, Runnable action) {
        transactions.executeInTransaction(() -> {
            OffsetDateTime at = OffsetDateTime.now(CLOCK);
            UUID runId = UUID.randomUUID();
            dsl.execute(
                    "INSERT INTO runtime.deletion_run("
                            + "deletion_run_id,closure_id,confirmed_by_decision_id,state,started_at,"
                            + "database_erased_at,payload_task_count) "
                            + "SELECT ?,closure_id,confirmed_by_decision_id,'FILE_PENDING',"
                            + "?::timestamptz,?::timestamptz,0 "
                            + "FROM memory.deletion_closure WHERE closure_id=?",
                    runId, at, at, closure.closureId());
            dsl.execute(
                    "INSERT INTO runtime.deletion_erasure_marker(closure_id,inserted_at) "
                            + "VALUES (?,?::timestamptz)",
                    closure.closureId(), at);
            action.run();
            return null;
        });
    }

    private static void assertRejected(Executable executable) {
        Exception failure = assertThrows(Exception.class, executable);
        assertSqlFailure(failure, "23514", DENIED);
    }

    private static void assertSqlFailure(Throwable failure, String sqlState, String identifier) {
        Throwable current = failure;
        SQLException sql = null;
        StringBuilder messages = new StringBuilder();
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            if (current instanceof SQLException candidate) {
                sql = candidate;
            }
            current = current.getCause();
        }
        assertNotNull(sql, () -> "missing SQLException in: " + messages);
        assertEquals(sqlState, sql.getSQLState(), messages::toString);
        assertTrue(messages.toString().contains(identifier), messages::toString);
    }

    private static FactCounts factCounts(UUID closureId) {
        return new FactCounts(
                count("SELECT count(*) FROM memory.decision "
                        + "WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?", closureId),
                count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", closureId),
                count("SELECT count(*) FROM memory.deletion_closure WHERE closure_id=?", closureId),
                count("SELECT count(*) FROM memory.deletion_closure_member WHERE closure_id=?", closureId),
                count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", closureId));
    }

    private static ErasureSnapshot erasureSnapshot(Projected projected, UUID closureId) {
        UUID memoryId = projected.memoryIds().get(0);
        return new ErasureSnapshot(
                count("SELECT count(*) FROM memory.candidate_evidence_mapping WHERE anchor_id=?", projected.anchorId()),
                count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id=?", projected.anchorId()),
                count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId),
                count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId),
                count("SELECT count(*) FROM memory.memory_revision_embedding e "
                        + "JOIN memory.memory_revision r ON r.memory_revision_id=e.memory_revision_id "
                        + "WHERE r.memory_id=?", memoryId),
                count("SELECT count(*) FROM runtime.deletion_run WHERE closure_id=?", closureId),
                count("SELECT count(*) FROM runtime.deletion_payload_task t "
                        + "JOIN runtime.deletion_run r ON r.deletion_run_id=t.deletion_run_id "
                        + "WHERE r.closure_id=?", closureId));
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static void runJooqGeneration(Path repository, Path output, Path log) throws Exception {
        Files.createDirectories(output);
        ProcessBuilder builder = new ProcessBuilder(
                "cmd.exe",
                "/d",
                "/c",
                repository.resolve("mvnw.cmd").toString(),
                "-o",
                "-pl",
                "modules/database-adapter",
                "-Pdatabase-tools",
                "org.jooq:jooq-codegen-maven:3.21.5:generate");
        builder.directory(repository.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Map<String, String> environment = builder.environment();
        environment.put("JAVA_HOME", "C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.4-hotspot");
        environment.put(
                "PATH",
                environment.get("JAVA_HOME") + "\\bin;" + environment.getOrDefault("PATH", ""));
        environment.put("HDM005_DB_URL", postgres.getJdbcUrl());
        environment.put("HDM005_DB_USER", USER);
        environment.put("HDM005_DB_PASSWORD", password);
        environment.put("HDM005_JOOQ_OUTPUT", output.toString());
        int exit = builder.start().waitFor();
        assertEquals(
                0,
                exit,
                () -> "jOOQ generation failed: "
                        + readText(log));
    }

    private static Map<String, String> treeManifest(Path root) throws Exception {
        Map<String, String> manifest = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                manifest.put(
                        relative,
                        HexFormat.of().formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(Files.readAllBytes(file))));
            }
        }
        return manifest;
    }

    private static Path moduleRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        if (current.endsWith(Path.of("modules", "database-adapter"))) {
            return current;
        }
        return current.resolve("modules/database-adapter");
    }

    private static String readText(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return failure.toString();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static String text(String sql, Object... args) {
        return dsl.fetchOne(sql, args).get(0, String.class);
    }

    private static boolean bool(String sql) {
        return Boolean.TRUE.equals(dsl.fetchValue(sql, Boolean.class));
    }

    private static byte[] sha(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String flipHex(String value) {
        assertNotNull(value);
        assertEquals(64, value.length());
        String replacement = value.charAt(0) == '0' ? "1" : "0";
        String flipped = replacement + value.substring(1);
        assertNotEquals(value, flipped);
        return flipped;
    }

    private static PostgreSQLContainer<?> container(String database, String containerPassword) {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database)
                .withUsername(USER)
                .withPassword(containerPassword)
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    private static Flyway flyway(PostgreSQLContainer<?> container, String target) {
        var configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), USER, container.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void createRoles(PostgreSQLContainer<?> container, String containerPassword)
            throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(container.getJdbcUrl(), USER, containerPassword);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE hide_nest_api NOLOGIN");
            statement.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
    }

    private static long tableCount(PostgreSQLContainer<?> container, String containerPassword)
            throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(container.getJdbcUrl(), USER, containerPassword);
                Statement statement = connection.createStatement();
                var result = statement.executeQuery(
                        "SELECT count(*) FROM information_schema.tables "
                                + "WHERE table_schema IN ('memory','evidence','runtime') "
                                + "AND table_type='BASE TABLE'")) {
            result.next();
            return result.getLong(1);
        }
    }

    private record Projected(
            UUID setId,
            UUID actorId,
            UUID anchorId,
            UUID unitId,
            UUID payloadId,
            List<UUID> memoryIds) {}

    private record ConfirmedNoRun(
            UUID closureId,
            UUID memoryId,
            long previewRevision,
            String requestHashHex,
            String manifestHex,
            String confirmKey) {}

    private record FactCounts(
            long decisions,
            long fences,
            long closures,
            long members,
            long runs) {}

    private record ErasureSnapshot(
            long mappings,
            long anchors,
            long memories,
            long revisions,
            long vectors,
            long runs,
            long tasks) {}

    private static final class FakeEmbeddingProvider implements EmbeddingProviderPort {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public EmbeddingHealth health() {
            return new EmbeddingHealth(true, MODEL, DIMENSION);
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            calls.incrementAndGet();
            double[] unit = new double[DIMENSION];
            unit[0] = 1.0;
            List<double[]> vectors = new ArrayList<>(texts.size());
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(unit.clone());
            }
            return new EmbeddingResult(MODEL, DIMENSION, vectors);
        }
    }
}
