package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_ANCHOR;
import static io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_ANCHOR_UNIT;
import static io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD;
import static io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_UNIT;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.ACCESS_POLICY;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.ACTOR_REF;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.ACCESS_POLICY_REVISION;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE_MEMBER;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RELATION;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION;

import io.github.candyxi0.hidenest.database.generated.memory.tables.records.DeletionClosureMemberRecord;
import io.github.candyxi0.hidenest.database.generated.memory.tables.records.DeletionClosureRecord;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicy;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicyRevision;
import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/** PostgreSQL/jOOQ implementation of the S3A preview storage boundary. */
public final class JooqDeletionPreviewAdapter implements DeletionPreviewPort {

    private final DSLContext dsl;

    public JooqDeletionPreviewAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public ExistingPreview findByIdempotencyKey(String idempotencyKey) {
        var closure = dsl.selectFrom(DELETION_CLOSURE)
                .where(DELETION_CLOSURE.REQUEST_IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOne();
        return closure == null ? null : toExisting(closure);
    }

    @Override
    public DeletionPreviewGraph lockAndReadGraph(UUID rootMemoryId) {
        if (rootMemoryId == null) throw new IllegalArgumentException("memoryId must not be null");

        var rootRow = dsl.selectFrom(MEMORY_RECORD)
                .where(MEMORY_RECORD.MEMORY_ID.eq(rootMemoryId))
                .forUpdate()
                .fetchOne();
        if (rootRow == null) throw new IllegalArgumentException("memory not found");

        MemoryRecord root = new MemoryRecord(
                rootRow.getMemoryId(), rootRow.getState(), rootRow.getCurrentRevisionId(),
                rootRow.getPolicyId(), rootRow.getCurrentPolicyRevisionNo(),
                rootRow.getCreatedAt(), rootRow.getUpdatedAt());
        var currentRow = dsl.selectFrom(MEMORY_REVISION)
                .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(root.currentRevisionId()))
                .fetchOne();
        if (currentRow == null || !rootMemoryId.equals(currentRow.getMemoryId())) {
            throw new IllegalStateException("current revision owner mismatch");
        }
        MemoryRevision current = toMemoryRevision(currentRow);

        var policyRow = dsl.selectFrom(ACCESS_POLICY)
                .where(ACCESS_POLICY.POLICY_ID.eq(root.policyId()))
                .fetchOne();
        if (policyRow == null
                || !"MEMORY".equals(policyRow.getOwnerKind())
                || !rootMemoryId.equals(policyRow.getOwnerId())) {
            throw new IllegalStateException("policy owner mismatch");
        }
        AccessPolicy policy = new AccessPolicy(
                policyRow.getPolicyId(), policyRow.getOwnerKind(), policyRow.getOwnerId(),
                policyRow.getCurrentRevisionNo(), policyRow.getCreatedAt());
        var policyRevisionRow = dsl.selectFrom(ACCESS_POLICY_REVISION)
                .where(ACCESS_POLICY_REVISION.POLICY_ID.eq(root.policyId()))
                .and(ACCESS_POLICY_REVISION.REVISION_NO.eq(root.currentPolicyRevisionNo()))
                .fetchOne();
        if (policyRevisionRow == null) throw new IllegalStateException("policy pointer mismatch");
        AccessPolicyRevision policyRevision = new AccessPolicyRevision(
                policyRevisionRow.getPolicyId(), policyRevisionRow.getRevisionNo(),
                policyRevisionRow.getCompanionAllowed(), policyRevisionRow.getMaintenanceAllowed(),
                policyRevisionRow.getExportAllowed(), policyRevisionRow.getExternalProviderAllowed(),
                policyRevisionRow.getIsolated(), policyRevisionRow.getCreatedByDecisionId(),
                policyRevisionRow.getCreatedAt());

        var revisionRows = dsl.selectFrom(MEMORY_REVISION)
                .where(MEMORY_REVISION.MEMORY_ID.eq(rootMemoryId))
                .orderBy(MEMORY_REVISION.REVISION_NO.asc(), MEMORY_REVISION.MEMORY_REVISION_ID.asc())
                .fetch();
        if (revisionRows.isEmpty()) throw new IllegalStateException("memory has no revisions");
        List<MemoryRevision> revisions = revisionRows.map(JooqDeletionPreviewAdapter::toMemoryRevision);
        Set<UUID> revisionIds = new LinkedHashSet<>(revisionRows.getValues(MEMORY_REVISION.MEMORY_REVISION_ID));
        if (!revisionIds.contains(root.currentRevisionId())) {
            throw new IllegalStateException("current revision is outside owner revisions");
        }

        var rootRelations = dsl.selectFrom(MEMORY_RELATION)
                .where(MEMORY_RELATION.FROM_REVISION_ID.in(revisionIds))
                .and(MEMORY_RELATION.RELATION_TYPE.eq("EVIDENCED_BY"))
                .orderBy(MEMORY_RELATION.FROM_REVISION_ID.asc(), MEMORY_RELATION.TO_ANCHOR_ID.asc(),
                        MEMORY_RELATION.RELATION_ID.asc())
                .fetch();
        Set<UUID> rootAnchorIds = new LinkedHashSet<>(rootRelations.getValues(MEMORY_RELATION.TO_ANCHOR_ID));
        if (rootAnchorIds.contains(null)) throw new IllegalStateException("evidence anchor is null");
        if (rootAnchorIds.size() != rootRelations.size()) {
            throw new IllegalStateException("duplicate evidence anchor relation");
        }

        var anchorRows = rootAnchorIds.isEmpty()
                ? List.<org.jooq.Record>of()
                : dsl.select(SOURCE_ANCHOR.ANCHOR_ID, SOURCE_ANCHOR.SOURCE_ID)
                        .from(SOURCE_ANCHOR)
                        .where(SOURCE_ANCHOR.ANCHOR_ID.in(rootAnchorIds))
                        .fetch();
        Map<UUID, UUID> anchorSources = new LinkedHashMap<>();
        for (Record row : anchorRows) anchorSources.put(row.get(SOURCE_ANCHOR.ANCHOR_ID), row.get(SOURCE_ANCHOR.SOURCE_ID));
        if (anchorSources.size() != rootAnchorIds.size()) throw new IllegalStateException("evidence anchor missing");

        var rootAnchorUnitRows = rootAnchorIds.isEmpty()
                ? List.<org.jooq.Record>of()
                : dsl.select(SOURCE_ANCHOR_UNIT.ANCHOR_ID, SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID,
                                SOURCE_ANCHOR_UNIT.ORDINAL)
                        .from(SOURCE_ANCHOR_UNIT)
                        .where(SOURCE_ANCHOR_UNIT.ANCHOR_ID.in(rootAnchorIds))
                        .orderBy(SOURCE_ANCHOR_UNIT.ANCHOR_ID.asc(), SOURCE_ANCHOR_UNIT.ORDINAL.asc())
                        .fetch();
        Map<UUID, List<UUID>> anchorUnits = new LinkedHashMap<>();
        Map<UUID, Long> unitOrdinals = new LinkedHashMap<>();
        Map<UUID, UUID> unitAnchorIds = new LinkedHashMap<>();
        for (Record row : rootAnchorUnitRows) {
            anchorUnits.computeIfAbsent(row.get(SOURCE_ANCHOR_UNIT.ANCHOR_ID), ignored -> new ArrayList<>())
                    .add(row.get(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID));
            unitOrdinals.putIfAbsent(row.get(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID), row.get(SOURCE_ANCHOR_UNIT.ORDINAL));
            unitAnchorIds.putIfAbsent(row.get(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID), row.get(SOURCE_ANCHOR_UNIT.ANCHOR_ID));
        }
        for (List<UUID> units : anchorUnits.values()) {
            if (new HashSet<>(units).size() != units.size()) {
                throw new IllegalStateException("duplicate source unit in anchor");
            }
        }
        Set<UUID> rootSourceUnitIds = new LinkedHashSet<>();
        anchorUnits.values().forEach(rootSourceUnitIds::addAll);
        if (rootSourceUnitIds.contains(null)) throw new IllegalStateException("source unit is null");

        var sourceUnitRows = rootSourceUnitIds.isEmpty()
                ? List.<org.jooq.Record>of()
                : dsl.select(SOURCE_UNIT.SOURCE_UNIT_ID, SOURCE_UNIT.SOURCE_ID,
                                SOURCE_UNIT.ACTOR_ID, SOURCE_UNIT.OCCURRED_AT)
                        .from(SOURCE_UNIT)
                        .where(SOURCE_UNIT.SOURCE_UNIT_ID.in(rootSourceUnitIds))
                        .fetch();
        Map<UUID, UUID> unitSources = new LinkedHashMap<>();
        Map<UUID, UUID> unitActors = new LinkedHashMap<>();
        Map<UUID, OffsetDateTime> unitOccurredAt = new LinkedHashMap<>();
        for (Record row : sourceUnitRows) {
            unitSources.put(row.get(SOURCE_UNIT.SOURCE_UNIT_ID), row.get(SOURCE_UNIT.SOURCE_ID));
            unitActors.put(row.get(SOURCE_UNIT.SOURCE_UNIT_ID), row.get(SOURCE_UNIT.ACTOR_ID));
            unitOccurredAt.put(row.get(SOURCE_UNIT.SOURCE_UNIT_ID), row.get(SOURCE_UNIT.OCCURRED_AT));
        }
        if (unitSources.size() != rootSourceUnitIds.size()) throw new IllegalStateException("source unit missing");
        for (Map.Entry<UUID, List<UUID>> entry : anchorUnits.entrySet()) {
            UUID anchorSource = anchorSources.get(entry.getKey());
            for (UUID unitId : entry.getValue()) {
                if (!anchorSource.equals(unitSources.get(unitId))) {
                    throw new IllegalStateException("anchor crosses source");
                }
            }
        }

        var payloadRows = rootSourceUnitIds.isEmpty()
                ? List.<org.jooq.Record>of()
                : dsl.select(SOURCE_PAYLOAD.PAYLOAD_ID, SOURCE_PAYLOAD.SOURCE_UNIT_ID,
                                SOURCE_PAYLOAD.PAYLOAD_KIND, SOURCE_PAYLOAD.CONTENT_TYPE,
                                SOURCE_PAYLOAD.STORE_ADAPTER, SOURCE_PAYLOAD.OBJECT_VERSION_REF,
                                SOURCE_PAYLOAD.OBJECT_REF, SOURCE_PAYLOAD.RETENTION_CLASS,
                                SOURCE_PAYLOAD.SIZE_BYTES, SOURCE_PAYLOAD.CONTENT_HASH)
                        .from(SOURCE_PAYLOAD)
                        .where(SOURCE_PAYLOAD.SOURCE_UNIT_ID.in(rootSourceUnitIds))
                        .orderBy(SOURCE_PAYLOAD.SOURCE_UNIT_ID.asc(), SOURCE_PAYLOAD.PAYLOAD_ID.asc())
                        .fetch();
        Map<UUID, List<DeletionPreviewGraph.Payload>> payloads = new LinkedHashMap<>();
        Map<UUID, String> payloadObjectRefs = new LinkedHashMap<>();
        for (Record row : payloadRows) {
            payloads.computeIfAbsent(row.get(SOURCE_PAYLOAD.SOURCE_UNIT_ID), ignored -> new ArrayList<>())
                    .add(new DeletionPreviewGraph.Payload(
                            row.get(SOURCE_PAYLOAD.PAYLOAD_ID), row.get(SOURCE_PAYLOAD.SOURCE_UNIT_ID),
                            row.get(SOURCE_PAYLOAD.PAYLOAD_KIND), row.get(SOURCE_PAYLOAD.CONTENT_TYPE),
                            row.get(SOURCE_PAYLOAD.STORE_ADAPTER), row.get(SOURCE_PAYLOAD.OBJECT_VERSION_REF),
                            row.get(SOURCE_PAYLOAD.RETENTION_CLASS),
                            row.get(SOURCE_PAYLOAD.SIZE_BYTES), row.get(SOURCE_PAYLOAD.CONTENT_HASH)));
            payloadObjectRefs.put(row.get(SOURCE_PAYLOAD.PAYLOAD_ID), row.get(SOURCE_PAYLOAD.OBJECT_REF));
        }

        List<DeletionPreviewGraph.Anchor> anchors = new ArrayList<>();
        for (UUID anchorId : rootAnchorIds) {
            List<DeletionPreviewGraph.SourceUnit> units = new ArrayList<>();
            for (UUID unitId : anchorUnits.getOrDefault(anchorId, List.of())) {
                units.add(new DeletionPreviewGraph.SourceUnit(unitId, unitSources.get(unitId), payloads.getOrDefault(unitId, List.of())));
            }
            anchors.add(new DeletionPreviewGraph.Anchor(anchorId, anchorSources.get(anchorId), units));
        }

        // ---- shared / exclusive detection ---------------------------------
        // Anchors (root or external) that reference a root source unit.
        List<UUID> allSharedAnchorIds = rootSourceUnitIds.isEmpty()
                ? List.of()
                : dsl.selectDistinct(SOURCE_ANCHOR_UNIT.ANCHOR_ID)
                        .from(SOURCE_ANCHOR_UNIT)
                        .where(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID.in(rootSourceUnitIds))
                        .fetch(SOURCE_ANCHOR_UNIT.ANCHOR_ID);

        // Shared root anchors: a root anchor that an EVIDENCED_BY relation from
        // a non-root revision also references (A and B reuse the same anchor).
        Set<UUID> sharedAnchorIds = new LinkedHashSet<>();
        if (!rootAnchorIds.isEmpty()) {
            sharedAnchorIds.addAll(dsl.selectDistinct(MEMORY_RELATION.TO_ANCHOR_ID)
                    .from(MEMORY_RELATION)
                    .where(MEMORY_RELATION.RELATION_TYPE.eq("EVIDENCED_BY"))
                    .and(MEMORY_RELATION.TO_ANCHOR_ID.in(rootAnchorIds))
                    .and(MEMORY_RELATION.FROM_REVISION_ID.notIn(revisionIds))
                    .fetch(MEMORY_RELATION.TO_ANCHOR_ID));
        }

        // Shared root units: referenced by a non-root anchor, or under a shared root anchor.
        Set<UUID> sharedUnitIds = new LinkedHashSet<>();
        if (!rootSourceUnitIds.isEmpty()) {
            sharedUnitIds.addAll(dsl.selectDistinct(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID)
                    .from(SOURCE_ANCHOR_UNIT)
                    .where(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID.in(rootSourceUnitIds))
                    .and(SOURCE_ANCHOR_UNIT.ANCHOR_ID.notIn(rootAnchorIds)
                            .or(SOURCE_ANCHOR_UNIT.ANCHOR_ID.in(sharedAnchorIds)))
                    .fetch(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID));
        }

        // Shared payloads: payloads whose source unit is shared.
        Set<UUID> sharedPayloadIds = new LinkedHashSet<>();
        for (Map.Entry<UUID, List<DeletionPreviewGraph.Payload>> entry : payloads.entrySet()) {
            if (sharedUnitIds.contains(entry.getKey())) {
                for (DeletionPreviewGraph.Payload payload : entry.getValue()) sharedPayloadIds.add(payload.payloadId());
            }
        }

        // ---- shared reference memories (memories != root sharing a root payload) ----
        var sharingRelations = allSharedAnchorIds.isEmpty()
                ? List.<org.jooq.Record>of()
                : dsl.select(MEMORY_RELATION.FROM_REVISION_ID, MEMORY_RELATION.TO_ANCHOR_ID)
                        .from(MEMORY_RELATION)
                        .where(MEMORY_RELATION.RELATION_TYPE.eq("EVIDENCED_BY"))
                        .and(MEMORY_RELATION.TO_ANCHOR_ID.in(allSharedAnchorIds))
                        .fetch();
        Map<UUID, List<UUID>> allAnchorUnits = new HashMap<>();
        if (!allSharedAnchorIds.isEmpty()) {
            var allUnitRows = dsl.select(SOURCE_ANCHOR_UNIT.ANCHOR_ID, SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID)
                    .from(SOURCE_ANCHOR_UNIT).where(SOURCE_ANCHOR_UNIT.ANCHOR_ID.in(allSharedAnchorIds)).fetch();
            for (Record row : allUnitRows) {
                allAnchorUnits.computeIfAbsent(row.get(SOURCE_ANCHOR_UNIT.ANCHOR_ID), ignored -> new ArrayList<>())
                        .add(row.get(SOURCE_ANCHOR_UNIT.SOURCE_UNIT_ID));
            }
        }
        Set<UUID> sharingRevisionIds = new LinkedHashSet<>();
        Map<UUID, Set<UUID>> revisionToPayloads = new HashMap<>();
        for (Record relation : sharingRelations) {
            UUID revisionId = relation.get(MEMORY_RELATION.FROM_REVISION_ID);
            UUID anchorId = relation.get(MEMORY_RELATION.TO_ANCHOR_ID);
            sharingRevisionIds.add(revisionId);
            Set<UUID> shared = revisionToPayloads.computeIfAbsent(revisionId, ignored -> new HashSet<>());
            for (UUID unitId : allAnchorUnits.getOrDefault(anchorId, List.of())) {
                for (DeletionPreviewGraph.Payload payload : payloads.getOrDefault(unitId, List.of())) shared.add(payload.payloadId());
            }
        }

        Map<UUID, MemoryRevision> revisionById = new HashMap<>();
        for (MemoryRevision revision : revisions) revisionById.put(revision.memoryRevisionId(), revision);
        if (!sharingRevisionIds.isEmpty()) {
            var otherRows = dsl.selectFrom(MEMORY_REVISION)
                    .where(MEMORY_REVISION.MEMORY_REVISION_ID.in(sharingRevisionIds)).fetch();
            for (var row : otherRows) revisionById.put(row.getMemoryRevisionId(), toMemoryRevision(row));
        }
        Set<UUID> sharedMemoryIds = new LinkedHashSet<>();
        for (UUID revisionId : sharingRevisionIds) {
            MemoryRevision revision = revisionById.get(revisionId);
            if (revision != null && !rootMemoryId.equals(revision.memoryId())
                    && !revisionToPayloads.getOrDefault(revisionId, Set.of()).isEmpty()) {
                sharedMemoryIds.add(revision.memoryId());
            }
        }

        // R5: payload → set of other memory ids that retain it, covering both direct anchor reuse
        // and indirect SourceUnit/Payload reuse through a different anchor.
        Map<UUID, Set<UUID>> payloadToMemoryIds = new HashMap<>();
        for (UUID revisionId : sharingRevisionIds) {
            MemoryRevision revision = revisionById.get(revisionId);
            if (revision != null && !rootMemoryId.equals(revision.memoryId())) {
                for (UUID payloadId : revisionToPayloads.getOrDefault(revisionId, Set.of())) {
                    payloadToMemoryIds.computeIfAbsent(payloadId, ignored -> new LinkedHashSet<>())
                            .add(revision.memoryId());
                }
            }
        }

        List<DeletionPreviewGraph.SharedMemory> sharedMemories = new ArrayList<>();
        if (!sharedMemoryIds.isEmpty()) {
            var sharedMemoryRows = dsl.selectFrom(MEMORY_RECORD)
                    .where(MEMORY_RECORD.MEMORY_ID.in(sharedMemoryIds))
                    .orderBy(MEMORY_RECORD.MEMORY_ID.asc()).fetch();
            for (var row : sharedMemoryRows) {
                var sharedCurrentRow = dsl.selectFrom(MEMORY_REVISION)
                        .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(row.getCurrentRevisionId()))
                        .and(MEMORY_REVISION.MEMORY_ID.eq(row.getMemoryId())).fetchOne();
                if (sharedCurrentRow == null) throw new IllegalStateException("shared memory current revision mismatch");
                MemoryRevision sharedCurrent = toMemoryRevision(sharedCurrentRow);
                sharedMemories.add(new DeletionPreviewGraph.SharedMemory(
                        row.getMemoryId(), sharedCurrent.revisionNo(), title(sharedCurrent.bodyText())));
            }
        }

