package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface MemoryGovernancePort {

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
}
