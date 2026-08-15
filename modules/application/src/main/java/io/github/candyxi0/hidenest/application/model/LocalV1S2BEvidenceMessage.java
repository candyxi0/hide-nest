package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Safe evidence output: no object reference, hash, path, or storage policy fields. */
public record LocalV1S2BEvidenceMessage(
        UUID anchorId,
        UUID sourceUnitId,
        Long ordinal,
        UUID actorId,
        String actorKind,
        String actorStableRef,
        String displayLabel,
        OffsetDateTime occurredAt,
        String bodyText) {}
