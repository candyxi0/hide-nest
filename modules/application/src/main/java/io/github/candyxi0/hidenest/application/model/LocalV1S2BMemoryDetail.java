package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public record LocalV1S2BMemoryDetail(
        UUID memoryId,
        UUID currentRevisionId,
        String state,
        Long revisionNo,
        String memoryType,
        UUID perspectiveActorId,
        String bodyText,
        String uncertaintyCode,
        OffsetDateTime updatedAt,
        int evidenceCount) {}
