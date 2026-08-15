package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorException;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorIndexResult;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorMatch;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionExecutionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/** Local V1 embedding vector vertical: offline loopback fake-HTTP + real PostgreSQL/pgvector. */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1EmbeddingVectorTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA = HexFormat.of()
            .parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneId.of("UTC"));

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static String password;
    private static String jdbcUrl;
    private static HttpServer server;
    private static final Map<String, double[]> VECTORS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static LocalV1VectorCoordinator vector;
    private static MemoryGovernancePort governance;
    private static TransactionExecutor transactions;
    private static HttpEmbeddingProviderAdapter embedding;
    private static MemoryVectorStorePort vectorStore;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static DeletionExecutionPort execution;
    private static Path payloadRoot;

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
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(
                18,
                Flyway.configure()
                        .dataSource(jdbcUrl, USER, password)
                        .defaultSchema("public")
                        .locations("classpath:db/migration")
                        .cleanDisabled(true)
                        .load()
                        .migrate()
                        .migrationsExecuted);

        var raw = new DriverManagerDataSource(jdbcUrl, USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        governance = new JooqMemoryGovernanceAdapter(dsl);
        transactions = new SpringTransactionExecutor(tx);

        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, transactions, CLOCK);
        payloadRoot = Files.createTempDirectory("embedding-vector-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, transactions, publisher, payloadStore, CLOCK);

        DeletionPreviewPort previewAdapter = new JooqDeletionPreviewAdapter(dsl);
        DeletionFencePort fenceAdapter = new JooqDeletionFenceAdapter(dsl);
        preview = new LocalV1S3ADeletionPreviewCoordinator(previewAdapter, transactions, CLOCK, fenceAdapter, payloadStore);
        confirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), governance, fenceAdapter, previewAdapter, transactions, CLOCK);
        execution = new JooqDeletionExecutionAdapter(dsl);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/embed", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode root = MAPPER.readTree(body);
            String text = root.path("texts").get(0).asText();
            double[] vec = VECTORS.getOrDefault(text, unitFirst());
            StringBuilder sb = new StringBuilder(DIMENSION * 12);
            sb.append('[');
            for (int i = 0; i < DIMENSION; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(i < vec.length ? vec[i] : 0.0);
            }
            sb.append(']');
            String response = "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION + ",\"vectors\":[" + sb + "]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();

        embedding = new HttpEmbeddingProviderAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), MODEL, DIMENSION, 30000, 32);
        vectorStore = new JooqVectorStoreAdapter(dsl);
        vector = new LocalV1VectorCoordinator(
                embedding,
                vectorStore,
                governance,
                transactions,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION),
                CLOCK);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
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

    // ── 1. Real index path writes a normalized 512-dim vector ─────────────

    @Test
    @Order(1)
    void indexesCurrentRevisionAndPersistsNormalizedVector() {
        Memory mem = createMemory("index", "pink-preference");
        VECTORS.put("pink-preference", new double[] {3, 4});

        LocalV1VectorIndexResult result = vector.indexCurrentRevision(mem.memoryId());

        assertEquals(mem.memoryId(), result.memoryId());
        assertEquals(mem.revisionId(), result.memoryRevisionId());
        assertEquals(MODEL, result.modelName());
        assertEquals(DIMENSION, result.dimension());
        assertEquals(sha256Hex("pink-preference"), result.embeddedBodySha256Hex());
        assertTrue(Math.abs(result.normalizedNorm() - 1.0) <= 1e-6);
        assertFalse(result.idempotent());

        assertEquals(512L, scalarLong(
                "SELECT dimension FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
        assertTrue(Math.abs(scalarDouble(
                        "SELECT public.vector_norm(embedding) FROM memory.memory_revision_embedding WHERE memory_revision_id=?",
                        mem.revisionId())
                - 1.0)
                <= 1e-4);
    }

    // ── 2. Idempotent exact replay ────────────────────────────────────────

    @Test
    @Order(2)
    void idempotentReplayIsExact() {
        Memory mem = createMemory("replay", "replay-body");
        VECTORS.put("replay-body", new double[] {1, 2, 2});

        vector.indexCurrentRevision(mem.memoryId());
        LocalV1VectorIndexResult replay = vector.indexCurrentRevision(mem.memoryId());

        assertTrue(replay.idempotent());
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
    }

    // ── 3. Body hash conflict fails closed (adapter write entry) ──────────

    @Test
    @Order(3)
    void bodyHashConflictFailsClosed() {
        Memory mem = createMemory("conflict", "conflict-body");
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        MemoryVectorStorePort store = new JooqVectorStoreAdapter(dsl);
        double[] unit = unitFirst();

        store.upsertEmbedding(new MemoryVectorStorePort.VectorEmbeddingDraft(
                mem.revisionId(), MODEL, GGUF_SHA, DIMENSION, NORMALIZATION, sha256("hash-a"), unit, now));
        assertThrows(
                IllegalStateException.class,
                () -> store.upsertEmbedding(new MemoryVectorStorePort.VectorEmbeddingDraft(
                        mem.revisionId(), MODEL, GGUF_SHA, DIMENSION, NORMALIZATION, sha256("hash-b"), unit, now)));
    }

    // ── 4. Exact cosine ordering and stable tie-break ─────────────────────

    @Test
    @Order(4)
    void searchReturnsExactCosineOrderingWithTieBreak() {
        Memory a = createMemory("order-a", "pink-memory");
        Memory b = createMemory("order-b", "server-memory");
        VECTORS.put("pink-memory", new double[] {3, 4});
        VECTORS.put("server-memory", new double[] {0, 5});
        vector.indexCurrentRevision(a.memoryId());
        vector.indexCurrentRevision(b.memoryId());

        VECTORS.put("color-query", new double[] {3, 4});
        List<LocalV1VectorMatch> matches = vector.searchSimilar("color-query", 20);

        int aIdx = indexOf(matches, a.memoryId());
        int bIdx = indexOf(matches, b.memoryId());
        assertTrue(aIdx >= 0 && bIdx >= 0, "both memories must be returned");
        assertTrue(aIdx < bIdx, "A must rank before B");
        assertTrue(matches.get(aIdx).score() > matches.get(bIdx).score());

        // Stable tie-break: two memories with identical vectors -> ascending memory id.
        Memory c = createMemory("tie-c", "tie-body-c");
        Memory d = createMemory("tie-d", "tie-body-d");
        VECTORS.put("tie-body-c", new double[] {1, 0});
        VECTORS.put("tie-body-d", new double[] {1, 0});
        vector.indexCurrentRevision(c.memoryId());
        vector.indexCurrentRevision(d.memoryId());
        VECTORS.put("tie-query", new double[] {1, 0});
        List<LocalV1VectorMatch> tie = vector.searchSimilar("tie-query", 20);
        int cIdx = indexOf(tie, c.memoryId());
        int dIdx = indexOf(tie, d.memoryId());
        assertTrue(cIdx >= 0 && dIdx >= 0, "both tie memories must be returned");
        assertEquals(0.0, Math.abs(tie.get(cIdx).score() - tie.get(dIdx).score()), 1e-9);
        if (c.memoryId().toString().compareTo(d.memoryId().toString()) < 0) {
            assertTrue(cIdx < dIdx, "ascending memory id tie-break");
        } else {
            assertTrue(dIdx < cIdx, "ascending memory id tie-break");
        }
    }

    // ── 5. Invalid vectors are rejected at the write entry ────────────────

    @Test
    @Order(5)
    void vectorStoreRejectsInvalidVectors() {
        Memory mem = createMemory("invalid", "invalid-body");
        MemoryVectorStorePort store = new JooqVectorStoreAdapter(dsl);
        OffsetDateTime now = OffsetDateTime.now(CLOCK);

        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), unitFirst(), 256, NORMALIZATION)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), unitFirst(), DIMENSION, "L2")));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, new byte[31], unitFirst(), DIMENSION, NORMALIZATION)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), nanVector(), DIMENSION, NORMALIZATION)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), infVector(), DIMENSION, NORMALIZATION)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), zeroVector(), DIMENSION, NORMALIZATION)));
        assertThrows(IllegalArgumentException.class, () -> store.upsertEmbedding(draft(mem, sha256("x"), notNormalized(), DIMENSION, NORMALIZATION)));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
    }

    // ── 6. Search filters by exact model fingerprint ──────────────────────

    @Test
    @Order(6)
    void searchFiltersByExactModelFingerprint() {
        Memory mem = createMemory("fingerprint", "fingerprint-body");
        MemoryVectorStorePort store = new JooqVectorStoreAdapter(dsl);
        OffsetDateTime now = OffsetDateTime.now(CLOCK);
        store.upsertEmbedding(new MemoryVectorStorePort.VectorEmbeddingDraft(
                mem.revisionId(), "other-model", GGUF_SHA, DIMENSION, NORMALIZATION, sha256("x"), unitFirst(), now));
        store.upsertEmbedding(new MemoryVectorStorePort.VectorEmbeddingDraft(
                mem.revisionId(), MODEL, GGUF_SHA, DIMENSION, NORMALIZATION, sha256("x"), unitFirst(), now));

        VECTORS.put("fp-query", new double[] {1, 0});
        List<LocalV1VectorMatch> matches = vector.searchSimilar("fp-query", 20);
        long occurrences = matches.stream()
                .filter(m -> m.memoryRevisionId().equals(mem.revisionId()))
                .count();
        assertEquals(1, occurrences, "the other-model embedding must be excluded");
    }

    // ── 7. Limit bounds rejection ─────────────────────────────────────────

    @Test
    @Order(7)
    void searchRejectsLimitBounds() {
        VECTORS.put("limit-query", new double[] {1, 0});
        assertThrows(LocalV1VectorException.class, () -> vector.searchSimilar("limit-query", 0));
        assertThrows(LocalV1VectorException.class, () -> vector.searchSimilar("limit-query", 21));
    }

    // ── 8. Permanent deletion cascades the vector ─────────────────────────

    @Test
    @Order(8)
    void permanentDeletionCascadesVector() {
        Memory mem = createMemory("delete", "delete-body");
        VECTORS.put("delete-body", new double[] {1, 1});
        vector.indexCurrentRevision(mem.memoryId());
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));

        UUID closureId = confirmClosure(mem);
        execution.executeDatabasePhase(UUID.randomUUID(), closureId, OffsetDateTime.now(CLOCK));

        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision WHERE memory_id=?", mem.memoryId()));
    }

    // ── 9. Deletion rollback retains the vector ───────────────────────────

    @Test
    @Order(9)
    void deletionRollbackRetainsVector() {
        Memory mem = createMemory("rollback", "rollback-body");
        VECTORS.put("rollback-body", new double[] {1, 1});
        vector.indexCurrentRevision(mem.memoryId());
        UUID closureId = confirmClosure(mem);

        dsl.execute("CREATE OR REPLACE FUNCTION public.test_fail_before_memory() RETURNS trigger "
                + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'TEST_ROLLBACK' USING ERRCODE='23514'; END $$");
        dsl.execute("CREATE TRIGGER test_fail_before_memory_tg BEFORE DELETE ON memory.memory_revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.test_fail_before_memory()");
        try {
            assertThrows(
                    RuntimeException.class,
                    () -> execution.executeDatabasePhase(UUID.randomUUID(), closureId, OffsetDateTime.now(CLOCK)));
        } finally {
            dsl.execute("DROP TRIGGER IF EXISTS test_fail_before_memory_tg ON memory.memory_revision");
            dsl.execute("DROP FUNCTION IF EXISTS public.test_fail_before_memory()");
        }
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
    }

    // ── 10. Shared evidence retention leaves other vectors intact ─────────

    @Test
    @Order(10)
    void deletingOneMemoryLeavesOtherVectorIntact() {
        Memory a = createMemory("shared-a", "shared-a-body");
        Memory b = createMemory("shared-b", "shared-b-body");
        VECTORS.put("shared-a-body", new double[] {1, 0});
        VECTORS.put("shared-b-body", new double[] {0, 1});
        vector.indexCurrentRevision(a.memoryId());
        vector.indexCurrentRevision(b.memoryId());

        UUID closureId = confirmClosure(a);
        execution.executeDatabasePhase(UUID.randomUUID(), closureId, OffsetDateTime.now(CLOCK));

        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", a.revisionId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", b.revisionId()));
    }

    // ── 11. R1-01: same-body new revision during embed is rejected ────────

    @Test
    @Order(11)
    void sameBodyNewRevisionConcurrentChangeIsRejected() throws Exception {
        Memory mem = createMemory("race", "same-body");
        VECTORS.put("same-body", new double[] {1, 1});

        BlockingEmbeddingProvider blocking = new BlockingEmbeddingProvider(embedding);
        LocalV1VectorCoordinator racing = new LocalV1VectorCoordinator(
                blocking, vectorStore, governance, transactions,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION), CLOCK);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread indexer = new Thread(() -> {
            try {
                racing.indexCurrentRevision(mem.memoryId());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });
        indexer.start();

        assertTrue(blocking.entered.await(10, TimeUnit.SECONDS), "embed must be entered and blocked");
        long currentRevisionNo = count("SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id=?", mem.revisionId());
        UUID newRevisionId = reviseMemorySameBody(mem.memoryId(), mem.actorId(), mem.revisionId(), currentRevisionNo, "same-body");
        blocking.release.countDown();
        indexer.join(10000);

        assertTrue(failure.get() instanceof LocalV1VectorException);
        assertEquals(LocalV1VectorException.Code.CURRENT_POINTER_CHANGED, ((LocalV1VectorException) failure.get()).code());
        assertEquals(0L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id IN (?,?)", mem.revisionId(), newRevisionId));
    }

    // ── 12. R1-02: same-value concurrent upsert is idempotent ─────────────

    @Test
    @Order(12)
    void sameValueConcurrentUpsertIsIdempotent() throws Exception {
        Memory mem = createMemory("conc-same", "conc-same-body");
        byte[] hash = sha256("same-hash");
        JooqVectorStoreAdapter first = new JooqVectorStoreAdapter(dsl);
        JooqVectorStoreAdapter second = new JooqVectorStoreAdapter(dsl);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<MemoryVectorStorePort.VectorUpsertResult> r1 = new AtomicReference<>();
        AtomicReference<MemoryVectorStorePort.VectorUpsertResult> r2 = new AtomicReference<>();
        AtomicReference<Throwable> e1 = new AtomicReference<>();
        AtomicReference<Throwable> e2 = new AtomicReference<>();
        Thread t1 = new Thread(() -> runAfter(start, () -> r1.set(first.upsertEmbedding(draft(mem, hash, unitFirst(), DIMENSION, NORMALIZATION))), e1));
        Thread t2 = new Thread(() -> runAfter(start, () -> r2.set(second.upsertEmbedding(draft(mem, hash, unitFirst(), DIMENSION, NORMALIZATION))), e2));
        t1.start();
        t2.start();
        start.countDown();
        t1.join(15000);
        t2.join(15000);

        assertTrue(e1.get() == null && e2.get() == null, "no exception expected");
        boolean oneInserted = r1.get() instanceof MemoryVectorStorePort.VectorUpsertResult.Inserted
                ^ r2.get() instanceof MemoryVectorStorePort.VectorUpsertResult.Inserted;
        boolean oneReplay = r1.get() instanceof MemoryVectorStorePort.VectorUpsertResult.AlreadyPresent
                ^ r2.get() instanceof MemoryVectorStorePort.VectorUpsertResult.AlreadyPresent;
        assertTrue(oneInserted && oneReplay, "exactly one Inserted + one AlreadyPresent");
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
    }

    // ── 13. R1-02: different-value concurrent upsert conflicts ────────────

    @Test
    @Order(13)
    void differentValueConcurrentUpsertConflicts() throws Exception {
        Memory mem = createMemory("conc-diff", "conc-diff-body");
        JooqVectorStoreAdapter first = new JooqVectorStoreAdapter(dsl);
        JooqVectorStoreAdapter second = new JooqVectorStoreAdapter(dsl);

        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<MemoryVectorStorePort.VectorUpsertResult> success = new AtomicReference<>();
        AtomicReference<Throwable> conflict = new AtomicReference<>();
        Thread t1 = new Thread(() -> runAfter(start, () -> success.set(first.upsertEmbedding(draft(mem, sha256("diff-a"), unitFirst(), DIMENSION, NORMALIZATION))), conflict));
        Thread t2 = new Thread(() -> runAfter(start, () -> success.set(second.upsertEmbedding(draft(mem, sha256("diff-b"), unitFirst(), DIMENSION, NORMALIZATION))), conflict));
        t1.start();
        t2.start();
        start.countDown();
        t1.join(15000);
        t2.join(15000);

        assertTrue(success.get() instanceof MemoryVectorStorePort.VectorUpsertResult.Inserted, "one thread must insert");
        assertTrue(conflict.get() instanceof IllegalStateException, "one thread must conflict");
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", mem.revisionId()));
    }

    private static void runAfter(CountDownLatch start, Runnable work, AtomicReference<Throwable> failure) {
        try {
            start.await();
            work.run();
        } catch (Throwable throwable) {
            failure.set(throwable);
        }
    }

    private UUID reviseMemorySameBody(UUID memoryId, UUID actorId, UUID currentRevisionId, long currentRevisionNo, String body) {
        return transactions.executeInTransaction(() -> {
            long currentPolicyRev = count("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id=?", memoryId);
            UUID proposalId = UUID.randomUUID();
            UUID proposalRevisionId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID decisionId = UUID.randomUUID();
            UUID newRevisionId = UUID.randomUUID();
            long newRevisionNo = currentRevisionNo + 1;

            dsl.execute("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES (?::uuid,'REVISE',?::uuid,clock_timestamp())", proposalId, memoryId);
            dsl.execute("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES (?::uuid,?::uuid,1,'REVISE',?::uuid,?,clock_timestamp())", proposalRevisionId, proposalId, currentRevisionId, currentPolicyRev);
            dsl.execute("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) VALUES (?::uuid,'OPEN',?,decode(repeat('aa',32),'hex'),clock_timestamp())", reviewId, "rev-review-" + reviewId);
            dsl.execute("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES (?::uuid,?::uuid,1)", reviewId, proposalRevisionId);
            dsl.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES (?::uuid,'USER_CONFIRM',?::uuid,'USER',?::uuid,?::uuid,'MEMORY',?::uuid,?,'proof',?,clock_timestamp())", decisionId, actorId, proposalRevisionId, reviewId, memoryId, newRevisionNo, "rev-dec-" + decisionId);
            governedOutbox("REVIEW_SESSION", reviewId, newRevisionNo, "review.decisions-committed.v1", decisionId);
            dsl.execute("INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) VALUES (?::uuid,?::uuid,?,'Claim',?,?::uuid,clock_timestamp())", newRevisionId, memoryId, newRevisionNo, body, decisionId);
            governedOutbox("MEMORY", memoryId, newRevisionNo, "memory.canonical-committed.v1", decisionId);
            dsl.execute("UPDATE memory.memory_record SET current_revision_id=?::uuid,updated_at=clock_timestamp() WHERE memory_id=?::uuid AND current_revision_id=?::uuid", newRevisionId, memoryId, currentRevisionId);
            return newRevisionId;
        });
    }

    private void governedOutbox(String aggregateKind, UUID aggregateId, long revision, String eventType, UUID decisionId) {
        UUID changeId = UUID.randomUUID();
        dsl.execute("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES (?::uuid,?,?,?::uuid,?,?::uuid,clock_timestamp())", changeId, eventType, aggregateKind, aggregateId, revision, decisionId);
        String manifest = "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":" + revision
                + ",\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + "ab".repeat(32) + "\"}";
        dsl.execute("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,created_at) VALUES (?::uuid,?,'GOVERNED',?,?,?::uuid,?,'pink.event.v1','DATABASE_TEST',0,decode(repeat('ab',32),'hex'),?::jsonb,?::uuid,'READY',clock_timestamp(),clock_timestamp())", UUID.randomUUID(), "ob-" + UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision, manifest, changeId);
    }

    private static final class BlockingEmbeddingProvider implements EmbeddingProviderPort {
        private final EmbeddingProviderPort delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingEmbeddingProvider(EmbeddingProviderPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingProviderPort.EmbeddingHealth health() {
            return delegate.health();
        }

        @Override
        public EmbeddingProviderPort.EmbeddingResult embed(List<String> texts) {
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

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID confirmClosure(Memory mem) {
        byte[] requestHash = sha256(("preview-" + mem.memoryId()).getBytes(StandardCharsets.UTF_8));
        var previewResult = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "preview-" + mem.memoryId(), requestHash));
        var confirmResult = confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                previewResult.previewId(), previewResult.previewRevision(), previewResult.manifestHash(),
                mem.actorId(), "confirm-" + mem.memoryId()));
        return previewResult.previewId();
    }

    private Memory createMemory(String marker, String body) {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String key = "ev-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key,
                sha256(key.getBytes(StandardCharsets.UTF_8)),
                actor,
                "Claim",
                body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        unit, actor, 1L, "unit-" + marker, OffsetDateTime.now(CLOCK), "evidence-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(
                        anchor, List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unit, 0L, 1L, 1L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key,
                sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(),
                prepared.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                new byte[32]));
        return new Memory(memoryId, confirmed.currentRevisionId(), actor);
    }

    private static MemoryVectorStorePort.VectorEmbeddingDraft draft(
            Memory mem, byte[] bodyHash, double[] vector, int dimension, String normalization) {
        return new MemoryVectorStorePort.VectorEmbeddingDraft(
                mem.revisionId(), MODEL, GGUF_SHA, dimension, normalization, bodyHash, vector, OffsetDateTime.now(CLOCK));
    }

    private static double[] unitFirst() {
        double[] v = new double[DIMENSION];
        v[0] = 1.0;
        return v;
    }

    private static double[] nanVector() {
        double[] v = unitFirst();
        v[1] = Double.NaN;
        return v;
    }

    private static double[] infVector() {
        double[] v = unitFirst();
        v[1] = Double.POSITIVE_INFINITY;
        return v;
    }

    private static double[] zeroVector() {
        return new double[DIMENSION];
    }

    private static double[] notNormalized() {
        double[] v = new double[DIMENSION];
        v[0] = 3.0;
        v[1] = 4.0;
        return v;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String text) {
        return HexFormat.of().formatHex(sha256(text));
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static long scalarLong(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static double scalarDouble(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).doubleValue();
    }

    private static int indexOf(List<LocalV1VectorMatch> matches, UUID memoryId) {
        for (int i = 0; i < matches.size(); i++) {
            if (matches.get(i).memoryId().equals(memoryId)) {
                return i;
            }
        }
        return -1;
    }

    private record Memory(UUID memoryId, UUID revisionId, UUID actorId) {}
}
