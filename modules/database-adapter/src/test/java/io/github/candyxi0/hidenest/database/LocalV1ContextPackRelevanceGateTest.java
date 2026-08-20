package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutVectorProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.HideSelection;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.UserConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryDetail;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Relevance gate (maxResults / minScore) behaviour of the context pack vertical against real
 * PostgreSQL/pgvector: empty set when all candidates fall below minScore, top-maxResults capping,
 * boundary acceptance at score == minScore, request validation, and exact replay.
 */
class LocalV1ContextPackRelevanceGateTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA = HexFormat.of()
            .parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneId.of("UTC"));

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static PayloadStore payloadStore;

    private static LocalV1CloseoutVectorProjectionCoordinator projection;
    private static LocalV1ContextPackCoordinator contextPack;
    private static MapEmbeddingProvider bodyEmbedding;
    private static MapEmbeddingProvider queryEmbedding;
    private static TransactionExecutor transactions;
    private static ModelFingerprint fingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(21,
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

        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtimeTx = new JooqRuntimeTransactionAdapter(dsl);
        RuntimeQueryPort runtimeQuery = new JooqRuntimeQueryAdapter(dsl);
        transactions = new SpringTransactionExecutor(tx);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtimeTx, transactions, CLOCK);
        payloadRoot = Files.createTempDirectory("context-pack-gate-payload-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        LocalV1S1WindowCloseCoordinator s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtimeTx, transactions, publisher, payloadStore, CLOCK);
        LocalV1CloseoutWriteCoordinator closeout =
                new LocalV1CloseoutWriteCoordinator(runtimeTx, runtimeQuery, transactions, s1, CLOCK);

        JooqMemoryReadAdapter memoryRead = new JooqMemoryReadAdapter(dsl);
        JooqVectorStoreAdapter vectorStore = new JooqVectorStoreAdapter(dsl);
        fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);

        bodyEmbedding = new MapEmbeddingProvider();
        var closeoutVector =
                new LocalV1VectorCoordinator(bodyEmbedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        projection = new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, closeoutVector, memoryRead, vectorStore, fingerprint);

        queryEmbedding = new MapEmbeddingProvider();
        var contextPackVector =
                new LocalV1VectorCoordinator(queryEmbedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        var s2b = new LocalV1S2BQueryCoordinator(
                memoryRead, evidence, payloadStore, new JooqDeletionFenceAdapter(dsl));
        contextPack = new LocalV1ContextPackCoordinator(
                contextPackVector, s2b, memoryRead, runtimeTx, runtimeQuery, transactions, CLOCK);
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
                    }
                });
            }
        }
    }

    // ── minScore gate ──────────────────────────────────────────────────────

    @Test
    void allCandidatesBelowMinScoreYieldEmptyNoRelevantResult() {
        // memory vector orthogonal to query vector -> cosine similarity 0.0 < any minScore >= 0.4
        createMemory(basis(300));
        String idempotencyKey = key();
        LocalV1ContextPackResult result = contextPack.create(
                request("q-gate-all-below", basis(301), 3, 0.6d), idempotencyKey);
        assertEquals("NO_RELEVANT_RESULT", result.resultCategory());
        assertTrue(result.memories().isEmpty());
        assertTrue(result.policyRevisionSet().isEmpty());
        // audit: 1 trace + 1 delivery + 0 items + 1 receipt
        assertEquals(1, countRows("retrieval_trace", result.requestId()));
        assertEquals(1, countRows("context_delivery", result.deliveryId()));
        assertEquals(0, countRows("context_pack_delivery_item", result.deliveryId()));
        assertEquals(1, countReceipts(idempotencyKey));
    }

    @Test
    void mixedScoresDeliverOnlyAtOrAboveMinScore() {
        // high: identical direction -> score 1.0; low: orthogonal -> score 0.0
        UUID high = createMemory(basis(310));
        createMemory(basis(311));
        LocalV1ContextPackResult result = contextPack.create(
                request("q-gate-mixed", basis(310), 5, 0.6d), key());
        assertEquals("SUCCEEDED", result.resultCategory());
        assertEquals(1, result.memories().size());
        assertEquals(high, result.memories().get(0).memoryId());
    }

    @Test
    void scoreExactlyAtMinScoreIsAccepted() {
        // identical direction -> cosine similarity exactly 1.0, minScore 1.0 boundary
        UUID memory = createMemory(basis(320));
        LocalV1ContextPackResult result = contextPack.create(
                request("q-gate-boundary", basis(320), 3, 1.0d), key());
        assertEquals("SUCCEEDED", result.resultCategory());
        assertEquals(1, result.memories().size());
        assertEquals(memory, result.memories().get(0).memoryId());
        assertEquals(1.0d, result.memories().get(0).score(), 1e-9);
    }

    // ── maxResults cap ─────────────────────────────────────────────────────

    @Test
    void moreCandidatesThanMaxResultsDeliversOnlyTop() {
        // three memories all identical direction to query -> all score 1.0, cap at 2
        createMemory(basis(330));
        createMemory(basis(330));
        createMemory(basis(330));
        LocalV1ContextPackResult result = contextPack.create(
                request("q-gate-cap", basis(330), 2, 0.4d), key());
        assertEquals("SUCCEEDED", result.resultCategory());
        assertEquals(2, result.memories().size());
    }

    // ── request validation ─────────────────────────────────────────────────

    @Test
    void maxResultsOutOfRangeRejected() {
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-mr0", basis(340), 0, 0.6d), key()));
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-mr6", basis(340), 6, 0.6d), key()));
    }

    @Test
    void minScoreOutOfRangeOrNonFiniteRejected() {
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-ms-low", basis(341), 3, 0.3d), key()));
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-ms-high", basis(341), 3, 1.1d), key()));
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-ms-nan", basis(341), 3, Double.NaN), key()));
        assertSchemaInvalid(() -> contextPack.create(
                request("q-gate-ms-inf", basis(341), 3, Double.POSITIVE_INFINITY), key()));
    }

    // ── replay exactness ───────────────────────────────────────────────────

    @Test
    void sameRequestReplayIsExactWithPolicyFields() {
        createMemory(basis(350));
        LocalV1ContextPackRequest req = request("q-gate-replay", basis(350), 2, 0.7d);
        LocalV1ContextPackResult first = contextPack.create(req, "cp-gate-replay-1");
        LocalV1ContextPackResult replay = contextPack.create(req, "cp-gate-replay-1");
        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(first.issuedAt(), replay.issuedAt());
        assertEquals(first.expiresAt(), replay.expiresAt());
        assertEquals(first.memories(), replay.memories());
    }

    // ── H1-03 1. score just below the boundary is rejected ─────────────────

    @Test
    void scoreJustBelowMinScoreIsRejected() {
        // near-parallel memory -> cosine just below 1.0, so with minScore 1.0 the boundary rejects it
        createMemory(nearParallelTo(400));
        LocalV1ContextPackResult result = contextPack.create(
                request("q-gate-nextdown", basis(400), 3, 1.0d), key());
        assertEquals("NO_RELEVANT_RESULT", result.resultCategory());
        assertTrue(result.memories().isEmpty());
    }

    // ── H1-03 2. below-threshold candidates do not reach S2B ───────────────

    @Test
    void belowThresholdCandidateDoesNotInvokeS2b() {
        CountingS2b spy = new CountingS2b();
        var spyCoordinator = contextPackWith(spy);
        UUID high = createMemory(basis(410));
        createMemory(orthogonalTo(410)); // score 0.0 < minScore 0.6

        LocalV1ContextPackResult result = spyCoordinator.create(
                request("q-gate-nos2b", basis(410), 1, 0.6d), key());
        assertEquals("SUCCEEDED", result.resultCategory());
        assertEquals(1, result.memories().size());
        assertEquals(high, result.memories().get(0).memoryId());
        // only the single above-threshold candidate is expanded, never the below-threshold one
        assertEquals(1, spy.detailCalls(), "below-threshold candidate must not be S2B-expanded");
        assertEquals(1, spy.evidenceCalls(), "below-threshold candidate must not fetch evidence");
    }

    // ── H1-03 3. visibility failure leaves room without low-score fill ─────

    @Test
    void visibilityFailureAllowsFewerThanMaxResultsWithoutLowScoreFill() {
        UUID visible = createMemory(basis(420));
        UUID fenced = createMemory(basis(420)); // same high score but visibility-fenced
        createMemory(orthogonalTo(420)); // score 0.0, must not fill the vacated slot

        var fencedCoordinator = contextPackWith(fencedS2b(fenced));
        LocalV1ContextPackResult result = fencedCoordinator.create(
                request("q-gate-visibility", basis(420), 2, 0.4d), key());
        assertEquals("SUCCEEDED", result.resultCategory());
        assertEquals(1, result.memories().size(), "must not fill the fenced slot with a low-score candidate");
        assertEquals(visible, result.memories().get(0).memoryId());
    }

    // ── H1-03 4. same key with a different policy value conflicts ──────────

    @Test
    void sameKeyWithDifferentPolicyIsRejectedAndAddsNoFacts() {
        createMemory(basis(430));
        String k = key();
        LocalV1ContextPackResult first = contextPack.create(
                request("q-gate-conflict", basis(430), 2, 0.6d), k);
        long tracesAfter = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesAfter = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsAfter = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsAfter = count("SELECT count(*) FROM runtime.idempotency_receipt");

        // same key, only maxResults differs
        assertIdempotencyReused(() -> contextPack.create(
                request("q-gate-conflict", basis(430), 3, 0.6d), k));
        // same key, only minScore differs
        assertIdempotencyReused(() -> contextPack.create(
                request("q-gate-conflict", basis(430), 2, 0.7d), k));

        assertEquals(tracesAfter, count("SELECT count(*) FROM runtime.retrieval_trace"), "no new trace on conflict");
        assertEquals(deliveriesAfter, count("SELECT count(*) FROM runtime.context_delivery"), "no new delivery on conflict");
        assertEquals(itemsAfter, count("SELECT count(*) FROM runtime.context_pack_delivery_item"), "no new item on conflict");
        assertEquals(receiptsAfter, count("SELECT count(*) FROM runtime.idempotency_receipt"), "no new receipt on conflict");
    }

    // ── H1-03 5. replay item tampering fails closed ────────────────────────

    @Test
    void replayRejectsTamperedItemScoreBelowThreshold() {
        createMemory(basis(440));
        LocalV1ContextPackRequest req = request("q-gate-tamper-score", basis(440), 2, 0.6d);
        String k = key();
        LocalV1ContextPackResult first = contextPack.create(req, k);
        assertEquals("SUCCEEDED", first.resultCategory());

        dsl.execute(
                "UPDATE runtime.context_pack_delivery_item SET score=0.1 WHERE delivery_id=?::uuid",
                first.deliveryId());
        assertReplayInternalFailure(() -> contextPack.create(req, k));
    }

    @Test
    void replayRejectsItemCountOverMax() {
        UUID memory = createMemory(basis(450));
        LocalV1ContextPackRequest req = request("q-gate-tamper-count", basis(450), 1, 0.4d);
        String k = key();
        LocalV1ContextPackResult first = contextPack.create(req, k);
        assertEquals("SUCCEEDED", first.resultCategory());

        dsl.execute(
                "INSERT INTO runtime.context_pack_delivery_item(delivery_id,ordinal,memory_revision_id,"
                        + "policy_revision_no,score) VALUES (?::uuid, 99, ?::uuid, 1, 0.5)",
                first.deliveryId(), memoryRevisionId(memory));
        assertReplayInternalFailure(() -> contextPack.create(req, k));
    }

    // ── H1-03 6. Task42 legacy receipt must not masquerade as a v2 replay ──

    @Test
    void task42LegacyReceiptCannotMasqueradeAsNewDefaultReplay() {
        createMemory(basis(460));
        LocalV1ContextPackRequest req = request("q-gate-legacy", basis(460), 3, 0.6d);
        String k = key();
        // simulate a Task42-era receipt whose request hash carries no v2 policy version
        dsl.execute(
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "resource_kind,resource_id,response_manifest,created_at,committed_at) "
                        + "VALUES (?, 'LOCAL_V1_CONTEXT_PACK', decode(repeat('00',32),'hex'), 'COMMITTED', "
                        + "'CONTEXT_DELIVERY', ?::uuid, '{}', clock_timestamp(), clock_timestamp())",
                k, UUID.randomUUID());
        // the new v2 default request must be rejected, not replayed from the legacy receipt
        assertIdempotencyReused(() -> contextPack.create(req, k));
    }

    // ── H1-03 7. manifest content binding rejected for both policies ───────

    @Test
    void replayRejectsManifestContentBindingForDefaultAndCustomPolicies() {
        // default policy: in-range score change still breaks the manifest binding
        assertManifestContentBindingRejected(470, 3, 0.6d, 0.7d);
        // custom policy: same binding guard holds
        assertManifestContentBindingRejected(471, 5, 0.4d, 0.5d);
    }

    // ── H1-03 8. same-value replay proves zero deltas across all facts ─────

    @Test
    void sameValueReplayProvesZeroDeltasAcrossAllAuditFacts() {
        createMemory(basis(480));
        queryEmbedding.resetCalls();
        LocalV1ContextPackRequest req = request("q-gate-exact-replay", basis(480), 2, 0.6d);
        String k = key();
        LocalV1ContextPackResult first = contextPack.create(req, k);
        int callsAfterFirst = queryEmbedding.calls();
        long tracesAfterFirst = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesAfterFirst = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsAfterFirst = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsAfterFirst = count("SELECT count(*) FROM runtime.idempotency_receipt");

        LocalV1ContextPackResult replay = contextPack.create(req, k);
        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(first.memories(), replay.memories());

        assertEquals(callsAfterFirst, queryEmbedding.calls(), "replay must not re-embed");
        assertEquals(tracesAfterFirst, count("SELECT count(*) FROM runtime.retrieval_trace"), "replay must not add trace");
        assertEquals(deliveriesAfterFirst, count("SELECT count(*) FROM runtime.context_delivery"), "replay must not add delivery");
        assertEquals(itemsAfterFirst, count("SELECT count(*) FROM runtime.context_pack_delivery_item"), "replay must not add item");
        assertEquals(receiptsAfterFirst, count("SELECT count(*) FROM runtime.idempotency_receipt"), "replay must not add receipt");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void assertSchemaInvalid(ThrowingCall call) {
        LocalV1ContextPackException ex =
                assertThrows(LocalV1ContextPackException.class, call::run);
        assertEquals(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID, ex.code());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run();
    }

    private static long countRows(String table, UUID id) {
        String column = switch (table) {
            case "retrieval_trace" -> "request_id";
            case "context_delivery" -> "delivery_id";
            case "context_pack_delivery_item" -> "delivery_id";
            default -> throw new IllegalArgumentException(table);
        };
        return ((Number) dsl.fetchValue(
                "select count(*) from runtime." + table + " where " + column + " = ?", id))
                .longValue();
    }

    private static long countReceipts(String idempotencyKey) {
        return ((Number) dsl.fetchValue(
                "select count(*) from runtime.idempotency_receipt where idempotency_key = ?", idempotencyKey))
                .longValue();
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static UUID memoryRevisionId(UUID memoryId) {
        return dsl.fetchOne(
                        "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId)
                .get("current_revision_id", UUID.class);
    }

    private static void assertIdempotencyReused(ThrowingCall call) {
        LocalV1ContextPackException ex = assertThrows(LocalV1ContextPackException.class, call::run);
        assertEquals(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REUSED, ex.code());
    }

    private static void assertReplayInternalFailure(ThrowingCall call) {
        LocalV1ContextPackException ex = assertThrows(LocalV1ContextPackException.class, call::run);
        assertEquals(LocalV1ContextPackException.Code.INTERNAL_FAILURE, ex.code());
    }

    /**
     * The delivery identity columns are DB-immutable (HDM006), so a manifest-binding attack is
     * proven through a writable vector that feeds the manifest: change a delivered item's score to
     * a different value still at/above minScore. The coordinator must recompute a different manifest
     * hash and fail closed instead of replaying the stored body.
     */
    private void assertManifestContentBindingRejected(
            int basisIndex, int maxResults, double minScore, double tamperedScore) {
        createMemory(basis(basisIndex));
        LocalV1ContextPackRequest req =
                request("q-gate-hash-" + basisIndex, basis(basisIndex), maxResults, minScore);
        String k = key();
        LocalV1ContextPackResult first = contextPack.create(req, k);
        assertEquals("SUCCEEDED", first.resultCategory());

        dsl.execute(
                "UPDATE runtime.context_pack_delivery_item SET score=? WHERE delivery_id=?::uuid",
                tamperedScore, first.deliveryId());
        assertReplayInternalFailure(() -> contextPack.create(req, k));
    }

    private static LocalV1ContextPackRequest request(
            String queryText, double[] queryVector, int maxResults, double minScore) {
        queryEmbedding.put(queryText, queryVector);
        return new LocalV1ContextPackRequest(
                UUID.randomUUID(), UUID.randomUUID(), "RECALL", queryText, maxResults, minScore);
    }

    private static String key() {
        return "cp-gate-" + UUID.randomUUID();
    }

    private static LocalV1ContextPackCoordinator contextPackWith(LocalV1S2BQueryCoordinator s2bOverride) {
        var vectorCoordinator = new LocalV1VectorCoordinator(
                queryEmbedding, new JooqVectorStoreAdapter(dsl),
                new JooqMemoryGovernanceAdapter(dsl), transactions, fingerprint, CLOCK);
        return new LocalV1ContextPackCoordinator(
                vectorCoordinator, s2bOverride, new JooqMemoryReadAdapter(dsl),
                new JooqRuntimeTransactionAdapter(dsl), new JooqRuntimeQueryAdapter(dsl), transactions, CLOCK);
    }

    /** L2-normalized vector with the first {@code n} unit-axis components removed, i.e. orthogonal to {@code basis(index)}. */
    private static double[] orthogonalTo(int index) {
        double[] vector = new double[DIMENSION];
        vector[index] = 0.0;
        vector[(index + 1) % DIMENSION] = 1.0;
        return vector;
    }

    /** Nearly-but-not-exactly parallel unit vector to {@code basis(index)} so the cosine is just below 1.0. */
    private static double[] nearParallelTo(int index) {
        double[] vector = new double[DIMENSION];
        double secondary = 0.1d;
        vector[index] = 1.0d;
        vector[(index + 1) % DIMENSION] = secondary;
        double norm = Math.sqrt(1.0d + secondary * secondary);
        vector[index] /= norm;
        vector[(index + 1) % DIMENSION] /= norm;
        return vector;
    }

    /** Spy S2B that counts detail/evidence expansions to prove below-threshold candidates are not expanded. */
    private static final class CountingS2b extends LocalV1S2BQueryCoordinator {
        private final AtomicInteger detailCalls = new AtomicInteger();
        private final AtomicInteger evidenceCalls = new AtomicInteger();

        CountingS2b() {
            super(
                    new JooqMemoryReadAdapter(dsl),
                    new JooqEvidenceReferenceAdapter(dsl),
                    payloadStore,
                    new JooqDeletionFenceAdapter(dsl));
        }

        @Override
        public LocalV1S2BMemoryDetail getMemoryDetail(UUID memoryId) {
            detailCalls.incrementAndGet();
            return super.getMemoryDetail(memoryId);
        }

        @Override
        public LocalV1S2BEvidenceResult getFullEvidence(UUID memoryId) {
            evidenceCalls.incrementAndGet();
            return super.getFullEvidence(memoryId);
        }

        int detailCalls() {
            return detailCalls.get();
        }

        int evidenceCalls() {
            return evidenceCalls.get();
        }
    }

    /** S2B that reports a deletion fence for one specific memory so its candidate fails visibility. */
    private static LocalV1S2BQueryCoordinator fencedS2b(UUID fencedMemoryId) {
        DeletionFencePort fenceStub = new DeletionFencePort() {
            @Override
            public void insertFences(List<io.github.candyxi0.hidenest.memory.port.DeletionFencePort.FenceDraft> drafts) {
                throw new UnsupportedOperationException("fence stub");
            }

            @Override
            public boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef) {
                return "MEMORY".equals(targetKind) && fencedMemoryId.equals(targetId);
            }

            @Override
            public List<DeletionFence> findByClosureId(UUID closureId) {
                return List.of();
            }
        };
        return new LocalV1S2BQueryCoordinator(
                new JooqMemoryReadAdapter(dsl), new JooqEvidenceReferenceAdapter(dsl), payloadStore, fenceStub);
    }

    private UUID createMemory(double[] vector) {
        String bodyText = "合成记忆正文-" + UUID.randomUUID();
        bodyEmbedding.put(bodyText, vector);
        UUID submissionId = UUID.randomUUID();
        projection.submit(buildSubmission(submissionId, bodyText));
        return deterministicId("memory:", submissionId);
    }

    private static LocalV1CloseoutSubmission buildSubmission(UUID submissionId, String bodyText) {
        UUID msg1Unit = deterministicId("msg1:", submissionId);
        UUID msg2Unit = deterministicId("msg2:", submissionId);
        UUID actor1 = deterministicId("a1:", submissionId);
        UUID actor2 = deterministicId("a2:", submissionId);
        String msg1 = "协作者：只保存必要证据";
        String msg2 = "小林：已确认本版";
        List<EvidenceMessage> messages = List.of(
                new EvidenceMessage(
                        msg1Unit, actor1, 1L, "unit-1",
                        OffsetDateTime.parse("2026-08-12T09:30:00Z"), msg1, sha256Hex(msg1)),
                new EvidenceMessage(
                        msg2Unit, actor2, 2L, "unit-2",
                        OffsetDateTime.parse("2026-08-12T09:30:01Z"), msg2, sha256Hex(msg2)));

        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyHash = sha256Hex(bodyText);
        UUID anchor1 = deterministicId("anchor1:", submissionId);
        List<AnchorUnit> anchorUnits = new ArrayList<>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            EvidenceMessage message = messages.get(i);
            anchorUnits.add(new AnchorUnit(
                    message.sourceUnitId(),
                    0L,
                    (long) message.bodyText().codePointCount(0, message.bodyText().length()),
                    (long) (i + 1)));
        }
        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, "", messages));
        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(
                messages.get(messages.size() - 1).actorId(), "INTERPRETATION", bodyText, bodyHash);
        List<SourceAnchor> anchors = List.of(new SourceAnchor(anchor1, anchorUnits));
        LocalV1CloseoutSubmission provisional = new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection,
                new UserConfirmation("CONFIRM", "", confirmationUnit), anchors, manifest, "");
        String reviewManifestHash = LocalV1CloseoutCanonicalizer.reviewManifestHash(provisional);
        String proof = LocalV1CloseoutCanonicalizer.confirmationProof(
                threadId, confirmationUnit, reviewManifestHash, submissionId);
        return new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection,
                new UserConfirmation("CONFIRM", reviewManifestHash, confirmationUnit),
                anchors, manifest, proof);
    }

    private static double[] basis(int index) {
        double[] vector = new double[DIMENSION];
        vector[index] = 1.0;
        return vector;
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    /** Text-keyed embedding provider with a call counter (used to prove replay does not re-embed). */
    private static final class MapEmbeddingProvider implements EmbeddingProviderPort {
        private final Map<String, double[]> vectors = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        void put(String text, double[] vector) {
            vectors.put(text, vector.clone());
        }

        void resetCalls() {
            calls.set(0);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public EmbeddingHealth health() {
            return new EmbeddingHealth(true, MODEL, DIMENSION);
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            calls.incrementAndGet();
            List<double[]> out = new ArrayList<>();
            for (String text : texts) {
                double[] vector = vectors.get(text);
                if (vector == null) {
                    throw new RuntimeException("no vector for text");
                }
                out.add(vector);
            }
            return new EmbeddingResult(MODEL, DIMENSION, out);
        }
    }
}
