package io.github.candyxi0.hidenest.evidence.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceAnchor(
        UUID anchorId,
        UUID sourceId,
        String anchorKind,
        OffsetDateTime createdAt) {}
