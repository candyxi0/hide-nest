-- V020 | Local V1 | CandidateSet batch decision core
--
-- Persists the frozen multi-candidate closeout decision set: one CandidateSet root,
-- 1..8 Candidate members, and the candidate→anchor evidence mapping. These are
-- immutable governance facts written atomically in the same transaction as the
-- Proposal/ProposalRevision/ReviewMember/Decision/ChangeEvent/governed Outbox rows.
--
-- Each member carries a NOT NULL decision_id that precisely binds it to its single
-- final verdict Decision (USER_CONFIRM / USER_REJECT). Deferred constraint triggers
-- close the set at COMMIT: 1..8 members, contiguous ordinal, member↔ReviewSession/
-- ProposalRevision/Decision/action/target/evidence rules, and the accepted/rejected
-- Decision target matrix.
--
-- No Memory/MemoryRevision/SUPERSEDES relation is created here (Task36B publishes
-- each accepted candidate). No new role is created; the API role receives only
-- INSERT/SELECT, and UPDATE/DELETE are denied both by privilege and by the
-- immutability triggers.

-- ============================================================
-- memory.candidate_set (root)
-- ============================================================
CREATE TABLE memory.candidate_set (
    candidate_set_id uuid PRIMARY KEY,
    review_session_id uuid NOT NULL,
    thread_id uuid NOT NULL,
    scope_ref text,
    set_version bigint NOT NULL,
    confirmation_hash bytea NOT NULL,
    request_hash bytea NOT NULL,
    created_at timestamptz NOT NULL,
    confirmed_at timestamptz NOT NULL,
    CONSTRAINT candidate_set_review_session_fk
        FOREIGN KEY (review_session_id)
        REFERENCES memory.review_session (review_session_id)
        ON DELETE NO ACTION,
    -- a ReviewSession carries exactly one CandidateSet
    CONSTRAINT candidate_set_review_session_unique UNIQUE (review_session_id),
    CONSTRAINT candidate_set_set_version_check CHECK (set_version >= 1),
    CONSTRAINT candidate_set_confirmation_hash_check CHECK (octet_length(confirmation_hash) = 32),
    CONSTRAINT candidate_set_request_hash_check CHECK (octet_length(request_hash) = 32)
);

