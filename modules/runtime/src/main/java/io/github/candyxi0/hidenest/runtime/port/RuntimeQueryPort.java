package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.Checkpoint;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.ConsumerEffect;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.ContextPackDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.ModelRun;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.domain.WorkArtifact;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only queries for runtime domain objects. */
public interface RuntimeQueryPort {

    /** Find capture scope by primary key. */
    CaptureScope findCaptureScopeById(UUID scopeId);

    /** Find capture scope units by scope id. */
    List<CaptureScopeUnit> findCaptureScopeUnitsByScopeId(UUID scopeId);

    /** Find closeout run by primary key. */
    CloseoutRun findCloseoutRunById(UUID runId);

    /** Find closeout run by permanent submission id (unique binding). */
    CloseoutRun findCloseoutRunBySubmissionId(UUID submissionId);

    /** Find closeout runs by scope id. */
    List<CloseoutRun> findCloseoutRunsByScopeId(UUID scopeId);

    /** Find checkpoint by primary key. */
    Checkpoint findCheckpointById(UUID checkpointId);

    /** Find checkpoints by run kind and run id, ordered by sequence_no descending. */
    List<Checkpoint> findCheckpointsByRunKindAndRunId(String runKind, UUID runId);

    /** Find work artifact by primary key. */
    WorkArtifact findWorkArtifactById(UUID artifactId);

    /** Find work artifacts by run id. */
    List<WorkArtifact> findWorkArtifactsByRunId(UUID runId);

    /** Find model run by primary key. */
    ModelRun findModelRunById(UUID modelRunId);

    /** Find retrieval trace by primary key. */
    RetrievalTrace findRetrievalTraceById(UUID traceId);

    /** Find context delivery by primary key. */
    ContextDelivery findContextDeliveryById(UUID deliveryId);

    /** Find context pack delivery items by delivery id, ordered by ordinal ascending. */
    List<ContextPackDeliveryItem> findContextPackDeliveryItemsByDeliveryId(UUID deliveryId);

    /**
     * Return the distinct memory ids delivered in this thread within the half-open interval
     * {@code (cutoff, now]} for {@code purpose = CONTEXT_PACK}. A memory delivered in the same
     * thread is cooled until the cutoff; other threads, other purposes and deliveries outside the
     * interval are not returned.
     *
     * <p>Only the memory identity matters: query, score, revision number and memory type do not
     * affect the result, and a later revision of the same memory is still the same memory id.
     * Permanently deleted memories whose revision can no longer be joined to
     * {@code memory.memory_revision} are naturally absent and must not raise an error.</p>
     *
     * @param threadId the thread whose delivered memory ids are sought; must be non-null
     * @param cutoff the lower bound, exclusive ({@code delivered_at > cutoff}); must be non-null
     * @param now the upper bound, inclusive ({@code delivered_at <= now}); must be non-null
     * @throws NullPointerException if {@code threadId} is null
     * @throws IllegalArgumentException if {@code cutoff} or {@code now} is null
     */
    Set<UUID> findDeliveredMemoryIdsSince(UUID threadId, OffsetDateTime cutoff, OffsetDateTime now);

    /** Check if a consumer effect exists. */
    boolean existsConsumerEffect(String consumerCode, UUID eventId, String effectKey);

    /** Find consumer effect by key. */
    ConsumerEffect findConsumerEffectByKey(String consumerCode, UUID eventId, String effectKey);
}
