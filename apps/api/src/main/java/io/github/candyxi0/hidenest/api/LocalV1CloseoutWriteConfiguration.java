package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutVectorProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import java.time.Clock;
import java.util.HexFormat;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit assembly for the synthetic, loopback-only Local V1 closeout write vertical. */
@Configuration(proxyBeanMethods = false)
@Profile({"local-v1-synthetic", "local-private"})
public class LocalV1CloseoutWriteConfiguration {

    private static final String SERVICE_MODEL_ID = "bge-small-zh-v1.5-f16";
    private static final String GGUF_SHA_HEX =
            "ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final long EMBEDDING_TIMEOUT_MS = 30000;
    private static final int EMBEDDING_MAX_BATCH = 32;

    @Bean
    Clock localV1Clock() {
        return Clock.systemUTC();
    }

    @Bean
    JooqMemoryGovernanceAdapter jooqMemoryGovernanceAdapter(DSLContext dsl) {
        return new JooqMemoryGovernanceAdapter(dsl);
    }

    @Bean
    JooqRuntimeTransactionAdapter jooqRuntimeTransactionAdapter(DSLContext dsl) {
        return new JooqRuntimeTransactionAdapter(dsl);
    }

    @Bean
    JooqRuntimeQueryAdapter jooqRuntimeQueryAdapter(DSLContext dsl) {
        return new JooqRuntimeQueryAdapter(dsl);
    }

    @Bean
    TransactionTemplate localV1TransactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    SpringTransactionExecutor springTransactionExecutor(TransactionTemplate transactionTemplate) {
        return new SpringTransactionExecutor(transactionTemplate);
    }

    @Bean
    CanonicalPublishCoordinator canonicalPublishCoordinator(
            JooqMemoryGovernanceAdapter memory,
            JooqRuntimeTransactionAdapter runtime,
            SpringTransactionExecutor tx,
            Clock clock) {
        return new CanonicalPublishCoordinator(memory, runtime, tx, clock);
    }

    @Bean
    LocalV1S1WindowCloseCoordinator localV1S1WindowCloseCoordinator(
            JooqEvidenceReferenceAdapter evidence,
            JooqMemoryGovernanceAdapter memory,
            JooqRuntimeTransactionAdapter runtime,
            SpringTransactionExecutor tx,
            CanonicalPublishCoordinator publisher,
            LocalPayloadStore payloadStore,
            Clock clock) {
        return new LocalV1S1WindowCloseCoordinator(
                evidence, memory, runtime, tx, publisher, payloadStore, clock);
    }

    @Bean
    LocalV1CloseoutWriteCoordinator localV1CloseoutWriteCoordinator(
            JooqRuntimeTransactionAdapter runtime,
            JooqRuntimeQueryAdapter runtimeQuery,
            SpringTransactionExecutor tx,
            LocalV1S1WindowCloseCoordinator s1,
            Clock clock) {
        return new LocalV1CloseoutWriteCoordinator(runtime, runtimeQuery, tx, s1, clock);
    }

    @Bean
    JooqVectorStoreAdapter jooqVectorStoreAdapter(DSLContext dsl) {
        return new JooqVectorStoreAdapter(dsl);
    }

    @Bean
    HttpEmbeddingProviderAdapter httpEmbeddingProviderAdapter(
            @Value("${HIDE_NEST_EMBEDDING_BASE_URL:}") String baseUrl) {
        return new HttpEmbeddingProviderAdapter(
                baseUrl, SERVICE_MODEL_ID, DIMENSION, EMBEDDING_TIMEOUT_MS, EMBEDDING_MAX_BATCH);
    }

    @Bean
    ModelFingerprint localV1VectorModelFingerprint() {
        return new ModelFingerprint(
                SERVICE_MODEL_ID,
                HexFormat.of().parseHex(GGUF_SHA_HEX),
                DIMENSION,
                NORMALIZATION);
    }

    @Bean
    LocalV1VectorCoordinator localV1VectorCoordinator(
            HttpEmbeddingProviderAdapter embedding,
            JooqVectorStoreAdapter vectorStore,
            JooqMemoryGovernanceAdapter governance,
            SpringTransactionExecutor tx,
            ModelFingerprint fingerprint,
            Clock clock) {
        return new LocalV1VectorCoordinator(embedding, vectorStore, governance, tx, fingerprint, clock);
    }

    @Bean
    LocalV1CloseoutVectorProjectionCoordinator localV1CloseoutVectorProjectionCoordinator(
            LocalV1CloseoutWriteCoordinator closeout,
            LocalV1VectorCoordinator vector,
            JooqMemoryReadAdapter memoryRead,
            JooqVectorStoreAdapter vectorStore,
            ModelFingerprint fingerprint) {
        return new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, vector, memoryRead, vectorStore, fingerprint);
    }
}
