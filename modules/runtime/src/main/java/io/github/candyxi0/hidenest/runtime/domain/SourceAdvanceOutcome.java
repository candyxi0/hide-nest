package io.github.candyxi0.hidenest.runtime.domain;

/** Deterministic outcome of one source progress notification. */
public enum SourceAdvanceOutcome {
    CREATED,
    MERGED,
    HEARTBEAT_NOOP,
    EXACT_REPLAY,
    STALE_NOOP,
    CONFLICT,
    SOURCE_ORDER_INVALID
}
