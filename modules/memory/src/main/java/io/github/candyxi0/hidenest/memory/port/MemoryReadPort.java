package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import java.util.List;
import java.util.UUID;

/** Read-only memory port. It exposes no database or adapter types. */
public interface MemoryReadPort {

    /** Find current records using the supplied bounded filter and fixed adapter ordering. */
    List<MemoryRecord> listCurrentMemoryRecords(MemoryReadFilter filter);

    /** Find a record by id without locking or changing it. */
    MemoryRecord findMemoryRecordById(UUID memoryId);

    /** Follow memory_record.current_revision_id; never infer a revision with MAX(revision_no). */
    MemoryRevision findCurrentRevisionByMemoryId(UUID memoryId);

    /** Find relations originating from the supplied current revision. */
    List<MemoryRelation> findRelationsByFromRevisionId(UUID revisionId);

    /** Find the persisted actor identity by primary key. */
    ActorRef findActorRefById(UUID actorId);
}
