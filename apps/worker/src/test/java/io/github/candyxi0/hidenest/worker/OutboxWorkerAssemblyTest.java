package io.github.candyxi0.hidenest.worker;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.outbox.OutboxEffectHandler;
import io.github.candyxi0.hidenest.application.outbox.ProcessedEffect;
import io.github.candyxi0.hidenest.runtime.domain.ClaimedOutboxEvent;
import io.github.candyxi0.hidenest.runtime.domain.CompletionGuardResult;
import io.github.candyxi0.hidenest.runtime.port.CompletionGuardPort;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

class OutboxWorkerAssemblyTest {

    // T01: Use real HideNestWorkerApplication as source for both tests.
    // Provide a DataSource URL so the full Spring context can bootstrap
    // (the PostgreSQL test container is already running from database tests).

    @Test
    @DisplayName("T01: default disabled — real app starts, no worker beans")
    void defaultDisabledRealAppStarts() {
        try (var ctx = new SpringApplicationBuilder(HideNestWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--spring.datasource.url=jdbc:tc:postgresql:18:///hide_nest?TC_DAEMON=true",
                     "--spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
                     "--spring.sql.init.mode=never")) {
            assertNotNull(ctx);
            assertFalse(ctx.containsBean("workerStartupGuard"));
            assertFalse(ctx.containsBean("handlerRegistry"));
            assertFalse(ctx.containsBean("workerRunner"));
            assertFalse(ctx.containsBean("workerCoordinator"));
            assertFalse(ctx.containsBean("backoffCalculator"));
        }
    }

    @Test
    @DisplayName("T01: explicit enabled without handler/guard — context fails with guard cause")
    void explicitEnabledFailsWithCorrectCause() {
        try {
            // Use command-line args (highest priority) to override application.properties default
            try (var ctx = new SpringApplicationBuilder(HideNestWorkerApplication.class)
                    .web(WebApplicationType.NONE)
                    .run("--spring.datasource.url=jdbc:tc:postgresql:18:///hide_nest?TC_DAEMON=true",
                         "--spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
                         "--spring.sql.init.mode=never",
                         "--hide.outbox.worker.enabled=true")) {
                String val = ctx.getEnvironment().getProperty("hide.outbox.worker.enabled");
                fail("Context should not start without handler/guard. Property=" + val
                     + ", workerStartupGuard=" + ctx.containsBean("workerStartupGuard"));
            }
        } catch (Exception ex) {
            Throwable cause = ex; boolean found = false;
            while (cause != null) {
                String msg = cause.getMessage();
                if (msg != null && (msg.contains("no OutboxEffectHandler")
                        || msg.contains("no CompletionGuardPort"))) { found = true; break; }
                cause = cause.getCause();
            }
            assertTrue(found, "must fail due to handler/guard. Got: " + ex.getMessage());
        }
    }

    // validateDependencies unit tests (retained per T01 allowance)

    @Test @DisplayName("validateDependencies — empty handler")
    void validateDependenciesEmptyHandler() {
        var ex = assertThrows(IllegalStateException.class, () ->
                OutboxWorkerConfiguration.validateDependencies(List.of(), List.of()));
        assertTrue(ex.getMessage().contains("no OutboxEffectHandler"));
    }

    @Test @DisplayName("validateDependencies — empty guard")
    void validateDependenciesEmptyGuard() {
        var h = new OutboxEffectHandler() {
            public boolean supports(String et) { return false; }
            public ProcessedEffect process(ClaimedOutboxEvent e) { return null; }
        };
        var ex = assertThrows(IllegalStateException.class, () ->
                OutboxWorkerConfiguration.validateDependencies(List.of(h), List.of()));
        assertTrue(ex.getMessage().contains("no CompletionGuardPort"));
    }

    @Test @DisplayName("validateDependencies — passes 1+1")
    void validateDependenciesPasses() {
        CompletionGuardPort g = (eid, ak, aid, ar, purp) -> new CompletionGuardResult.Pass();
        var h = new OutboxEffectHandler() {
            public boolean supports(String et) { return false; }
            public ProcessedEffect process(ClaimedOutboxEvent e) { return null; }
        };
        assertDoesNotThrow(() ->
                OutboxWorkerConfiguration.validateDependencies(List.of(h), List.of(g)));
    }
}
