package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_FENCE;

import io.github.candyxi0.hidenest.database.generated.memory.tables.records.DeletionFenceRecord;
import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;

/** PostgreSQL/jOOQ implementation of the deletion-fence seam. */
public final class JooqDeletionFenceAdapter implements DeletionFencePort {

    private final DSLContext dsl;

    public JooqDeletionFenceAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public void insertFences(List<FenceDraft> drafts) {
        Objects.requireNonNull(drafts, "drafts");
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("drafts must not be empty");
        }
        List<DeletionFenceRecord> records = new ArrayList<>(drafts.size());
        for (FenceDraft draft : drafts) {
            validateDraft(draft);
            records.add(new DeletionFenceRecord(
                    draft.fenceId(), draft.closureId(), draft.targetKind(), draft.targetId(),
                    draft.targetRevisionRef(), draft.createdByDecisionId(), draft.createdAt()));
        }
        dsl.batchInsert(records).execute();
    }

    @Override
    public boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef) {
        String normalizedTargetKind = validateTargetKind(targetKind);
        Objects.requireNonNull(targetId, "targetId");
        validateTargetRevisionRef(targetRevisionRef);
        Condition revisionCondition = targetRevisionRef == null
                ? DELETION_FENCE.TARGET_REVISION_REF.isNull()
                : DELETION_FENCE.TARGET_REVISION_REF.eq(targetRevisionRef);
        return dsl.fetchExists(dsl.selectOne()
                .from(DELETION_FENCE)
                .where(DELETION_FENCE.TARGET_KIND.eq(normalizedTargetKind))
                .and(DELETION_FENCE.TARGET_ID.eq(targetId))
                .and(revisionCondition));
    }

    @Override
    public List<DeletionFence> findByClosureId(UUID closureId) {
        Objects.requireNonNull(closureId, "closureId");
        List<DeletionFence> result = new ArrayList<>();
        for (DeletionFenceRecord row : dsl.selectFrom(DELETION_FENCE)
                .where(DELETION_FENCE.CLOSURE_ID.eq(closureId))
                .orderBy(DELETION_FENCE.TARGET_KIND.asc(), DELETION_FENCE.TARGET_ID.asc(),
                        DELETION_FENCE.TARGET_REVISION_REF.asc().nullsFirst(), DELETION_FENCE.FENCE_ID.asc())
                .fetch()) {
            result.add(toDomain(row));
        }
        return result;
    }

    private static DeletionFence toDomain(DeletionFenceRecord row) {
        return new DeletionFence(row.getFenceId(), row.getClosureId(), row.getTargetKind(), row.getTargetId(),
                row.getTargetRevisionRef(), row.getCreatedByDecisionId(), row.getCreatedAt());
    }

    private static void validateDraft(FenceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(draft.fenceId(), "draft.fenceId");
        Objects.requireNonNull(draft.closureId(), "draft.closureId");
        validateTargetKind(draft.targetKind());
        Objects.requireNonNull(draft.targetId(), "draft.targetId");
        validateTargetRevisionRef(draft.targetRevisionRef());
        Objects.requireNonNull(draft.createdByDecisionId(), "draft.createdByDecisionId");
        Objects.requireNonNull(draft.createdAt(), "draft.createdAt");
    }

    private static String validateTargetKind(String targetKind) {
        Objects.requireNonNull(targetKind, "targetKind");
        String normalized = targetKind.trim().toUpperCase(Locale.ROOT);
        if (!targetKind.equals(normalized) || normalized.isEmpty()) {
            throw new IllegalArgumentException("targetKind must be a non-blank canonical kind");
        }
        return normalized;
    }

    private static void validateTargetRevisionRef(Long targetRevisionRef) {
        if (targetRevisionRef != null && targetRevisionRef < 1L) {
            throw new IllegalArgumentException("targetRevisionRef must be positive when present");
        }
    }
}
