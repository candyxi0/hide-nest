package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface MemoryGovernancePort {

    /** Insert a proposal. */
    void insertProposal(Proposal proposal);

    /** Insert a proposal revision. */
    void insertProposalRevision(ProposalRevision revision);

    /** Insert a review session. */
    void insertReviewSession(ReviewSession session);

    /** Lock review session for write. */
    ReviewSession lockReviewSessionForWrite(UUID reviewSessionId);

    /** CAS transition review session state. Returns true iff exactly one row changed. */
    boolean transitionReviewSessionState(UUID reviewSessionId, String expectedState,
            String newState, OffsetDateTime terminalAt);

    /** Insert a review member. */
    void insertReviewMember(ReviewMember member);

    /** Find review members by session ID. */
    List<ReviewMember> findReviewMembersBySessionId(UUID reviewSessionId);

    /** Insert a decision. */
    void insertDecision(Decision decision);

    /** Find the unique decision created for an idempotency key. */
    Decision findDecisionByIdempotencyKey(String idempotencyKey);

    /** L2: Lock and verify confirmed decisions match the exact review/proposal/target. */
    List<Decision> lockAndVerifyDecisions(Set<UUID> decisionIds, UUID reviewSessionId,
            UUID proposalRevisionId, String targetKind, UUID targetId, Long targetRevisionRef);

    ProposalRevision findProposalRevisionById(UUID proposalRevisionId);

    ReviewSession findReviewSessionById(UUID reviewSessionId);

    MemoryRecord lockMemoryRecordForWrite(UUID memoryId);

    void insertMemoryRecord(MemoryRecord record);

    void insertMemoryRevision(MemoryRevision revision);

    /** Insert relations. Non-empty when schema has no relation table must fail-closed. */
    void insertMemoryRelations(List<MemoryRelation> relations);

    /** Find relations originating from a given revision. */
    List<MemoryRelation> findMemoryRelationsByFromRevisionId(UUID fromRevisionId);

    AccessPolicy lockAccessPolicyForWrite(UUID policyId);

    void insertAccessPolicy(AccessPolicy policy);

    void insertAccessPolicyRevision(AccessPolicyRevision revision);

    /**
     * CAS update of current pointer. Must include expected current revision and
     * expected policy revision in WHERE clause. Returns true if exactly 1 row
     * affected.
     */
    boolean updateMemoryRecordPointerCAS(UUID memoryId, UUID expectedRevisionId,
            UUID newRevisionId, UUID policyId, Long expectedPolicyRevisionNo,
            Long newPolicyRevisionNo);

    void insertChangeEvent(ChangeEvent event);

    /** Lock and read current MemoryRevision. */
    MemoryRevision lockMemoryRevisionForWrite(UUID memoryId);

    /** Insert an actor ref. */
    void insertActorRef(ActorRef actorRef);

    /**
     * Atomically insert an actor ref when absent and return the persisted identity fact.
     *
     * <p>If either the primary actor id or the {@code (actorKind, stableRef)} identity already
     * exists, the existing fact is returned without mutation. The application layer must compare
     * the returned immutable identity fields and fail closed on any mismatch.</p>
     */
    ActorRef insertActorRefIfAbsent(ActorRef actorRef);

    /** Find actor ref by primary key. */
    ActorRef findActorRefById(UUID actorId);

    /** Find actor ref by kind and stable ref. */
    ActorRef findActorRefByKindAndStableRef(String actorKind, String stableRef);
}
