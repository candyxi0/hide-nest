package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutVectorProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.HideSelection;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.UserConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackMemory;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackResult;
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
import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryReadFilter;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
 * Local V1 context pack retrieval vertical: exact cosine via pgvector, S2B visibility re-check,
 * audit (trace + delivery + receipt) and idempotent replay against real PostgreSQL/pgvector.
 */
class LocalV1ContextPackRetrievalTest {

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
    private static String password;

    private static MemoryGovernancePort governance;
    private static RuntimeTransactionPort runtimeTx;
    private static RuntimeQueryPort runtimeQuery;
    private static TransactionExecutor transactions;
    private static LocalV1CloseoutVectorProjectionCoordinator projection;
    private static LocalV1ContextPackCoordinator contextPack;
    private static MapEmbeddingProvider bodyEmbedding;
    private static MapEmbeddingProvider queryEmbedding;
    private static LocalV1S2BQueryCoordinator s2b;
    private static JooqMemoryReadAdapter memoryRead;
    private static EvidenceReferencePort evidence;
    private static PayloadStore payloadStore;
    private static DeletionFencePort deletionFence;
    private static JooqVectorStoreAdapter vectorStore;
    private static ModelFingerprint fingerprint;

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
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
        assertEquals(20,
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
        runtimeTx = new JooqRuntimeTransactionAdapter(dsl);
        runtimeQuery = new JooqRuntimeQueryAdapter(dsl);
        transactions = new SpringTransactionExecutor(tx);
        evidence = new JooqEvidenceReferenceAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtimeTx, transactions, CLOCK);
        payloadRoot = Files.createTempDirectory("context-pack-payload-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        LocalV1S1WindowCloseCoordinator s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtimeTx, transactions, publisher, payloadStore, CLOCK);
        LocalV1CloseoutWriteCoordinator closeout =
                new LocalV1CloseoutWriteCoordinator(runtimeTx, runtimeQuery, transactions, s1, CLOCK);

        memoryRead = new JooqMemoryReadAdapter(dsl);
        vectorStore = new JooqVectorStoreAdapter(dsl);
        fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);
        deletionFence = new JooqDeletionFenceAdapter(dsl);

        bodyEmbedding = new MapEmbeddingProvider();
        var closeoutVector =
                new LocalV1VectorCoordinator(bodyEmbedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        projection = new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, closeoutVector, memoryRead, vectorStore, fingerprint);

        queryEmbedding = new MapEmbeddingProvider();
        var contextPackVector =
                new LocalV1VectorCoordinator(queryEmbedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        s2b = new LocalV1S2BQueryCoordinator(
                memoryRead, evidence, payloadStore, deletionFence);
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

    // ── 1. A/B exact cosine: A first with higher score ─────────────────────

    @Test
    void aRanksFirstWithHigherScore() {
        UUID a = createMemory(basis(100));
        UUID b = createMemory(basis(101));
        LocalV1ContextPackResult result = contextPack.create(requestFor("q-1", basis(100)), key());

        assertEquals("SUCCEEDED", result.resultCategory());
        LocalV1ContextPackMemory first = result.memories().get(0);
        assertEquals(a, first.memoryId());
        assertEquals(1.0, first.score(), 1e-6);
        LocalV1ContextPackMemory bItem = result.memories().stream()
                .filter(m -> m.memoryId().equals(b))
                .findFirst()
                .orElseThrow();
        assertEquals(0.0, bItem.score(), 1e-6);
        assertTrue(first.score() > bItem.score(), "A must score strictly higher than B");
    }

    // ── 2. response matches S2B detail and current vector exactly ──────────

    @Test
    void responseMatchesS2bAndCurrentVector() {
        UUID a = createMemory(basis(110));
        LocalV1ContextPackResult result = contextPack.create(requestFor("q-2", basis(110)), key());
        LocalV1ContextPackMemory first = result.memories().get(0);

        var detail = s2b.getMemoryDetail(a);
        assertEquals(a, first.memoryId());
        assertEquals(detail.currentRevisionId(), first.memoryRevisionId());
        assertEquals(detail.revisionNo(), first.revisionNo());
        assertEquals(detail.currentPolicyRevisionNo(), first.policyRevisionNo());
        assertEquals("INTERPRETATION", first.memoryType());
        assertEquals("Interpretation", detail.memoryType());
        assertEquals(detail.bodyText(), first.bodyText());
        assertEquals(1L, count(
                "SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?",
                detail.currentRevisionId()));
    }

    // ── 3. ARCHIVED delivers nothing ───────────────────────────────────────

    @Test
    void archivedDeliversNothing() {
        UUID archived = createMemory(basis(120));
        archive(archived);
        LocalV1ContextPackResult r1 = contextPack.create(requestFor("q-arch", basis(120)), key());
        assertTrue(r1.memories().stream().noneMatch(m -> m.memoryId().equals(archived)));
    }

    // ── 4. replay no re-embed; different value conflicts ───────────────────

    @Test
    void sameValueReplayDoesNotReembedAndDifferentValueConflicts() {
        UUID a = createMemory(basis(130));
        queryEmbedding.resetCalls();
        LocalV1ContextPackRequest req = requestFor("q-replay", basis(130));
        LocalV1ContextPackResult first = contextPack.create(req, "cp-replay-fixed");

        assertEquals(1, queryEmbedding.calls());
        LocalV1ContextPackResult replay = contextPack.create(req, "cp-replay-fixed");
        assertEquals(1, queryEmbedding.calls(), "replay must not re-embed");

        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(first.issuedAt(), replay.issuedAt());
        assertEquals(first.expiresAt(), replay.expiresAt());
        assertEquals(first.memories(), replay.memories());
        assertEquals(first.policyRevisionSet(), replay.policyRevisionSet());

        LocalV1ContextPackRequest different = new LocalV1ContextPackRequest(
                req.threadId(), req.turnId(), req.purpose(), "另一个查询");
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> contextPack.create(different, "cp-replay-fixed"));
        assertEquals(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REUSED, ex.code());
    }

