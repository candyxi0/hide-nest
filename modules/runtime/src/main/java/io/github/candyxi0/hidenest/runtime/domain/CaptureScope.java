package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CaptureScope(
        UUID scopeId,
        UUID sourceId,
        Long fromOrdinal,
        Long toOrdinal,
        String ruleVersion,
        String coverageCode,
        OffsetDateTime frozenAt,
        byte[] manifestHash,
        OffsetDateTime createdAt) {}
