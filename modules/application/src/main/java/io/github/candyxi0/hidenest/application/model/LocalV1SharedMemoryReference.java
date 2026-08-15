package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/** A normative memory that legitimately shares the target's evidence, with its authorized title. */
public record LocalV1SharedMemoryReference(
        UUID memoryId,
        Long revisionNo,
        String title) {}
