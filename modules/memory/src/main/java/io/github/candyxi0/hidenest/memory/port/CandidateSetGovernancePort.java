package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.CandidateEvidenceMapping;
import io.github.candyxi0.hidenest.memory.domain.CandidateSet;
import io.github.candyxi0.hidenest.memory.domain.CandidateSetMember;
import java.util.List;
import java.util.UUID;

public interface CandidateSetGovernancePort {

    /** Insert a candidate set root. */
    void insertCandidateSet(CandidateSet set);

    /** Insert a candidate set member. */
    void insertCandidateSetMember(CandidateSetMember member);

    /** Insert candidate→anchor evidence mappings (batch). */
    void insertCandidateEvidenceMappings(List<CandidateEvidenceMapping> mappings);

    /** Find candidate set root by primary key. */
    CandidateSet findCandidateSetById(UUID candidateSetId);

    /** Find candidate set members ordered by ordinal. */
    List<CandidateSetMember> findCandidateSetMembers(UUID candidateSetId);

    /** Find candidate evidence mappings ordered by candidate_id, ordinal. */
    List<CandidateEvidenceMapping> findCandidateEvidenceMappings(UUID candidateSetId);
}