        // ---- ordered evidence units (conversation order by anchor-unit ordinal) ----
        Map<UUID, String[]> actorRefs = readActorRefs(unitActors.values());
        List<DeletionPreviewGraph.EvidenceUnit> evidenceUnits = new ArrayList<>();
        for (UUID unitId : rootSourceUnitIds) {
            DeletionPreviewGraph.Payload payload = payloads.getOrDefault(unitId, List.of()).get(0);
            UUID actorId = unitActors.get(unitId);
            String[] actor = actorId == null ? null : actorRefs.get(actorId);
            List<UUID> sharedByMemoryIds = payloadToMemoryIds.getOrDefault(payload.payloadId(), Set.of())
                    .stream().sorted().toList();
            evidenceUnits.add(new DeletionPreviewGraph.EvidenceUnit(
                    unitAnchorIds.get(unitId), unitId, unitOrdinals.get(unitId), actorId,
                    actor == null ? null : actor[0], actor == null ? null : actor[1],
                    actor == null ? null : actor[2],
                    unitOccurredAt.get(unitId), payloadObjectRefs.get(payload.payloadId()),
                    payload.sizeBytes(), payload.contentHash(), sharedByMemoryIds));
        }
        evidenceUnits.sort(Comparator
                .comparingLong((DeletionPreviewGraph.EvidenceUnit unit) -> unitOrdinals.getOrDefault(unit.sourceUnitId(), Long.MAX_VALUE))
                .thenComparing(DeletionPreviewGraph.EvidenceUnit::sourceUnitId));

