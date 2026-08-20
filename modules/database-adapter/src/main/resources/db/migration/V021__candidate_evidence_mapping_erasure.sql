-- V021 | Local V1 | CandidateSet evidence-mapping erasure bridge
--
-- V020 made memory.candidate_evidence_mapping immutable and bound it to
-- evidence.source_anchor with ON DELETE NO ACTION. The V017 erasure function
-- predates that mapping, so a confirmed permanent deletion can otherwise fail
-- with candidate_evidence_mapping_anchor_fk.
--
-- This forward-only migration preserves CandidateSet governance facts. It
-- permits only the formal erasure transaction to delete mappings whose anchor
-- is an exact SOURCE_ANCHOR + DELETE_CANDIDATE member of the confirmed closure.
-- UPDATE and ordinary DELETE remain forbidden; no FK, table privilege, or
-- canonical failure-code registry is widened.

-- ============================================================
-- 1. Erasure-aware CandidateSet evidence-mapping immutability
-- ============================================================
CREATE FUNCTION memory.enforce_candidate_evidence_mapping_immutable_or_erasure()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, runtime
AS $$
BEGIN
    IF TG_OP = 'DELETE'
       AND EXISTS (
            SELECT 1
            FROM runtime.deletion_erasure_marker marker
            JOIN memory.deletion_closure closure
              ON closure.closure_id = marker.closure_id
             AND closure.state = 'CONFIRMED'
             AND closure.confirmed_by_decision_id IS NOT NULL
            JOIN runtime.deletion_run run
              ON run.closure_id = marker.closure_id
             AND run.confirmed_by_decision_id = closure.confirmed_by_decision_id
             AND run.state = 'FILE_PENDING'
             AND run.started_at = marker.inserted_at
             AND run.database_erased_at = marker.inserted_at
            JOIN memory.deletion_closure_member member
              ON member.closure_id = marker.closure_id
             AND member.member_kind = 'SOURCE_ANCHOR'
             AND member.target_id = OLD.anchor_id
             AND member.disposition = 'DELETE_CANDIDATE'
            JOIN memory.deletion_fence fence
              ON fence.closure_id = member.closure_id
             AND fence.target_kind = member.member_kind
             AND fence.target_id = member.target_id
             AND fence.target_revision_ref IS NOT DISTINCT FROM member.target_revision_ref
             AND fence.created_by_decision_id = closure.confirmed_by_decision_id
            WHERE marker.xmin::text = pg_current_xact_id()::text
              AND run.xmin::text = pg_current_xact_id()::text
       ) THEN
        RETURN OLD;
    END IF;

    RAISE EXCEPTION 'HDM021_CANDIDATE_EVIDENCE_MAPPING_ERASURE_DENIED'
        USING ERRCODE = '23514';
END
$$;

DROP TRIGGER candidate_evidence_mapping_immutable
    ON memory.candidate_evidence_mapping;

CREATE TRIGGER candidate_evidence_mapping_immutable
    BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping
    FOR EACH ROW
    EXECUTE FUNCTION memory.enforce_candidate_evidence_mapping_immutable_or_erasure();