-- ============================================================
-- memory.candidate_set_member (1..8 members)
-- ============================================================
CREATE TABLE memory.candidate_set_member (
    candidate_set_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    proposal_revision_id uuid NOT NULL,
    decision_id uuid NOT NULL,
    disposition text COLLATE "C" NOT NULL,
    action text COLLATE "C" NOT NULL,
    origin_kind text COLLATE "C" NOT NULL,
    final_author_kind text COLLATE "C" NOT NULL,
    future_memory_id uuid NOT NULL,
    target_memory_id uuid,
    expected_memory_revision_id uuid,
    expected_policy_revision_no bigint,
    PRIMARY KEY (candidate_set_id, ordinal),
    CONSTRAINT candidate_set_member_candidate_unique UNIQUE (candidate_set_id, candidate_id),
    -- a final verdict Decision binds to exactly one member
    CONSTRAINT candidate_set_member_decision_unique UNIQUE (decision_id),
    CONSTRAINT candidate_set_member_set_fk
        FOREIGN KEY (candidate_set_id)
        REFERENCES memory.candidate_set (candidate_set_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_set_member_proposal_revision_fk
        FOREIGN KEY (proposal_revision_id)
        REFERENCES memory.proposal_revision (proposal_revision_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_set_member_decision_fk
        FOREIGN KEY (decision_id)
        REFERENCES memory.decision (decision_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_set_member_target_memory_fk
        FOREIGN KEY (target_memory_id)
        REFERENCES memory.memory_record (memory_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_set_member_expected_revision_fk
        FOREIGN KEY (expected_memory_revision_id)
        REFERENCES memory.memory_revision (memory_revision_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_set_member_ordinal_check CHECK (ordinal >= 1),
    CONSTRAINT candidate_set_member_disposition_check CHECK (disposition IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT candidate_set_member_action_check CHECK (action IN ('CREATE', 'REVISE', 'SUPERSEDE')),
    CONSTRAINT candidate_set_member_origin_check CHECK (
        origin_kind IN ('HIDE_PROPOSED', 'USER_EDITED', 'USER_ADDED')),
    CONSTRAINT candidate_set_member_author_check CHECK (final_author_kind IN ('HIDE', 'USER')),
    CONSTRAINT candidate_set_member_expected_policy_check CHECK (
        expected_policy_revision_no IS NULL OR expected_policy_revision_no >= 1),
    -- action ↔ target binding: CREATE targets nothing, REVISE/SUPERSEDE target an existing memory
    CONSTRAINT candidate_set_member_action_target_check CHECK (
        (action = 'CREATE' AND target_memory_id IS NULL)
        OR (action IN ('REVISE', 'SUPERSEDE') AND target_memory_id IS NOT NULL)),
    -- action ↔ expected revision/policy binding
    CONSTRAINT candidate_set_member_expected_binding_check CHECK (
        (action = 'CREATE' AND expected_memory_revision_id IS NULL AND expected_policy_revision_no IS NULL)
        OR (action IN ('REVISE', 'SUPERSEDE')
            AND expected_memory_revision_id IS NOT NULL AND expected_policy_revision_no IS NOT NULL)),
    -- origin → author attribution rule
    CONSTRAINT candidate_set_member_origin_author_check CHECK (
        (origin_kind IN ('USER_EDITED', 'USER_ADDED') AND final_author_kind = 'USER')
        OR (origin_kind = 'HIDE_PROPOSED' AND final_author_kind IN ('HIDE', 'USER')))
);

-- ============================================================
-- memory.candidate_evidence_mapping (candidate → anchor)
-- ============================================================
CREATE TABLE memory.candidate_evidence_mapping (
    candidate_set_id uuid NOT NULL,
    candidate_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    anchor_id uuid NOT NULL,
    PRIMARY KEY (candidate_set_id, candidate_id, ordinal),
    CONSTRAINT candidate_evidence_mapping_member_fk
        FOREIGN KEY (candidate_set_id, candidate_id)
        REFERENCES memory.candidate_set_member (candidate_set_id, candidate_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_evidence_mapping_anchor_fk
        FOREIGN KEY (anchor_id)
        REFERENCES evidence.source_anchor (anchor_id)
        ON DELETE NO ACTION,
    CONSTRAINT candidate_evidence_mapping_ordinal_check CHECK (ordinal >= 1),
    -- a candidate may not map the same anchor twice
    CONSTRAINT candidate_evidence_mapping_anchor_unique UNIQUE (candidate_set_id, candidate_id, anchor_id)
);

-- ============================================================
-- Immutability: no UPDATE/DELETE on any CandidateSet fact
-- ============================================================
CREATE TRIGGER candidate_set_immutable
    BEFORE UPDATE OR DELETE ON memory.candidate_set
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER candidate_set_member_immutable
    BEFORE UPDATE OR DELETE ON memory.candidate_set_member
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER candidate_evidence_mapping_immutable
    BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

-- ============================================================
-- Closure: CandidateSet root must have exactly 1..8 members at COMMIT
-- (rejects root-only / 0-member commits that no member trigger would catch)
-- ============================================================
CREATE FUNCTION memory.enforce_candidate_set_root_members()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_count bigint;
BEGIN
    SELECT count(*) INTO v_count
      FROM memory.candidate_set_member
     WHERE candidate_set_id = NEW.candidate_set_id;
    IF v_count < 1 OR v_count > 8 THEN
        RAISE EXCEPTION 'HDM020_CANDIDATE_SET_SIZE_INVALID count=%', v_count USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER candidate_set_root_members_guard
    AFTER INSERT ON memory.candidate_set
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_candidate_set_root_members();

-- ============================================================
-- Closure: member ↔ ReviewSession / ProposalRevision / Decision / action /
--          target / expected / evidence rules
-- ============================================================
CREATE FUNCTION memory.enforce_candidate_set_member_closure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_root_review uuid;
    v_count bigint;
    v_min bigint;
    v_max bigint;
BEGIN
    SELECT review_session_id INTO v_root_review
      FROM memory.candidate_set
     WHERE candidate_set_id = NEW.candidate_set_id;

    -- 1..8 members with contiguous ordinal starting at 1
    SELECT count(*), min(ordinal), max(ordinal)
      INTO v_count, v_min, v_max
      FROM memory.candidate_set_member
     WHERE candidate_set_id = NEW.candidate_set_id;
    IF v_count < 1 OR v_count > 8 THEN
        RAISE EXCEPTION 'HDM020_CANDIDATE_SET_SIZE_INVALID count=%', v_count USING ERRCODE = '23514';
    END IF;
    IF v_min <> 1 OR v_max <> v_count THEN
        RAISE EXCEPTION 'HDM020_CANDIDATE_ORDINAL_NOT_CONTIGUOUS min=% max=% count=%',
            v_min, v_max, v_count USING ERRCODE = '23514';
    END IF;

    -- disposition ↔ verdict Decision kind; Decision binds this review session + proposal revision
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
          JOIN memory.decision d ON d.decision_id = m.decision_id
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND ((m.disposition = 'ACCEPTED' AND d.decision_kind <> 'USER_CONFIRM')
             OR (m.disposition = 'REJECTED' AND d.decision_kind <> 'USER_REJECT')
             OR d.review_session_id IS DISTINCT FROM v_root_review
             OR d.proposal_revision_id IS DISTINCT FROM m.proposal_revision_id)
    ) THEN
        RAISE EXCEPTION 'HDM020_MEMBER_DECISION_BINDING_MISMATCH' USING ERRCODE = '23514';
    END IF;

    -- member ProposalRevision must be a ReviewMember of the root ReviewSession
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND NOT EXISTS (
               SELECT 1 FROM memory.review_member rm
                WHERE rm.review_session_id = v_root_review
                  AND rm.proposal_revision_id = m.proposal_revision_id)
    ) THEN
        RAISE EXCEPTION 'HDM020_MEMBER_PROPOSAL_NOT_REVIEW_MEMBER' USING ERRCODE = '23514';
    END IF;

    -- member ProposalRevision must not be reused by another CandidateSet
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
          JOIN memory.candidate_set_member o ON o.proposal_revision_id = m.proposal_revision_id
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND o.candidate_set_id <> m.candidate_set_id
    ) THEN
        RAISE EXCEPTION 'HDM020_MEMBER_PROPOSAL_REUSED' USING ERRCODE = '23514';
    END IF;

    -- action/target/expected revision/policy must match Proposal + ProposalRevision
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
          JOIN memory.proposal_revision pr ON pr.proposal_revision_id = m.proposal_revision_id
          JOIN memory.proposal p ON p.proposal_id = pr.proposal_id
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND (p.proposal_kind IS DISTINCT FROM m.action
             OR p.target_memory_id IS DISTINCT FROM m.target_memory_id
             OR pr.expected_memory_revision_id IS DISTINCT FROM m.expected_memory_revision_id
             OR pr.expected_policy_revision_no IS DISTINCT FROM m.expected_policy_revision_no)
    ) THEN
        RAISE EXCEPTION 'HDM020_MEMBER_ACTION_BINDING_MISMATCH' USING ERRCODE = '23514';
    END IF;

    -- Decision target matrix: accepted CREATE/REVISE/SUPERSEDE vs rejected
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
          JOIN memory.decision d ON d.decision_id = m.decision_id
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND (
               -- rejected: USER_REJECT targets the Proposal at revisionNo=1
               (m.disposition = 'REJECTED'
                AND (d.target_kind <> 'PROPOSAL'
                  OR d.target_id IS DISTINCT FROM (
                      SELECT pr.proposal_id FROM memory.proposal_revision pr
                       WHERE pr.proposal_revision_id = m.proposal_revision_id)
                  OR d.target_revision_ref <> 1))
             OR
               -- accepted CREATE: target deterministic future memory at revision 1
               (m.disposition = 'ACCEPTED' AND m.action = 'CREATE'
                AND (d.target_kind <> 'MEMORY'
                  OR d.target_id <> m.future_memory_id
                  OR d.target_revision_ref <> 1))
             OR
               -- accepted REVISE: target the existing memory at expectedRevisionNo+1
               (m.disposition = 'ACCEPTED' AND m.action = 'REVISE'
                AND (d.target_kind <> 'MEMORY'
                  OR d.target_id <> m.target_memory_id
                  OR m.future_memory_id <> m.target_memory_id
                  OR d.target_revision_ref IS DISTINCT FROM (
                      SELECT mr.revision_no + 1 FROM memory.memory_revision mr
                       WHERE mr.memory_revision_id = m.expected_memory_revision_id)))
             OR
               -- accepted SUPERSEDE: target a distinct deterministic future memory at revision 1
               (m.disposition = 'ACCEPTED' AND m.action = 'SUPERSEDE'
                AND (d.target_kind <> 'MEMORY'
                  OR d.target_id <> m.future_memory_id
                  OR d.target_revision_ref <> 1
                  OR m.future_memory_id = m.target_memory_id))
           )
    ) THEN
        RAISE EXCEPTION 'HDM020_MEMBER_DECISION_TARGET_MISMATCH' USING ERRCODE = '23514';
    END IF;

    -- accepted member must have at least one evidence mapping; rejected must have none
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND m.disposition = 'ACCEPTED'
           AND NOT EXISTS (
               SELECT 1 FROM memory.candidate_evidence_mapping e
                WHERE e.candidate_set_id = m.candidate_set_id
                  AND e.candidate_id = m.candidate_id)
    ) THEN
        RAISE EXCEPTION 'HDM020_ACCEPTED_CANDIDATE_NO_EVIDENCE' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND m.disposition = 'REJECTED'
           AND EXISTS (
               SELECT 1 FROM memory.candidate_evidence_mapping e
                WHERE e.candidate_set_id = m.candidate_set_id
                  AND e.candidate_id = m.candidate_id)
    ) THEN
        RAISE EXCEPTION 'HDM020_REJECTED_CANDIDATE_HAS_EVIDENCE' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER candidate_set_member_closure_guard
    AFTER INSERT ON memory.candidate_set_member
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_candidate_set_member_closure();