    // ── 5. policy set, trace, delivery manifest three-way exact ────────────

    @Test
    void policySetTraceAndDeliveryManifestAreExact() {
        UUID a = createMemory(basis(140));
        LocalV1ContextPackResult result = contextPack.create(requestFor("q-manifest", basis(140)), key());
        UUID deliveryId = result.deliveryId();

        ContextDelivery delivery = runtimeQuery.findContextDeliveryById(deliveryId);
        var trace = dsl.fetchOne(
                "SELECT policy_revision_set_hash, considered_ids, delivered_ids, result_category "
                        + "FROM runtime.retrieval_trace WHERE request_id=?::uuid",
                result.requestId());

        List<String> policySet = result.policyRevisionSet();
        assertTrue(policySet.contains("MEMORY:" + a + ":1"), "policy set must contain delivered A");
        assertTrue(policySet.equals(policySet.stream().sorted().toList()), "policy set must be lexicographically sorted");

        byte[] policySetHash = canonicalHash(policySet);
        assertTrue(Arrays.equals(policySetHash, delivery.policyRevisionSetHash()));
        assertTrue(Arrays.equals(policySetHash, trace.get("policy_revision_set_hash", byte[].class)));

        List<String> manifestFields = new ArrayList<>();
        manifestFields.add(result.requestId().toString());
        manifestFields.add(result.threadId().toString());
        manifestFields.add(result.turnId().toString());
        manifestFields.add(result.purpose());
        manifestFields.add(result.resultCategory());
        manifestFields.addAll(policySet);
        for (LocalV1ContextPackMemory m : result.memories()) {
            manifestFields.add(m.memoryId().toString());
            manifestFields.add(m.memoryRevisionId().toString());
            manifestFields.add(Long.toString(m.revisionNo()));
            manifestFields.add(Double.toString(m.score()));
            manifestFields.add(m.evidenceOccurredAt().toString());
        }
        assertTrue(Arrays.equals(canonicalHash(manifestFields), delivery.manifestHash()));

        UUID[] deliveredIds = trace.get("delivered_ids", UUID[].class);
        UUID[] consideredIds = trace.get("considered_ids", UUID[].class);
        assertEquals(result.memories().size(), deliveredIds.length);
        for (int i = 0; i < deliveredIds.length; i++) {
            assertEquals(result.memories().get(i).memoryId(), deliveredIds[i]);
        }
        assertEquals(a, consideredIds[0]);
        assertTrue(Arrays.asList(consideredIds).containsAll(Arrays.asList(deliveredIds)));
        assertEquals("SUCCEEDED", trace.get("result_category", String.class));
    }

    // ── 5b. evidence age correctness and boundary ──────────────────────────

    @Test
    void evidenceAgeUsesLatestOccurredAtAndIsNonNegative() {
        UUID actor = UUID.randomUUID();
        UUID unit1 = UUID.randomUUID();
        UUID unit2 = UUID.randomUUID();
        List<EvidenceMessage> messages = List.of(
                new EvidenceMessage(unit1, actor, 1L, "u1", OffsetDateTime.parse("2026-08-12T09:30:00Z"), "较早", sha256Hex("较早")),
                new EvidenceMessage(unit2, actor, 2L, "u2", OffsetDateTime.parse("2026-08-14T10:00:00Z"), "最晚", sha256Hex("最晚")));
        UUID memoryId = createMemory(basis(230), messages);

        LocalV1ContextPackResult result = contextPack.create(requestFor("q-age-latest", basis(230)), key());
        LocalV1ContextPackMemory delivered = result.memories().stream()
                .filter(m -> m.memoryId().equals(memoryId))
                .findFirst()
                .orElseThrow();

        assertEquals(OffsetDateTime.parse("2026-08-14T10:00:00Z"), delivered.evidenceOccurredAt());
        assertEquals(1, delivered.evidenceAgeDays());
    }

