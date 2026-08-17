package io.github.candyxi0.hidenest.api;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Local-private-only startup guard. The bearer, datasource, payload root and loopback address are
 * validated by the shared {@link LocalV1ReadConfiguration}; this guard additionally requires a
 * high-entropy action capability so that the write paths cannot start in a degraded state.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-private")
public class LocalV1PrivateStartupGuardConfiguration {

    @Bean
    ApplicationRunner localV1PrivateStartupGuard(Environment environment) {
        return arguments -> {
            LocalV1ReadConfiguration.requireHighEntropyToken(
                    environment.getRequiredProperty("hidenest.local-v1.synthetic-capability"));
        };
    }
}
