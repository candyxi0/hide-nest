package io.github.candyxi0.hidenest.runtime.domain;

public enum FormationSettlementOutcome {
    COMMITTED_WRITE,
    COMMITTED_NO_CHANGE,
    IDEMPOTENT_REPLAY,
    INVALID_RESULT,
    SLOT_ALREADY_SETTLED,
    ATTEMPT_NOT_ACCEPTED,
    IDEMPOTENCY_CONFLICT,
    RETRY_WAIT,
    ATTENTION_REQUIRED
}