-- ============================================================
-- 2. V017 complete carry-forward with one narrow mapping DELETE
-- ============================================================
CREATE OR REPLACE FUNCTION runtime.execute_confirmed_deletion_database_phase(
    p_run_id uuid,
    p_closure_id uuid,
    p_executed_at timestamptz
)
RETURNS TABLE(
    o_run_id uuid,
    o_closure_id uuid,
    o_state text,
    o_payload_task_count bigint,
    o_database_erased_at timestamptz
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, runtime, memory, evidence
AS $$
DECLARE
    v_closure_state text;
    v_root_memory_id uuid;
    v_preview_revision bigint;
    v_confirmed_decision_id uuid;
    v_existing_run_id uuid;
    v_existing_state text;
    v_payload_count bigint;
BEGIN
    -- (1) Validate parameters
    IF p_run_id IS NULL OR p_closure_id IS NULL OR p_executed_at IS NULL THEN
        RAISE EXCEPTION 'HDM014_DELETION_INVALID_PARAMS null parameter'
            USING ERRCODE = '23514';
    END IF;
    IF p_executed_at > clock_timestamp() THEN
        RAISE EXCEPTION 'HDM014_DELETION_FUTURE_EXECUTED_AT'
            USING ERRCODE = '23514';
    END IF;

    -- (2) FOR UPDATE lock closure; verify CONFIRMED + exact Decision binding
    SELECT dc.state, dc.root_memory_id, dc.preview_revision, dc.confirmed_by_decision_id
      INTO v_closure_state, v_root_memory_id, v_preview_revision, v_confirmed_decision_id
    FROM memory.deletion_closure dc
    WHERE dc.closure_id = p_closure_id
    FOR UPDATE;

    IF v_closure_state IS NULL THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_NOT_FOUND'
            USING ERRCODE = '23514';
    END IF;
    IF v_closure_state <> 'CONFIRMED' THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_NOT_CONFIRMED'
            USING ERRCODE = '23514';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM memory.decision d
        WHERE d.decision_id = v_confirmed_decision_id
          AND d.decision_kind = 'USER_DELETE_CONFIRM'
          AND d.target_kind = 'DELETION_CLOSURE'
          AND d.target_id = p_closure_id
          AND d.target_revision_ref = v_preview_revision
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_DECISION_MISMATCH'
            USING ERRCODE = '23514';
    END IF;

    -- (3) Check existing deletion_run
    SELECT dr.deletion_run_id, dr.state INTO v_existing_run_id, v_existing_state
    FROM runtime.deletion_run dr
    WHERE dr.closure_id = p_closure_id;

    IF v_existing_run_id IS NOT NULL THEN
        IF v_existing_run_id = p_run_id AND v_existing_state = 'FILE_PENDING' THEN
            -- Exact replay: return existing metadata, zero new work
            RETURN QUERY
            SELECT r.deletion_run_id, r.closure_id, r.state,
                   r.payload_task_count, r.database_erased_at
            FROM runtime.deletion_run r
            WHERE r.deletion_run_id = p_run_id;
            RETURN;
        END IF;
        RAISE EXCEPTION 'HDM014_DELETION_RUN_CONFLICT'
            USING ERRCODE = '23514';
    END IF;

    -- (4) (removed) AFFECTED_PENDING_CHOICE choice-required fail-closed.
    --     Shared objects are now represented as RETAIN_SHARED and are a
    --     valid, non-blocking part of the closure; the erasure steps below
    --     delete only DELETE_CANDIDATE objects.

    -- (5) Re-verify closure member <-> fence bidirectional equality +
    --     Decision consistency + root MEMORY exists.
    --     Any drift since confirmation → HDM014_DELETION_CLOSURE_DRIFT.
    IF NOT EXISTS (
        SELECT 1 FROM memory.memory_record WHERE memory_id = v_root_memory_id
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT root memory not found'
            USING ERRCODE = '23514';
    END IF;

    -- Root MEMORY member must exist with correct disposition
    IF NOT EXISTS (
        SELECT 1 FROM memory.deletion_closure_member dcm
        WHERE dcm.closure_id = p_closure_id
          AND dcm.member_kind = 'MEMORY'
          AND dcm.target_id = v_root_memory_id
          AND dcm.target_revision_ref IS NULL
          AND dcm.disposition = 'DELETE_REQUESTED'
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT root memory member mismatch'
            USING ERRCODE = '23514';
    END IF;

    -- Every DELETE_REQUESTED / DELETE_CANDIDATE member must have a matching fence
    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
          AND NOT EXISTS (
            SELECT 1 FROM memory.deletion_fence f
            WHERE f.closure_id = m.closure_id
              AND f.target_kind = m.member_kind
              AND f.target_id = m.target_id
              AND f.target_revision_ref IS NOT DISTINCT FROM m.target_revision_ref
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT member without fence'
            USING ERRCODE = '23514';
    END IF;

    -- Every fence must have a matching DELETE_REQUESTED / DELETE_CANDIDATE member
    IF EXISTS (
        SELECT 1 FROM memory.deletion_fence f
        WHERE f.closure_id = p_closure_id
          AND NOT EXISTS (
            SELECT 1 FROM memory.deletion_closure_member m
            WHERE m.closure_id = f.closure_id
              AND m.disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
              AND m.member_kind = f.target_kind
              AND m.target_id = f.target_id
              AND m.target_revision_ref IS NOT DISTINCT FROM f.target_revision_ref
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT fence without member'
            USING ERRCODE = '23514';
    END IF;

    -- No duplicate fences per (target_kind, target_id, target_revision_ref)
    IF EXISTS (
        SELECT 1 FROM memory.deletion_fence f
        WHERE f.closure_id = p_closure_id
        GROUP BY f.target_kind, f.target_id, f.target_revision_ref
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT duplicate fence'
            USING ERRCODE = '23514';
    END IF;

    -- No duplicate members per (member_kind, target_id, target_revision_ref)
    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
        GROUP BY m.member_kind, m.target_id, m.target_revision_ref
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT duplicate member'
            USING ERRCODE = '23514';
    END IF;

    -- All fences must be from the same confirmed decision
    IF EXISTS (
        SELECT 1 FROM memory.deletion_fence f
        WHERE f.closure_id = p_closure_id
          AND f.created_by_decision_id IS DISTINCT FROM v_confirmed_decision_id
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT fence decision mismatch'
            USING ERRCODE = '23514';
    END IF;

    -- (6) Lock all target rows FOR UPDATE
    PERFORM FROM memory.memory_record WHERE memory_id = v_root_memory_id FOR UPDATE;

    PERFORM FROM memory.memory_revision mr
    WHERE mr.memory_id = v_root_memory_id FOR UPDATE;

    PERFORM FROM memory.memory_relation mr2
    WHERE mr2.from_revision_id IN (
        SELECT mrev.memory_revision_id FROM memory.memory_revision mrev
        WHERE mrev.memory_id = v_root_memory_id
    )
    FOR UPDATE;

    PERFORM FROM evidence.source_anchor sa
    WHERE EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_ANCHOR'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND m.target_id = sa.anchor_id
    )
    FOR UPDATE;

    PERFORM FROM evidence.source_unit su
    WHERE EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_UNIT'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND m.target_id = su.source_unit_id
    )
    FOR UPDATE;

    PERFORM FROM evidence.source_payload sp
    WHERE EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_PAYLOAD'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND m.target_id = sp.payload_id
    )
    FOR UPDATE;

    -- (7) Verify DB graph = DELETE_REQUESTED/DELETE_CANDIDATE sets exactly.
    --     Every closed revision must exist and belong to root memory.
    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'MEMORY_REVISION'
          AND m.disposition = 'DELETE_REQUESTED'
          AND NOT EXISTS (
            SELECT 1 FROM memory.memory_revision mr
            WHERE mr.memory_revision_id = m.target_id
              AND mr.revision_no = m.target_revision_ref
              AND mr.memory_id = v_root_memory_id
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT revision member not found'
            USING ERRCODE = '23514';
    END IF;

    -- Every closed anchor/unit/payload must exist
    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_ANCHOR'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND NOT EXISTS (
            SELECT 1 FROM evidence.source_anchor WHERE anchor_id = m.target_id
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT anchor not found'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_UNIT'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND NOT EXISTS (
            SELECT 1 FROM evidence.source_unit WHERE source_unit_id = m.target_id
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT unit not found'
            USING ERRCODE = '23514';
    END IF;

    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_PAYLOAD'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND NOT EXISTS (
            SELECT 1 FROM evidence.source_payload WHERE payload_id = m.target_id
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT payload not found'
            USING ERRCODE = '23514';
    END IF;

    -- Verify no extra revisions of root memory exist outside closure
    IF EXISTS (
        SELECT 1 FROM memory.memory_revision mr
        WHERE mr.memory_id = v_root_memory_id
          AND NOT EXISTS (
            SELECT 1 FROM memory.deletion_closure_member m
            WHERE m.closure_id = p_closure_id
              AND m.member_kind = 'MEMORY_REVISION'
              AND m.target_id = mr.memory_revision_id
              AND m.target_revision_ref = mr.revision_no
          )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_CLOSURE_DRIFT extra revision outside closure'
            USING ERRCODE = '23514';
    END IF;

    -- (8) Check shared references outside closure — only for DELETE_CANDIDATE
    --     (exclusive) objects. RETAIN_SHARED anchors/units/payloads are
    --     expected to have external references and are exempt.
    --     Anchor used by memory_relation from a revision NOT in closure.
    IF EXISTS (
        SELECT 1 FROM memory.memory_relation mr3
        WHERE mr3.to_anchor_id IN (
            SELECT m.target_id FROM memory.deletion_closure_member m
            WHERE m.closure_id = p_closure_id AND m.member_kind = 'SOURCE_ANCHOR'
              AND m.disposition = 'DELETE_CANDIDATE'
        )
        AND mr3.from_revision_id NOT IN (
            SELECT m2.target_id FROM memory.deletion_closure_member m2
            WHERE m2.closure_id = p_closure_id AND m2.member_kind = 'MEMORY_REVISION'
        )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_SHARED_REFERENCE anchor used outside closure'
            USING ERRCODE = '23514';
    END IF;

    -- Anchor_unit referenced by a closure-external source_anchor_unit
    -- (different anchor referencing same unit)
    IF EXISTS (
        SELECT 1 FROM evidence.source_anchor_unit sau
        WHERE sau.source_unit_id IN (
            SELECT m.target_id FROM memory.deletion_closure_member m
            WHERE m.closure_id = p_closure_id AND m.member_kind = 'SOURCE_UNIT'
              AND m.disposition = 'DELETE_CANDIDATE'
        )
        AND sau.anchor_id NOT IN (
            SELECT m2.target_id FROM memory.deletion_closure_member m2
            WHERE m2.closure_id = p_closure_id AND m2.member_kind = 'SOURCE_ANCHOR'
              AND m2.disposition = 'DELETE_CANDIDATE'
        )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_SHARED_REFERENCE unit used outside closure'
            USING ERRCODE = '23514';
    END IF;

    -- Payload object_ref reused by a payload outside closure (different payload_id, same object_ref)
    IF EXISTS (
        SELECT 1 FROM evidence.source_payload sp1
        WHERE sp1.payload_id IN (
            SELECT m.target_id FROM memory.deletion_closure_member m
            WHERE m.closure_id = p_closure_id AND m.member_kind = 'SOURCE_PAYLOAD'
              AND m.disposition = 'DELETE_CANDIDATE'
        )
        AND EXISTS (
            SELECT 1 FROM evidence.source_payload sp2
            WHERE sp2.object_ref = sp1.object_ref
              AND sp2.payload_id <> sp1.payload_id
              AND sp2.payload_id NOT IN (
                SELECT m2.target_id FROM memory.deletion_closure_member m2
                WHERE m2.closure_id = p_closure_id AND m2.member_kind = 'SOURCE_PAYLOAD'
                  AND m2.disposition = 'DELETE_CANDIDATE'
              )
        )
    ) THEN
        RAISE EXCEPTION 'HDM014_DELETION_SHARED_REFERENCE payload object_ref reused'
            USING ERRCODE = '23514';
    END IF;

    -- (9) Insert deletion_run initial row
    INSERT INTO runtime.deletion_run (
        deletion_run_id, closure_id, confirmed_by_decision_id,
        state, started_at, database_erased_at, payload_task_count
    ) VALUES (
        p_run_id, p_closure_id, v_confirmed_decision_id,
        'FILE_PENDING', p_executed_at, p_executed_at, 0
    );

    -- (10) Insert deletion_payload_task rows from DELETE_CANDIDATE SOURCE_PAYLOAD members
    INSERT INTO runtime.deletion_payload_task (
        deletion_run_id, payload_id, object_ref, expected_hash, state, created_at
    )
    SELECT p_run_id, m.target_id, sp.object_ref, sp.content_hash, 'PENDING', p_executed_at
    FROM memory.deletion_closure_member m
    JOIN evidence.source_payload sp ON sp.payload_id = m.target_id
    WHERE m.closure_id = p_closure_id
      AND m.member_kind = 'SOURCE_PAYLOAD'
      AND m.disposition = 'DELETE_CANDIDATE';

    GET DIAGNOSTICS v_payload_count = ROW_COUNT;

    -- (11) Insert erasure marker — authorizes the deletions below
    INSERT INTO runtime.deletion_erasure_marker (closure_id, inserted_at)
    VALUES (p_closure_id, p_executed_at);

    -- (12) Clear proposal_revision body_text/body_hash for revisions
    --      whose creation Decision targeted the root memory.
    UPDATE memory.proposal_revision pr
    SET body_text = NULL, body_hash = NULL
    FROM memory.decision d
    WHERE d.target_kind = 'MEMORY'
      AND d.target_id = v_root_memory_id
      AND d.proposal_revision_id = pr.proposal_revision_id;

    -- (13) Handle CREATE proposal path (see V014/V016 rationale)
    UPDATE memory.proposal_revision pr
    SET body_text = NULL, body_hash = NULL
    WHERE pr.proposal_id IN (
        SELECT p.proposal_id FROM memory.proposal p
        WHERE p.target_memory_id = v_root_memory_id
           OR (p.target_memory_id IS NULL
               AND EXISTS (
                   SELECT 1 FROM memory.decision d
                   JOIN memory.memory_revision mr
                     ON mr.created_by_decision_id = d.decision_id
                   WHERE d.proposal_revision_id = pr.proposal_revision_id
                     AND mr.memory_id = v_root_memory_id
               ))
    );

    -- (14) Break proposal links to the root memory and its revisions
    UPDATE memory.proposal
    SET target_memory_id = NULL
    WHERE target_memory_id = v_root_memory_id;

    UPDATE memory.proposal_revision
    SET expected_memory_revision_id = NULL
    WHERE expected_memory_revision_id IN (
        SELECT memory_revision_id FROM memory.memory_revision
        WHERE memory_id = v_root_memory_id
    );

    -- (15) DELETE memory_relation — from-side for root memory revisions
    DELETE FROM memory.memory_relation
    WHERE from_revision_id IN (
        SELECT memory_revision_id FROM memory.memory_revision
        WHERE memory_id = v_root_memory_id
    );

    -- (16) DELETE source_anchor_unit for closure's exclusive anchors
    DELETE FROM evidence.source_anchor_unit
    WHERE anchor_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_ANCHOR'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (17) DELETE source_payload metadata for closure's exclusive payloads
    DELETE FROM evidence.source_payload
    WHERE payload_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_PAYLOAD'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (17a) DELETE CandidateSet evidence mappings for closure's exclusive anchors.
    --       Governance roots/members/decisions remain immutable audit facts.
    DELETE FROM memory.candidate_evidence_mapping cem
    WHERE EXISTS (
        SELECT 1
        FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_ANCHOR'
          AND m.disposition = 'DELETE_CANDIDATE'
          AND m.target_id = cem.anchor_id
    );

    -- (18) DELETE source_anchor for closure's exclusive anchors
    DELETE FROM evidence.source_anchor
    WHERE anchor_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_ANCHOR'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (18a) DELETE capture_scope_unit rows referencing this closure's
    --       exclusive SOURCE_UNIT + DELETE_CANDIDATE members.
    DELETE FROM runtime.capture_scope_unit
    WHERE source_unit_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_UNIT'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (19) DELETE source_unit for closure's exclusive units
    DELETE FROM evidence.source_unit
    WHERE source_unit_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_UNIT'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (20) DELETE memory_revision for all root memory revisions
    DELETE FROM memory.memory_revision
    WHERE memory_id = v_root_memory_id;

    -- (21) DELETE memory_record for root memory
    DELETE FROM memory.memory_record
    WHERE memory_id = v_root_memory_id;

    -- (22) Update run with final counts
    UPDATE runtime.deletion_run
    SET payload_task_count = v_payload_count
    WHERE deletion_run_id = p_run_id;

    -- (23) DELETE erasure marker — re-arm the trigger guards
    DELETE FROM runtime.deletion_erasure_marker
    WHERE closure_id = p_closure_id;

    -- (24) Return metadata
    RETURN QUERY
    SELECT r.deletion_run_id, r.closure_id, r.state,
           r.payload_task_count, r.database_erased_at
    FROM runtime.deletion_run r
    WHERE r.deletion_run_id = p_run_id;
END
$$;

-- ============================================================
-- 3. Privileges: preserve the narrow V014 execution boundary
-- ============================================================
REVOKE ALL ON FUNCTION memory.enforce_candidate_evidence_mapping_immutable_or_erasure()
    FROM PUBLIC;
GRANT EXECUTE ON FUNCTION runtime.execute_confirmed_deletion_database_phase(uuid, uuid, timestamptz)
    TO hide_nest_worker;
REVOKE EXECUTE ON FUNCTION runtime.execute_confirmed_deletion_database_phase(uuid, uuid, timestamptz)
    FROM PUBLIC;
