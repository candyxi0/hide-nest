package io.github.candyxi0.hidenest.evidence.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Source(
        UUID sourceId,
        String sourceKind,
        String platform,
        String externalRef,
        Boolean observedAccessible,
        Boolean compressedObserved,
        UUID policyId,
        OffsetDateTime createdAt,
        OffsetDateTime ingestedAt) {}
