package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Set;

/**
 * Sealed result of atomic failure settlement (R1-04).
 * Only legal combinations are constructible.
 */
public sealed interface OutboxFailureSettlement
        permits OutboxFailureSettlement.RetryScheduled,
                OutboxFailureSettlement.FinalFailed,
                OutboxFailureSettlement.Terminal,
                OutboxFailureSettlement.LeaseLost {

    /** New attempt 1-7: event back to READY with backoff. */
    record RetryScheduled(short attemptCount) implements OutboxFailureSettlement {
        public RetryScheduled {
            if (attemptCount < 1 || attemptCount > 7) {
                throw new IllegalArgumentException("attemptCount must be 1-7 for RETRY_SCHEDULED, got " + attemptCount);
            }
        }
        public String outcome() { return "RETRY_SCHEDULED"; }
        public String resultingState() { return "READY"; }
    }

    /** New attempt 8: event enters FINAL_FAILED. */
    record FinalFailed(short attemptCount) implements OutboxFailureSettlement {
        public FinalFailed {
            if (attemptCount != 8) {
                throw new IllegalArgumentException("attemptCount must be 8 for FINAL_FAILED, got " + attemptCount);
            }
        }
        public String outcome() { return "FINAL_FAILED"; }
        public String resultingState() { return "FINAL_FAILED"; }
    }

    /** Already in terminal state (SUCCEEDED or FINAL_FAILED). */
    record Terminal(String state, short attemptCount) implements OutboxFailureSettlement {
        public Terminal {
            if (state == null || state.isBlank()) {
                throw new NullPointerException("state must not be blank for TERMINAL");
            }
            if (!state.equals("SUCCEEDED") && !state.equals("FINAL_FAILED")) {
                throw new IllegalArgumentException("TERMINAL state must be SUCCEEDED or FINAL_FAILED, got " + state);
            }
            if (attemptCount < 0 || attemptCount > 8) {
                throw new IllegalArgumentException("attemptCount must be 0-8, got " + attemptCount);
            }
        }
        public String outcome() { return "TERMINAL"; }
        public String resultingState() { return state; }
    }

    Set<String> ALLOWED_OBSERVED_STATES =
            Set.of("UNKNOWN", "READY", "LEASED", "SUCCEEDED", "FINAL_FAILED");

    /** Lease not matched (wrong owner or expired). */
    record LeaseLost(String observedState, short attemptCount) implements OutboxFailureSettlement {
        public LeaseLost {
            if (observedState == null) {
                throw new NullPointerException("observedState must not be null for LEASE_LOST");
            }
            if (observedState.isBlank()) {
                throw new IllegalArgumentException("observedState must not be blank for LEASE_LOST");
            }
            if (!ALLOWED_OBSERVED_STATES.contains(observedState)) {
                throw new IllegalArgumentException(
                        "observedState must be one of " + ALLOWED_OBSERVED_STATES + ", got " + observedState);
            }
            if (attemptCount < 0 || attemptCount > 8) {
                throw new IllegalArgumentException("attemptCount must be 0-8, got " + attemptCount);
            }
        }
        public String outcome() { return "LEASE_LOST"; }
        public String resultingState() { return observedState; }
    }
}
