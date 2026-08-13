package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit assembly for the synthetic, loopback-only Local V1 closeout write vertical. */
@Configuration(proxyBeanMethods = false)
@Profile("local-v1-synthetic")
public class LocalV1CloseoutWriteConfiguration {

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
}
