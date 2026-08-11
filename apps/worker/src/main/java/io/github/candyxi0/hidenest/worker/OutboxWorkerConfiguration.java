package io.github.candyxi0.hidenest.worker;

import io.github.candyxi0.hidenest.application.outbox.BackoffCalculator;
import io.github.candyxi0.hidenest.application.outbox.OutboxEffectHandler;
import io.github.candyxi0.hidenest.application.outbox.OutboxHandlerRegistry;
import io.github.candyxi0.hidenest.application.outbox.OutboxWorkerCoordinator;
import io.github.candyxi0.hidenest.runtime.port.CompletionGuardPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.Clock;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default-disabled Worker assembly (R1-06).
 * hide.outbox.worker.enabled=false → no beans, no DB, no threads.
 * hide.outbox.worker.enabled=true → requires >=1 handler + exactly 1 guard.
 */
@Configuration
public class OutboxWorkerConfiguration {

    private final List<OutboxEffectHandler> handlers;
    private final List<CompletionGuardPort> guardPorts;

    public OutboxWorkerConfiguration(
            List<OutboxEffectHandler> handlers,
            List<CompletionGuardPort> guardPorts) {
        this.handlers = handlers;
        this.guardPorts = guardPorts;
    }

    static void validateDependencies(
            List<OutboxEffectHandler> handlers, List<CompletionGuardPort> guardPorts) {
        if (handlers.isEmpty())
            throw new IllegalStateException(
                    "hide.outbox.worker.enabled=true but no OutboxEffectHandler beans registered");
        if (guardPorts.isEmpty())
            throw new IllegalStateException(
                    "hide.outbox.worker.enabled=true but no CompletionGuardPort bean registered");
        if (guardPorts.size() > 1)
            throw new IllegalStateException(
                    "hide.outbox.worker.enabled=true but multiple CompletionGuardPort beans registered");
    }

    @Bean
    @ConditionalOnProperty(prefix = "hide.outbox.worker", name = "enabled", havingValue = "true", matchIfMissing = false)
    public Object workerStartupGuard() {
        validateDependencies(handlers, guardPorts);
        return new Object();
    }

    @Bean
    @ConditionalOnProperty(prefix = "hide.outbox.worker", name = "enabled", havingValue = "true", matchIfMissing = false)
    public OutboxHandlerRegistry handlerRegistry() {
        return new OutboxHandlerRegistry(handlers);
    }

    @Bean
    @ConditionalOnProperty(prefix = "hide.outbox.worker", name = "enabled", havingValue = "true", matchIfMissing = false)
    public BackoffCalculator backoffCalculator() {
        return new BackoffCalculator(Clock.systemUTC(), RandomGenerator.getDefault());
    }

    @Bean
    @ConditionalOnProperty(prefix = "hide.outbox.worker", name = "enabled", havingValue = "true", matchIfMissing = false)
    public OutboxWorkerCoordinator workerCoordinator(
            RuntimeTransactionPort txPort,
            OutboxHandlerRegistry handlerRegistry,
            BackoffCalculator backoff) {
        // Pick exactly one guard from the list (validated by startupGuard)
        CompletionGuardPort guard = guardPorts.get(0);
        return new OutboxWorkerCoordinator(txPort, guard, handlerRegistry, backoff, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(prefix = "hide.outbox.worker", name = "enabled", havingValue = "true", matchIfMissing = false)
    public OutboxWorkerRunner workerRunner(OutboxWorkerCoordinator coordinator) {
        return new OutboxWorkerRunner(coordinator);
    }
}
