package io.github.candyxi0.hidenest.memory.port;

import java.util.UUID;

/**
 * Read-only seam the Local V1 deletion HTTP facade uses to bind a request to the exact
 * persisted preview snapshot before delegating to the existing deletion coordinators.
 *
 * <p>Neither method locks or mutates anything; the canonical coordinators re-verify every
 * binding fact inside their own transactions, so any race here is fail-closed downstream.</p>
 */
public interface DeletionBindingPort {

    /** Current canonical facts of a root memory, or {@code null} when it does not exist. */
    MemoryFacts readMemoryFacts(UUID memoryId);

    /** Immutable binding fields of a deletion closure, or {@code null} when it does not exist. */
    ClosureBinding readClosureBinding(UUID closureId);

    record MemoryFacts(long revisionNo, long policyRevisionNo, UUID perspectiveActorId) {}

    record ClosureBinding(
            UUID closureId,
            UUID rootMemoryId,
            long rootRevisionNo,
            long rootPolicyRevisionNo,
            byte[] requestHash,
            long previewRevision,
            byte[] manifestHash,
            String state) {
        public ClosureBinding {
            requestHash = requestHash == null ? null : requestHash.clone();
            manifestHash = manifestHash == null ? null : manifestHash.clone();
        }

        @Override
        public byte[] requestHash() {
            return requestHash == null ? null : requestHash.clone();
        }

        @Override
        public byte[] manifestHash() {
            return manifestHash == null ? null : manifestHash.clone();
        }
    }
}
