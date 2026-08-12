-- V014 | Local V1 S3C1A | recoverable deletion database phase
-- Creates deletion_run, deletion_payload_task, and the SECURITY DEFINER
-- database function that atomically erases database-resident body text
-- and exclusive evidence metadata for a CONFIRMED deletion closure.

-- ============================================================
-- Erasure marker (authorization gate — no GRANT to api/worker)
-- ============================================================
CREATE TABLE runtime.deletion_erasure_marker (
    closure_id uuid PRIMARY KEY,
    inserted_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- ============================================================
-- deletion_run
-- ============================================================
CREATE TABLE runtime.deletion_run (
    deletion_run_id uuid PRIMARY KEY,
    closure_id uuid NOT NULL UNIQUE,
    confirmed_by_decision_id uuid NOT NULL,
    state text COLLATE "C" NOT NULL,
    started_at timestamptz NOT NULL,
    database_erased_at timestamptz NOT NULL,
    completed_at timestamptz,
    last_failure_code text COLLATE "C",
    payload_task_count bigint NOT NULL,
    CONSTRAINT deletion_run_closure_fk
        FOREIGN KEY (closure_id)
        REFERENCES memory.deletion_closure (closure_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_run_decision_fk
        FOREIGN KEY (confirmed_by_decision_id)
        REFERENCES memory.decision (decision_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_run_failure_code_fk
        FOREIGN KEY (last_failure_code)
        REFERENCES runtime.failure_code_registry (failure_code)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_run_state_check CHECK (state = 'FILE_PENDING'),
    CONSTRAINT deletion_run_payload_count_check CHECK (payload_task_count >= 0),
    CONSTRAINT deletion_run_erased_requires_started CHECK (
        database_erased_at >= started_at
    ),
    CONSTRAINT deletion_run_completed_requires_erased CHECK (
        completed_at IS NULL OR database_erased_at IS NOT NULL
    )
);

-- ============================================================
-- deletion_payload_task
-- ============================================================
CREATE TABLE runtime.deletion_payload_task (
    deletion_run_id uuid NOT NULL,
    payload_id uuid NOT NULL,
    object_ref text COLLATE "C" NOT NULL,
    expected_hash bytea NOT NULL,
    state text COLLATE "C" NOT NULL DEFAULT 'PENDING',
    created_at timestamptz NOT NULL,
    PRIMARY KEY (deletion_run_id, payload_id),
    CONSTRAINT deletion_payload_task_run_fk
        FOREIGN KEY (deletion_run_id)
        REFERENCES runtime.deletion_run (deletion_run_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_payload_task_object_ref_check CHECK (char_length(trim(object_ref)) > 0),
    CONSTRAINT deletion_payload_task_hash_check CHECK (octet_length(expected_hash) = 32),
    CONSTRAINT deletion_payload_task_state_check CHECK (state = 'PENDING'),
    CONSTRAINT deletion_payload_task_object_ref_unique UNIQUE (deletion_run_id, object_ref)
);

-- ============================================================
-- Make memory_revision_memory_fk DEFERRABLE so that
-- memory_revision can be deleted before memory_record
-- in the same transaction.
-- ============================================================
ALTER TABLE memory.memory_revision
    DROP CONSTRAINT memory_revision_memory_fk;

ALTER TABLE memory.memory_revision
    ADD CONSTRAINT memory_revision_memory_fk
    FOREIGN KEY (memory_id)
    REFERENCES memory.memory_record (memory_id)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;

-- ============================================================
-- Helper: is an erasure marker active for a fenced target?
-- Used by the modified triggers below.
-- ============================================================
CREATE FUNCTION memory.is_erasure_marker_active_for(
    p_target_kind text,
    p_target_id uuid,
    p_target_revision_ref bigint
)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path = pg_catalog, memory, runtime
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM runtime.deletion_erasure_marker m
        JOIN memory.deletion_fence f ON f.closure_id = m.closure_id
        WHERE f.target_kind = p_target_kind
          AND f.target_id = p_target_id
          AND f.target_revision_ref IS NOT DISTINCT FROM p_target_revision_ref
    )
$$;

-- ============================================================
-- Marker-aware replacement: memory_revision immutability
-- V005 blocks all UPDATE + DELETE via memory_revision_immutable.
-- V014 allows DELETE when an active erasure marker covers the
-- revision's memory or the revision itself.
-- UPDATE is still blocked unconditionally.
-- ============================================================
DROP TRIGGER memory_revision_immutable ON memory.memory_revision;

CREATE FUNCTION memory.enforce_memory_revision_immutable_or_erasure()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, runtime
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF memory.is_erasure_marker_active_for('MEMORY', OLD.memory_id, NULL)
           OR memory.is_erasure_marker_active_for('MEMORY_REVISION', OLD.memory_revision_id, OLD.revision_no) THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER memory_revision_immutable
    BEFORE UPDATE OR DELETE ON memory.memory_revision
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_revision_immutable_or_erasure();

-- ============================================================
-- Marker-aware replacement: memory_record DELETE guard
-- V005 blocks all DELETE via memory_record_delete_guard.
-- V014 allows DELETE when an active erasure marker covers the memory.
-- ============================================================
DROP TRIGGER memory_record_delete_guard ON memory.memory_record;

CREATE FUNCTION memory.enforce_memory_record_delete_or_erasure()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, runtime
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF memory.is_erasure_marker_active_for('MEMORY', OLD.memory_id, NULL) THEN
            RETURN OLD;
        END IF;
    END IF;
    RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER memory_record_delete_guard
    BEFORE DELETE ON memory.memory_record
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_delete_or_erasure();

-- ============================================================
-- Marker-aware replacement: proposal_revision immutability
-- V005 blocks all UPDATE + DELETE.
-- V014 allows UPDATE only when an active erasure marker for the
-- same closure binds this revision to its root_memory via the
-- governance chain, and only body_text, body_hash, and
-- expected_memory_revision_id may change (to NULL only).
-- DELETE remains blocked.
-- ============================================================
DROP TRIGGER proposal_revision_immutable ON memory.proposal_revision;

CREATE FUNCTION memory.enforce_proposal_revision_immutable_or_erasure()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, runtime
AS $$
DECLARE
    v_marker_closure_id uuid;
    v_marker_root_memory_id uuid;
    v_linked boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- Look up the active marker (at most one erasure runs at a time)
    SELECT closure_id INTO v_marker_closure_id
    FROM runtime.deletion_erasure_marker LIMIT 1;

    IF v_marker_closure_id IS NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- Get the root memory this marker's closure is erasing
    SELECT c.root_memory_id INTO v_marker_root_memory_id
    FROM memory.deletion_closure c
    WHERE c.closure_id = v_marker_closure_id;

    IF v_marker_root_memory_id IS NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- Prove OLD belongs to the marker closure's root memory governance chain.
    -- Path 1: proposal.target_memory_id = root_memory_id
    -- Path 2: decision used this proposal_revision to create the root memory
    SELECT EXISTS (
        SELECT 1 FROM memory.proposal p
        WHERE p.proposal_id = OLD.proposal_id
          AND p.target_memory_id = v_marker_root_memory_id
        UNION ALL
        SELECT 1 FROM memory.decision d
        WHERE d.proposal_revision_id = OLD.proposal_revision_id
          AND d.target_kind = 'MEMORY'
          AND d.target_id = v_marker_root_memory_id
        UNION ALL
        SELECT 1 FROM memory.decision d2
        JOIN memory.memory_revision mr
          ON mr.created_by_decision_id = d2.decision_id
        WHERE d2.proposal_revision_id = OLD.proposal_revision_id
          AND mr.memory_id = v_marker_root_memory_id
    ) INTO v_linked;

    IF NOT v_linked THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- ONLY body_text, body_hash, expected_memory_revision_id may change to NULL.
    -- All other columns must remain identical.
    IF NEW.proposal_revision_id IS DISTINCT FROM OLD.proposal_revision_id
       OR NEW.proposal_id IS DISTINCT FROM OLD.proposal_id
       OR NEW.revision_no IS DISTINCT FROM OLD.revision_no
       OR NEW.action_code IS DISTINCT FROM OLD.action_code
       OR NEW.memory_type IS DISTINCT FROM OLD.memory_type
       OR NEW.perspective_actor_id IS DISTINCT FROM OLD.perspective_actor_id
       OR NEW.expected_policy_revision_no IS DISTINCT FROM OLD.expected_policy_revision_no
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;
    IF NEW.body_text IS DISTINCT FROM OLD.body_text AND NEW.body_text IS NOT NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;
    IF NEW.body_hash IS DISTINCT FROM OLD.body_hash AND NEW.body_hash IS NOT NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;
    IF NEW.expected_memory_revision_id IS DISTINCT FROM OLD.expected_memory_revision_id
       AND NEW.expected_memory_revision_id IS NOT NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;
    IF NEW.expected_memory_revision_id IS DISTINCT FROM OLD.expected_memory_revision_id
       AND OLD.expected_memory_revision_id IS NOT NULL THEN
        -- The old value must belong to the root memory
        IF NOT EXISTS (
            SELECT 1 FROM memory.memory_revision mr
            WHERE mr.memory_revision_id = OLD.expected_memory_revision_id
              AND mr.memory_id = v_marker_root_memory_id
        ) THEN
            RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
                USING ERRCODE = '55000';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER proposal_revision_immutable
    BEFORE UPDATE OR DELETE ON memory.proposal_revision
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_proposal_revision_immutable_or_erasure();

-- ============================================================
-- Marker-aware replacement: proposal immutability
-- V005 blocks all UPDATE + DELETE.
-- V014 allows UPDATE only when an active erasure marker exists
-- for the same root_memory, and only target_memory_id may change
-- to NULL. All other columns must remain identical.
-- DELETE remains blocked.
-- ============================================================
DROP TRIGGER proposal_immutable ON memory.proposal;

CREATE FUNCTION memory.enforce_proposal_immutable_or_erasure()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, runtime
AS $$
DECLARE
    v_marker_closure_id uuid;
    v_marker_root_memory_id uuid;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- Look up the active marker
    SELECT closure_id INTO v_marker_closure_id
    FROM runtime.deletion_erasure_marker LIMIT 1;

    IF v_marker_closure_id IS NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    SELECT c.root_memory_id INTO v_marker_root_memory_id
    FROM memory.deletion_closure c
    WHERE c.closure_id = v_marker_closure_id;

    -- Must belong to the marked closure's root memory
    IF OLD.target_memory_id IS DISTINCT FROM v_marker_root_memory_id THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    -- Only target_memory_id may change to NULL; all other columns must match
    IF NEW.target_memory_id IS NOT NULL THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;
    IF NEW.proposal_id IS DISTINCT FROM OLD.proposal_id
       OR NEW.proposal_kind IS DISTINCT FROM OLD.proposal_kind
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER proposal_immutable
    BEFORE UPDATE OR DELETE ON memory.proposal
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_proposal_immutable_or_erasure();

-- ============================================================
-- SECURITY DEFINER: execute_confirmed_deletion_database_phase
-- Single-transaction database erasure for a CONFIRMED closure.
-- ============================================================
CREATE FUNCTION runtime.execute_confirmed_deletion_database_phase(
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

-- ============================================================
-- Privileges
-- ============================================================

-- Erasure marker: no access for api/worker
REVOKE ALL ON runtime.deletion_erasure_marker FROM PUBLIC;

-- Run + task tables: worker can read (for S3C1B/S3C2), api gets nothing
GRANT SELECT ON runtime.deletion_run, runtime.deletion_payload_task TO hide_nest_worker;

-- Only worker can EXECUTE the erasure function
GRANT EXECUTE ON FUNCTION runtime.execute_confirmed_deletion_database_phase(uuid, uuid, timestamptz)
    TO hide_nest_worker;
REVOKE EXECUTE ON FUNCTION runtime.execute_confirmed_deletion_database_phase(uuid, uuid, timestamptz)
    FROM PUBLIC;

-- Marker-aware trigger functions: revoke from PUBLIC (consistent with V006)
REVOKE ALL ON FUNCTION memory.is_erasure_marker_active_for(text, uuid, bigint) FROM PUBLIC;
REVOKE ALL ON FUNCTION memory.enforce_memory_revision_immutable_or_erasure() FROM PUBLIC;
REVOKE ALL ON FUNCTION memory.enforce_memory_record_delete_or_erasure() FROM PUBLIC;
REVOKE ALL ON FUNCTION memory.enforce_proposal_revision_immutable_or_erasure() FROM PUBLIC;
REVOKE ALL ON FUNCTION memory.enforce_proposal_immutable_or_erasure() FROM PUBLIC;

-- Revoke PUBLIC from the SECURITY DEFINER too (redundant with above but explicit)
REVOKE ALL ON FUNCTION runtime.execute_confirmed_deletion_database_phase(uuid, uuid, timestamptz)
    FROM PUBLIC;
