package io.github.candyxi0.hidenest.memory.v2;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/** Internal, direct-only lookup; callers retain the requested identity. */
public record RecordSuccession(
        UUID predecessorRecordId,
        UUID predecessorRevisionId,
        UUID successorRecordId,
        UUID successorRevisionId,
        UUID taskId,
        OffsetDateTime createdAt) {
    public interface Reader {
        Optional<RecordSuccession> findDirectSuccessor(UUID predecessorRecordId) throws java.sql.SQLException;
    }
}
