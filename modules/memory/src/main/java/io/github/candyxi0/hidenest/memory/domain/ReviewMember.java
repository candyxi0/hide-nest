package io.github.candyxi0.hidenest.memory.domain;

import java.util.UUID;

public record ReviewMember(
        UUID reviewSessionId,
        UUID proposalRevisionId,
        Long ordinal) {}
