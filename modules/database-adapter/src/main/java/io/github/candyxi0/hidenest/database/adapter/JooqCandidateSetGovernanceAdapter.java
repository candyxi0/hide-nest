package io.github.candyxi0.hidenest.database.adapter;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.CANDIDATE_EVIDENCE_MAPPING;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.CANDIDATE_SET;
import static io.github.candyxi0.hidenest.database.generated.memory.Tables.CANDIDATE_SET_MEMBER;

import io.github.candyxi0.hidenest.memory.domain.CandidateEvidenceMapping;
import io.github.candyxi0.hidenest.memory.domain.CandidateSet;
import io.github.candyxi0.hidenest.memory.domain.CandidateSetMember;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

public class JooqCandidateSetGovernanceAdapter implements CandidateSetGovernancePort {

    private final DSLContext dsl;

    public JooqCandidateSetGovernanceAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void insertCandidateSet(CandidateSet set) {
        dsl.insertInto(CANDIDATE_SET)
                .set(CANDIDATE_SET.CANDIDATE_SET_ID, set.candidateSetId())
                .set(CANDIDATE_SET.REVIEW_SESSION_ID, set.reviewSessionId())
                .set(CANDIDATE_SET.THREAD_ID, set.threadId())
                .set(CANDIDATE_SET.SCOPE_REF, set.scopeRef())
                .set(CANDIDATE_SET.SET_VERSION, set.setVersion())
                .set(CANDIDATE_SET.CONFIRMATION_HASH, set.confirmationHash())
                .set(CANDIDATE_SET.REQUEST_HASH, set.requestHash())
                .set(CANDIDATE_SET.CREATED_AT, set.createdAt())
                .set(CANDIDATE_SET.CONFIRMED_AT, set.confirmedAt())
                .execute();
    }

    @Override
    public void insertCandidateSetMember(CandidateSetMember member) {
        dsl.insertInto(CANDIDATE_SET_MEMBER)
                .set(CANDIDATE_SET_MEMBER.CANDIDATE_SET_ID, member.candidateSetId())
                .set(CANDIDATE_SET_MEMBER.CANDIDATE_ID, member.candidateId())
                .set(CANDIDATE_SET_MEMBER.ORDINAL, member.ordinal())
                .set(CANDIDATE_SET_MEMBER.PROPOSAL_REVISION_ID, member.proposalRevisionId())
                .set(CANDIDATE_SET_MEMBER.DECISION_ID, member.decisionId())
                .set(CANDIDATE_SET_MEMBER.DISPOSITION, member.disposition())
                .set(CANDIDATE_SET_MEMBER.ACTION, member.action())
                .set(CANDIDATE_SET_MEMBER.ORIGIN_KIND, member.originKind())
                .set(CANDIDATE_SET_MEMBER.FINAL_AUTHOR_KIND, member.finalAuthorKind())
                .set(CANDIDATE_SET_MEMBER.FUTURE_MEMORY_ID, member.futureMemoryId())
                .set(CANDIDATE_SET_MEMBER.TARGET_MEMORY_ID, member.targetMemoryId())
                .set(CANDIDATE_SET_MEMBER.EXPECTED_MEMORY_REVISION_ID, member.expectedMemoryRevisionId())
                .set(CANDIDATE_SET_MEMBER.EXPECTED_POLICY_REVISION_NO, member.expectedPolicyRevisionNo())
                .execute();
    }

    @Override
    public void insertCandidateEvidenceMappings(List<CandidateEvidenceMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return;
        }
        var insert = dsl.insertInto(CANDIDATE_EVIDENCE_MAPPING)
                .columns(
                        CANDIDATE_EVIDENCE_MAPPING.CANDIDATE_SET_ID,
                        CANDIDATE_EVIDENCE_MAPPING.CANDIDATE_ID,
                        CANDIDATE_EVIDENCE_MAPPING.ORDINAL,
                        CANDIDATE_EVIDENCE_MAPPING.ANCHOR_ID);
        for (var mapping : mappings) {
            insert = insert.values(
                    mapping.candidateSetId(), mapping.candidateId(),
                    mapping.ordinal(), mapping.anchorId());
        }
        insert.execute();
    }

    @Override
    public CandidateSet findCandidateSetById(UUID candidateSetId) {
        var r = dsl.selectFrom(CANDIDATE_SET)
                .where(CANDIDATE_SET.CANDIDATE_SET_ID.eq(candidateSetId))
                .fetchOne();
        if (r == null) {
            return null;
        }
        return new CandidateSet(
                r.getCandidateSetId(),
                r.getReviewSessionId(),
                r.getThreadId(),
                r.getScopeRef(),
                r.getSetVersion(),
                r.getConfirmationHash(),
                r.getRequestHash(),
                r.getCreatedAt(),
                r.getConfirmedAt());
    }

    @Override
    public List<CandidateSetMember> findCandidateSetMembers(UUID candidateSetId) {
        var records = dsl.selectFrom(CANDIDATE_SET_MEMBER)
                .where(CANDIDATE_SET_MEMBER.CANDIDATE_SET_ID.eq(candidateSetId))
                .orderBy(CANDIDATE_SET_MEMBER.ORDINAL.asc())
                .fetch();
        List<CandidateSetMember> result = new ArrayList<>(records.size());
        for (var r : records) {
            result.add(new CandidateSetMember(
                    r.getCandidateSetId(),
                    r.getCandidateId(),
                    r.getOrdinal(),
                    r.getProposalRevisionId(),
                    r.getDecisionId(),
                    r.getDisposition(),
                    r.getAction(),
                    r.getOriginKind(),
                    r.getFinalAuthorKind(),
                    r.getFutureMemoryId(),
                    r.getTargetMemoryId(),
                    r.getExpectedMemoryRevisionId(),
                    r.getExpectedPolicyRevisionNo()));
        }
        return result;
    }

    @Override
    public List<CandidateEvidenceMapping> findCandidateEvidenceMappings(UUID candidateSetId) {
        var records = dsl.selectFrom(CANDIDATE_EVIDENCE_MAPPING)
                .where(CANDIDATE_EVIDENCE_MAPPING.CANDIDATE_SET_ID.eq(candidateSetId))
                .orderBy(CANDIDATE_EVIDENCE_MAPPING.CANDIDATE_ID.asc(), CANDIDATE_EVIDENCE_MAPPING.ORDINAL.asc())
                .fetch();
        List<CandidateEvidenceMapping> result = new ArrayList<>(records.size());
        for (var r : records) {
            result.add(new CandidateEvidenceMapping(
                    r.getCandidateSetId(), r.getCandidateId(), r.getOrdinal(), r.getAnchorId()));
        }
        return result;
    }
}
