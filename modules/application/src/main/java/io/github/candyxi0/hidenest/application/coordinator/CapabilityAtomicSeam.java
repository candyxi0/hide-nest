package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import io.github.candyxi0.hidenest.security.domain.CapabilityRef;
import io.github.candyxi0.hidenest.security.port.CapabilitySeamPort;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Minimal seam for capability atomic consumption.
 * Full implementation deferred to HDM-008.
 * No production allow-implementation exists; always fails closed.
 */
public class CapabilityAtomicSeam {

    private final CapabilitySeamPort capabilityPort;
    private final TransactionExecutor tx;

    public CapabilityAtomicSeam(CapabilitySeamPort capabilityPort, TransactionExecutor tx) {
        this.capabilityPort = capabilityPort;
        this.tx = tx;
    }

    public boolean isSeamOnly() {
        return capabilityPort.seamOnly();
    }

    /**
     * Execute business action with capability verification in one transaction.
     * capability lock/verify/consume and business effect share the same tx boundary.
     * If business effect fails, capability must not be left consumed.
     */
    public <T> T executeWithCapability(String capabilityHash, String purpose,
            UUID targetId, Long targetRevisionRef, Long policyRevisionNo,
            Supplier<T> businessEffect) {
        return tx.executeInTransaction(() -> {
            capabilityPort.lockAndVerifyCapability(
                    capabilityHash, purpose, targetId, targetRevisionRef, policyRevisionNo);
            return businessEffect.get();
        });
    }
}
