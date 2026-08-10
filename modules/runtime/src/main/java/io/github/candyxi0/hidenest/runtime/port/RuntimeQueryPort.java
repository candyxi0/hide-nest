package io.github.candyxi0.hidenest.runtime.port;

import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.Checkpoint;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.ConsumerEffect;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.ModelRun;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.domain.WorkArtifact;
import java.util.List;
import java.util.UUID;

/** Read-only queries for runtime domain objects. */
public interface RuntimeQueryPort {

    /** Find capture scope by primary key. */
    CaptureScope findCaptureScopeById(UUID scopeId);

    /** Find capture scope units by scope id. */
    List<CaptureScopeUnit> findCaptureScopeUnitsByScopeId(UUID scopeId);

    /** Find closeout run by primary key. */
    CloseoutRun findCloseoutRunById(UUID runId);

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

    /** Check if a consumer effect exists. */
    boolean existsConsumerEffect(String consumerCode, UUID eventId, String effectKey);

    /** Find consumer effect by key. */
    ConsumerEffect findConsumerEffectByKey(String consumerCode, UUID eventId, String effectKey);
}