-- ============================================================
-- Closure: every evidence mapping must reference an ACCEPTED member
-- ============================================================
CREATE FUNCTION memory.enforce_candidate_evidence_mapping_member()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    v_cnt bigint;
    v_min bigint;
    v_max bigint;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM memory.candidate_set_member m
         WHERE m.candidate_set_id = NEW.candidate_set_id
           AND m.candidate_id = NEW.candidate_id
           AND m.disposition = 'ACCEPTED') THEN
        RAISE EXCEPTION 'HDM020_EVIDENCE_MAPPING_NOT_ACCEPTED_MEMBER' USING ERRCODE = '23514';
    END IF;

    -- R2-03: mapping ordinal must be contiguous 1..N per (candidate_set_id, candidate_id).
    -- The PK already enforces no duplicate ordinal; this closes gaps / non-1 start.
    SELECT count(*), min(ordinal), max(ordinal)
      INTO v_cnt, v_min, v_max
      FROM memory.candidate_evidence_mapping
     WHERE candidate_set_id = NEW.candidate_set_id
       AND candidate_id = NEW.candidate_id;
    IF v_min <> 1 OR v_max <> v_cnt THEN
        RAISE EXCEPTION 'HDM020_MAPPING_ORDINAL_NOT_CONTIGUOUS' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER candidate_evidence_mapping_member_guard
    AFTER INSERT ON memory.candidate_evidence_mapping
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_candidate_evidence_mapping_member();

