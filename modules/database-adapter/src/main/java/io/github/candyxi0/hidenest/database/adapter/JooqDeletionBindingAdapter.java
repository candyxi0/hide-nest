package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION;

import io.github.candyxi0.hidenest.memory.port.DeletionBindingPort;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;

/** PostgreSQL/jOOQ read-only implementation of the deletion binding seam. */
public final class JooqDeletionBindingAdapter implements DeletionBindingPort {

    private final DSLContext dsl;

    public JooqDeletionBindingAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public MemoryFacts readMemoryFacts(UUID memoryId) {
        Objects.requireNonNull(memoryId, "memoryId");
        var row = dsl.select(
                        MEMORY_REVISION.REVISION_NO,
                        MEMORY_REVISION.PERSPECTIVE_ACTOR_ID,
                        MEMORY_RECORD.CURRENT_POLICY_REVISION_NO)
                .from(MEMORY_RECORD)
                .join(MEMORY_REVISION)
                .on(MEMORY_REVISION.MEMORY_REVISION_ID.eq(MEMORY_RECORD.CURRENT_REVISION_ID))
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .fetchOne();
        if (row == null) {
            return null;
        }
        return new MemoryFacts(
                row.value1(), row.value3(), row.value2());
    }

    @Override
    public ClosureBinding readClosureBinding(UUID closureId) {
        Objects.requireNonNull(closureId, "closureId");
        var row = dsl.selectFrom(DELETION_CLOSURE)
                .where(DELETION_CLOSURE.CLOSURE_ID.eq(closureId))
                .fetchOne();
        if (row == null) {
            return null;
        }
        return new ClosureBinding(
                row.getClosureId(),
                row.getRootMemoryId(),
                row.getRootRevisionNo(),
                row.getRootPolicyRevisionNo(),
                row.getRequestHash(),
                row.getPreviewRevision(),
                row.getManifestHash(),
                row.getState());
    }
}
