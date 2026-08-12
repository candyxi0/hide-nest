package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Database-neutral boundary for immutable deletion-fence reads and controlled writes. */
public interface DeletionFencePort {

    void insertFences(List<FenceDraft> drafts);

    boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef);

    List<DeletionFence> findByClosureId(UUID closureId);

    record FenceDraft(
            UUID fenceId,
            UUID closureId,
            String targetKind,
            UUID targetId,
            Long targetRevisionRef,
            UUID createdByDecisionId,
            OffsetDateTime createdAt) {}
}
