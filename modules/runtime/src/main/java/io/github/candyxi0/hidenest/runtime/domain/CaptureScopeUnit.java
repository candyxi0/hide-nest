package io.github.candyxi0.hidenest.runtime.domain;

import java.util.UUID;

public record CaptureScopeUnit(
        UUID scopeId,
        UUID sourceUnitId,
        Long ordinal,
        String exclusionReason) {}
