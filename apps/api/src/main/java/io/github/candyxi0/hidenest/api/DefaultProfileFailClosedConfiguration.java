package io.github.candyxi0.hidenest.api;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** HDM-007/008 are unfinished, so there is no default-profile anonymous read surface. */
@Configuration(proxyBeanMethods = false)
@Profile("!local-v1-synthetic")
public class DefaultProfileFailClosedConfiguration {

    @Bean
    ApplicationRunner noProductionIdentityGateYet() {
        return arguments -> {
            throw new IllegalStateException(
                    "HDM-007/008 production identity gate is not available; API startup is fail closed");
        };
    }
}
