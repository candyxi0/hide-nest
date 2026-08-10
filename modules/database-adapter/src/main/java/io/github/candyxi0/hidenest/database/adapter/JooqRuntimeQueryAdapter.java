package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.runtime.Tables.*;

import io.github.candyxi0.hidenest.runtime.domain.CaptureScope;
import io.github.candyxi0.hidenest.runtime.domain.CaptureScopeUnit;
import io.github.candyxi0.hidenest.runtime.domain.Checkpoint;
import io.github.candyxi0.hidenest.runtime.domain.CloseoutRun;
import io.github.candyxi0.hidenest.runtime.domain.ConsumerEffect;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.ModelRun;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.domain.WorkArtifact;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqRuntimeQueryAdapter implements RuntimeQueryPort {

    private final DSLContext dsl;

    public JooqRuntimeQueryAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public CaptureScope findCaptureScopeById(UUID scopeId) {
        var r = dsl.selectFrom(CAPTURE_SCOPE)
                .where(CAPTURE_SCOPE.SCOPE_ID.eq(scopeId))
                .fetchOne();
        if (r == null) return null;
        return new CaptureScope(
                r.getScopeId(), r.getSourceId(), r.getFromOrdinal(), r.getToOrdinal(),
                r.getRuleVersion(), r.getCoverageCode(), r.getFrozenAt(),
                r.getManifestHash(), r.getCreatedAt());
    }

    @Override
    public List<CaptureScopeUnit> findCaptureScopeUnitsByScopeId(UUID scopeId) {
        var records = dsl.selectFrom(CAPTURE_SCOPE_UNIT)
                .where(CAPTURE_SCOPE_UNIT.SCOPE_ID.eq(scopeId))
                .orderBy(CAPTURE_SCOPE_UNIT.ORDINAL.asc())
                .fetch();
        List<CaptureScopeUnit> result = new ArrayList<>();
        for (var r : records) {
            result.add(new CaptureScopeUnit(
                    r.getScopeId(), r.getSourceUnitId(),
                    r.getOrdinal(), r.getExclusionReason()));
        }
        return result;
    }

    @Override
    public CloseoutRun findCloseoutRunById(UUID runId) {
        var r = dsl.selectFrom(CLOSEOUT_RUN)
                .where(CLOSEOUT_RUN.RUN_ID.eq(runId))
                .fetchOne();
        if (r == null) return null;
        return new CloseoutRun(
                r.getRunId(), r.getScopeId(), r.getState(), r.getRetryOf(),
                r.getSubmissionId(), r.getStartedAt(), r.getTerminalAt(),
                r.getFailureCode(), r.getCreatedAt());
    }

    @Override
    public List<CloseoutRun> findCloseoutRunsByScopeId(UUID scopeId) {
        var records = dsl.selectFrom(CLOSEOUT_RUN)
                .where(CLOSEOUT_RUN.SCOPE_ID.eq(scopeId))
                .orderBy(CLOSEOUT_RUN.CREATED_AT.asc(), CLOSEOUT_RUN.RUN_ID.asc())
                .fetch();
        List<CloseoutRun> result = new ArrayList<>();
        for (var r : records) {
            result.add(new CloseoutRun(
                    r.getRunId(), r.getScopeId(), r.getState(), r.getRetryOf(),
                    r.getSubmissionId(), r.getStartedAt(), r.getTerminalAt(),
                    r.getFailureCode(), r.getCreatedAt()));
        }
        return result;
    }

    @Override
    public Checkpoint findCheckpointById(UUID checkpointId) {
        var r = dsl.selectFrom(CHECKPOINT)
                .where(CHECKPOINT.CHECKPOINT_ID.eq(checkpointId))
                .fetchOne();
        if (r == null) return null;
        return new Checkpoint(
                r.getCheckpointId(), r.getRunKind(), r.getRunId(),
                r.getSequenceNo(), r.getManifestHash(), r.getObjectRef(),
                r.getCreatedAt());
    }

    @Override
    public List<Checkpoint> findCheckpointsByRunKindAndRunId(String runKind, UUID runId) {
        var records = dsl.selectFrom(CHECKPOINT)
                .where(CHECKPOINT.RUN_KIND.eq(runKind))
                .and(CHECKPOINT.RUN_ID.eq(runId))
                .orderBy(CHECKPOINT.SEQUENCE_NO.desc(), CHECKPOINT.CHECKPOINT_ID.asc())
                .fetch();
        List<Checkpoint> result = new ArrayList<>();
        for (var r : records) {
            result.add(new Checkpoint(
                    r.getCheckpointId(), r.getRunKind(), r.getRunId(),
                    r.getSequenceNo(), r.getManifestHash(), r.getObjectRef(),
                    r.getCreatedAt()));
        }
        return result;
    }

    @Override
    public WorkArtifact findWorkArtifactById(UUID artifactId) {
        var r = dsl.selectFrom(WORK_ARTIFACT)
                .where(WORK_ARTIFACT.ARTIFACT_ID.eq(artifactId))
                .fetchOne();
        if (r == null) return null;
        return new WorkArtifact(
                r.getArtifactId(), r.getRunId(), r.getArtifactKind(),
                r.getObjectRef(), r.getContentHash(), r.getExpiresAt(),
                r.getCreatedAt());
    }

    @Override
    public List<WorkArtifact> findWorkArtifactsByRunId(UUID runId) {
        var records = dsl.selectFrom(WORK_ARTIFACT)
                .where(WORK_ARTIFACT.RUN_ID.eq(runId))
                .orderBy(WORK_ARTIFACT.CREATED_AT.asc(), WORK_ARTIFACT.ARTIFACT_ID.asc())
                .fetch();
        List<WorkArtifact> result = new ArrayList<>();
        for (var r : records) {
            result.add(new WorkArtifact(
                    r.getArtifactId(), r.getRunId(), r.getArtifactKind(),
                    r.getObjectRef(), r.getContentHash(), r.getExpiresAt(),
                    r.getCreatedAt()));
        }
        return result;
    }

    @Override
    public ModelRun findModelRunById(UUID modelRunId) {
        var r = dsl.selectFrom(MODEL_RUN)
                .where(MODEL_RUN.MODEL_RUN_ID.eq(modelRunId))
                .fetchOne();
        if (r == null) return null;
        return new ModelRun(
                r.getModelRunId(), r.getRoleCode(), r.getProviderManifestId(),
                r.getState(), r.getInputManifestHash(), r.getOutputManifestHash(),
                r.getRetryOf(), r.getStartedAt(), r.getTerminalAt(),
                r.getFailureCode());
    }

    @Override
    public RetrievalTrace findRetrievalTraceById(UUID traceId) {
        var r = dsl.selectFrom(RETRIEVAL_TRACE)
                .where(RETRIEVAL_TRACE.TRACE_ID.eq(traceId))
                .fetchOne();
        if (r == null) return null;
        return new RetrievalTrace(
                r.getTraceId(), r.getRequestId(), r.getThreadId(), r.getTurnId(),
                r.getPurpose(), r.getResultCategory(), r.getPolicyRevisionSetHash(),
                r.getConsideredIds() != null
                        ? Arrays.asList(r.getConsideredIds()) : null,
                r.getDeliveredIds() != null
                        ? Arrays.asList(r.getDeliveredIds()) : null,
                r.getCreatedAt(), r.getExpiresAt());
    }

    @Override
    public ContextDelivery findContextDeliveryById(UUID deliveryId) {
        var r = dsl.selectFrom(CONTEXT_DELIVERY)
                .where(CONTEXT_DELIVERY.DELIVERY_ID.eq(deliveryId))
                .fetchOne();
        if (r == null) return null;
        return new ContextDelivery(
                r.getDeliveryId(), r.getRequestId(), r.getThreadId(), r.getTurnId(),
                r.getPurpose(), r.getPolicyRevisionSetHash(), r.getManifestHash(),
                r.getDeliveredAt(), r.getExpiresAt(),
                r.getInvalidatedAt(), r.getInvalidationReason());
    }

    @Override
    public boolean existsConsumerEffect(String consumerCode, UUID eventId, String effectKey) {
        int count = dsl.selectCount()
                .from(CONSUMER_EFFECT)
                .where(CONSUMER_EFFECT.CONSUMER_CODE.eq(consumerCode))
                .and(CONSUMER_EFFECT.EVENT_ID.eq(eventId))
                .and(CONSUMER_EFFECT.EFFECT_KEY.eq(effectKey))
                .fetchOne(0, int.class);
        return count > 0;
    }

    @Override
    public ConsumerEffect findConsumerEffectByKey(String consumerCode, UUID eventId, String effectKey) {
        var r = dsl.selectFrom(CONSUMER_EFFECT)
                .where(CONSUMER_EFFECT.CONSUMER_CODE.eq(consumerCode))
                .and(CONSUMER_EFFECT.EVENT_ID.eq(eventId))
                .and(CONSUMER_EFFECT.EFFECT_KEY.eq(effectKey))
                .fetchOne();
        if (r == null) return null;
        return new ConsumerEffect(
                r.getConsumerCode(), r.getEventId(), r.getEffectKey(),
                r.getRecordedAt());
    }
}
