-- V015 | Local V1 S3C2 | deletion file settlement
-- Extends deletion_run and deletion_payload_task state machines
-- with COMPLETED/DELETED terminal states.
-- Adds SECURITY DEFINER functions for controlled file-phase settlement.

-- ============================================================
-- Extend deletion_run.state: FILE_PENDING | COMPLETED
-- ============================================================
ALTER TABLE runtime.deletion_run DROP CONSTRAINT deletion_run_state_check;
ALTER TABLE runtime.deletion_run ADD CONSTRAINT deletion_run_state_check
    CHECK (state IN ('FILE_PENDING', 'COMPLETED'));

-- ============================================================
-- Extend deletion_payload_task.state: PENDING | DELETED
-- ============================================================
ALTER TABLE runtime.deletion_payload_task DROP CONSTRAINT deletion_payload_task_state_check;
ALTER TABLE runtime.deletion_payload_task ADD CONSTRAINT deletion_payload_task_state_check
    CHECK (state IN ('PENDING', 'DELETED'));

-- ============================================================
-- SECURITY DEFINER: settle_deletion_payload_task
-- Idempotent single-task settlement: PENDING → DELETED.
-- Validates runId, payloadId, objectRef, expectedHash.
-- Exact replay of already-settled task returns existing result.
-- Any field mismatch → fail closed.
-- ============================================================
CREATE FUNCTION runtime.settle_deletion_payload_task(
    p_run_id uuid,
    p_payload_id uuid,
    p_object_ref text,
    p_expected_hash bytea
)
RETURNS TABLE(
    o_run_id uuid,
    o_payload_id uuid,
    o_state text,
    o_settled boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, runtime
AS $$
DECLARE
    v_state text;
    v_object_ref text;
    v_expected_hash bytea;
BEGIN
    -- Validate parameters
    IF p_run_id IS NULL OR p_payload_id IS NULL
       OR p_object_ref IS NULL OR p_expected_hash IS NULL THEN
        RAISE EXCEPTION 'HDM015_SETTLEMENT_INVALID_PARAMS null parameter'
            USING ERRCODE = '23514';
    END IF;
    IF octet_length(p_expected_hash) <> 32 THEN
        RAISE EXCEPTION 'HDM015_SETTLEMENT_INVALID_HASH'
            USING ERRCODE = '23514';
    END IF;

    -- Lock and read the task row
    SELECT t.state, t.object_ref, t.expected_hash
      INTO v_state, v_object_ref, v_expected_hash
    FROM runtime.deletion_payload_task t
    WHERE t.deletion_run_id = p_run_id
      AND t.payload_id = p_payload_id
    FOR UPDATE;

    IF v_state IS NULL THEN
        RAISE EXCEPTION 'HDM015_SETTLEMENT_TASK_NOT_FOUND'
            USING ERRCODE = '23514';
    END IF;

    -- Already settled: verify all fields match (idempotent replay)
    IF v_state = 'DELETED' THEN
        IF v_object_ref IS DISTINCT FROM p_object_ref THEN
            RAISE EXCEPTION 'HDM015_SETTLEMENT_OBJECT_REF_MISMATCH'
                USING ERRCODE = '23514';
        END IF;
        IF v_expected_hash IS DISTINCT FROM p_expected_hash THEN
            RAISE EXCEPTION 'HDM015_SETTLEMENT_HASH_MISMATCH'
                USING ERRCODE = '23514';
        END IF;
        -- Exact replay: return existing facts
        RETURN QUERY
        SELECT p_run_id, p_payload_id, 'DELETED'::text, false;
        RETURN;
    END IF;

    -- PENDING: validate exact match on object_ref and expected_hash
    IF v_object_ref IS DISTINCT FROM p_object_ref THEN
        RAISE EXCEPTION 'HDM015_SETTLEMENT_OBJECT_REF_MISMATCH'
            USING ERRCODE = '23514';
    END IF;
    IF v_expected_hash IS DISTINCT FROM p_expected_hash THEN
        RAISE EXCEPTION 'HDM015_SETTLEMENT_HASH_MISMATCH'
            USING ERRCODE = '23514';
    END IF;

    -- Transition PENDING → DELETED
    UPDATE runtime.deletion_payload_task
    SET state = 'DELETED'
    WHERE deletion_run_id = p_run_id
      AND payload_id = p_payload_id
      AND state = 'PENDING';

    RETURN QUERY
    SELECT p_run_id, p_payload_id, 'DELETED'::text, true;
END
$$;

-- ============================================================
-- SECURITY DEFINER: record_deletion_file_failure
-- Writes DELETION_EXECUTION_FAILED for a FILE_PENDING run.
-- Does not accept arbitrary failure code from caller.
-- ============================================================
CREATE FUNCTION runtime.record_deletion_file_failure(
    p_run_id uuid,
    p_failed_at timestamptz
)
RETURNS TABLE(
    o_run_id uuid,
    o_state text,
    o_failure_code text
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, runtime
AS $$
DECLARE
    v_state text;
BEGIN
    IF p_run_id IS NULL OR p_failed_at IS NULL THEN
        RAISE EXCEPTION 'HDM015_FAILURE_INVALID_PARAMS null parameter'
            USING ERRCODE = '23514';
    END IF;

    SELECT r.state INTO v_state
    FROM runtime.deletion_run r
    WHERE r.deletion_run_id = p_run_id
    FOR UPDATE;

    IF v_state IS NULL THEN
        RAISE EXCEPTION 'HDM015_FAILURE_RUN_NOT_FOUND'
            USING ERRCODE = '23514';
    END IF;

    IF v_state <> 'FILE_PENDING' THEN
        RAISE EXCEPTION 'HDM015_FAILURE_RUN_NOT_FILE_PENDING'
            USING ERRCODE = '23514';
    END IF;

    UPDATE runtime.deletion_run
    SET last_failure_code = 'DELETION_EXECUTION_FAILED'
    WHERE deletion_run_id = p_run_id;

    RETURN QUERY
    SELECT p_run_id, 'FILE_PENDING'::text, 'DELETION_EXECUTION_FAILED'::text;
END
$$;

-- ============================================================
-- SECURITY DEFINER: complete_deletion_run
-- Recalculates task count and state within the database.
-- If all tasks are DELETED and count matches payload_task_count,
-- atomically transitions to COMPLETED.
-- Incomplete → rejected.
-- Already COMPLETED → exact replay.
-- ============================================================
CREATE FUNCTION runtime.complete_deletion_run(
    p_run_id uuid,
    p_completed_at timestamptz
)
RETURNS TABLE(
    o_run_id uuid,
    o_closure_id uuid,
    o_state text,
    o_payload_task_count bigint,
    o_completed_at timestamptz,
    o_completed boolean
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, runtime
AS $$
DECLARE
    v_state text;
    v_closure_id uuid;
    v_payload_task_count bigint;
    v_actual_task_count bigint;
    v_deleted_task_count bigint;
    v_completed_at timestamptz;
    v_last_failure_code text;
BEGIN
    IF p_run_id IS NULL OR p_completed_at IS NULL THEN
        RAISE EXCEPTION 'HDM015_COMPLETE_INVALID_PARAMS null parameter'
            USING ERRCODE = '23514';
    END IF;

    -- Lock and read the run row
    SELECT r.state, r.closure_id, r.payload_task_count,
           r.completed_at, r.last_failure_code
      INTO v_state, v_closure_id, v_payload_task_count,
           v_completed_at, v_last_failure_code
    FROM runtime.deletion_run r
    WHERE r.deletion_run_id = p_run_id
    FOR UPDATE;

    IF v_state IS NULL THEN
        RAISE EXCEPTION 'HDM015_COMPLETE_RUN_NOT_FOUND'
            USING ERRCODE = '23514';
    END IF;

    -- Already COMPLETED: exact replay
    IF v_state = 'COMPLETED' THEN
        RETURN QUERY
        SELECT p_run_id, v_closure_id, 'COMPLETED'::text,
               v_payload_task_count, v_completed_at, false;
        RETURN;
    END IF;

    -- Must be FILE_PENDING
    IF v_state <> 'FILE_PENDING' THEN
        RAISE EXCEPTION 'HDM015_COMPLETE_RUN_NOT_FILE_PENDING'
            USING ERRCODE = '23514';
    END IF;

    -- Recalculate task counts from actual data
    SELECT count(*), count(*) FILTER (WHERE t.state = 'DELETED')
      INTO v_actual_task_count, v_deleted_task_count
    FROM runtime.deletion_payload_task t
    WHERE t.deletion_run_id = p_run_id;

    -- Task count must match the run's recorded count
    IF v_actual_task_count <> v_payload_task_count THEN
        RAISE EXCEPTION 'HDM015_COMPLETE_TASK_COUNT_MISMATCH'
            USING ERRCODE = '23514';
    END IF;

    -- All tasks must be DELETED
    IF v_deleted_task_count <> v_payload_task_count THEN
        RAISE EXCEPTION 'HDM015_COMPLETE_TASKS_NOT_ALL_DELETED'
            USING ERRCODE = '23514';
    END IF;

    -- Atomically complete the run
    UPDATE runtime.deletion_run
    SET state = 'COMPLETED',
        completed_at = p_completed_at,
        last_failure_code = NULL
    WHERE deletion_run_id = p_run_id;

    RETURN QUERY
    SELECT p_run_id, v_closure_id, 'COMPLETED'::text,
           v_payload_task_count, p_completed_at, true;
END
$$;

-- ============================================================
-- Privileges
-- ============================================================

-- Worker can EXECUTE the settlement functions
GRANT EXECUTE ON FUNCTION runtime.settle_deletion_payload_task(uuid, uuid, text, bytea)
    TO hide_nest_worker;
REVOKE EXECUTE ON FUNCTION runtime.settle_deletion_payload_task(uuid, uuid, text, bytea)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION runtime.record_deletion_file_failure(uuid, timestamptz)
    TO hide_nest_worker;
REVOKE EXECUTE ON FUNCTION runtime.record_deletion_file_failure(uuid, timestamptz)
    FROM PUBLIC;

GRANT EXECUTE ON FUNCTION runtime.complete_deletion_run(uuid, timestamptz)
    TO hide_nest_worker;
REVOKE EXECUTE ON FUNCTION runtime.complete_deletion_run(uuid, timestamptz)
    FROM PUBLIC;
