package io.github.candyxi0.hidenest.evidence.domain;

import java.util.UUID;

public record AnchorRef(
        UUID anchorId,
        UUID sourceId,
        String anchorKind) {}
