package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import java.time.Clock;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Synthetic-only fixture assembly. This configuration is intentionally isolated from the
 * {@code local-private} profile so that fixture beans do not exist when the API runs with real
 * private data.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-v1-synthetic")
public class LocalV1SyntheticFixtureConfiguration {

    @Bean
    LocalV1SharedEvidenceFixtureCoordinator localV1SharedEvidenceFixtureCoordinator(
            LocalV1S1WindowCloseCoordinator s1,
            DSLContext dsl,
            PayloadStore payloadStore,
            Clock clock) {
        return new LocalV1SharedEvidenceFixtureCoordinator(s1, dsl, payloadStore, clock);
    }
}
