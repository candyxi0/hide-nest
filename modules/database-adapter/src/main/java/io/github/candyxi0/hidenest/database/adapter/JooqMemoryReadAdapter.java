package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.*;

import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.MemoryReadFilter;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/** PostgreSQL/jOOQ implementation of the read-only memory port. */
public class JooqMemoryReadAdapter implements MemoryReadPort {

    private static final char LIKE_ESCAPE = '!';

    private final DSLContext dsl;

    public JooqMemoryReadAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<MemoryRecord> listCurrentMemoryRecords(MemoryReadFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        validateFilter(filter);

        Condition where = DSL.noCondition();
        if (!"ALL".equals(filter.state())) {
            where = where.and(MEMORY_RECORD.STATE.eq(filter.state()));
        }

        if (filter.keyword() != null && !filter.keyword().isEmpty()) {
            String pattern = "%" + escapeLike(filter.keyword().toLowerCase(java.util.Locale.ROOT)) + "%";
            where = where.and(DSL.exists(
                    dsl.selectOne()
                            .from(MEMORY_REVISION)
                            .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(
                                    MEMORY_RECORD.CURRENT_REVISION_ID))
                            .and(MEMORY_REVISION.MEMORY_ID.eq(MEMORY_RECORD.MEMORY_ID))
                            .and(DSL.lower(MEMORY_REVISION.BODY_TEXT).like(pattern, LIKE_ESCAPE))));
        } else {
            // A current pointer must bind to a revision owned by the same memory.
            where = where.and(DSL.exists(
                    dsl.selectOne()
                            .from(MEMORY_REVISION)
                            .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(
                                    MEMORY_RECORD.CURRENT_REVISION_ID))
                            .and(MEMORY_REVISION.MEMORY_ID.eq(MEMORY_RECORD.MEMORY_ID))));
        }

        var records = dsl.selectFrom(MEMORY_RECORD)
                .where(where)
                .orderBy(MEMORY_RECORD.UPDATED_AT.desc(), MEMORY_RECORD.MEMORY_ID.asc())
                .limit(filter.limit())
                .offset(filter.offset())
                .fetch();

        List<MemoryRecord> result = new ArrayList<>(records.size());
        for (var record : records) {
            result.add(toMemoryRecord(record));
        }
        return result;
    }

    @Override
    public MemoryRecord findMemoryRecordById(UUID memoryId) {
        if (memoryId == null) {
            return null;
        }
        var record = dsl.selectFrom(MEMORY_RECORD)
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .fetchOne();
        return record == null ? null : toMemoryRecord(record);
    }

    @Override
    public MemoryRevision findCurrentRevisionByMemoryId(UUID memoryId) {
        if (memoryId == null) {
            return null;
        }
        var pointer = dsl.select(MEMORY_RECORD.CURRENT_REVISION_ID)
                .from(MEMORY_RECORD)
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .fetchOne();
        if (pointer == null || pointer.value1() == null) {
            return null;
        }
        var revision = dsl.selectFrom(MEMORY_REVISION)
                .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(pointer.value1()))
                .fetchOne();
        return revision == null ? null : toMemoryRevision(revision);
    }

    @Override
    public List<MemoryRelation> findRelationsByFromRevisionId(UUID revisionId) {
        if (revisionId == null) {
            return List.of();
        }
        var rows = dsl.selectFrom(MEMORY_RELATION)
                .where(MEMORY_RELATION.FROM_REVISION_ID.eq(revisionId))
                .orderBy(MEMORY_RELATION.CREATED_AT.asc(), MEMORY_RELATION.RELATION_ID.asc())
                .fetch();
        List<MemoryRelation> result = new ArrayList<>(rows.size());
        for (var row : rows) {
            result.add(new MemoryRelation(
                    row.getRelationId(), row.getFromRevisionId(), row.getRelationType(),
                    row.getToRevisionId(), row.getToAnchorId(), row.getPerspectiveActorId(),
                    row.getCreatedByDecisionId(), row.getCreatedAt()));
        }
        return result;
    }

    @Override
    public ActorRef findActorRefById(UUID actorId) {
        if (actorId == null) {
            return null;
        }
        var row = dsl.selectFrom(ACTOR_REF)
                .where(ACTOR_REF.ACTOR_ID.eq(actorId))
                .fetchOne();
        if (row == null) {
            return null;
        }
        return new ActorRef(
                row.getActorId(), row.getActorKind(), row.getStableRef(),
                row.getDisplayLabel(), row.getCreatedAt());
    }

    private static void validateFilter(MemoryReadFilter filter) {
        if (!("ALL".equals(filter.state())
                || "ACTIVE".equals(filter.state())
                || "ARCHIVED".equals(filter.state()))) {
            throw new IllegalArgumentException("unsupported memory state");
        }
        if (filter.keyword() == null
                || filter.keyword().codePoints().count() <= 100) {
            if (filter.limit() < 1 || filter.limit() > 50) {
                throw new IllegalArgumentException("limit out of range");
            }
            if (filter.offset() < 0) {
                throw new IllegalArgumentException("offset out of range");
            }
            return;
        }
        throw new IllegalArgumentException("keyword too long");
    }

    private static String escapeLike(String value) {
        return value.replace(String.valueOf(LIKE_ESCAPE), "!!")
                .replace("%", "!%")
                .replace("_", "!_")
                .replace("\\", "!\\");
    }

    private static MemoryRecord toMemoryRecord(
            io.github.candyxi0.hidenest.database.generated.memory.tables.records.MemoryRecordRecord row) {
        return new MemoryRecord(
                row.getMemoryId(), row.getState(), row.getCurrentRevisionId(), row.getPolicyId(),
                row.getCurrentPolicyRevisionNo(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private static MemoryRevision toMemoryRevision(
            io.github.candyxi0.hidenest.database.generated.memory.tables.records.MemoryRevisionRecord row) {
        return new MemoryRevision(
                row.getMemoryRevisionId(), row.getMemoryId(), row.getRevisionNo(),
                row.getMemoryType(), row.getPerspectiveActorId(), row.getBodyText(),
                row.getValidFrom(), row.getValidTo(), row.getUncertaintyCode(),
                row.getCreatedByDecisionId(), row.getCreatedAt());
    }
}
