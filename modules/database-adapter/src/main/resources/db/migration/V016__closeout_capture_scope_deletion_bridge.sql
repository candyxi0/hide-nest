-- V016 | Local V1 | closeout capture_scope deletion bridge
--
-- Bridges the frozen database erasure (V014) with the formal closeout write
-- vertical (V009 capture_scope). Closeout leaves runtime.capture_scope_unit
-- rows referencing evidence.source_unit (ON DELETE NO ACTION); V014 deletes
-- the closure's source_unit directly, which would violate that FK for any
-- memory produced by the formal closeout entry.
--
-- V016 does NOT widen the FK, add ON DELETE CASCADE, or change any table
-- structure. It only:
--   1. allows runtime.enforce_capture_scope_unit_frozen to permit a DELETE
--      that is exactly bound to the active erasure marker's
--      SOURCE_UNIT + DELETE_CANDIDATE member; and
--   2. clears those capture_scope_unit rows inside
--      runtime.execute_confirmed_deletion_database_phase before the
--      source_unit DELETE.
--
-- runtime.capture_scope and runtime.closeout_run are intentionally preserved
-- as body-less audit summaries; no marker access is granted to api/worker.

-- ============================================================
-- 1. Erasure-marker-aware capture_scope_unit DELETE
--    Ordinary INSERT/UPDATE/DELETE frozen rules are unchanged. DELETE is
--    additionally allowed only when the active deletion_erasure_marker binds
--    OLD.source_unit_id as a SOURCE_UNIT + DELETE_CANDIDATE member of that
--    marker's closure. UPDATE is never allowed, and neither same-source,
--    same-scope, nor no-marker can bypass.
-- ============================================================
CREATE OR REPLACE FUNCTION runtime.enforce_capture_scope_unit_frozen()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime, memory
AS $$
DECLARE
    scope_frozen timestamptz;
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF EXISTS (
            SELECT 1
            FROM runtime.deletion_erasure_marker m
            JOIN memory.deletion_closure_member dcm
              ON dcm.closure_id = m.closure_id
             AND dcm.member_kind = 'SOURCE_UNIT'
             AND dcm.disposition = 'DELETE_CANDIDATE'
             AND dcm.target_id = OLD.source_unit_id
        ) THEN
            RETURN OLD;
        END IF;
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT frozen_at INTO scope_frozen
        FROM runtime.capture_scope WHERE scope_id = NEW.scope_id;
        IF scope_frozen IS NOT NULL THEN
            RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_UNIT_FROZEN scope_id=% unit=%',
                NEW.scope_id, NEW.source_unit_id USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT frozen_at INTO scope_frozen
        FROM runtime.capture_scope WHERE scope_id = OLD.scope_id;
        IF scope_frozen IS NOT NULL THEN
            RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_UNIT_FROZEN scope_id=% unit=%',
                OLD.scope_id, OLD.source_unit_id USING ERRCODE = '23514';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.scope_id IS DISTINCT FROM NEW.scope_id
           OR OLD.source_unit_id IS DISTINCT FROM NEW.source_unit_id THEN
            RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_UNIT_IDENTITY_IMMUTABLE'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