        return new DeletionPreviewGraph(root, current, policy, policyRevision, revisions, anchors,
                sharedAnchorIds, sharedUnitIds, sharedPayloadIds, evidenceUnits, sharedMemories);
    }

    @Override
    public long nextPreviewRevision(UUID rootMemoryId) {
        Long maximum = dsl.select(org.jooq.impl.DSL.max(DELETION_CLOSURE.PREVIEW_REVISION))
                .from(DELETION_CLOSURE)
                .where(DELETION_CLOSURE.ROOT_MEMORY_ID.eq(rootMemoryId))
                .fetchOne(0, Long.class);
        return maximum == null ? 1L : Math.addExact(maximum, 1L);
    }

    @Override
    public void insertPreview(PreviewDraft draft) {
        dsl.insertInto(DELETION_CLOSURE)
                .set(DELETION_CLOSURE.CLOSURE_ID, draft.previewId())
                .set(DELETION_CLOSURE.ROOT_MEMORY_ID, draft.rootMemoryId())
                .set(DELETION_CLOSURE.PREVIEW_REVISION, draft.previewRevision())
                .set(DELETION_CLOSURE.ROOT_CURRENT_REVISION_ID, draft.rootCurrentRevisionId())
                .set(DELETION_CLOSURE.ROOT_REVISION_NO, draft.rootRevisionNo())
                .set(DELETION_CLOSURE.ROOT_POLICY_ID, draft.rootPolicyId())
                .set(DELETION_CLOSURE.ROOT_POLICY_REVISION_NO, draft.rootPolicyRevisionNo())
                .set(DELETION_CLOSURE.REQUEST_IDEMPOTENCY_KEY, draft.idempotencyKey())
                .set(DELETION_CLOSURE.REQUEST_HASH, draft.requestHash())
                .set(DELETION_CLOSURE.MANIFEST_HASH, draft.manifestHash())
                .set(DELETION_CLOSURE.STATE, draft.state())
                .set(DELETION_CLOSURE.CREATED_AT, draft.createdAt())
                .set(DELETION_CLOSURE.EXPIRES_AT, draft.expiresAt())
                .execute();
        List<DeletionClosureMemberRecord> records = new ArrayList<>();
        for (Member member : draft.members()) {
            records.add(new DeletionClosureMemberRecord(
                    draft.previewId(), member.ordinal(), member.memberKind(), member.targetId(),
                    member.targetRevisionRef(), member.disposition(), member.sizeBytes(), member.contentHash()));
        }
        if (!records.isEmpty()) dsl.batchInsert(records).execute();
    }

    private ExistingPreview toExisting(DeletionClosureRecord closure) {
        List<Member> members = dsl.selectFrom(DELETION_CLOSURE_MEMBER)
                .where(DELETION_CLOSURE_MEMBER.CLOSURE_ID.eq(closure.getClosureId()))
                .orderBy(DELETION_CLOSURE_MEMBER.ORDINAL.asc()).fetch().map(row -> new Member(
                        row.getOrdinal(), row.getMemberKind(), row.getTargetId(), row.getTargetRevisionRef(),
                        row.getDisposition(), row.getSizeBytes(), row.getContentHash()));
        return new ExistingPreview(closure.getClosureId(), closure.getRootMemoryId(), closure.getPreviewRevision(),
                closure.getRootCurrentRevisionId(), closure.getRootRevisionNo(), closure.getRootPolicyId(),
                closure.getRootPolicyRevisionNo(), closure.getRequestHash(), closure.getManifestHash(),
                closure.getState(), closure.getCreatedAt(), closure.getExpiresAt(), members);
    }

    private static MemoryRevision toMemoryRevision(
            io.github.candyxi0.hidenest.database.generated.memory.tables.records.MemoryRevisionRecord row) {
        return new MemoryRevision(row.getMemoryRevisionId(), row.getMemoryId(), row.getRevisionNo(),
                row.getMemoryType(), row.getPerspectiveActorId(), row.getBodyText(), row.getValidFrom(),
                row.getValidTo(), row.getUncertaintyCode(), row.getCreatedByDecisionId(), row.getCreatedAt());
    }

    private Map<UUID, String[]> readActorRefs(Collection<UUID> actorIds) {
        Map<UUID, String[]> result = new HashMap<>();
        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID id : actorIds) {
            if (id != null) distinct.add(id);
        }
        if (distinct.isEmpty()) return result;
        var rows = dsl.select(ACTOR_REF.ACTOR_ID, ACTOR_REF.ACTOR_KIND, ACTOR_REF.STABLE_REF, ACTOR_REF.DISPLAY_LABEL)
                .from(ACTOR_REF)
                .where(ACTOR_REF.ACTOR_ID.in(distinct))
                .fetch();
        for (Record row : rows) {
            result.put(row.get(ACTOR_REF.ACTOR_ID),
                    new String[]{row.get(ACTOR_REF.ACTOR_KIND), row.get(ACTOR_REF.STABLE_REF), row.get(ACTOR_REF.DISPLAY_LABEL)});
        }
        return result;
    }

    private static String title(String body) {
        if (body == null) return "";
        for (String line : body.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                int count = stripped.codePointCount(0, stripped.length());
                return count <= 40 ? stripped : stripped.substring(0, stripped.offsetByCodePoints(0, 40));
            }
        }
        return "";
    }
}
