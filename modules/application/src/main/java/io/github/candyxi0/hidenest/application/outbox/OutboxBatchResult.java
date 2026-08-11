package io.github.candyxi0.hidenest.application.outbox;

import java.util.List;
import java.util.UUID;

/**
 * Count-only result of a bounded coordinator run (R1-02).
 * Invariant: claimed == settled + alreadySettled + retryScheduled + finalFailed + rejected + leaseLost.
 * handlerMissing and handlerAmbiguous are diagnostic only — not in the exclusive sum.
 */
public record OutboxBatchResult(
        int claimed,
        int settled,
        int alreadySettled,
        int retryScheduled,
        int finalFailed,
        int rejected,
        int leaseLost,
        int handlerMissing,
        int handlerAmbiguous,
        List<UUID> eventIds) {

    public OutboxBatchResult {
        if (claimed < 0) throw new IllegalArgumentException("claimed must be >= 0");
        if (settled < 0 || alreadySettled < 0 || retryScheduled < 0
                || finalFailed < 0 || rejected < 0 || leaseLost < 0
                || handlerMissing < 0 || handlerAmbiguous < 0) {
            throw new IllegalArgumentException("all counts must be non-negative");
        }
        int exclusive = settled + alreadySettled + retryScheduled + finalFailed + rejected + leaseLost;
        if (claimed != exclusive) {
            throw new IllegalArgumentException(
                    "claimed=" + claimed + " != sum of exclusive outcomes=" + exclusive);
        }
        if (eventIds == null) throw new NullPointerException("eventIds must not be null");
        if (eventIds.size() != claimed) {
            throw new IllegalArgumentException(
                    "eventIds.size()=" + eventIds.size() + " != claimed=" + claimed);
        }
        eventIds = List.copyOf(eventIds);
    }

    /** Sum of all exclusive terminal outcomes. */
    public int totalProcessed() {
        return settled + alreadySettled + retryScheduled + finalFailed + rejected + leaseLost;
    }

    /** Body-free: no payload, no exception text. */
    @Override
    public String toString() {
        return "OutboxBatchResult[claimed=" + claimed + ",settled=" + settled
                + ",alreadySettled=" + alreadySettled + ",retryScheduled=" + retryScheduled
                + ",finalFailed=" + finalFailed + ",rejected=" + rejected
                + ",leaseLost=" + leaseLost + ",handlerMissing=" + handlerMissing
                + ",handlerAmbiguous=" + handlerAmbiguous + "]";
    }

    public static OutboxBatchResult empty() {
        return new OutboxBatchResult(0, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
    }
}
