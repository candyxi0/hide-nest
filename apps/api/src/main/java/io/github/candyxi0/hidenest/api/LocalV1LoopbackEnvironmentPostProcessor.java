package io.github.candyxi0.hidenest.api;

import java.net.InetAddress;
import java.util.Set;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Early startup validator for the Local V1 loopback-only profiles. Runs before the application
 * context refreshes and before the embedded web server binds, so a non-loopback address or missing
 * secrets fail closed before any socket is opened.
 */
public final class LocalV1LoopbackEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Set<String> LOCAL_V1_PROFILES = Set.of("local-v1-synthetic", "local-private");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!isLocalV1Active(environment)) {
            return;
        }
        boolean localPrivate = environment.matchesProfiles("local-private");
        requireLoopback(environment.getProperty("server.address"));
        requireHighEntropy(environment.getProperty("hidenest.local-v1.synthetic-token"), "bearer");
        if (localPrivate) {
            requireHighEntropy(environment.getProperty("hidenest.local-v1.synthetic-capability"), "action capability");
        }
        requireNonBlank(environment.getProperty("spring.datasource.url"), "datasource url");
        requireNonBlank(environment.getProperty("hidenest.local-v1.payload-root"), "payload root");
    }

    private static boolean isLocalV1Active(ConfigurableEnvironment environment) {
        for (String profile : environment.getActiveProfiles()) {
            if (LOCAL_V1_PROFILES.contains(profile)) {
                return true;
            }
        }
        return false;
    }

    private static void requireLoopback(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("Local V1 requires an explicit loopback server address");
        }
        try {
            InetAddress resolved = InetAddress.getByName(address);
            if (!resolved.isLoopbackAddress()) {
                throw new IllegalStateException("Local V1 server address must be loopback");
            }
        } catch (java.net.UnknownHostException exception) {
            throw new IllegalStateException("Local V1 server address is invalid", exception);
        }
    }

    private static void requireHighEntropy(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required Local V1 " + label);
        }
        if (value.length() < 43 || value.chars().distinct().count() < 16) {
            throw new IllegalStateException("Local V1 " + label + " does not meet the entropy floor");
        }
    }

    private static void requireNonBlank(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required Local V1 " + label);
        }
    }
}
