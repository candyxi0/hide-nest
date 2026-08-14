package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3C2FileDeletionCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionBindingAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionExecutionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.DeletionBindingPort;
import io.github.candyxi0.hidenest.memory.port.DeletionConfirmationPort;
import io.github.candyxi0.hidenest.memory.port.DeletionExecutionPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit assembly for the synthetic, loopback-only Local V1 permanent-deletion write vertical. */
@Configuration(proxyBeanMethods = false)
@Profile("local-v1-synthetic")
public class LocalV1DeletionWriteConfiguration {

    @Bean
    JooqDeletionPreviewAdapter jooqDeletionPreviewAdapter(DSLContext dsl) {
        return new JooqDeletionPreviewAdapter(dsl);
    }

    @Bean
    JooqDeletionConfirmationAdapter jooqDeletionConfirmationAdapter(DSLContext dsl) {
        return new JooqDeletionConfirmationAdapter(dsl);
    }

    @Bean
    JooqDeletionExecutionAdapter jooqDeletionExecutionAdapter(DSLContext dsl) {
        return new JooqDeletionExecutionAdapter(dsl);
    }

    @Bean
    JooqDeletionBindingAdapter jooqDeletionBindingAdapter(DSLContext dsl) {
        return new JooqDeletionBindingAdapter(dsl);
    }

    @Bean
    LocalV1S3ADeletionPreviewCoordinator localV1S3ADeletionPreviewCoordinator(
            DeletionPreviewPort previewPort,
            TransactionExecutor transactions,
            Clock clock,
            DeletionFencePort deletionFencePort) {
        return new LocalV1S3ADeletionPreviewCoordinator(previewPort, transactions, clock, deletionFencePort);
    }

    @Bean
    LocalV1S3B2BDeletionConfirmCoordinator localV1S3B2BDeletionConfirmCoordinator(
            DeletionConfirmationPort confirmationPort,
            MemoryGovernancePort governancePort,
            DeletionFencePort fencePort,
            DeletionPreviewPort previewPort,
            TransactionExecutor transactions,
            Clock clock) {
        return new LocalV1S3B2BDeletionConfirmCoordinator(
                confirmationPort, governancePort, fencePort, previewPort, transactions, clock);
    }

    @Bean
    LocalV1S3C2FileDeletionCoordinator localV1S3C2FileDeletionCoordinator(
            DeletionExecutionPort executionPort, PayloadStore payloadStore, Clock clock) {
        return new LocalV1S3C2FileDeletionCoordinator(executionPort, payloadStore, clock);
    }

    @Bean
    LocalV1DeletionWriteCoordinator localV1DeletionWriteCoordinator(
            DeletionBindingPort bindingPort,
            DeletionPreviewPort previewPort,
            MemoryGovernancePort governancePort,
            LocalV1S3ADeletionPreviewCoordinator previewCoordinator,
            LocalV1S3B2BDeletionConfirmCoordinator confirmCoordinator,
            DeletionExecutionPort executionPort,
            LocalV1S3C2FileDeletionCoordinator fileDeletionCoordinator,
            Clock clock) {
        return new LocalV1DeletionWriteCoordinator(
                bindingPort,
                previewPort,
                governancePort,
                previewCoordinator,
                confirmCoordinator,
                executionPort,
                fileDeletionCoordinator,
                clock);
    }
}
