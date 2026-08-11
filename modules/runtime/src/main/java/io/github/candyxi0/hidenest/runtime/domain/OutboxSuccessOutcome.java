package io.github.candyxi0.hidenest.runtime.domain;

/** Three-way result for success settlement (R1-02). */
public enum OutboxSuccessOutcome {
    /** First-time settlement: ConsumerEffect inserted, event transitioned to SUCCEEDED. */
    SETTLED,
    /** Idempotent replay: ConsumerEffect already exists, event already SUCCEEDED. */
    ALREADY_SETTLED,
    /** Lease not matched: wrong owner, expired lease reclaimed by another worker, or state changed. */
    LEASE_LOST
}
