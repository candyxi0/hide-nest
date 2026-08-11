package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Set;

/**
 * Sealed result of STALE/DENIED one-shot termination (R1-04).
 * Only legal combinations are constructible.
 */
public sealed interface OutboxTerminalSettlement
        permits OutboxTerminalSettlement.Rejected,
                OutboxTerminalSettlement.AlreadyTerminal,
                OutboxTerminalSettlement.LeaseLost {

    Set<String> ALLOWED_OBSERVED_STATES = Set.of("UNKNOWN", "READY", "LEASED", "SUCCEEDED", "FINAL_FAILED");

    /** Event terminated to FINAL_FAILED. */
    record Rejected() implements OutboxTerminalSettlement {
        public String outcome() { return "REJECTED"; }
        public String state() { return "FINAL_FAILED"; }
    }

    /** Event was already in a terminal state. */
    record AlreadyTerminal(String state) implements OutboxTerminalSettlement {
        public AlreadyTerminal {
            if (state == null || state.isBlank()) {
                throw new NullPointerException("state must not be blank for ALREADY_TERMINAL");
            }
            if (!state.equals("SUCCEEDED") && !state.equals("FINAL_FAILED")) {
                throw new IllegalArgumentException("ALREADY_TERMINAL state must be SUCCEEDED or FINAL_FAILED, got " + state);
            }
        }
        public String outcome() { return "ALREADY_TERMINAL"; }
    }

    /** Lease not matched. */
    record LeaseLost(String observedState) implements OutboxTerminalSettlement {
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
        }
        public String outcome() { return "LEASE_LOST"; }
        public String state() { return observedState; }
    }
}
