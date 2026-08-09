package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.*;
import static io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION;

import io.github.candyxi0.hidenest.memory.domain.*;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;

public class JooqMemoryGovernanceAdapter implements MemoryGovernancePort {

    private final DSLContext dsl;

    public JooqMemoryGovernanceAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<Decision> lockAndVerifyDecisions(Set<UUID> decisionIds, UUID reviewSessionId,
            UUID proposalRevisionId, String targetKind, UUID targetId, Long targetRevisionRef) {
        var records = dsl.selectFrom(DECISION)
                .where(DECISION.DECISION_ID.in(decisionIds))
                .forUpdate()
                .fetch();
        List<Decision> decisions = new ArrayList<>();
        for (var r : records) {
            // Verify semantic binding: correct review session, proposal revision, target
            if (!reviewSessionId.equals(r.getReviewSessionId())
                    || !proposalRevisionId.equals(r.getProposalRevisionId())
                    || !"USER_CONFIRM".equals(r.getDecisionKind())
                    || !targetKind.equals(r.getTargetKind())
                    || !targetId.equals(r.getTargetId())) {
                continue; // exclude mismatched decisions
            }
            if (targetRevisionRef != null
                    && !targetRevisionRef.equals(r.getTargetRevisionRef())) {
                continue;
            }
            decisions.add(new Decision(
                    r.getDecisionId(), r.getDecisionKind(), r.getActorId(),
                    r.getActorRole(), r.getProposalRevisionId(), r.getReviewSessionId(),
                    r.getTargetKind(), r.getTargetId(), r.getTargetRevisionRef(),
                    r.getAuthorizationRef(), r.getIdempotencyKey(), r.getCreatedAt()));
        }
        return decisions;
    }

    @Override
    public ProposalRevision findProposalRevisionById(UUID proposalRevisionId) {
        var r = dsl.selectFrom(PROPOSAL_REVISION)
                .where(PROPOSAL_REVISION.PROPOSAL_REVISION_ID.eq(proposalRevisionId))
                .fetchOne();
        if (r == null) return null;
        return new ProposalRevision(
                r.getProposalRevisionId(), r.getProposalId(), r.getRevisionNo(),
                r.getActionCode(), r.getBodyText(), r.getMemoryType(),
                r.getPerspectiveActorId(), r.getExpectedMemoryRevisionId(),
                r.getExpectedPolicyRevisionNo(), r.getBodyHash(), r.getCreatedAt());
    }

    @Override
    public ReviewSession findReviewSessionById(UUID reviewSessionId) {
        var r = dsl.selectFrom(REVIEW_SESSION)
                .where(REVIEW_SESSION.REVIEW_SESSION_ID.eq(reviewSessionId))
                .fetchOne();
        if (r == null) return null;
        return new ReviewSession(
                r.getReviewSessionId(), r.getState(), r.getIdempotencyKey(),
                r.getRequestHash(), r.getOpenedAt(), r.getTerminalAt());
    }

    @Override
    public MemoryRecord lockMemoryRecordForWrite(UUID memoryId) {
        var r = dsl.selectFrom(MEMORY_RECORD)
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .forUpdate()
                .fetchOne();
        if (r == null) return null;
        return new MemoryRecord(
                r.getMemoryId(), r.getState(), r.getCurrentRevisionId(),
                r.getPolicyId(), r.getCurrentPolicyRevisionNo(),
                r.getCreatedAt(), r.getUpdatedAt());
    }

    @Override
    public MemoryRevision lockMemoryRevisionForWrite(UUID memoryId) {
        var mr = dsl.selectFrom(MEMORY_RECORD)
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .forUpdate()
                .fetchOne();
        if (mr == null || mr.getCurrentRevisionId() == null) return null;
        var rev = dsl.selectFrom(MEMORY_REVISION)
                .where(MEMORY_REVISION.MEMORY_REVISION_ID.eq(mr.getCurrentRevisionId()))
                .forUpdate()
                .fetchOne();
        if (rev == null) return null;
        return new MemoryRevision(
                rev.getMemoryRevisionId(), rev.getMemoryId(), rev.getRevisionNo(),
                rev.getMemoryType(), rev.getPerspectiveActorId(), rev.getBodyText(),
                rev.getValidFrom(), rev.getValidTo(), rev.getUncertaintyCode(),
                rev.getCreatedByDecisionId(), rev.getCreatedAt());
    }

    @Override
    public void insertMemoryRecord(MemoryRecord record) {
        dsl.insertInto(MEMORY_RECORD)
                .set(MEMORY_RECORD.MEMORY_ID, record.memoryId())
                .set(MEMORY_RECORD.STATE, record.state())
                .set(MEMORY_RECORD.CURRENT_REVISION_ID, record.currentRevisionId())
                .set(MEMORY_RECORD.POLICY_ID, record.policyId())
                .set(MEMORY_RECORD.CURRENT_POLICY_REVISION_NO, record.currentPolicyRevisionNo())
                .set(MEMORY_RECORD.CREATED_AT, record.createdAt())
                .set(MEMORY_RECORD.UPDATED_AT, record.updatedAt())
                .execute();
    }

