package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
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
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Zero-result and empty-replay scenarios for the context pack vertical against a fresh empty DB. */
class LocalV1ContextPackEmptyTest {

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
    private static LocalV1ContextPackCoordinator contextPack;
    private static EmbeddingProviderPort embedding;

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

        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtimeTx = new JooqRuntimeTransactionAdapter(dsl);
        RuntimeQueryPort runtimeQuery = new JooqRuntimeQueryAdapter(dsl);
        TransactionExecutor transactions = new SpringTransactionExecutor(tx);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        payloadRoot = Files.createTempDirectory("context-pack-empty-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        JooqMemoryReadAdapter memoryRead = new JooqMemoryReadAdapter(dsl);
        JooqVectorStoreAdapter vectorStore = new JooqVectorStoreAdapter(dsl);
        var fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);

        embedding = new FixedEmbeddingProvider();
        var vector = new LocalV1VectorCoordinator(
                embedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        var s2b = new LocalV1S2BQueryCoordinator(
                memoryRead, evidence, payloadStore, new JooqDeletionFenceAdapter(dsl));
        contextPack = new LocalV1ContextPackCoordinator(
                vector, s2b, memoryRead, runtimeTx, runtimeQuery, transactions, CLOCK);
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

    @Test
    void emptyDatabaseYieldsNoRelevantResult() {
        LocalV1ContextPackResult result = contextPack.create(request("她喜欢什么颜色？"), "cp-empty-1");
        assertEquals("NO_RELEVANT_RESULT", result.resultCategory());
        assertTrue(result.memories().isEmpty());
        assertTrue(result.policyRevisionSet().isEmpty());
    }

    @Test
    void emptyReplayReturnsIdenticalIdsAndTimestamps() {
        LocalV1ContextPackRequest req = request("她喜欢什么颜色？");
        LocalV1ContextPackResult first = contextPack.create(req, "cp-empty-replay");
        LocalV1ContextPackResult replay = contextPack.create(req, "cp-empty-replay");

        assertEquals(first.requestId(), replay.requestId());
        assertEquals(first.deliveryId(), replay.deliveryId());
        assertEquals(first.issuedAt(), replay.issuedAt());
        assertEquals(first.expiresAt(), replay.expiresAt());
        assertEquals("NO_RELEVANT_RESULT", replay.resultCategory());
    }

    private static LocalV1ContextPackRequest request(String query) {
        return new LocalV1ContextPackRequest(UUID.randomUUID(), UUID.randomUUID(), "RECALL", query);
    }

    private static double[] unitVector() {
        double[] vector = new double[DIMENSION];
        vector[0] = 1.0;
        return vector;
    }

    private static final class FixedEmbeddingProvider implements EmbeddingProviderPort {
        @Override
        public EmbeddingHealth health() {
            return new EmbeddingHealth(true, MODEL, DIMENSION);
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            List<double[]> vectors = new java.util.ArrayList<>();
            for (String ignored : texts) {
                vectors.add(unitVector());
            }
            return new EmbeddingResult(MODEL, DIMENSION, vectors);
        }
    }
}
