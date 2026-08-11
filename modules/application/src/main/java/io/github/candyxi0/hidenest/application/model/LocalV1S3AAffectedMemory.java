package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

public record LocalV1S3AAffectedMemory(
        UUID memoryId,
        String state,
        Long currentRevisionNo,
        String bodyPreview,
        int affectedPayloadCount) {}