    @Test
    void evidenceAgeBoundaryAt24Hours() {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        // 23:59:59 before issuedAt -> 0 days
        List<EvidenceMessage> justUnder = List.of(new EvidenceMessage(
                unit, actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-15T00:00:01Z"), "body", sha256Hex("body")));
        UUID underId = createMemory(basis(231), justUnder);
        LocalV1ContextPackResult under = contextPack.create(requestFor("q-age-under", basis(231)), key());
        LocalV1ContextPackMemory underMemory = under.memories().stream()
                .filter(m -> m.memoryId().equals(underId))
                .findFirst()
                .orElseThrow();
        assertEquals(0, underMemory.evidenceAgeDays());

        // Exactly 24 hours before issuedAt -> 1 day
        List<EvidenceMessage> exactly = List.of(new EvidenceMessage(
                UUID.randomUUID(), UUID.randomUUID(), 1L, "u2",
                OffsetDateTime.parse("2026-08-15T00:00:00Z"), "body2", sha256Hex("body2")));
        UUID exactId = createMemory(basis(232), exactly);
        LocalV1ContextPackResult exact = contextPack.create(requestFor("q-age-exact", basis(232)), key());
        LocalV1ContextPackMemory exactMemory = exact.memories().stream()
                .filter(m -> m.memoryId().equals(exactId))
                .findFirst()
                .orElseThrow();
        assertEquals(1, exactMemory.evidenceAgeDays());
    }

    @Test
    void futureEvidenceFailsClosed() {
        UUID actor = UUID.randomUUID();
        List<EvidenceMessage> future = List.of(new EvidenceMessage(
                UUID.randomUUID(), actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-16T00:00:01Z"), "body", sha256Hex("body")));
        UUID memoryId = createMemory(basis(233), future);
        try {
            LocalV1ContextPackException ex = assertThrows(
                    LocalV1ContextPackException.class,
                    () -> contextPack.create(requestFor("q-future", basis(233)), key()));
            assertEquals(LocalV1ContextPackException.Code.INTERNAL_FAILURE, ex.code());
        } finally {
            archive(memoryId);
        }
    }

    @Test
    void futureEvidenceByOneMicrosecondFailsClosed() {
        UUID actor = UUID.randomUUID();
        List<EvidenceMessage> future = List.of(new EvidenceMessage(
                UUID.randomUUID(), actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-16T00:00:00.000001Z"), "body", sha256Hex("body")));
        UUID memoryId = createMemory(basis(237), future);
        try {
            LocalV1ContextPackException ex = assertThrows(
                    LocalV1ContextPackException.class,
                    () -> contextPack.create(requestFor("q-future-1us", basis(237)), key()));
            assertEquals(LocalV1ContextPackException.Code.INTERNAL_FAILURE, ex.code());
        } finally {
            archive(memoryId);
        }
    }

    @Test
    void evidenceAgeBoundaryAt24HoursMinusOneMicrosecond() {
        UUID actor = UUID.randomUUID();
        List<EvidenceMessage> messages = List.of(new EvidenceMessage(
                UUID.randomUUID(), actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-15T00:00:00.000001Z"), "body", sha256Hex("body")));
        UUID memoryId = createMemory(basis(238), messages);
        LocalV1ContextPackResult result = contextPack.create(requestFor("q-age-24h-1us", basis(238)), key());
        LocalV1ContextPackMemory memory = result.memories().stream()
                .filter(m -> m.memoryId().equals(memoryId))
                .findFirst()
                .orElseThrow();
        assertEquals(0, memory.evidenceAgeDays());
    }

    @Test
    void replayAcrossNaturalDayUsesOriginalIssuedAtAndAgeDays() {
        UUID actor = UUID.randomUUID();
        List<EvidenceMessage> messages = List.of(new EvidenceMessage(
                UUID.randomUUID(), actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), "body", sha256Hex("body")));
        createMemory(basis(234), messages);
        LocalV1ContextPackRequest request = requestFor("q-replay-age", basis(234));
        String idempotencyKey = "cp-replay-age-fixed";

        // Create just before midnight and replay just after midnight, both inside the 10-minute expiry.
        Clock createClock = Clock.fixed(Instant.parse("2026-08-16T23:59:00Z"), ZoneId.of("UTC"));
        var createCoordinator = contextPackWith(createClock, s2b);
        LocalV1ContextPackResult first = createCoordinator.create(request, idempotencyKey);
        LocalV1ContextPackMemory firstMemory = first.memories().get(0);
        int firstAgeDays = firstMemory.evidenceAgeDays();

        int callsAfterFirstCreate = queryEmbedding.calls();
        long tracesAfterFirstCreate = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesAfterFirstCreate = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsAfterFirstCreate = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsAfterFirstCreate = count("SELECT count(*) FROM runtime.idempotency_receipt");

        Clock replayClock = Clock.fixed(Instant.parse("2026-08-17T00:01:00Z"), ZoneId.of("UTC"));
        var replayCoordinator = contextPackWith(replayClock, s2b);
        LocalV1ContextPackResult replay = replayCoordinator.create(request, idempotencyKey);
        LocalV1ContextPackMemory replayMemory = replay.memories().get(0);

        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(first.issuedAt(), replay.issuedAt());
        assertEquals(first.expiresAt(), replay.expiresAt());
        assertEquals(firstMemory.evidenceOccurredAt(), replayMemory.evidenceOccurredAt());
        assertEquals(firstAgeDays, replayMemory.evidenceAgeDays());

        assertEquals(callsAfterFirstCreate, queryEmbedding.calls(), "replay must not re-embed");
        assertEquals(tracesAfterFirstCreate, count("SELECT count(*) FROM runtime.retrieval_trace"), "replay must not add trace");
        assertEquals(deliveriesAfterFirstCreate, count("SELECT count(*) FROM runtime.context_delivery"), "replay must not add delivery");
        assertEquals(itemsAfterFirstCreate, count("SELECT count(*) FROM runtime.context_pack_delivery_item"), "replay must not add item");
        assertEquals(receiptsAfterFirstCreate, count("SELECT count(*) FROM runtime.idempotency_receipt"), "replay must not add receipt");
    }

    @Test
    void replayRejectsEvidenceTimeTampering() {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        List<EvidenceMessage> messages = List.of(new EvidenceMessage(
                unit, actor, 1L, "u1",
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), "body", sha256Hex("body")));
        createMemory(basis(236), messages);
        LocalV1ContextPackRequest request = requestFor("q-tamper-evidence", basis(236));
        String key = key();
        contextPack.create(request, key);

        dsl.execute(
                "UPDATE evidence.source_unit SET occurred_at=?::timestamptz WHERE source_unit_id=?::uuid",
                OffsetDateTime.parse("2026-08-13T09:30:00Z"), unit);
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> contextPack.create(request, key));
        assertEquals(LocalV1ContextPackException.Code.INTERNAL_FAILURE, ex.code());
    }

    // ── 6. trace/delivery/receipt failure injection full rollback ──────────

    @Test
    void traceDeliveryReceiptFailureInjectionRollsBack() {
        assertAtomicRollback("trace");
        assertAtomicRollback("delivery");
        assertAtomicRollback("receipt");
    }

    private void assertAtomicRollback(String failOn) {
        var failing = new FailingRuntimeTx(dsl, failOn);
        var failingCoordinator = new LocalV1ContextPackCoordinator(
                new LocalV1VectorCoordinator(
                        queryEmbedding, new JooqVectorStoreAdapter(dsl), governance, transactions,
                        new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION), CLOCK),
                s2b, memoryRead, failing, runtimeQuery, transactions, CLOCK);

        long tracesBefore = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesBefore = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsBefore = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsBefore = count("SELECT count(*) FROM runtime.idempotency_receipt");

        assertThrows(
                RuntimeException.class,
                () -> failingCoordinator.create(requestFor("q-fail-" + failOn, basis(150)), key()));
        assertEquals(tracesBefore, count("SELECT count(*) FROM runtime.retrieval_trace"));
        assertEquals(deliveriesBefore, count("SELECT count(*) FROM runtime.context_delivery"));
        assertEquals(itemsBefore, count("SELECT count(*) FROM runtime.context_pack_delivery_item"));
        assertEquals(receiptsBefore, count("SELECT count(*) FROM runtime.idempotency_receipt"));
    }

    // ── 7. embedding failure maps to unavailable, no facts ─────────────────

    @Test
    void embeddingFailureMapsToUnavailableWithoutFacts() {
        long tracesBefore = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesBefore = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsBefore = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsBefore = count("SELECT count(*) FROM runtime.idempotency_receipt");

        queryEmbedding.setFail(true);
        try {
            LocalV1ContextPackException ex = assertThrows(
                    LocalV1ContextPackException.class,
                    () -> contextPack.create(requestFor("q-embed-fail", basis(160)), key()));
            assertEquals(LocalV1ContextPackException.Code.EMBEDDING_UNAVAILABLE, ex.code());
        } finally {
            queryEmbedding.setFail(false);
        }
        assertEquals(tracesBefore, count("SELECT count(*) FROM runtime.retrieval_trace"));
        assertEquals(deliveriesBefore, count("SELECT count(*) FROM runtime.context_delivery"));
        assertEquals(itemsBefore, count("SELECT count(*) FROM runtime.context_pack_delivery_item"));
        assertEquals(receiptsBefore, count("SELECT count(*) FROM runtime.idempotency_receipt"));
    }

    // ── 8. blank / oversized rejected before embedding ─────────────────────

    @Test
    void validationRejectsBlankAndOversizedBeforeEmbedding() {
        queryEmbedding.resetCalls();

        assertThrows(
                LocalV1ContextPackException.class,
                () -> contextPack.create(
                        new LocalV1ContextPackRequest(UUID.randomUUID(), UUID.randomUUID(), "p", "   "),
                        key()));
        String oversizedQuery = "好".repeat(481);
        assertThrows(
                LocalV1ContextPackException.class,
                () -> contextPack.create(
                        new LocalV1ContextPackRequest(UUID.randomUUID(), UUID.randomUUID(), "p", oversizedQuery),
                        key()));
        assertThrows(
                LocalV1ContextPackException.class,
                () -> contextPack.create(
                        new LocalV1ContextPackRequest(UUID.randomUUID(), UUID.randomUUID(), "   ", "查询"),
                        key()));
        assertThrows(
                LocalV1ContextPackException.class,
                () -> contextPack.create(requestFor("q-null-key", basis(170)), null));
        assertThrows(
                LocalV1ContextPackException.class,
                () -> contextPack.create(requestFor("q-null-key-2", basis(170)), "   "));

        assertEquals(0, queryEmbedding.calls(), "validation failures must not embed");
    }

    // ── 9. API role write permissions (R1-01) ──────────────────────────────

    @Test
    void apiRoleCanWriteAuditFactsButNotMutateOrExceedScope() throws Exception {
        UUID traceId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        String hash = "00".repeat(32);
        String key = "role-" + UUID.randomUUID();

        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password);
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE hide_nest_api");

            // allowed write set: trace + delivery + item + receipt
            statement.execute(("INSERT INTO runtime.retrieval_trace(trace_id,request_id,thread_id,turn_id,purpose,"
                    + "result_category,policy_revision_set_hash,considered_ids,delivered_ids,created_at,expires_at) "
                    + "VALUES ('%s','%s','%s','%s','RETRIEVAL','SUCCEEDED',decode('%s','hex'),"
                    + "ARRAY['%s']::uuid[],ARRAY['%s']::uuid[],'2026-01-01T00:00:00Z','2027-01-01T00:00:00Z')")
                    .formatted(traceId, requestId, threadId, turnId, hash, revisionId, revisionId));
            statement.execute(("INSERT INTO runtime.context_delivery(delivery_id,request_id,thread_id,turn_id,purpose,"
                    + "policy_revision_set_hash,manifest_hash,delivered_at,expires_at) "
                    + "VALUES ('%s','%s','%s','%s','RETRIEVAL',decode('%s','hex'),decode('%s','hex'),"
                    + "'2026-01-01T00:00:00Z','2026-01-01T00:10:00Z')")
                    .formatted(deliveryId, requestId, threadId, turnId, hash, hash));
            statement.execute(("INSERT INTO runtime.context_pack_delivery_item(delivery_id,ordinal,"
                    + "memory_revision_id,policy_revision_no,score) VALUES ('%s',0,'%s',1,0.5)")
                    .formatted(deliveryId, revisionId));
            statement.execute(("INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,"
                    + "state,resource_kind,resource_id,response_manifest,created_at,committed_at) "
                    + "VALUES ('%s','LOCAL_V1_CONTEXT_PACK',decode('%s','hex'),'COMMITTED','CONTEXT_DELIVERY','%s',"
                    + "'{}','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')")
                    .formatted(key, hash, deliveryId));

            // UPDATE / DELETE rejected: API has no UPDATE/DELETE on the three audit tables
            assertDenied(() -> statement.execute(
                    "UPDATE runtime.retrieval_trace SET purpose='X' WHERE trace_id='" + traceId + "'"));
            assertDenied(() -> statement.execute(
                    "DELETE FROM runtime.context_delivery WHERE delivery_id='" + deliveryId + "'"));
            assertDenied(() -> statement.execute(
                    "UPDATE runtime.context_pack_delivery_item SET score=0 WHERE delivery_id='" + deliveryId + "'"));

            // out-of-bounds table write rejected: checkpoint is worker-INSERT only
            assertDenied(() -> statement.execute(("INSERT INTO runtime.checkpoint(checkpoint_id,run_kind,run_id,"
                    + "sequence_no,manifest_hash,created_at) VALUES ('%s','TEST','%s',1,decode('%s','hex'),"
                    + "'2026-01-01T00:00:00Z')")
                    .formatted(UUID.randomUUID(), UUID.randomUUID(), hash)));
        }
    }

    // ── 10. replay visibility attacks (R1-02) ──────────────────────────────

    @Test
    void replayRejectsArchiveInvalidatedExpiredPolicyPointer() {
        assertReplayRejected("archived", cp -> archive(cp.memoryId()), LocalV1ContextPackException.Code.CONTEXT_PACK_STALE);
        assertReplayRejected(
                "invalidated",
                cp -> dsl.execute(
                        "UPDATE runtime.context_delivery SET invalidated_at=?::timestamptz, invalidation_reason='REVOKED' "
                                + "WHERE delivery_id=?::uuid",
                        OffsetDateTime.now(CLOCK).plusSeconds(1), cp.deliveryId()),
                LocalV1ContextPackException.Code.CONTEXT_PACK_INVALIDATED);
        assertReplayRejected(
                "expired",
                cp -> {},
                LocalV1ContextPackException.Code.CONTEXT_PACK_EXPIRED,
                Clock.fixed(Instant.parse("2026-08-16T01:00:00Z"), ZoneId.of("UTC")));
        assertReplayRejected(
                "policy",
                cp -> bumpPolicyRevision(cp.memoryId(), 7L),
                LocalV1ContextPackException.Code.CONTEXT_PACK_STALE);
        assertReplayRejected(
                "pointer",
                cp -> reviseMemorySameBody(cp.memoryId()),
                LocalV1ContextPackException.Code.CONTEXT_PACK_STALE);
    }

    @Test
    void replayRejectsFencedDelivery() {
        UUID memoryId = createMemory(basis(200));
        String queryText = "q-fence-replay";
        LocalV1ContextPackRequest request = requestFor(queryText, basis(200));
        String key = key();
        contextPack.create(request, key);

        LocalV1ContextPackCoordinator fenced = contextPackWith(CLOCK, fencedS2b(memoryId));
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> fenced.create(request, key));
        assertEquals(LocalV1ContextPackException.Code.CONTEXT_PACK_STALE, ex.code());
    }

    // ── 11. S2B structure damage not degraded (R1-03) ──────────────────────

    @Test
    void s2bStructureDamageDoesNotDegradeToEmptyResult() {
        UUID memoryId = createMemory(basis(210));
        String queryText = "q-damage";
        LocalV1ContextPackRequest request = requestFor(queryText, basis(210));
        String key = key();

        long tracesBefore = count("SELECT count(*) FROM runtime.retrieval_trace");
        long deliveriesBefore = count("SELECT count(*) FROM runtime.context_delivery");
        long itemsBefore = count("SELECT count(*) FROM runtime.context_pack_delivery_item");
        long receiptsBefore = count("SELECT count(*) FROM runtime.idempotency_receipt");

        LocalV1ContextPackCoordinator damaging = contextPackWith(CLOCK, damagingS2b());
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> damaging.create(request, key));
        assertEquals(LocalV1ContextPackException.Code.INTERNAL_FAILURE, ex.code());

        assertEquals(tracesBefore, count("SELECT count(*) FROM runtime.retrieval_trace"));
        assertEquals(deliveriesBefore, count("SELECT count(*) FROM runtime.context_delivery"));
        assertEquals(itemsBefore, count("SELECT count(*) FROM runtime.context_pack_delivery_item"));
        assertEquals(receiptsBefore, count("SELECT count(*) FROM runtime.idempotency_receipt"));
    }

