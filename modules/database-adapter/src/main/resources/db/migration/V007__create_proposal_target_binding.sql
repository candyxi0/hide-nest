-- V007 | Proposal target binding for first-publish path
-- hide ruling: proposal.target_memory_id is NULL for CREATE proposals
-- (memory does not exist at Stage A commit time).
-- First-publish: CREATE + revision_no=1 → target_memory_id MUST be NULL,
-- Decision binds the new memory_id exactly.
-- Revision path: target_memory_id MUST match NEW.memory_id.
-- REVISE with NULL target is rejected.

CREATE OR REPLACE FUNCTION memory.enforce_memory_revision_governance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    dec_record record; prop_rev_record record; prop_record record;
    mem_policy_rev bigint; review_member_exists boolean;
BEGIN
    SELECT * INTO dec_record FROM memory.decision WHERE decision_id = NEW.created_by_decision_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_DECISION_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    IF dec_record.decision_kind <> 'USER_CONFIRM' THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_NOT_CONFIRMED' USING ERRCODE = '23514';
    END IF;
    IF dec_record.target_kind <> 'MEMORY' OR dec_record.target_id <> NEW.memory_id
       OR dec_record.target_revision_ref IS DISTINCT FROM NEW.revision_no THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_DECISION_TARGET_MISMATCH' USING ERRCODE = '23514';
    END IF;
    IF dec_record.proposal_revision_id IS NULL THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_REQUIRED' USING ERRCODE = '23514';
    END IF;

    -- R3-01: USER_CONFIRM must have review_session_id (enforced by CHECK constraint);
    -- ProposalRevision MUST be a frozen member of that ReviewSession (no NULL bypass)
    SELECT EXISTS (
        SELECT 1 FROM memory.review_member
        WHERE review_session_id = dec_record.review_session_id
          AND proposal_revision_id = dec_record.proposal_revision_id
    ) INTO review_member_exists;
    IF NOT review_member_exists THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_NOT_REVIEW_MEMBER' USING ERRCODE = '23514';
    END IF;

    SELECT * INTO prop_rev_record FROM memory.proposal_revision
    WHERE proposal_revision_id = dec_record.proposal_revision_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_NOT_FOUND' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO prop_record FROM memory.proposal WHERE proposal_id = prop_rev_record.proposal_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_NOT_FOUND' USING ERRCODE = '23514';
    END IF;

    -- V007: Proposal target binding for first-publish vs revision
    -- First-publish (CREATE + revision_no=1): target_memory_id MUST be NULL
    --   because the new MemoryRecord does not exist at Stage A commit time.
    --   The Decision target_id/revision_ref provides the exact binding.
    -- Revision (revision_no > 1): target_memory_id MUST match NEW.memory_id.
    --   REVISE with NULL target is rejected.
    IF prop_record.proposal_kind = 'CREATE' AND NEW.revision_no = 1 THEN
        IF prop_record.target_memory_id IS NOT NULL THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_TARGET_MISMATCH'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF prop_record.target_memory_id IS DISTINCT FROM NEW.memory_id THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_TARGET_MISMATCH'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.revision_no > 1 THEN
        IF prop_rev_record.expected_memory_revision_id IS NULL THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_EXPECTED_REVISION_REQUIRED' USING ERRCODE = '23514';
        END IF;
        IF NOT EXISTS (
            SELECT 1 FROM memory.memory_revision
            WHERE memory_id = NEW.memory_id AND revision_no = NEW.revision_no - 1
              AND memory_revision_id = prop_rev_record.expected_memory_revision_id
        ) THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_STALE_REVISION expected=%',
                prop_rev_record.expected_memory_revision_id USING ERRCODE = '23514';
        END IF;

        IF prop_rev_record.expected_policy_revision_no IS NULL THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_EXPECTED_POLICY_REQUIRED' USING ERRCODE = '23514';
        END IF;

        -- R3-02: Compare against memory_record.current_policy_revision_no (the memory's actual pointer)
        SELECT current_policy_revision_no INTO mem_policy_rev
        FROM memory.memory_record WHERE memory_id = NEW.memory_id;

        IF prop_rev_record.expected_policy_revision_no IS DISTINCT FROM mem_policy_rev THEN
            RAISE EXCEPTION 'HDM005_MEMORY_REVISION_STALE_POLICY expected=% actual=%',
                prop_rev_record.expected_policy_revision_no, mem_policy_rev USING ERRCODE = '23514';
        END IF;
    END IF;

    PERFORM memory.require_governed_outbox(
        'MEMORY', NEW.memory_id, NEW.revision_no, 'memory.canonical-committed.v1', NEW.created_by_decision_id);
    RETURN NEW;
END
$$;
