package io.github.candyxi0.hidenest.security.port;

import io.github.candyxi0.hidenest.security.domain.CapabilityRef;
import java.util.UUID;

/**
 * Minimal seam for capability atomic consumption.
 * Full implementation deferred to HDM-008.
 * No production allow-implementation exists in this slice.
 */
public interface CapabilitySeamPort {

    /** Always true in this slice. */
    boolean seamOnly();

    /**
     * Verify and lock capability within the current transaction.
     * Must be called inside a transaction. Business effects must be
     * in the same transaction. Fail-closed: always throws.
     */
    CapabilityRef lockAndVerifyCapability(String capabilityHash, String purpose,
            UUID targetId, Long targetRevisionRef, Long policyRevisionNo);
}
