package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1BubbleCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1BubblePolicy;
import io.github.candyxi0.hidenest.database.adapter.JooqBubbleStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit, fail-closed Bubble V1 assembly for the two controlled local profiles. */
@Configuration(proxyBeanMethods = false)
@Profile({"local-v1-synthetic", "local-private"})
public class LocalV1BubbleConfiguration {

    @Bean
    JooqBubbleStoreAdapter jooqBubbleStoreAdapter(DSLContext dsl) {
        return new JooqBubbleStoreAdapter(dsl);
    }

    @Bean
    LocalV1BubblePolicy localV1BubblePolicy(Environment environment) {
        String spaceKey = environment.getRequiredProperty("hidenest.bubble.default-space-key");
        String configuredScore = environment.getRequiredProperty("hidenest.bubble.min-score");
        double minScore;
        try {
            minScore = Double.parseDouble(configuredScore);
        } catch (RuntimeException exception) {
            throw new IllegalStateException("Bubble min score is invalid", exception);
        }
        return new LocalV1BubblePolicy(spaceKey, minScore, LocalV1BubbleCoordinator.POLICY_VERSION);
    }

    @Bean
    LocalV1BubbleCoordinator localV1BubbleCoordinator(
            LocalV1VectorCoordinator vector,
            LocalV1S2BQueryCoordinator s2b,
            JooqMemoryReadAdapter memoryRead,
            JooqBubbleStoreAdapter bubbleStore,
            SpringTransactionExecutor transactions,
            Clock clock,
            LocalV1BubblePolicy policy) {
        return new LocalV1BubbleCoordinator(
                vector, s2b, memoryRead, bubbleStore, bubbleStore, transactions, clock, policy);
    }
}
