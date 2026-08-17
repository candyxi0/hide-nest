package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import java.net.InetAddress;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit assembly for the loopback-only Local V1 read vertical. */
@Configuration(proxyBeanMethods = false)
@Profile({"local-v1-synthetic", "local-private"})
public class LocalV1ReadConfiguration {

    @Bean
    ApplicationRunner localV1SyntheticStartupGuard(Environment environment) {
        return arguments -> {
            requireLoopback(environment.getRequiredProperty("server.address"));
            requireHighEntropyToken(environment.getRequiredProperty("hidenest.local-v1.synthetic-token"));
            requireNonBlank(environment.getRequiredProperty("spring.datasource.url"), "datasource url");
            requireNonBlank(environment.getRequiredProperty("hidenest.local-v1.payload-root"), "payload root");
        };
    }

    @Bean
    JooqMemoryReadAdapter jooqMemoryReadAdapter(DSLContext dsl) {
        return new JooqMemoryReadAdapter(dsl);
    }

    @Bean
    JooqEvidenceReferenceAdapter jooqEvidenceReferenceAdapter(DSLContext dsl) {
        return new JooqEvidenceReferenceAdapter(dsl);
    }

    @Bean
    JooqDeletionFenceAdapter jooqDeletionFenceAdapter(DSLContext dsl) {
        return new JooqDeletionFenceAdapter(dsl);
    }

    @Bean
    LocalPayloadStore localPayloadStore(Environment environment) {
        String configured = environment.getRequiredProperty("hidenest.local-v1.payload-root");
        requireNonBlank(configured, "payload root");
        return new LocalPayloadStore(Path.of(configured));
    }

    @Bean
    LocalV1S2BQueryCoordinator localV1S2BQueryCoordinator(
            JooqMemoryReadAdapter memory,
            JooqEvidenceReferenceAdapter evidence,
            LocalPayloadStore payload,
            JooqDeletionFenceAdapter deletionFence) {
        return new LocalV1S2BQueryCoordinator(memory, evidence, payload, deletionFence);
    }

    static void requireLoopback(String address) {
        try {
            InetAddress resolved = InetAddress.getByName(requireNonBlank(address, "server address"));
            if (!resolved.isLoopbackAddress()) {
                throw new IllegalStateException("Local V1 API requires an explicit loopback server address");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("Local V1 API server address is invalid", exception);
        }
    }

    static void requireHighEntropyToken(String token) {
        String value = requireNonBlank(token, "token");
        if (value.length() < 43 || value.chars().distinct().count() < 16) {
            throw new IllegalStateException("Local V1 bearer does not meet the entropy floor");
        }
    }

    private static String requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required Local V1 " + label);
        }
        return value;
    }
}
