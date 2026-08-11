package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.CompletionGuardResult;
import java.util.UUID;

/**
 * Re-check preconditions before committing consumer effects (R1-04).
 * Called AFTER claim + business processing, BEFORE settlement.
 * Only the port is defined here; real block/fence adapters are deferred
 * to HDM-017/018. In Slice D2, only a test double is provided.
 */
public interface CompletionGuardPort {

    CompletionGuardResult check(
            UUID eventId,
            String aggregateKind,
            UUID aggregateId,
            Long aggregateRevision,
            String purpose);
}