    @Override
    public void insertMemoryRevision(MemoryRevision revision) {
        dsl.insertInto(MEMORY_REVISION)
                .set(MEMORY_REVISION.MEMORY_REVISION_ID, revision.memoryRevisionId())
                .set(MEMORY_REVISION.MEMORY_ID, revision.memoryId())
                .set(MEMORY_REVISION.REVISION_NO, revision.revisionNo())
                .set(MEMORY_REVISION.MEMORY_TYPE, revision.memoryType())
                .set(MEMORY_REVISION.PERSPECTIVE_ACTOR_ID, revision.perspectiveActorId())
                .set(MEMORY_REVISION.BODY_TEXT, revision.bodyText())
                .set(MEMORY_REVISION.VALID_FROM, revision.validFrom())
                .set(MEMORY_REVISION.VALID_TO, revision.validTo())
                .set(MEMORY_REVISION.UNCERTAINTY_CODE, revision.uncertaintyCode())
                .set(MEMORY_REVISION.CREATED_BY_DECISION_ID, revision.createdByDecisionId())
                .set(MEMORY_REVISION.CREATED_AT, revision.createdAt())
                .execute();
    }

    @Override
    public void insertMemoryRelations(List<MemoryRelation> relations) {
        if (relations != null && !relations.isEmpty()) {
            // memory_relation table deferred to HDM-017. Fail closed.
            throw new RuntimeException("CANONICAL_COMMIT_FAILED: memory_relation table not available");
        }
    }

    @Override
    public AccessPolicy lockAccessPolicyForWrite(UUID policyId) {
        var r = dsl.selectFrom(ACCESS_POLICY)
                .where(ACCESS_POLICY.POLICY_ID.eq(policyId))
                .forUpdate()
                .fetchOne();
        if (r == null) return null;
        return new AccessPolicy(
                r.getPolicyId(), r.getOwnerKind(), r.getOwnerId(),
                r.getCurrentRevisionNo(), r.getCreatedAt());
    }

    @Override
    public void insertAccessPolicy(AccessPolicy policy) {
        dsl.insertInto(ACCESS_POLICY)
                .set(ACCESS_POLICY.POLICY_ID, policy.policyId())
                .set(ACCESS_POLICY.OWNER_KIND, policy.ownerKind())
                .set(ACCESS_POLICY.OWNER_ID, policy.ownerId())
                .set(ACCESS_POLICY.CURRENT_REVISION_NO, policy.currentRevisionNo())
                .set(ACCESS_POLICY.CREATED_AT, policy.createdAt())
                .execute();
    }

    @Override
    public void insertAccessPolicyRevision(AccessPolicyRevision revision) {
        dsl.insertInto(ACCESS_POLICY_REVISION)
                .set(ACCESS_POLICY_REVISION.POLICY_ID, revision.policyId())
                .set(ACCESS_POLICY_REVISION.REVISION_NO, revision.revisionNo())
                .set(ACCESS_POLICY_REVISION.COMPANION_ALLOWED, revision.companionAllowed())
                .set(ACCESS_POLICY_REVISION.MAINTENANCE_ALLOWED, revision.maintenanceAllowed())
                .set(ACCESS_POLICY_REVISION.EXPORT_ALLOWED, revision.exportAllowed())
                .set(ACCESS_POLICY_REVISION.EXTERNAL_PROVIDER_ALLOWED,
                        revision.externalProviderAllowed())
                .set(ACCESS_POLICY_REVISION.ISOLATED, revision.isolated())
                .set(ACCESS_POLICY_REVISION.CREATED_BY_DECISION_ID,
                        revision.createdByDecisionId())
                .set(ACCESS_POLICY_REVISION.CREATED_AT, revision.createdAt())
                .execute();
    }

    @Override
    public boolean updateMemoryRecordPointerCAS(UUID memoryId, UUID expectedRevisionId,
            UUID newRevisionId, UUID policyId, Long expectedPolicyRevisionNo,
            Long newPolicyRevisionNo) {
        int rows = dsl.update(MEMORY_RECORD)
                .set(MEMORY_RECORD.CURRENT_REVISION_ID, newRevisionId)
                .set(MEMORY_RECORD.POLICY_ID, policyId)
                .set(MEMORY_RECORD.CURRENT_POLICY_REVISION_NO, newPolicyRevisionNo)
                .set(MEMORY_RECORD.UPDATED_AT, OffsetDateTime.now())
                .where(MEMORY_RECORD.MEMORY_ID.eq(memoryId))
                .and(MEMORY_RECORD.CURRENT_REVISION_ID.eq(expectedRevisionId))
                .and(MEMORY_RECORD.CURRENT_POLICY_REVISION_NO.eq(expectedPolicyRevisionNo))
                .execute();
        return rows == 1;
    }

    @Override
    public void insertChangeEvent(ChangeEvent event) {
        dsl.insertInto(CHANGE_EVENT)
                .set(CHANGE_EVENT.CHANGE_EVENT_ID, event.changeEventId())
                .set(CHANGE_EVENT.EVENT_TYPE, event.eventType())
                .set(CHANGE_EVENT.ACTOR_ID, event.actorId())
                .set(CHANGE_EVENT.TARGET_KIND, event.targetKind())
                .set(CHANGE_EVENT.TARGET_ID, event.targetId())
                .set(CHANGE_EVENT.TARGET_REVISION_REF, event.targetRevisionRef())
                .set(CHANGE_EVENT.DECISION_ID, event.decisionId())
                .set(CHANGE_EVENT.OCCURRED_AT, event.occurredAt())
                .set(CHANGE_EVENT.DETAIL_MANIFEST,
                        event.detailManifest() != null
                                ? JSONB.valueOf(event.detailManifest()) : null)
                .execute();
    }
}
