package io.github.candyxi0.hidenest.evidence.v2;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Trusted, bounded reread of an exact source range; never called inside a database transaction. */
@FunctionalInterface
public interface SourceResolver {
    VerifiedAnchor resolve(ResolutionRequest request, String locator);

    record ResolutionRequest(
            UUID sourceId,
            String sourceRef,
            String sourceVersion,
            PositionBoundary fromExclusive,
            PositionBoundary toInclusive,
            ReadBinding readBinding) {
        public ResolutionRequest {
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(sourceRef);
            Objects.requireNonNull(sourceVersion);
            Objects.requireNonNull(toInclusive);
            Objects.requireNonNull(readBinding);
        }
    }

    record VerifiedAnchor(
            UUID sourceId,
            String sourceRef,
            String sourceVersion,
            ReadBinding readBinding,
            String locator,
            String exactText,
            Long fromSequence,
            Long toSequence,
            String frame,
            String actor,
            String speakingAs) {
        public VerifiedAnchor {
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(sourceRef);
            Objects.requireNonNull(sourceVersion);
            Objects.requireNonNull(readBinding);
            Objects.requireNonNull(locator);
            Objects.requireNonNull(exactText);
        }
    }

    record PositionBoundary(long sequence, String cursor, String sourceVersion) {}

    record ReadBinding(String kind, String reference, String version, OffsetDateTime expiresAt) {}
}
