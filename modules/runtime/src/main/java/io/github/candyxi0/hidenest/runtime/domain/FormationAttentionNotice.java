package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One generic read-only notice per stalled task. */
public record FormationAttentionNotice(UUID taskId, OffsetDateTime createdAt, String text) {}
