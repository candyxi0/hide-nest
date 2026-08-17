package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetBatchCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCloseoutCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCreateProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqCandidateSetGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Explicit assembly for the synthetic, loopback-only Local V1 CandidateSet HTTP vertical.
 * Common adapters (JooqMemoryGovernanceAdapter, JooqRuntimeTransactionAdapter,
 * SpringTransactionExecutor, CanonicalPublishCoordinator, LocalV1VectorCoordinator, etc.)
 * are provided by the existing {@link LocalV1CloseoutWriteConfiguration} and
 * {@link LocalV1ReadConfiguration}; this configuration only defines CandidateSet-specific beans.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-v1-synthetic")
public class LocalV1CandidateSetConfiguration {

    @Bean
    JooqCandidateSetGovernanceAdapter jooqCandidateSetGovernanceAdapter(DSLContext dsl) {
        return new JooqCandidateSetGovernanceAdapter(dsl);
    }

    @Bean
    LocalV1CandidateSetBatchCoordinator localV1CandidateSetBatchCoordinator(
            JooqEvidenceReferenceAdapter evidence,
            JooqMemoryGovernanceAdapter memory,
            JooqRuntimeTransactionAdapter runtime,
            JooqCandidateSetGovernanceAdapter candidateSet,
            SpringTransactionExecutor tx,
            LocalPayloadStore payloadStore,
            Clock clock) {
        return new LocalV1CandidateSetBatchCoordinator(
                evidence, memory, runtime, candidateSet, tx, payloadStore, clock);
    }

    @Bean
    LocalV1CandidateSetCreateProjectionCoordinator localV1CandidateSetCreateProjectionCoordinator(
            JooqCandidateSetGovernanceAdapter candidateSet,
            JooqMemoryGovernanceAdapter memory,
            JooqMemoryReadAdapter memoryRead,
            JooqEvidenceReferenceAdapter evidence,
            CanonicalPublishCoordinator publish,
            LocalV1VectorCoordinator vector,
            JooqVectorStoreAdapter vectorStore,
            ModelFingerprint fingerprint,
            JooqRuntimeTransactionAdapter runtime) {
        return new LocalV1CandidateSetCreateProjectionCoordinator(
                candidateSet, memory, memoryRead, evidence, publish, vector, vectorStore, fingerprint, runtime);
    }

    @Bean
    LocalV1CandidateSetCloseoutCoordinator localV1CandidateSetCloseoutCoordinator(
            LocalV1CandidateSetBatchCoordinator batch,
            LocalV1CandidateSetCreateProjectionCoordinator projection) {
        return new LocalV1CandidateSetCloseoutCoordinator(batch, projection);
    }
}