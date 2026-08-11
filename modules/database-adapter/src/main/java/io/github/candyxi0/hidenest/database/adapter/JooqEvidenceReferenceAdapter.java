package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.evidence.Tables.*;

import io.github.candyxi0.hidenest.evidence.domain.Source;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchor;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchorUnit;
import io.github.candyxi0.hidenest.evidence.domain.SourcePayload;
import io.github.candyxi0.hidenest.evidence.domain.SourceUnit;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqEvidenceReferenceAdapter implements EvidenceReferencePort {

    private final DSLContext dsl;

    public JooqEvidenceReferenceAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insertSource(Source source) {
        dsl.insertInto(SOURCE)
                .set(SOURCE.SOURCE_ID, source.sourceId())
                .set(SOURCE.SOURCE_KIND, source.sourceKind())
                .set(SOURCE.PLATFORM, source.platform())
                .set(SOURCE.EXTERNAL_REF, source.externalRef())
                .set(SOURCE.OBSERVED_ACCESSIBLE, source.observedAccessible())
                .set(SOURCE.COMPRESSED_OBSERVED, source.compressedObserved())
                .set(SOURCE.POLICY_ID, source.policyId())
                .set(SOURCE.CREATED_AT, source.createdAt())
                .set(SOURCE.INGESTED_AT, source.ingestedAt())
                .execute();
    }

    @Override
    public Source findSourceById(UUID sourceId) {
        var r = dsl.selectFrom(SOURCE)
                .where(SOURCE.SOURCE_ID.eq(sourceId))
                .fetchOne();
        if (r == null) return null;
        return new Source(
                r.getSourceId(), r.getSourceKind(), r.getPlatform(), r.getExternalRef(),
                r.getObservedAccessible(), r.getCompressedObserved(), r.getPolicyId(),
                r.getCreatedAt(), r.getIngestedAt());
    }

    @Override
    public void insertSourceUnit(SourceUnit unit) {
        dsl.insertInto(SOURCE_UNIT)
                .set(SOURCE_UNIT.SOURCE_UNIT_ID, unit.sourceUnitId())
                .set(SOURCE_UNIT.SOURCE_ID, unit.sourceId())
                .set(SOURCE_UNIT.EXTERNAL_UNIT_REF, unit.externalUnitRef())
                .set(SOURCE_UNIT.SOURCE_VERSION, unit.sourceVersion())
                .set(SOURCE_UNIT.ORDINAL, unit.ordinal())
                .set(SOURCE_UNIT.ACTOR_ID, unit.actorId())
                .set(SOURCE_UNIT.OCCURRED_AT, unit.occurredAt())
                .set(SOURCE_UNIT.CREATED_AT, unit.createdAt())
                .execute();
    }

    @Override
    public SourceUnit findSourceUnitById(UUID sourceUnitId) {
        var r = dsl.selectFrom(SOURCE_UNIT)
                .where(SOURCE_UNIT.SOURCE_UNIT_ID.eq(sourceUnitId))
                .fetchOne();
        if (r == null) return null;
        return new SourceUnit(
                r.getSourceUnitId(), r.getSourceId(), r.getExternalUnitRef(),
                r.getSourceVersion(), r.getOrdinal(), r.getActorId(),
                r.getOccurredAt(), r.getCreatedAt());
    }

    @Override
    public void insertSourcePayload(SourcePayload payload) {
        dsl.insertInto(SOURCE_PAYLOAD)
                .set(SOURCE_PAYLOAD.PAYLOAD_ID, payload.payloadId())
                .set(SOURCE_PAYLOAD.SOURCE_UNIT_ID, payload.sourceUnitId())
                .set(SOURCE_PAYLOAD.PAYLOAD_KIND, payload.payloadKind())
                .set(SOURCE_PAYLOAD.STORE_ADAPTER, payload.storeAdapter())
                .set(SOURCE_PAYLOAD.OBJECT_REF, payload.objectRef())
                .set(SOURCE_PAYLOAD.OBJECT_VERSION_REF, payload.objectVersionRef())
                .set(SOURCE_PAYLOAD.CONTENT_TYPE, payload.contentType())
                .set(SOURCE_PAYLOAD.SIZE_BYTES, payload.sizeBytes())
                .set(SOURCE_PAYLOAD.CONTENT_HASH, payload.contentHash())
                .set(SOURCE_PAYLOAD.POLICY_ID, payload.policyId())
                .set(SOURCE_PAYLOAD.CURRENT_POLICY_REVISION_NO, payload.currentPolicyRevisionNo())
                .set(SOURCE_PAYLOAD.RETENTION_CLASS, payload.retentionClass())
                .set(SOURCE_PAYLOAD.EXPIRES_AT, payload.expiresAt())
                .set(SOURCE_PAYLOAD.CREATED_AT, payload.createdAt())
                .execute();
    }

    @Override
    public SourcePayload findSourcePayloadById(UUID payloadId) {
        var r = dsl.selectFrom(SOURCE_PAYLOAD)
                .where(SOURCE_PAYLOAD.PAYLOAD_ID.eq(payloadId))
                .fetchOne();
        if (r == null) return null;
        return new SourcePayload(
                r.getPayloadId(), r.getSourceUnitId(), r.getPayloadKind(),
                r.getStoreAdapter(), r.getObjectRef(), r.getObjectVersionRef(),
                r.getContentType(), r.getSizeBytes(), r.getContentHash(),
                r.getPolicyId(), r.getCurrentPolicyRevisionNo(),
                r.getRetentionClass(), r.getExpiresAt(), r.getCreatedAt());
    }

    @Override
    public List<SourcePayload> findSourcePayloadsBySourceUnitId(UUID sourceUnitId) {
        var records = dsl.selectFrom(SOURCE_PAYLOAD)
                .where(SOURCE_PAYLOAD.SOURCE_UNIT_ID.eq(sourceUnitId))
                .orderBy(SOURCE_PAYLOAD.PAYLOAD_ID.asc())
                .fetch();
        List<SourcePayload> result = new ArrayList<>(records.size());
        for (var r : records) {
            result.add(new SourcePayload(
                    r.getPayloadId(), r.getSourceUnitId(), r.getPayloadKind(),
                    r.getStoreAdapter(), r.getObjectRef(), r.getObjectVersionRef(),
                    r.getContentType(), r.getSizeBytes(), r.getContentHash(),
                    r.getPolicyId(), r.getCurrentPolicyRevisionNo(),
                    r.getRetentionClass(), r.getExpiresAt(), r.getCreatedAt()));
        }
        return result;
    }

    @Override
    public void insertSourceAnchor(SourceAnchor anchor) {
        dsl.insertInto(SOURCE_ANCHOR)
                .set(SOURCE_ANCHOR.ANCHOR_ID, anchor.anchorId())
                .set(SOURCE_ANCHOR.SOURCE_ID, anchor.sourceId())
                .set(SOURCE_ANCHOR.ANCHOR_KIND, anchor.anchorKind())
                .set(SOURCE_ANCHOR.CREATED_AT, anchor.createdAt())
                .execute();
    }

    @Override
    public SourceAnchor findSourceAnchorById(UUID anchorId) {
        var r = dsl.selectFrom(SOURCE_ANCHOR)
                .where(SOURCE_ANCHOR.ANCHOR_ID.eq(anchorId))
                .fetchOne();
        if (r == null) return null;
        return new SourceAnchor(
                r.getAnchorId(), r.getSourceId(), r.getAnchorKind(), r.getCreatedAt());
    }

    @Override
    public void insertSourceAnchorUnits(List<SourceAnchorUnit> units) {
        if (units == null || units.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(SOURCE_ANCHOR_UNIT)
                .columns(SOURCE_ANCHOR_UNIT.ANCHOR_ID, SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID,
                        SOURCE_ANCHOR_UNIT.FROM_OFFSET, SOURCE_ANCHOR_UNIT.TO_OFFSET,
                        SOURCE_ANCHOR_UNIT.ORDINAL);
        for (var unit : units) {
            insert = insert.values(unit.anchorId(), unit.sourceUnitId(),
                    unit.fromOffset(), unit.toOffset(), unit.ordinal());
        }
        insert.execute();
    }

    @Override
    public List<SourceAnchorUnit> findSourceAnchorUnitsByAnchorId(UUID anchorId) {
        var records = dsl.selectFrom(SOURCE_ANCHOR_UNIT)
                .where(SOURCE_ANCHOR_UNIT.ANCHOR_ID.eq(anchorId))
                .orderBy(SOURCE_ANCHOR_UNIT.ORDINAL.asc())
                .fetch();
        List<SourceAnchorUnit> result = new ArrayList<>();
        for (var r : records) {
            result.add(new SourceAnchorUnit(
                    r.getAnchorId(), r.getSourceUnitId(),
                    r.getFromOffset(), r.getToOffset(), r.getOrdinal()));
        }
        return result;
    }

    @Override
    public void verifyAnchorsExist(Set<UUID> anchorIds) {
        if (anchorIds == null || anchorIds.isEmpty()) {
            return;
        }
        int found = dsl.selectCount()
                .from(SOURCE_ANCHOR)
                .where(SOURCE_ANCHOR.ANCHOR_ID.in(anchorIds))
                .fetchOne(0, int.class);
        if (found != anchorIds.size()) {
            throw new RuntimeException(
                    "CANONICAL_COMMIT_FAILED: one or more anchors not found");
        }
    }

    @Override
    public Source findSourceByExternalRef(String platform, String externalRef) {
        var r = dsl.selectFrom(SOURCE)
                .where(SOURCE.PLATFORM.eq(platform))
                .and(SOURCE.EXTERNAL_REF.eq(externalRef))
                .fetchOne();
        if (r == null) return null;
        return new Source(
                r.getSourceId(), r.getSourceKind(), r.getPlatform(), r.getExternalRef(),
                r.getObservedAccessible(), r.getCompressedObserved(), r.getPolicyId(),
                r.getCreatedAt(), r.getIngestedAt());
    }

    @Override
    public List<SourceAnchor> findSourceAnchorsBySourceId(UUID sourceId) {
        var records = dsl.selectFrom(SOURCE_ANCHOR)
                .where(SOURCE_ANCHOR.SOURCE_ID.eq(sourceId))
                .orderBy(SOURCE_ANCHOR.CREATED_AT.asc())
                .fetch();
        List<SourceAnchor> result = new ArrayList<>();
        for (var r : records) {
            result.add(new SourceAnchor(
                    r.getAnchorId(), r.getSourceId(), r.getAnchorKind(), r.getCreatedAt()));
        }
        return result;
    }
}