-- ============================================================
-- Privileges (API role only; INSERT/SELECT, no UPDATE/DELETE)
-- ============================================================
REVOKE ALL ON
    memory.candidate_set,
    memory.candidate_set_member,
    memory.candidate_evidence_mapping
FROM PUBLIC;

GRANT SELECT, INSERT ON
    memory.candidate_set,
    memory.candidate_set_member,
    memory.candidate_evidence_mapping
TO hide_nest_api;

-- ============================================================
-- Multi-member review decision governance
--
-- The frozen V005 require_governed_outbox matches only on (kind, id, revision,
-- event_type) and takes LIMIT 1. With 1..8 members sharing one review session
-- and revision, that helper cannot bind each final verdict Decision to its own
-- governed outbox (it would match an arbitrary sibling outbox and raise
-- HDM005_GOVERNED_DECISION_BINDING_MISMATCH). This narrow replacement of the
-- review-decision trigger's backing function matches the outbox on the exact
-- decision_id. It does not touch require_governed_outbox or any other frozen
-- governance trigger, and it keeps the same fail-closed error code.
-- ============================================================
CREATE OR REPLACE FUNCTION memory.enforce_review_decision_governance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.review_session_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1
              FROM runtime.outbox_event AS event
              JOIN memory.change_event AS change
                ON change.change_event_id = event.change_event_id
             WHERE event.event_category = 'GOVERNED'
               AND event.event_type = 'review.decisions-committed.v1'
               AND event.aggregate_kind = 'REVIEW_SESSION'
               AND event.aggregate_id = NEW.review_session_id
               AND event.aggregate_revision IS NOT DISTINCT FROM NEW.target_revision_ref
               AND change.target_kind = 'REVIEW_SESSION'
               AND change.target_id = NEW.review_session_id
               AND change.target_revision_ref IS NOT DISTINCT FROM NEW.target_revision_ref
               AND change.decision_id = NEW.decision_id
        ) THEN
            RAISE EXCEPTION 'HDM005_GOVERNED_WRITE_OUTBOX_REQUIRED kind=REVIEW_SESSION id=% revision=% event=review.decisions-committed.v1',
                NEW.review_session_id, NEW.target_revision_ref USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- ============================================================
-- Function privileges (consistent with V006): PUBLIC has no EXECUTE
-- on the new CandidateSet integrity functions.
-- ============================================================
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA memory FROM PUBLIC;