-- ============================================================
-- 2. execute_confirmed_deletion_database_phase with the capture_scope_unit bridge
--    Preserves V014 checks, locks, shared-reference rejection, erasure order,
--    return values, and internal diagnostic identifiers verbatim. The only
--    addition clears capture_scope_unit rows for this closure's SOURCE_UNIT
--    DELETE_CANDIDATE members before the source_unit DELETE.
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
    v_affected_exists boolean;
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

    -- (4) Fail closed: AFFECTED_PENDING_CHOICE must not exist
    SELECT EXISTS (
        SELECT 1 FROM memory.deletion_closure_member dcm
        WHERE dcm.closure_id = p_closure_id
          AND dcm.disposition = 'AFFECTED_PENDING_CHOICE'
    ) INTO v_affected_exists;
    IF v_affected_exists THEN
        RAISE EXCEPTION 'HDM014_DELETION_CHOICE_REQUIRED'
            USING ERRCODE = '23514';
    END IF;

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

    -- Every non-AFFECTED member must have a matching fence
    IF EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.disposition <> 'AFFECTED_PENDING_CHOICE'
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

    -- Every fence must have a matching non-AFFECTED member
    IF EXISTS (
        SELECT 1 FROM memory.deletion_fence f
        WHERE f.closure_id = p_closure_id
          AND NOT EXISTS (
            SELECT 1 FROM memory.deletion_closure_member m
            WHERE m.closure_id = f.closure_id
              AND m.disposition <> 'AFFECTED_PENDING_CHOICE'
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
          AND m.target_id = sa.anchor_id
    )
    FOR UPDATE;

    PERFORM FROM evidence.source_unit su
    WHERE EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_UNIT'
          AND m.target_id = su.source_unit_id
    )
    FOR UPDATE;

    PERFORM FROM evidence.source_payload sp
    WHERE EXISTS (
        SELECT 1 FROM memory.deletion_closure_member m
        WHERE m.closure_id = p_closure_id
          AND m.member_kind = 'SOURCE_PAYLOAD'
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

    -- (8) Check shared references outside closure.
    --     Anchor used by memory_relation from a revision NOT in closure.
    IF EXISTS (
        SELECT 1 FROM memory.memory_relation mr3
        WHERE mr3.to_anchor_id IN (
            SELECT m.target_id FROM memory.deletion_closure_member m
            WHERE m.closure_id = p_closure_id AND m.member_kind = 'SOURCE_ANCHOR'
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
        )
        AND sau.anchor_id NOT IN (
            SELECT m2.target_id FROM memory.deletion_closure_member m2
            WHERE m2.closure_id = p_closure_id AND m2.member_kind = 'SOURCE_ANCHOR'
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
        )
        AND EXISTS (
            SELECT 1 FROM evidence.source_payload sp2
            WHERE sp2.object_ref = sp1.object_ref
              AND sp2.payload_id <> sp1.payload_id
              AND sp2.payload_id NOT IN (
                SELECT m2.target_id FROM memory.deletion_closure_member m2
                WHERE m2.closure_id = p_closure_id AND m2.member_kind = 'SOURCE_PAYLOAD'
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

    -- (10) Insert deletion_payload_task rows from SOURCE_PAYLOAD members
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
    --      This covers both the initial CREATE path (decision_kind=USER_CONFIRM,
    --      target_kind=MEMORY, target_id=root_memory_id) and any subsequent
    --      REVISE decisions on the same memory.
    UPDATE memory.proposal_revision pr
    SET body_text = NULL, body_hash = NULL
    FROM memory.decision d
    WHERE d.target_kind = 'MEMORY'
      AND d.target_id = v_root_memory_id
      AND d.proposal_revision_id = pr.proposal_revision_id;

    -- (13) Handle CREATE proposal path: proposals where target_memory_id
    --      is NULL (memory didn't exist at Stage A) but Decision target
    --      is the root memory. The body was already cleared in step (12).
    --      Additionally clear body for the case where the CREATE decision
    --      used a proposal_revision that did NOT have a direct decision
    --      row with target_kind=MEMORY — e.g., the HIDE_SELECT decision
    --      for the access_policy. These proposal_revisions' body also
    --      need clearing because their body_text was the original CREATE
    --      proposal text for the root memory.
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
    --      (only NULL out, preserve governance history)
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
    --      and to-side anchor references in closure
    DELETE FROM memory.memory_relation
    WHERE from_revision_id IN (
        SELECT memory_revision_id FROM memory.memory_revision
        WHERE memory_id = v_root_memory_id
    );

    -- (16) DELETE source_anchor_unit for closure's anchors
    DELETE FROM evidence.source_anchor_unit
    WHERE anchor_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_ANCHOR'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (17) DELETE source_payload metadata for closure's payloads
    DELETE FROM evidence.source_payload
    WHERE payload_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_PAYLOAD'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (18) DELETE source_anchor for closure's anchors
    DELETE FROM evidence.source_anchor
    WHERE anchor_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_ANCHOR'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (18a) DELETE capture_scope_unit rows referencing this closure's
    --       SOURCE_UNIT + DELETE_CANDIDATE members. The erasure marker from
    --       step (11) authorizes this via enforce_capture_scope_unit_frozen.
    --       capture_scope and closeout_run are preserved as body-less audit.
    DELETE FROM runtime.capture_scope_unit
    WHERE source_unit_id IN (
        SELECT target_id FROM memory.deletion_closure_member
        WHERE closure_id = p_closure_id
          AND member_kind = 'SOURCE_UNIT'
          AND disposition = 'DELETE_CANDIDATE'
    );

    -- (19) DELETE source_unit for closure's units
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
