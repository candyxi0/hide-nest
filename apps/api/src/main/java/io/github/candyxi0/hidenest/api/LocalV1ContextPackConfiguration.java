package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Explicit assembly for the synthetic, loopback-only Local V1 context pack retrieval vertical. */
@Configuration(proxyBeanMethods = false)
@Profile("local-v1-synthetic")
public class LocalV1ContextPackConfiguration {

    @Bean
    LocalV1ContextPackCoordinator localV1ContextPackCoordinator(
            LocalV1VectorCoordinator vector,
            LocalV1S2BQueryCoordinator s2b,
            JooqMemoryReadAdapter memoryRead,
            JooqRuntimeTransactionAdapter runtimeTx,
            JooqRuntimeQueryAdapter runtimeQuery,
            SpringTransactionExecutor tx,
            Clock clock) {
        return new LocalV1ContextPackCoordinator(vector, s2b, memoryRead, runtimeTx, runtimeQuery, tx, clock);
    }
}
