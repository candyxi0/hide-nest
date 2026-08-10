package io.github.candyxi0.hidenest.evidence.domain;

import java.util.UUID;

public record SourceAnchorUnit(
        UUID anchorId,
        UUID sourceUnitId,
        Long fromOffset,
        Long toOffset,
        Long ordinal) {}
