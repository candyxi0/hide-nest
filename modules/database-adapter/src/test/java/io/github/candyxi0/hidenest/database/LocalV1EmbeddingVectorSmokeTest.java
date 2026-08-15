package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorIndexResult;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorMatch;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
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
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real home-server smoke test. Enabled only when {@code HIDE_NEST_EMBEDDING_BASE_URL} is set
 * (the SSH tunnel is up). Uses synthetic memories only and a disposable PostgreSQL container.
 */
@EnabledIfEnvironmentVariable(named = "HIDE_NEST_EMBEDDING_BASE_URL", matches = ".+")
class LocalV1EmbeddingVectorSmokeTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA = HexFormat.of()
            .parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");

    private static PostgreSQLContainer<?> postgres;
    private static LocalV1VectorCoordinator vector;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static HttpEmbeddingProviderAdapter embedding;

    @BeforeAll
    static void setUp() throws Exception {
        String baseUrl = System.getenv("HIDE_NEST_EMBEDDING_BASE_URL");
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, password)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);

        Clock clock = Clock.fixed(OffsetDateTime.parse("2026-08-15T00:00:00Z").toInstant(), ZoneOffset.UTC);
        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        TransactionExecutor transactions = new SpringTransactionExecutor(tx);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, transactions, clock);
        payloadRoot = Files.createTempDirectory("embedding-smoke-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, transactions, publisher, payloadStore, clock);

        embedding = new HttpEmbeddingProviderAdapter(
                baseUrl, MODEL, DIMENSION, 30000, 32);
        MemoryVectorStorePort vectorStore = new JooqVectorStoreAdapter(dsl);
        vector = new LocalV1VectorCoordinator(
                embedding,
                vectorStore,
                governance,
                transactions,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION),
                clock);
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

    @Test
    void realEmbeddingSmokeAB() {
        var health = embedding.health();
        assertTrue(health.healthy(), "health must be healthy");
        assertEquals(MODEL, health.model(), "health model must match service model id");
        assertEquals(DIMENSION, health.dimension(), "health dimension must match");

        SmokeMemory a = createMemory("pink", "小林偏爱粉色");
        SmokeMemory b = createMemory("server", "下周准备购买家庭服务器");

        long t0 = System.nanoTime();
        LocalV1VectorIndexResult indexA = vector.indexCurrentRevision(a.memoryId());
        LocalV1VectorIndexResult indexB = vector.indexCurrentRevision(b.memoryId());
        LocalV1VectorIndexResult replayA = vector.indexCurrentRevision(a.memoryId());
        List<LocalV1VectorMatch> matches = vector.searchSimilar("她喜欢什么颜色？", 5);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(a.memoryId(), matches.get(0).memoryId());
        assertTrue(matches.get(0).score() > matches.get(1).score());
        assertTrue(replayA.idempotent());
        assertEquals(DIMENSION, indexA.dimension());
        assertEquals(MODEL, indexA.modelName());
        assertTrue(Math.abs(indexA.normalizedNorm() - 1.0) <= 1e-6);
        assertTrue(Math.abs(indexB.normalizedNorm() - 1.0) <= 1e-6);
        assertEquals(512L, count("SELECT dimension FROM memory.memory_revision_embedding WHERE memory_revision_id=?", a.revisionId()));
        assertEquals(512L, count("SELECT dimension FROM memory.memory_revision_embedding WHERE memory_revision_id=?", b.revisionId()));

        System.out.println("[smoke] A=" + indexA.embeddedBodySha256Hex() + " score=" + matches.get(0).score()
                + " dim=" + indexA.dimension() + " norm=" + indexA.normalizedNorm() + " idempotent=" + replayA.idempotent());
        System.out.println("[smoke] B=" + indexB.embeddedBodySha256Hex() + " score=" + matches.get(1).score()
                + " dim=" + indexB.dimension() + " norm=" + indexB.normalizedNorm());
        System.out.println("[smoke] elapsedMs=" + elapsedMs + " matches=" + matches.size());
    }

    private SmokeMemory createMemory(String marker, String body) {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String key = "smoke-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key,
                sha256(key.getBytes(StandardCharsets.UTF_8)),
                actor,
                "Claim",
                body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        unit, actor, 1L, "unit-" + marker, OffsetDateTime.now(ZoneOffset.UTC), "evidence-" + marker)),
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
        return new SmokeMemory(memoryId, confirmed.currentRevisionId());
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private record SmokeMemory(UUID memoryId, UUID revisionId) {}
}
