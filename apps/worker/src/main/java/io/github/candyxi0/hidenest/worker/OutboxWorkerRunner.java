package io.github.candyxi0.hidenest.worker;

import io.github.candyxi0.hidenest.application.outbox.OutboxBatchResult;
import io.github.candyxi0.hidenest.application.outbox.OutboxWorkerCoordinator;
import java.time.Duration;
import java.util.UUID;

/**
 * Bounded single-run shell. No background thread, no polling loop, no scheduling.
 * Scheduling/lifecycle is deferred to deployment assembly (HDM-013+).
 */
public class OutboxWorkerRunner {

    private final OutboxWorkerCoordinator coordinator;

    public OutboxWorkerRunner(OutboxWorkerCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /**
     * Execute one bounded polling cycle.
     * Returns the batch result for observability.
     * Does not loop, does not sleep, does not spawn threads.
     */
    public OutboxBatchResult runOnce() {
        String leaseOwner = "worker-" + UUID.randomUUID().toString().substring(0, 12);
        return coordinator.runOnce(leaseOwner, Duration.ofMinutes(5), 50);
    }
}