    // ── 12. commit race classification (R1-04) ─────────────────────────────

    @Test
    void sameKeyDifferentValueCommitRaceYieldsOneSuccessOneConflict() throws Exception {
        UUID a = createMemory(basis(220));
        createMemory(basis(221));
        queryEmbedding.put("q-race-a", basis(220));
        queryEmbedding.put("q-race-b", basis(221));

        BlockingEmbeddingProvider blocking = new BlockingEmbeddingProvider(queryEmbedding, 2);
        var blockingVector = new LocalV1VectorCoordinator(
                blocking, vectorStore, governance, transactions, fingerprint, CLOCK);
        var blockingCoordinator = new LocalV1ContextPackCoordinator(
                blockingVector, s2b, memoryRead, runtimeTx, runtimeQuery, transactions, CLOCK);

        UUID threadId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        String key = "cp-race-" + UUID.randomUUID();
        LocalV1ContextPackRequest reqA = new LocalV1ContextPackRequest(threadId, turnId, "RECALL", "q-race-a");
        LocalV1ContextPackRequest reqB = new LocalV1ContextPackRequest(threadId, turnId, "RECALL", "q-race-b");

        AtomicReference<LocalV1ContextPackResult> success = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();
        Thread t1 = new Thread(() -> {
            try {
                success.set(blockingCoordinator.create(reqA, key));
            } catch (Throwable e) {
                errorA.set(e);
            }
        });
        Thread t2 = new Thread(() -> {
            try {
                success.set(blockingCoordinator.create(reqB, key));
            } catch (Throwable e) {
                errorB.set(e);
            }
        });
        t1.start();
        t2.start();
        assertTrue(blocking.entered.await(10, TimeUnit.SECONDS), "both threads must enter the search");
        blocking.release.countDown();
        t1.join(15000);
        t2.join(15000);

        // exactly one success, one conflict; audit facts = exactly one group
        assertTrue(success.get() != null, "one thread must commit; errors A=" + errorA.get() + " B=" + errorB.get());
        LocalV1ContextPackException conflict =
                errorA.get() instanceof LocalV1ContextPackException cpa ? cpa
                        : errorB.get() instanceof LocalV1ContextPackException cpb ? cpb : null;
        assertTrue(conflict != null, "the other thread must observe a conflict; A=" + errorA.get() + " B=" + errorB.get());
        assertEquals(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REUSED, conflict.code());
        assertEquals(1L, count("SELECT count(*) FROM runtime.context_delivery WHERE request_id=?", success.get().requestId()));
        assertEquals(1L, count("SELECT count(*) FROM runtime.idempotency_receipt WHERE idempotency_key=?", key));
        assertEquals(1L, count("SELECT count(*) FROM runtime.retrieval_trace WHERE request_id=?", success.get().requestId()));
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String key() {
        return "cp-" + UUID.randomUUID();
    }

    private static LocalV1ContextPackRequest requestFor(String queryText, double[] queryVector) {
        queryEmbedding.put(queryText, queryVector);
        return new LocalV1ContextPackRequest(UUID.randomUUID(), UUID.randomUUID(), "RECALL", queryText);
    }

    private void assertReplayRejected(String label, Consumer<Pack> attack, LocalV1ContextPackException.Code expected) {
        assertReplayRejected(label, attack, expected, CLOCK);
    }

    private void assertReplayRejected(
            String label, Consumer<Pack> attack, LocalV1ContextPackException.Code expected, Clock replayClock) {
        int basisIndex = 300 + BASIS.getAndIncrement();
        UUID memoryId = createMemory(basis(basisIndex));
        LocalV1ContextPackRequest request = requestFor("q-" + label, basis(basisIndex));
        String key = key();
        LocalV1ContextPackResult first = contextPack.create(request, key);
        assertEquals("SUCCEEDED", first.resultCategory(), label + " first create must deliver");
        attack.accept(new Pack(memoryId, first.deliveryId()));
        LocalV1ContextPackCoordinator replayCoordinator = replayClock == CLOCK ? contextPack : contextPackWith(replayClock, s2b);
        LocalV1ContextPackException ex = assertThrows(
                LocalV1ContextPackException.class, () -> replayCoordinator.create(request, key));
        assertEquals(expected, ex.code(), label + " replay must be rejected with " + expected);
    }

    private LocalV1ContextPackCoordinator contextPackWith(Clock clock, LocalV1S2BQueryCoordinator s2bOverride) {
        var vectorCoordinator =
                new LocalV1VectorCoordinator(queryEmbedding, vectorStore, governance, transactions, fingerprint, clock);
        return new LocalV1ContextPackCoordinator(
                vectorCoordinator, s2bOverride, memoryRead, runtimeTx, runtimeQuery, transactions, clock);
    }

    private LocalV1S2BQueryCoordinator fencedS2b(UUID fencedMemoryId) {
        DeletionFencePort fenceStub = new DeletionFencePort() {
            @Override
            public void insertFences(List<FenceDraft> drafts) {
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
        return new LocalV1S2BQueryCoordinator(memoryRead, evidence, payloadStore, fenceStub);
    }

    private LocalV1S2BQueryCoordinator damagingS2b() {
        MemoryReadPort damaging = new MemoryReadPort() {
            @Override
            public List<MemoryRecord> listCurrentMemoryRecords(MemoryReadFilter filter) {
                return memoryRead.listCurrentMemoryRecords(filter);
            }

            @Override
            public MemoryRecord findMemoryRecordById(UUID memoryId) {
                return memoryRead.findMemoryRecordById(memoryId);
            }

            @Override
            public MemoryRevision findCurrentRevisionByMemoryId(UUID memoryId) {
                return memoryRead.findCurrentRevisionByMemoryId(memoryId);
            }

            @Override
            public MemoryRevision findMemoryRevisionById(UUID memoryRevisionId) {
                return memoryRead.findMemoryRevisionById(memoryRevisionId);
            }

            @Override
            public List<MemoryRelation> findRelationsByFromRevisionId(UUID revisionId) {
                return Arrays.asList((MemoryRelation) null);
            }

            @Override
            public ActorRef findActorRefById(UUID actorId) {
                return memoryRead.findActorRefById(actorId);
            }
        };
        return new LocalV1S2BQueryCoordinator(damaging, evidence, payloadStore, deletionFence);
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable executable) {
        SQLException ex = assertThrows(SQLException.class, executable);
        assertEquals("42501", ex.getSQLState());
    }

    private static UUID perspectiveActorId(UUID memoryId) {
        return dsl.fetchOne(
                        "SELECT perspective_actor_id FROM memory.memory_revision WHERE memory_revision_id="
                                + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?)",
                        memoryId)
                .get("perspective_actor_id", UUID.class);
    }

    private static void bumpPolicyRevision(UUID memoryId, long newPolicyRevision) {
        UUID policyId = dsl.fetchOne("SELECT policy_id FROM memory.memory_record WHERE memory_id=?", memoryId)
                .get("policy_id", UUID.class);
        UUID actorId = perspectiveActorId(memoryId);
        UUID decisionId = UUID.randomUUID();
        UUID policyChangeId = UUID.randomUUID();
        UUID memoryPolicyChangeId = UUID.randomUUID();
        String manifestHash = "00".repeat(32);

        dsl.execute(("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,"
                        + "target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                        + "('%s','USER_ISOLATE','%s','USER','MEMORY','%s',1,'cp-policy','%s',clock_timestamp())")
                .formatted(decisionId, actorId, memoryId, "cp-policy-" + decisionId));
        dsl.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES ('%s','memory.policy-changed.v1','ACCESS_POLICY','%s',%d,'%s',clock_timestamp())")
                .formatted(policyChangeId, policyId, newPolicyRevision, decisionId));
        String policyManifest = "{\"aggregateId\":\"" + policyId + "\",\"aggregateRevision\":" + newPolicyRevision
                + ",\"policyRevision\":0,\"purpose\":\"CONTEXT_PACK_TEST\",\"manifestHash\":\"" + manifestHash + "\"}";
        dsl.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,created_at) VALUES ('%s','cp-policy-outbox-%s','GOVERNED',"
                        + "'memory.policy-changed.v1','ACCESS_POLICY','%s',%d,'pink.event.v1','CONTEXT_PACK_TEST',0,decode('%s','hex'),"
                        + "'%s'::jsonb,'%s','READY',clock_timestamp(),clock_timestamp())")
                .formatted(UUID.randomUUID(), policyChangeId, policyId, newPolicyRevision, manifestHash, policyManifest, policyChangeId));
        dsl.execute(("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,maintenance_allowed,"
                        + "export_allowed,external_provider_allowed,isolated,created_by_decision_id,created_at) "
                        + "SELECT policy_id,%d,companion_allowed,maintenance_allowed,export_allowed,external_provider_allowed,"
                        + "isolated,'%s',clock_timestamp() FROM memory.access_policy_revision WHERE policy_id='%s' AND revision_no=1")
                .formatted(newPolicyRevision, decisionId, policyId));
        dsl.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES ('%s','memory.policy-changed.v1','MEMORY','%s',1,'%s',clock_timestamp())")
                .formatted(memoryPolicyChangeId, memoryId, decisionId));
        String memoryManifest = "{\"aggregateId\":\"" + memoryId + "\",\"aggregateRevision\":1,"
                + "\"policyRevision\":0,\"purpose\":\"CONTEXT_PACK_TEST\",\"manifestHash\":\"" + manifestHash + "\"}";
        dsl.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,created_at) VALUES ('%s','cp-policy-mem-outbox-%s','GOVERNED',"
                        + "'memory.policy-changed.v1','MEMORY','%s',1,'pink.event.v1','CONTEXT_PACK_TEST',0,decode('%s','hex'),"
                        + "'%s'::jsonb,'%s','READY',clock_timestamp(),clock_timestamp())")
                .formatted(UUID.randomUUID(), memoryPolicyChangeId, memoryId, manifestHash, memoryManifest, memoryPolicyChangeId));
        dsl.execute("UPDATE memory.memory_record SET current_policy_revision_no=? WHERE memory_id=?", newPolicyRevision, memoryId);
    }

    private void reviseMemorySameBody(UUID memoryId) {
        var current = dsl.fetchOne("SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?", memoryId)
                .get("current_revision_id", UUID.class);
        var revision = dsl.fetchOne(
                "SELECT revision_no, memory_type, body_text, perspective_actor_id FROM memory.memory_revision "
                        + "WHERE memory_revision_id=?",
                current);
        long currentRevisionNo = revision.get("revision_no", Long.class);
        String memoryType = revision.get("memory_type", String.class);
        String body = revision.get("body_text", String.class);
        UUID actorId = revision.get("perspective_actor_id", UUID.class);

        transactions.executeInTransaction(() -> {
            long currentPolicyRev = count("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id=?", memoryId);
            UUID proposalId = UUID.randomUUID();
            UUID proposalRevisionId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID decisionId = UUID.randomUUID();
            UUID newRevisionId = UUID.randomUUID();
            long newRevisionNo = currentRevisionNo + 1;
            dsl.execute(
                    "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                            + "VALUES (?::uuid,'REVISE',?::uuid,clock_timestamp())",
                    proposalId, memoryId);
            dsl.execute(
                    "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                            + "expected_memory_revision_id,expected_policy_revision_no,created_at) "
                            + "VALUES (?::uuid,?::uuid,1,'REVISE',?::uuid,?,clock_timestamp())",
                    proposalRevisionId, proposalId, current, currentPolicyRev);
            dsl.execute(
                    "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                            + "VALUES (?::uuid,'OPEN',?,decode(repeat('aa',32),'hex'),clock_timestamp())",
                    reviewId, "cp-rev-review-" + reviewId);
            dsl.execute(
                    "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                            + "VALUES (?::uuid,?::uuid,1)",
                    reviewId, proposalRevisionId);
            dsl.execute(
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                            + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                            + "VALUES (?::uuid,'USER_CONFIRM',?::uuid,'USER',?::uuid,?::uuid,'MEMORY',?::uuid,?,'proof',?,clock_timestamp())",
                    decisionId, actorId, proposalRevisionId, reviewId, memoryId, newRevisionNo, "cp-rev-dec-" + decisionId);
            governedOutbox("REVIEW_SESSION", reviewId, newRevisionNo, "review.decisions-committed.v1", decisionId);
            dsl.execute(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,"
                            + "perspective_actor_id,body_text,created_by_decision_id,created_at) "
                            + "VALUES (?::uuid,?::uuid,?,?,?::uuid,?,?::uuid,clock_timestamp())",
                    newRevisionId, memoryId, newRevisionNo, memoryType, actorId, body, decisionId);
            governedOutbox("MEMORY", memoryId, newRevisionNo, "memory.canonical-committed.v1", decisionId);
            dsl.execute(
                    "UPDATE memory.memory_record SET current_revision_id=?::uuid, updated_at=?::timestamptz "
                            + "WHERE memory_id=?::uuid AND current_revision_id=?::uuid",
                    newRevisionId, OffsetDateTime.now(CLOCK).plusSeconds(1), memoryId, current);
            return null;
        });
    }

    private static void governedOutbox(String aggregateKind, UUID aggregateId, long revision, String eventType, UUID decisionId) {
        UUID changeId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES (?::uuid,?,?,?::uuid,?,?::uuid,clock_timestamp())",
                changeId, eventType, aggregateKind, aggregateId, revision, decisionId);
        String manifest = "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":" + revision
                + ",\"policyRevision\":0,\"purpose\":\"CONTEXT_PACK_TEST\",\"manifestHash\":\"" + "ab".repeat(32) + "\"}";
        dsl.execute(
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,created_at) VALUES (?::uuid,?,'GOVERNED',?,?,?::uuid,?,"
                        + "'pink.event.v1','CONTEXT_PACK_TEST',0,decode(repeat('ab',32),'hex'),?::jsonb,?::uuid,'READY',"
                        + "clock_timestamp(),clock_timestamp())",
                UUID.randomUUID(), "cp-ob-" + UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision, manifest, changeId);
    }

    private record Pack(UUID memoryId, UUID deliveryId) {}

    private static final AtomicInteger BASIS = new AtomicInteger(0);

    private static final class BlockingEmbeddingProvider implements EmbeddingProviderPort {
        private final EmbeddingProviderPort delegate;
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingEmbeddingProvider(EmbeddingProviderPort delegate, int parties) {
            this.delegate = delegate;
            this.entered = new CountDownLatch(parties);
            this.release = new CountDownLatch(1);
        }

        @Override
        public EmbeddingHealth health() {
            return delegate.health();
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
            return delegate.embed(texts);
        }
    }

    private UUID createMemory(double[] vector) {
        String bodyText = "合成记忆正文-" + UUID.randomUUID();
        bodyEmbedding.put(bodyText, vector);
        UUID submissionId = UUID.randomUUID();
        projection.submit(buildSubmission(submissionId, bodyText));
        return deterministicId("memory:", submissionId);
    }

    private UUID createMemory(double[] vector, List<EvidenceMessage> messages) {
        String bodyText = "合成记忆正文-" + UUID.randomUUID();
        bodyEmbedding.put(bodyText, vector);
        UUID submissionId = UUID.randomUUID();
        projection.submit(buildSubmission(submissionId, bodyText, messages));
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
        return buildSubmission(submissionId, bodyText, messages);
    }

    private static LocalV1CloseoutSubmission buildSubmission(
            UUID submissionId, String bodyText, List<EvidenceMessage> messages) {
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyHash = sha256Hex(bodyText);

        UUID anchor1 = deterministicId("anchor1:", submissionId);
        long messageCount = messages.size();
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
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, messageCount, true, "", messages));
        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, messageCount, true, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(
                messages.get(messages.size() - 1).actorId(), "INTERPRETATION", bodyText, bodyHash);
        UserConfirmation placeholder = new UserConfirmation("CONFIRM", "", confirmationUnit);
        List<SourceAnchor> anchors = List.of(new SourceAnchor(anchor1, anchorUnits));

        LocalV1CloseoutSubmission provisional = new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection, placeholder, anchors, manifest, "");
        String reviewManifestHash = LocalV1CloseoutCanonicalizer.reviewManifestHash(provisional);
        String proof = LocalV1CloseoutCanonicalizer.confirmationProof(
                threadId, confirmationUnit, reviewManifestHash, submissionId);
        return new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection,
                new UserConfirmation("CONFIRM", reviewManifestHash, confirmationUnit),
                anchors, manifest, proof);
    }

    private static void archive(UUID memoryId) {
        var current = dsl.fetchOne(
                "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId);
        UUID currentRevisionId = current.get("current_revision_id", UUID.class);
        var revision = dsl.fetchOne(
                "SELECT revision_no, perspective_actor_id FROM memory.memory_revision "
                        + "WHERE memory_revision_id=?::uuid",
                currentRevisionId);
        long revisionNo = revision.get("revision_no", Long.class);
        UUID actorId = revision.get("perspective_actor_id", UUID.class);

        UUID decisionId = UUID.randomUUID();
        UUID changeId = UUID.randomUUID();
        String manifest = "{\"aggregateId\":\"" + memoryId + "\",\"aggregateRevision\":" + revisionNo
                + ",\"policyRevision\":0,\"purpose\":\"CONTEXT_PACK_TEST\",\"manifestHash\":\""
                + "00".repeat(32) + "\"}";
        dsl.execute(
                "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                        + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                        + "VALUES (?::uuid,'USER_ARCHIVE',?::uuid,'USER','MEMORY',?::uuid,?,'cp-archive',"
                        + "?,clock_timestamp())",
                decisionId, actorId, memoryId, revisionNo, "cp-archive-" + decisionId);
        dsl.execute(
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at) "
                        + "VALUES (?::uuid,'memory.state-changed.v1','MEMORY',?::uuid,?,?::uuid,clock_timestamp())",
                changeId, memoryId, revisionNo, decisionId);
        dsl.execute(
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                        + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,"
                        + "policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,created_at) "
                        + "VALUES (?::uuid,?,'GOVERNED','memory.state-changed.v1','MEMORY',?::uuid,?,"
                        + "'pink.event.v1','CONTEXT_PACK_TEST',0,decode(repeat('00',32),'hex'),?::jsonb,"
                        + "?::uuid,'READY',clock_timestamp(),clock_timestamp())",
                UUID.randomUUID(), "cp-ob-" + UUID.randomUUID(), memoryId, revisionNo, manifest, changeId);
        dsl.execute("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=?::timestamptz WHERE memory_id=?::uuid",
                OffsetDateTime.now(CLOCK).plusSeconds(1), memoryId);
    }

    private static byte[] canonicalHash(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return digest.digest();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
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

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    /** Text-keyed embedding provider with a fail switch and a call counter. */
    private static final class MapEmbeddingProvider implements EmbeddingProviderPort {
        private final Map<String, double[]> vectors = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean fail = false;

        void put(String text, double[] vector) {
            vectors.put(text, vector.clone());
        }

        void setFail(boolean value) {
            fail = value;
        }

        int calls() {
            return calls.get();
        }

        void resetCalls() {
            calls.set(0);
        }

        @Override
        public EmbeddingHealth health() {
            return new EmbeddingHealth(!fail, MODEL, DIMENSION);
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            calls.incrementAndGet();
            if (fail) {
                throw new RuntimeException("embedding unavailable");
            }
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

    /** Delegating runtime transaction adapter that fails on a chosen write. */
    private static final class FailingRuntimeTx extends JooqRuntimeTransactionAdapter {
        private final String failOn;

        FailingRuntimeTx(DSLContext dsl, String failOn) {
            super(dsl);
            this.failOn = failOn;
        }

        @Override
        public void insertRetrievalTrace(RetrievalTrace trace) {
            if ("trace".equals(failOn)) {
                throw new RuntimeException("injected trace failure");
            }
            super.insertRetrievalTrace(trace);
        }

        @Override
        public void insertContextDelivery(ContextDelivery delivery) {
            if ("delivery".equals(failOn)) {
                throw new RuntimeException("injected delivery failure");
            }
            super.insertContextDelivery(delivery);
        }

        @Override
        public void commitReceipt(
                String idempotencyKey,
                String operationCode,
                byte[] requestHash,
                UUID resourceId,
                String resourceKind,
                String responseManifest) {
            if ("receipt".equals(failOn)) {
                throw new RuntimeException("injected receipt failure");
            }
            super.commitReceipt(idempotencyKey, operationCode, requestHash, resourceId, resourceKind, responseManifest);
        }
    }
}
