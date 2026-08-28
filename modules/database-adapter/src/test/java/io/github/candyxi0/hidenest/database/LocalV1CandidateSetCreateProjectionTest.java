package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetBatchCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCreateProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetProjectionCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetProjectionException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetProjectionResult;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorSpec;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.Candidate;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.FinalConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareResult;
import io.github.candyxi0.hidenest.database.adapter.JooqCandidateSetGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Local V1 CandidateSet CREATE projection vertical: real PostgreSQL/pgvector + real adapters, with a
 * controllable loopback embedding fixture. Verifies the multi-CREATE publish + per-candidate vector
 * projection, idempotent replay, fail-closed REVISE/SUPERSEDE and replay tamper rejection.
 */
class LocalV1CandidateSetCreateProjectionTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA = HexFormat.of()
            .parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneId.of("UTC"));

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static String password;
    private static Path payloadRoot;

    private static MemoryGovernancePort governance;
    private static RuntimeTransactionPort runtime;
    private static TransactionExecutor transactions;
    private static CandidateSetGovernancePort candidateSetPort;
    private static JooqMemoryReadAdapter memoryRead;
    private static MemoryVectorStorePort vectorStore;
    private static LocalV1CandidateSetBatchCoordinator batch;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1CandidateSetCreateProjectionCoordinator projection;
    private static FakeEmbeddingProvider embedding;

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password);
                Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN");
            s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(22,
                Flyway.configure()
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

        governance = new JooqMemoryGovernanceAdapter(dsl);
        runtime = new JooqRuntimeTransactionAdapter(dsl);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        candidateSetPort = new JooqCandidateSetGovernanceAdapter(dsl);
        transactions = new SpringTransactionExecutor(tx);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, transactions, CLOCK);
        payloadRoot = Files.createTempDirectory("candidate-set-projection-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, transactions, publisher, payloadStore, CLOCK);
        batch = new LocalV1CandidateSetBatchCoordinator(
                evidence, governance, runtime, candidateSetPort, transactions, payloadStore, CLOCK);
        memoryRead = new JooqMemoryReadAdapter(dsl);
        vectorStore = new JooqVectorStoreAdapter(dsl);

        embedding = new FakeEmbeddingProvider();
        var fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);
        var vector = new LocalV1VectorCoordinator(
                embedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        projection = new LocalV1CandidateSetCreateProjectionCoordinator(
                candidateSetPort, governance, memoryRead, evidence, publisher, vector, vectorStore,
                fingerprint, runtime);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (postgres != null) {
            postgres.stop();
        }
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort
                    }
                });
            }
        }
    }

    @BeforeEach
    void resetEmbedding() {
        embedding.reset();
    }

    // ── 1. three accepted CREATE → 3 Memory + 3 Revision + 3 vectors ──────

    @Test
    void threeAcceptedCreatesProduceThreeMemoriesRevisionsVectors() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "小林喜欢粉色，下周准备购买家庭服务器，预算不超过3000元";
        List<Candidate> candidates = List.of(
                candidate(UUID.randomUUID(), 1, "ACCEPTED", "小林喜欢粉色", "Claim", actor, anchor),
                candidate(UUID.randomUUID(), 2, "ACCEPTED", "下周准备购买家庭服务器", "Event", actor, anchor),
                candidate(UUID.randomUUID(), 3, "ACCEPTED", "购买预算不超过 3000 元", "Claim", actor, anchor));
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)), candidates);

        LocalV1CandidateSetProjectionResult result = projection.project(set);

        assertEquals("INDEX_READY", result.phase());
        assertEquals(3, result.candidates().size());
        for (var rec : result.candidates()) {
            assertEquals("INDEX_READY", rec.phase());
            assertEquals("CREATE", rec.action());
            assertEquals("ACCEPTED", rec.disposition());
            assertEquals(1L, rec.revisionNo());
            assertNotNull(rec.memoryId());
            assertNotNull(rec.memoryRevisionId());
        }
        // three memories, three revisions, three vectors; each memoryId == member.future_memory_id
        List<UUID> futureIds = futureMemoryIds(set);
        assertEquals(3, futureIds.size());
        for (UUID memoryId : futureIds) {
            assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
            assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId));
            assertEquals(1L,
                    count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id="
                            + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?)", memoryId));
        }
        assertEquals(3, result.candidates().stream().filter(c -> "INDEX_READY".equals(c.phase())).count());
    }

    // ── 2. shared evidence: 1/1/1 underlying, three EVIDENCED_BY ───────────

    @Test
    void sharedEvidenceSingleAnchorThreeEvidencedByRelations() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "下周准备购买家庭服务器，预算不超过3000元";
        List<Candidate> candidates = List.of(
                candidate(UUID.randomUUID(), 1, "ACCEPTED", "下周准备购买家庭服务器", "Event", actor, anchor),
                candidate(UUID.randomUUID(), 2, "ACCEPTED", "购买预算不超过 3000 元", "Claim", actor, anchor),
                candidate(UUID.randomUUID(), 3, "ACCEPTED", "本周内确定配置", "Claim", actor, anchor));
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)), candidates);

        LocalV1CandidateSetProjectionResult result = projection.project(set);
        assertEquals("INDEX_READY", result.phase());

        UUID sourceId = uuid("SELECT source_id FROM evidence.source WHERE external_ref='candidate-set:" + set + "'");
        assertEquals(1L, count("SELECT count(*) FROM evidence.source_unit WHERE source_id=?", sourceId));
        assertEquals(1L,
                count("SELECT count(*) FROM evidence.source_payload WHERE source_unit_id IN "
                        + "(SELECT source_unit_id FROM evidence.source_unit WHERE source_id=?)", sourceId));
        assertEquals(1L, count("SELECT count(*) FROM evidence.source_anchor WHERE source_id=?", sourceId));
        // three revisions, each with exactly one EVIDENCED_BY relation
        List<UUID> futureIds = futureMemoryIds(set);
        assertEquals(3, futureIds.size());
        for (UUID memoryId : futureIds) {
            assertEquals(1L,
                    count("SELECT count(*) FROM memory.memory_relation r "
                            + "JOIN memory.memory_revision v ON v.memory_revision_id = r.from_revision_id "
                            + "WHERE v.memory_id=? AND r.relation_type='EVIDENCED_BY'", memoryId));
        }
    }

    // ── 3. mixed accepted/rejected → only accepted published ──────────────

    @Test
    void mixedAcceptedRejectedOnlyAcceptedPublished() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "混合证据";
        UUID acceptedCandidateId = UUID.randomUUID();
        UUID rejectedCandidateId = UUID.randomUUID();
        List<Candidate> candidates = List.of(
                candidate(acceptedCandidateId, 1, "ACCEPTED", "被接受的记忆", "Claim", actor, anchor),
                new Candidate(rejectedCandidateId, 2, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        null, null, actor, List.of(), null, null, null, null, "reason-rejected"));
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)), candidates);

        LocalV1CandidateSetProjectionResult result = projection.project(set);

        assertEquals("INDEX_READY", result.phase());
        assertEquals(2, result.candidates().size());
        var accepted = result.candidates().get(0);
        var rejected = result.candidates().get(1);
        assertEquals("INDEX_READY", accepted.phase());
        assertEquals("REJECTED", rejected.phase());
        assertNull(rejected.memoryId());
        assertNull(rejected.memoryRevisionId());

        UUID acceptedFuture = futureMemoryId(set, acceptedCandidateId);
        UUID rejectedFuture = futureMemoryId(set, rejectedCandidateId);
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", acceptedFuture));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", rejectedFuture));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", rejectedFuture));
        assertEquals(0L,
                count("SELECT count(*) FROM memory.memory_relation r JOIN memory.memory_revision v "
                        + "ON v.memory_revision_id=r.from_revision_id WHERE v.memory_id=?", rejectedFuture));
        assertEquals(0L,
                count("SELECT count(*) FROM memory.memory_revision_embedding e JOIN memory.memory_revision v "
                        + "ON v.memory_revision_id=e.memory_revision_id WHERE v.memory_id=?", rejectedFuture));
    }

    // ── 4. all-rejected → NO_ACCEPTED_CANDIDATES, zero publish/embed ──────

    @Test
    void allRejectedZeroPublishZeroEmbed() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        List<Candidate> candidates = List.of(
                new Candidate(UUID.randomUUID(), 1, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        null, null, actor, List.of(), null, null, null, null, "r1"),
                new Candidate(UUID.randomUUID(), 2, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        null, null, actor, List.of(), null, null, null, null, "r2"));
        UUID set = seed(
                setId,
                threadId(),
                List.of(message(UUID.randomUUID(), actor, 1, "rejected perspective identity")),
                List.of(),
                candidates);

        long memBefore = count("SELECT count(*) FROM memory.memory_record");
        int embedBefore = embedding.callCount();
        LocalV1CandidateSetProjectionResult result = projection.project(set);

        assertEquals("NO_ACCEPTED_CANDIDATES", result.phase());
        assertEquals(2, result.candidates().size());
        assertTrue(result.candidates().stream().allMatch(c -> "REJECTED".equals(c.phase())));
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));
        assertEquals(0, embedding.callCount() - embedBefore);
    }

    // ── 5. same-set replay → EXACT, no increment, embed delta 0 ───────────

    @Test
    void sameSetReplayExactNoIncrementNoEmbedDelta() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "重放证据";
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(candidate(UUID.randomUUID(), 1, "ACCEPTED", "重放记忆", "Claim", actor, anchor)));

        LocalV1CandidateSetProjectionResult first = projection.project(set);
        assertEquals("INDEX_READY", first.phase());
        long mem = count("SELECT count(*) FROM memory.memory_record");
        long rev = count("SELECT count(*) FROM memory.memory_revision");
        long rel = count("SELECT count(*) FROM memory.memory_relation");
        long pol = count("SELECT count(*) FROM memory.access_policy");
        long emb = count("SELECT count(*) FROM memory.memory_revision_embedding");
        int embedCalls = embedding.callCount();

        LocalV1CandidateSetProjectionResult replay = projection.project(set);

        assertEquals(first.phase(), replay.phase());
        assertEquals(first.candidates().get(0).memoryId(), replay.candidates().get(0).memoryId());
        assertEquals(first.candidates().get(0).memoryRevisionId(), replay.candidates().get(0).memoryRevisionId());
        assertEquals(mem, count("SELECT count(*) FROM memory.memory_record"));
        assertEquals(rev, count("SELECT count(*) FROM memory.memory_revision"));
        assertEquals(rel, count("SELECT count(*) FROM memory.memory_relation"));
        assertEquals(pol, count("SELECT count(*) FROM memory.access_policy"));
        assertEquals(emb, count("SELECT count(*) FROM memory.memory_revision_embedding"));
        assertEquals(0, embedding.callCount() - embedCalls);
    }

    // ── 6. two-thread same-set concurrency → one fact each ────────────────

    @Test
    void concurrentSameSetOneFactEach() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "并发证据";
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(candidate(UUID.randomUUID(), 1, "ACCEPTED", "并发记忆", "Claim", actor, anchor)));

        CountDownLatch barrier = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Runnable task = () -> {
            try {
                barrier.countDown();
                go.await();
                projection.project(set);
                ok.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        exec.submit(task);
        exec.submit(task);
        barrier.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(20, TimeUnit.SECONDS));
        assertEquals(2, ok.get());

        UUID memoryId = futureMemoryIds(set).get(0);
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId));
        assertEquals(1L,
                count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id="
                        + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?)", memoryId));
    }

    // ── 7+8. second vector failure isolated, then recovery backfills ──────

    @Test
    void secondVectorFailureIsolatedThenRecoveryBackfills() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "向量失败证据";
        String body2 = "第二条正文触发失败";
        List<Candidate> candidates = List.of(
                candidate(UUID.randomUUID(), 1, "ACCEPTED", "第一条正文", "Claim", actor, anchor),
                candidate(UUID.randomUUID(), 2, "ACCEPTED", body2, "Claim", actor, anchor),
                candidate(UUID.randomUUID(), 3, "ACCEPTED", "第三条正文", "Claim", actor, anchor));
        UUID set = seed(setId, threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)), candidates);

        embedding.failOnText = body2;
        LocalV1CandidateSetProjectionResult first = projection.project(set);
        assertEquals("CANONICAL_COMMITTED", first.phase());
        assertEquals("INDEX_READY", first.candidates().get(0).phase());
        assertEquals("CANONICAL_COMMITTED", first.candidates().get(1).phase());
        assertEquals("INDEX_READY", first.candidates().get(2).phase());
        // three canonical memories, two vectors
        List<UUID> futureIds = futureMemoryIds(set);
        assertEquals(3, futureIds.size());
        for (UUID memoryId : futureIds) {
            assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
            assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId));
        }
        assertEquals(2L, embeddingsForSet(set));

        embedding.failOnText = null;
        int embedBefore = embedding.callCount();
        LocalV1CandidateSetProjectionResult replay = projection.project(set);
        assertEquals("INDEX_READY", replay.phase());
        assertTrue(replay.candidates().stream().allMatch(c -> "INDEX_READY".equals(c.phase())));
        assertEquals(3L, embeddingsForSet(set));
        assertEquals(1, embedding.callCount() - embedBefore, "only the missing second vector must be embedded");
    }

    // ── 10. accepted REVISE / SUPERSEDE / mixed → fail closed, zero new ───

    @Test
    void acceptedReviseSupersedeAndMixedFailClosed() {
        SeededMemory m1 = seedActiveMemory();
        SeededMemory m2 = seedActiveMemory();
        long memBefore = count("SELECT count(*) FROM memory.memory_record");

        // accepted REVISE alone
        UUID a1 = UUID.randomUUID();
        UUID u1 = UUID.randomUUID();
        UUID an1 = UUID.randomUUID();
        String b1 = "revise evidence";
        UUID reviseSetId = UUID.randomUUID();
        seed(reviseSetId, threadId(), List.of(message(u1, a1, 1, b1)),
                List.of(fullAnchor(an1, u1, b1)),
                List.of(reviseCandidate(UUID.randomUUID(), 1, "ACCEPTED", "REVISE", a1, an1,
                        m1.memoryId(), m1.currentRevisionId(), 1L, 1L)));
        assertProjectionRejected(reviseSetId, LocalV1CandidateSetProjectionException.Code.UNSUPPORTED_CANDIDATE_ACTION);
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));

        // accepted SUPERSEDE alone
        UUID a2 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID an2 = UUID.randomUUID();
        String b2 = "supersede evidence";
        UUID supersedeSetId = UUID.randomUUID();
        seed(supersedeSetId, threadId(), List.of(message(u2, a2, 1, b2)),
                List.of(fullAnchor(an2, u2, b2)),
                List.of(reviseCandidate(UUID.randomUUID(), 1, "ACCEPTED", "SUPERSEDE", a2, an2,
                        m2.memoryId(), m2.currentRevisionId(), 1L, 1L)));
        assertProjectionRejected(supersedeSetId, LocalV1CandidateSetProjectionException.Code.UNSUPPORTED_CANDIDATE_ACTION);
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));

        // mixed accepted CREATE + accepted REVISE → reject before publishing the CREATE
        UUID a3 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        UUID an3 = UUID.randomUUID();
        String b3 = "mixed evidence";
        UUID mixedSetId = UUID.randomUUID();
        seed(mixedSetId, threadId(), List.of(message(u3, a3, 1, b3)),
                List.of(fullAnchor(an3, u3, b3)),
                List.of(
                        candidate(UUID.randomUUID(), 1, "ACCEPTED", "create candidate", "Claim", a3, an3),
                        reviseCandidate(UUID.randomUUID(), 2, "ACCEPTED", "REVISE", a3, an3,
                                m1.memoryId(), m1.currentRevisionId(), 1L, 1L)));
        assertProjectionRejected(mixedSetId, LocalV1CandidateSetProjectionException.Code.UNSUPPORTED_CANDIDATE_ACTION);
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));
    }

    // ── 11. closure attacks: mapping / proposal-revision missing → reject, 0 new ──

    @Test
    void closureAttacksMappingAndProposalRevisionRejected() throws Exception {
        UUID set = seedSingleAccepted("closure");
        long memBefore = count("SELECT count(*) FROM memory.memory_record");

        // delete the evidence mapping → MAPPING_CLOSURE_INVALID (accepted with no mappings)
        dropAndRestoreTrigger("candidate_evidence_mapping_immutable", "memory.candidate_evidence_mapping",
                () -> dsl.execute("DELETE FROM memory.candidate_evidence_mapping WHERE candidate_set_id=?", set));
        assertProjectionRejected(set, LocalV1CandidateSetProjectionException.Code.MAPPING_CLOSURE_INVALID);
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));

        UUID set2 = seedSingleAccepted("closure-bodyhash");
        UUID proposalRevId = uuid(
                "SELECT proposal_revision_id FROM memory.candidate_set_member WHERE candidate_set_id=? AND ordinal=1",
                set2);
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET body_hash=? WHERE proposal_revision_id=?",
                        sha("tampered-body"), proposalRevId));
        assertProjectionRejected(set2, LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
        assertEquals(memBefore, count("SELECT count(*) FROM memory.memory_record"));
    }

    // ── 12. replay tampered memory facts rejected, no second memory ───────

    @Test
    void replayTamperedMemoryFactsRejected() throws Exception {
        // (a) delete EVIDENCED_BY relation → replay rejects
        UUID set = seedSingleAccepted("tamper-relation");
        assertEquals("INDEX_READY", projection.project(set).phase());
        UUID memoryId = futureMemoryIds(set).get(0);
        UUID revisionId = currentRevisionId(memoryId);
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", revisionId);
        assertProjectionRejected(set, LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));

        // (b) tamper revision body → replay rejects
        UUID set2 = seedSingleAccepted("tamper-body");
        assertEquals("INDEX_READY", projection.project(set2).phase());
        UUID memoryId2 = futureMemoryIds(set2).get(0);
        UUID revisionId2 = currentRevisionId(memoryId2);
        dropAndRestoreTrigger("memory_revision_immutable", "memory.memory_revision",
                () -> dsl.execute("UPDATE memory.memory_revision SET body_text='tampered' WHERE memory_revision_id=?", revisionId2));
        assertProjectionRejected(set2, LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId2));
    }

    // ── 13. mismatched vector is never INDEX_READY ────────────────────────

    @Test
    void mismatchedVectorIsNotIndexReady() {
        UUID set = seedSingleAccepted("vector-mismatch");
        assertEquals("INDEX_READY", projection.project(set).phase());
        UUID memoryId = futureMemoryIds(set).get(0);
        UUID revisionId = currentRevisionId(memoryId);

        // wrong model → the frozen-fingerprint hasEmbedding must be false; replay re-embeds rather than faking ready
        dsl.execute("UPDATE memory.memory_revision_embedding SET model_name='wrong-model' WHERE memory_revision_id=?", revisionId);
        LocalV1CandidateSetProjectionResult result = projection.project(set);
        assertEquals("INDEX_READY", result.phase());
        // a correct-model vector must now exist alongside the wrong one (no overwrite of the wrong fact)
        assertEquals(1L,
                count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=? AND model_name=?",
                        revisionId, MODEL));
        assertEquals(1L,
                count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=? AND model_name='wrong-model'",
                        revisionId));
    }

    // ── 14. R1-01: bodyHash binds canonical identity ──────────────────────

    @Test
    void bodyHashOnlyDifferenceChangesHashes() {
        UUID setId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewSessionId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        List<UUID> anchors = List.of(UUID.randomUUID(), UUID.randomUUID());
        byte[] h1 = sha("body-one");
        byte[] h2 = sha("body-two");

        byte[] req1 = LocalV1CandidateSetProjectionCanonicalizer.requestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, h1, anchors);
        byte[] req2 = LocalV1CandidateSetProjectionCanonicalizer.requestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, h2, anchors);
        byte[] man1 = LocalV1CandidateSetProjectionCanonicalizer.manifestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, h1, anchors);
        byte[] man2 = LocalV1CandidateSetProjectionCanonicalizer.manifestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, h2, anchors);

        assertFalse(java.util.Arrays.equals(req1, req2), "bodyHash must change requestHash");
        assertFalse(java.util.Arrays.equals(man1, man2), "bodyHash must change manifestHash");

        // null / empty / 32-byte must be pairwise distinct.
        byte[] nullHash = LocalV1CandidateSetProjectionCanonicalizer.requestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, null, anchors);
        byte[] emptyHash = LocalV1CandidateSetProjectionCanonicalizer.requestHash(
                setId, candidateId, 1, decisionId, proposalRevisionId, reviewSessionId, memoryId,
                "Claim", actor, new byte[0], anchors);
        assertFalse(java.util.Arrays.equals(nullHash, emptyHash));
        assertFalse(java.util.Arrays.equals(nullHash, req1));
        assertFalse(java.util.Arrays.equals(emptyHash, req1));
    }

    @Test
    void bodyHashPairedSwapRejectsOnReplay() throws Exception {
        UUID set = seedSingleAccepted("paired-swap");
        assertEquals("INDEX_READY", projection.project(set).phase());
        UUID memoryId = futureMemoryIds(set).get(0);

        // Pairwise swap: body_text AND body_hash are both replaced with a new consistent pair.
        String newBody = "replaced body";
        byte[] newBodyHash = sha(newBody);
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET body_text=?, body_hash=? WHERE proposal_revision_id=?",
                        newBody, newBodyHash, proposalRevisionId(set)));

        // The bodyHash-bound requestHash no longer matches the frozen receipt → reject, no second memory.
        LocalV1CandidateSetProjectionException ex =
                assertThrows(LocalV1CandidateSetProjectionException.class, () -> projection.project(set));
        assertEquals(LocalV1CandidateSetProjectionException.Code.CANONICAL_PUBLISH_FAILED, ex.code());
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
    }

    @Test
    void invalidAcceptedBodyFactsRejected() throws Exception {
        // null bodyHash
        UUID set = seedSingleAccepted("bad-nullhash");
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET body_hash=NULL WHERE proposal_revision_id=?",
                        proposalRevisionId(set)));
        assertProjectionRejected(set, LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);

        // blank body
        UUID set2 = seedSingleAccepted("bad-blank");
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET body_text='   ' WHERE proposal_revision_id=?",
                        proposalRevisionId(set2)));
        assertProjectionRejected(set2, LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);

        // null memoryType (non-null illegal values are DB-CHECK-blocked)
        UUID set3 = seedSingleAccepted("bad-type");
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET memory_type=NULL WHERE proposal_revision_id=?",
                        proposalRevisionId(set3)));
        assertProjectionRejected(set3, LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);

        // null perspective actor
        UUID set4 = seedSingleAccepted("bad-actor");
        dropAndRestoreTrigger("proposal_revision_immutable", "memory.proposal_revision",
                () -> dsl.execute("UPDATE memory.proposal_revision SET perspective_actor_id=NULL WHERE proposal_revision_id=?",
                        proposalRevisionId(set4)));
        assertProjectionRejected(set4, LocalV1CandidateSetProjectionException.Code.PROPOSAL_REVISION_MISMATCH);
    }

    // ── 15. R1-03: single-factor replay attacks (pointer/policy/owner/receipt) ──

    @Test
    void replayPointerTamperRejected() throws Exception {
        assertReplayRejected("pointer", (set, memoryId, revisionId) -> {
            UUID actorId = uuid("SELECT pr.perspective_actor_id FROM memory.proposal_revision pr "
                    + "JOIN memory.candidate_set_member m ON m.proposal_revision_id=pr.proposal_revision_id "
                    + "WHERE m.candidate_set_id=? AND m.ordinal=1", set);
            reviseMemorySameBody(memoryId, actorId, revisionId, 1L, "second revision body");
        });
    }

    @Test
    void replayPolicyTamperRejected() throws Exception {
        assertReplayRejected("policy", (set, memoryId, revisionId) -> {
            SeededMemory foreign = seedActiveMemory();
            UUID foreignPolicy = uuid("SELECT policy_id FROM memory.memory_record WHERE memory_id=?", foreign.memoryId());
            suspendMemoryRecordUpdate(() ->
                    dsl.execute("UPDATE memory.memory_record SET policy_id=? WHERE memory_id=?", foreignPolicy, memoryId));
        });
    }

    @Test
    void replayDecisionOwnerTamperRejected() throws Exception {
        assertReplayRejected("owner", (set, memoryId, revisionId) -> {
            UUID otherSet = seedSingleAccepted("owner-other");
            UUID otherDecision = uuid("SELECT decision_id FROM memory.candidate_set_member WHERE candidate_set_id=? AND ordinal=1", otherSet);
            dropAndRestoreTrigger("memory_revision_immutable", "memory.memory_revision",
                    () -> dsl.execute("UPDATE memory.memory_revision SET created_by_decision_id=? WHERE memory_revision_id=?",
                            otherDecision, revisionId));
        });
    }

    @Test
    void replayReceiptOperationCodeTamperRejected() throws Exception {
        assertReplayRejected("receipt-op", (set, memoryId, revisionId) ->
                tamperReceipt(set, "UPDATE runtime.idempotency_receipt SET operation_code='WRONG' WHERE idempotency_key=?"));
    }

    @Test
    void replayReceiptResourceIdTamperRejected() throws Exception {
        assertReplayRejected("receipt-resource", (set, memoryId, revisionId) ->
                tamperReceipt(set, "UPDATE runtime.idempotency_receipt SET resource_id='" + UUID.randomUUID() + "' WHERE idempotency_key=?"));
    }

    @Test
    void replayReceiptRequestHashTamperRejected() throws Exception {
        // A tampered receipt request_hash is caught by the frozen publishFirst idempotency check.
        assertReplayRejected("receipt-hash",
                (set, memoryId, revisionId) ->
                        tamperReceipt(set, "UPDATE runtime.idempotency_receipt SET request_hash=decode(repeat('ab',32),'hex') WHERE idempotency_key=?"),
                LocalV1CandidateSetProjectionException.Code.CANONICAL_PUBLISH_FAILED);
    }

    @Test
    void replayReceiptManifestTamperRejected() throws Exception {
        assertReplayRejected("receipt-manifest", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                        + "\"requestId\":\"" + UUID.randomUUID() + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}"));
    }

    // ── 15b. R1A: strict closed-shape receipt manifest single-factor attacks ──

    @Test
    void replayReceiptManifestWrongRequestIdWithNoteRejected() throws Exception {
        assertReplayRejected("manifest-note", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                        + "\"requestId\":\"" + UUID.randomUUID() + "\",\"resultCategory\":\"SUCCEEDED\","
                        + "\"retryable\":false,\"note\":\"" + revisionId + "\"}"));
    }

    @Test
    void replayReceiptManifestExtraFieldRejected() throws Exception {
        assertReplayRejected("manifest-extra", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                        + "\"requestId\":\"" + revisionId + "\",\"resultCategory\":\"SUCCEEDED\","
                        + "\"retryable\":false,\"extra\":true}"));
    }

    @Test
    void replayReceiptManifestWrongTypesRejected() throws Exception {
        // status as the string "200"
        assertReplayRejected("manifest-status-str", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":\"200\","
                        + "\"requestId\":\"" + revisionId + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":false}"));
        // retryable as the string "false"
        assertReplayRejected("manifest-retry-str", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                        + "\"requestId\":\"" + revisionId + "\",\"resultCategory\":\"SUCCEEDED\",\"retryable\":\"false\"}"));
        // resultCategory wrong value
        assertReplayRejected("manifest-cat-wrong", (set, memoryId, revisionId) ->
                tamperManifest(set, "{\"type\":\"urn:pink:response:canonical-publish\",\"status\":200,"
                        + "\"requestId\":\"" + revisionId + "\",\"resultCategory\":\"FAILED\",\"retryable\":false}"));
    }

    @Test
    void legalReceiptManifestKeyOrderNormalizedPasses() {
        UUID set = seedSingleAccepted("manifest-legal");
        LocalV1CandidateSetProjectionResult result = projection.project(set);
        // The receipt manifest is stored as JSONB (PostgreSQL-normalized key order) and must still pass.
        assertEquals("INDEX_READY", result.phase());
    }

    // ── 16. R1-03: wrong vector bodyHash is never INDEX_READY ─────────────

    @Test
    void mismatchedVectorBodyHashNotIndexReady() {
        UUID set = seedSingleAccepted("vector-bodyhash");
        assertEquals("INDEX_READY", projection.project(set).phase());
        UUID memoryId = futureMemoryIds(set).get(0);
        UUID revisionId = currentRevisionId(memoryId);
        String body = dsl.fetchOne("SELECT body_text FROM memory.memory_revision WHERE memory_revision_id=?", revisionId)
                .get("body_text", String.class);

        dsl.execute("UPDATE memory.memory_revision_embedding SET embedded_body_sha256=? WHERE memory_revision_id=?",
                sha("tampered-body"), revisionId);

        LocalV1CandidateSetProjectionResult result = projection.project(set);
        assertEquals("CANONICAL_COMMITTED", result.phase());
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=? AND embedded_body_sha256=?",
                revisionId, sha("tampered-body")));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=? AND embedded_body_sha256=?",
                revisionId, sha(body)));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void assertProjectionRejected(UUID set, LocalV1CandidateSetProjectionException.Code expected) {
        LocalV1CandidateSetProjectionException ex =
                assertThrows(LocalV1CandidateSetProjectionException.class, () -> projection.project(set));
        assertEquals(expected, ex.code());
    }

    private UUID seedSingleAccepted(String marker) {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "single accepted " + marker;
        return seed(UUID.randomUUID(), threadId(), List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(candidate(UUID.randomUUID(), 1, "ACCEPTED", "memory " + marker, "Claim", actor, anchor)));
    }

    private void dropAndRestoreTrigger(String trigger, String table, ThrowingRunnable action) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            try {
                action.run();
            } finally {
                s.execute("CREATE TRIGGER " + trigger + " BEFORE UPDATE OR DELETE ON " + table
                        + " FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            }
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private interface TamperFn {
        void run(UUID set, UUID memoryId, UUID revisionId) throws Exception;
    }

    private void assertReplayRejected(String marker, TamperFn tamper) throws Exception {
        assertReplayRejected(marker, tamper, LocalV1CandidateSetProjectionException.Code.PUBLISHED_MEMORY_VERIFICATION_FAILED);
    }

    private void assertReplayRejected(
            String marker, TamperFn tamper, LocalV1CandidateSetProjectionException.Code expected) throws Exception {
        UUID set = seedSingleAccepted(marker);
        assertEquals("INDEX_READY", projection.project(set).phase());
        UUID memoryId = futureMemoryIds(set).get(0);
        int embedBefore = embedding.callCount();

        tamper.run(set, memoryId, currentRevisionId(memoryId));

        long memCount = count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId);
        long revCount = count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId);

        assertProjectionRejected(set, expected);
        // the replay must not add a second memory, a further revision, or any embedding
        assertEquals(memCount, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
        assertEquals(revCount, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", memoryId));
        assertEquals(embedBefore, embedding.callCount());
        assertEquals(1L, memCount);
    }

    private UUID proposalRevisionId(UUID set) {
        return uuid("SELECT proposal_revision_id FROM memory.candidate_set_member WHERE candidate_set_id=? AND ordinal=1", set);
    }

    private UUID candidateIdOf(UUID set) {
        return uuid("SELECT candidate_id FROM memory.candidate_set_member WHERE candidate_set_id=? AND ordinal=1", set);
    }

    private void suspendMemoryRecordUpdate(ThrowingRunnable action) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS memory_record_update_guard ON memory.memory_record");
            s.execute("DROP TRIGGER IF EXISTS memory_record_governance_guard ON memory.memory_record");
            try {
                action.run();
            } finally {
                s.execute("CREATE TRIGGER memory_record_update_guard BEFORE UPDATE ON memory.memory_record "
                        + "FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_update()");
                s.execute("CREATE CONSTRAINT TRIGGER memory_record_governance_guard "
                        + "AFTER UPDATE ON memory.memory_record DEFERRABLE INITIALLY DEFERRED "
                        + "FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_governance()");
            }
        }
    }

    private void tamperReceipt(UUID set, String updateSql) throws Exception {
        tamperReceipt(set, updateSql, null);
    }

    private void tamperReceipt(UUID set, String updateSql, Object arg) throws Exception {
        String key = LocalV1CandidateSetProjectionCanonicalizer.publishIdempotencyKey(set, candidateIdOf(set));
        dropAndRestoreTrigger("idempotency_receipt_immutable", "runtime.idempotency_receipt",
                () -> {
                    if (arg == null) {
                        dsl.execute(updateSql, key);
                    } else {
                        dsl.execute(updateSql, arg, key);
                    }
                });
    }

    private void tamperManifest(UUID set, String manifest) throws Exception {
        String key = LocalV1CandidateSetProjectionCanonicalizer.publishIdempotencyKey(set, candidateIdOf(set));
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password);
                Statement s = c.createStatement()) {
            // Temporarily lift the immutability trigger and the loose DB manifest CHECK so a
            // malformed manifest (extra/duplicate keys, wrong types) can actually be injected; the
            // projection parser — not the DB CHECK — is what must reject it.
            s.execute("DROP TRIGGER IF EXISTS idempotency_receipt_immutable ON runtime.idempotency_receipt");
            s.execute("ALTER TABLE runtime.idempotency_receipt DROP CONSTRAINT IF EXISTS idempotency_receipt_manifest_body_check");
            try {
                dsl.execute("UPDATE runtime.idempotency_receipt SET response_manifest=?::jsonb WHERE idempotency_key=?",
                        manifest, key);
            } finally {
                s.execute("ALTER TABLE runtime.idempotency_receipt ADD CONSTRAINT idempotency_receipt_manifest_body_check "
                        + "CHECK (runtime.valid_receipt_manifest(response_manifest)) NOT VALID");
                s.execute("CREATE TRIGGER idempotency_receipt_immutable BEFORE UPDATE OR DELETE ON runtime.idempotency_receipt "
                        + "FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            }
        }
    }

    private UUID reviseMemorySameBody(
            UUID memoryId, UUID actorId, UUID currentRevisionId, long currentRevisionNo, String body) {
        return transactions.executeInTransaction(() -> {
            long currentPolicyRev = count("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id=?", memoryId);
            UUID proposalId = UUID.randomUUID();
            UUID proposalRevisionId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID decisionId = UUID.randomUUID();
            UUID newRevisionId = UUID.randomUUID();
            long newRevisionNo = currentRevisionNo + 1;
            dsl.execute(
                    "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES (?::uuid,'REVISE',?::uuid,clock_timestamp())",
                    proposalId, memoryId);
            dsl.execute(
                    "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES (?::uuid,?::uuid,1,'REVISE',?::uuid,?,clock_timestamp())",
                    proposalRevisionId, proposalId, currentRevisionId, currentPolicyRev);
            dsl.execute(
                    "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) VALUES (?::uuid,'OPEN',?,decode(repeat('aa',32),'hex'),clock_timestamp())",
                    reviewId, "rev-review-" + reviewId);
            dsl.execute(
                    "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES (?::uuid,?::uuid,1)",
                    reviewId, proposalRevisionId);
            dsl.execute(
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES (?::uuid,'USER_CONFIRM',?::uuid,'USER',?::uuid,?::uuid,'MEMORY',?::uuid,?,'proof',?,clock_timestamp())",
                    decisionId, actorId, proposalRevisionId, reviewId, memoryId, newRevisionNo, "rev-dec-" + decisionId);
            governedOutbox("REVIEW_SESSION", reviewId, newRevisionNo, "review.decisions-committed.v1", decisionId);
            dsl.execute(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) VALUES (?::uuid,?::uuid,?,'Claim',?,?::uuid,clock_timestamp())",
                    newRevisionId, memoryId, newRevisionNo, body, decisionId);
            governedOutbox("MEMORY", memoryId, newRevisionNo, "memory.canonical-committed.v1", decisionId);
            dsl.execute(
                    "UPDATE memory.memory_record SET current_revision_id=?::uuid,updated_at=clock_timestamp() WHERE memory_id=?::uuid AND current_revision_id=?::uuid",
                    newRevisionId, memoryId, currentRevisionId);
            return newRevisionId;
        });
    }

    private void governedOutbox(String aggregateKind, UUID aggregateId, long revision, String eventType, UUID decisionId) {
        UUID changeId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES (?::uuid,?,?,?::uuid,?,?::uuid,clock_timestamp())",
                changeId, eventType, aggregateKind, aggregateId, revision, decisionId);
        String manifest = "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":" + revision
                + ",\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + "ab".repeat(32) + "\"}";
        dsl.execute(
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,created_at) VALUES (?::uuid,?,'GOVERNED',?,?,?::uuid,?,'pink.event.v1','DATABASE_TEST',0,decode(repeat('ab',32),'hex'),?::jsonb,?::uuid,'READY',clock_timestamp(),clock_timestamp())",
                UUID.randomUUID(), "ob-" + UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision, manifest, changeId);
    }

    private UUID currentRevisionId(UUID memoryId) {
        return dsl.fetchOne("SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?", memoryId)
                .get("current_revision_id", UUID.class);
    }

    private List<UUID> futureMemoryIds(UUID set) {
        var rows = dsl.fetch("SELECT future_memory_id FROM memory.candidate_set_member "
                + "WHERE candidate_set_id=? ORDER BY ordinal", set);
        List<UUID> ids = new ArrayList<>(rows.size());
        for (var row : rows) {
            ids.add(row.get("future_memory_id", UUID.class));
        }
        return ids;
    }

    private long embeddingsForSet(UUID set) {
        long total = 0;
        for (UUID memoryId : futureMemoryIds(set)) {
            total += count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id="
                    + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?)", memoryId);
        }
        return total;
    }

    private UUID futureMemoryId(UUID set, UUID candidateId) {
        return dsl.fetchOne("SELECT future_memory_id FROM memory.candidate_set_member "
                + "WHERE candidate_set_id=? AND candidate_id=?", set, candidateId)
                .get("future_memory_id", UUID.class);
    }

    private UUID seed(
            UUID setId, UUID threadId, List<EvidenceMessage> messages, List<AnchorSpec> anchors, List<Candidate> candidates) {
        LocalV1CandidateSetRequest draft = new LocalV1CandidateSetRequest(
                setId, "key-" + setId, new byte[32], threadId, "scope", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors), candidates);
        byte[] requestHash = LocalV1CandidateSetCanonicalizer.requestHash(draft);
        byte[] confirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(draft);
        LocalV1CandidateSetRequest sealed = new LocalV1CandidateSetRequest(
                setId, "key-" + setId, requestHash, threadId, "scope", 1,
                new FinalConfirmation("CONFIRM_SET", 1, confirmationHash),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors), candidates);
        batch.submit(sealed);
        return setId;
    }

    private static UUID threadId() {
        return UUID.randomUUID();
    }

    private Candidate candidate(
            UUID candidateId, long ordinal, String disposition, String text, String type, UUID actor, UUID anchor) {
        return new Candidate(candidateId, ordinal, disposition, "CREATE", "HIDE_PROPOSED", "HIDE",
                text, type, actor, List.of(anchor), null, null, null, null, "reason-" + candidateId);
    }

    private Candidate reviseCandidate(
            UUID candidateId, long ordinal, String disposition, String action, UUID actor, UUID anchor,
            UUID target, UUID expectedRevisionId, long expectedRevisionNo, long expectedPolicyNo) {
        return new Candidate(candidateId, ordinal, disposition, action, "HIDE_PROPOSED", "HIDE",
                "revised text", "Claim", actor, List.of(anchor), target, expectedRevisionId,
                expectedRevisionNo, expectedPolicyNo, "reason-" + candidateId);
    }

    private EvidenceMessage message(UUID unitId, UUID actorId, long ordinal, String text) {
        return new EvidenceMessage(unitId, actorId, "XIAOLIN", ordinal, "msg-" + unitId, OffsetDateTime.now(CLOCK), text, sha(text));
    }

    private AnchorSpec fullAnchor(UUID anchorId, UUID unitId, String text) {
        return new AnchorSpec(anchorId, List.of(new AnchorUnit(unitId, 0L, (long) text.length(), 1L)));
    }

    private record SeededMemory(UUID memoryId, UUID currentRevisionId) {}

    private SeededMemory seedActiveMemory() {
        UUID actor = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        String bodyText = "seed memory body";
        var prepare = new LocalV1S1PrepareRequest(
                prepKey, sha(prepKey), actor, "Claim", bodyText, sha(bodyText),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(msg1, actor, 1L, "m1", OffsetDateTime.now(CLOCK), "证据一"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(msg2, actor, 2L, "m2", OffsetDateTime.now(CLOCK), "证据二")),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(UUID.randomUUID(),
                                List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(msg1, 0L, 3L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(UUID.randomUUID(),
                                List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(msg2, 0L, 3L, 2L)))));
        LocalV1S1PrepareResult prep = s1.prepare(prepare);
        UUID memoryId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var confirm = s1.confirm(new LocalV1S1ConfirmRequest(
                confirmKey, sha(confirmKey), prep.proposalRevisionId(), prep.reviewSessionId(),
                memoryId, UUID.randomUUID(), new byte[32]));
        return new SeededMemory(memoryId, confirm.currentRevisionId());
    }

    // ── SQL / crypto helpers ──────────────────────────────────────────────

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static UUID uuid(String sql, Object... args) {
        return dsl.fetchOne(sql, args).get(0, UUID.class);
    }

    private static byte[] sha(String in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── controllable loopback embedding fixture ───────────────────────────

    private static final class FakeEmbeddingProvider implements EmbeddingProviderPort {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile String failOnText = null;

        int callCount() {
            return callCount.get();
        }

        void reset() {
            failOnText = null;
        }

        @Override
        public EmbeddingHealth health() {
            return new EmbeddingHealth(true, MODEL, DIMENSION);
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            callCount.incrementAndGet();
            String target = failOnText;
            if (target != null && texts.stream().anyMatch(target::equals)) {
                throw new RuntimeException("simulated per-text embedding failure");
            }
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
