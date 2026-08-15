-- V017 | Local V1 | shared-evidence retain semantics for permanent deletion
--
-- The frozen product decision replaces the AFFECTED_PENDING_CHOICE "human
-- choice required" dead-end with an exact exclusive-vs-shared closure:
--   * objects exclusively owned by the deleted target → DELETE_CANDIDATE (erased);
--   * SourceAnchor/SourceUnit/SourcePayload still legitimately referenced by
--     another normative memory → RETAIN_SHARED (kept out of the physical erase set);
--   * the other memory itself is recorded as a SHARED_REFERENCE member so the
--     closure manifest binds the shared-reference set (preview drift → STALE).
--
-- V017 widens the disposition/kind CHECKs and re-creates the two fence
-- invariants and the SECURITY DEFINER erasure function with shared-aware scoping.
-- It does not modify V001—V016, does not add ON DELETE CASCADE, does not widen
-- any privilege, and does not register a new failure code (HDM014_* diagnostics
-- are reused; RETAIN_SHARED never produces a choice-required stop).
--
-- Historical V016 rows are mechanically converted IN PLACE before the CHECKs are
-- tightened (see step 0): member_kind 'AFFECTED_MEMORY' → 'SHARED_REFERENCE',
-- disposition 'AFFECTED_PENDING_CHOICE' → 'RETAIN_SHARED', with closure_id /
-- target_id / target_revision_ref / ordinal and every other identity field
-- preserved. A CONFIRMED closure carrying old choice-required semantics is
-- rejected fail-closed (no silent re-approval of an old confirmation).

-- ============================================================
-- 0. Historical closure-member conversion (before CHECK tightening)
-- ============================================================

-- 0.1 Fail closed: a CONFIRMED closure must never carry old choice-required
--     semantics. The normal governance chain never produces such a closure;
--     if one exists it is an anomaly and must not be silently re-licensed.
DO $$
DECLARE
    v_confirmed_count bigint;
BEGIN
    SELECT count(*) INTO v_confirmed_count
    FROM memory.deletion_closure c
    WHERE c.state = 'CONFIRMED'
      AND EXISTS (
          SELECT 1 FROM memory.deletion_closure_member m
          WHERE m.closure_id = c.closure_id
            AND (m.member_kind = 'AFFECTED_MEMORY'
                 OR m.disposition = 'AFFECTED_PENDING_CHOICE')
      );
    IF v_confirmed_count > 0 THEN
        RAISE EXCEPTION 'HDM017_OLD_CONFIRMED_CHOICE_CLOSURE present'
            USING ERRCODE = '23514';
    END IF;
END
$$;

-- 0.2 Drop the old CHECKs so old rows may be converted in place. They are
--     re-added with the widened allow-list in steps 1—3 below.
ALTER TABLE memory.deletion_closure_member
    DROP CONSTRAINT deletion_closure_member_kind_check;
ALTER TABLE memory.deletion_closure_member
    DROP CONSTRAINT deletion_closure_member_disposition_check;
ALTER TABLE memory.deletion_closure_member
    DROP CONSTRAINT deletion_closure_member_disposition_kind_check;

-- 0.3 Temporarily suspend the immutable member trigger so old rows can be
--     rewritten inside this single migration transaction, then restore the
--     exact V011 trigger (equal-or-stronger protection, no permanent bypass).
DROP TRIGGER deletion_closure_member_immutable ON memory.deletion_closure_member;

UPDATE memory.deletion_closure_member
SET member_kind = 'SHARED_REFERENCE', disposition = 'RETAIN_SHARED'
WHERE member_kind = 'AFFECTED_MEMORY';

UPDATE memory.deletion_closure_member
SET disposition = 'RETAIN_SHARED'
WHERE disposition = 'AFFECTED_PENDING_CHOICE';

CREATE TRIGGER deletion_closure_member_immutable
    BEFORE UPDATE OR DELETE ON memory.deletion_closure_member
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

-- ============================================================
-- 1. member kind set: drop AFFECTED_MEMORY, add SHARED_REFERENCE
-- ============================================================
ALTER TABLE memory.deletion_closure_member
    ADD CONSTRAINT deletion_closure_member_kind_check CHECK (member_kind IN (
        'MEMORY', 'MEMORY_REVISION', 'SOURCE_ANCHOR', 'SOURCE_UNIT',
        'SOURCE_PAYLOAD', 'SHARED_REFERENCE'
    ));

-- ============================================================
-- 2. disposition set: drop AFFECTED_PENDING_CHOICE, add RETAIN_SHARED
-- ============================================================
ALTER TABLE memory.deletion_closure_member
    ADD CONSTRAINT deletion_closure_member_disposition_check CHECK (disposition IN (
        'DELETE_REQUESTED', 'DELETE_CANDIDATE', 'RETAIN_SHARED'
    ));

-- ============================================================
-- 3. kind ↔ disposition cross-field rule
-- ============================================================
ALTER TABLE memory.deletion_closure_member
    ADD CONSTRAINT deletion_closure_member_disposition_kind_check CHECK (
        (member_kind IN ('MEMORY', 'MEMORY_REVISION') AND disposition = 'DELETE_REQUESTED')
        OR (member_kind IN ('SOURCE_ANCHOR', 'SOURCE_UNIT', 'SOURCE_PAYLOAD')
            AND disposition IN ('DELETE_CANDIDATE', 'RETAIN_SHARED'))
        OR (member_kind = 'SHARED_REFERENCE' AND disposition = 'RETAIN_SHARED')
    );

-- ============================================================
-- 4. fence insert guard: only DELETE_REQUESTED / DELETE_CANDIDATE
--    members may ever be fenced. RETAIN_SHARED objects are retained and
--    therefore never fenced (defense-in-depth: attempting to fence one now
--    raises HDM012_DELETION_FENCED).
-- ============================================================
CREATE OR REPLACE FUNCTION memory.validate_deletion_fence_insert()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
DECLARE
    v_preview_revision bigint;
BEGIN
    SELECT c.preview_revision INTO v_preview_revision
    FROM memory.deletion_closure AS c
    WHERE c.closure_id = NEW.closure_id;

    IF v_preview_revision IS NULL
       OR NOT EXISTS (
            SELECT 1
            FROM memory.deletion_closure_member
            WHERE closure_id = NEW.closure_id
              AND member_kind = NEW.target_kind
              AND target_id = NEW.target_id
              AND target_revision_ref IS NOT DISTINCT FROM NEW.target_revision_ref
              AND disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
       )
       OR NOT EXISTS (
            SELECT 1
            FROM memory.decision
            WHERE decision_id = NEW.created_by_decision_id
              AND decision_kind = 'USER_DELETE_CONFIRM'
              AND target_kind = 'DELETION_CLOSURE'
              AND target_id = NEW.closure_id
              AND target_revision_ref = v_preview_revision
       ) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

-- ============================================================
-- 5. confirmation fence↔member invariant: only DELETE_REQUESTED /
--    DELETE_CANDIDATE members require a fence; RETAIN_SHARED members
--    (retained source objects and SHARED_REFERENCE memories) must not.
-- ============================================================
CREATE OR REPLACE FUNCTION memory.validate_deletion_closure_confirmation_fences()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
DECLARE
    v_confirmed_decision_id uuid;
    v_root_memory_id uuid;
BEGIN
    SELECT confirmed_by_decision_id, root_memory_id
      INTO v_confirmed_decision_id, v_root_memory_id
      FROM memory.deletion_closure
     WHERE closure_id = NEW.closure_id
       AND state = 'CONFIRMED';

    IF v_confirmed_decision_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM memory.deletion_closure_member AS m
         WHERE m.closure_id = NEW.closure_id
           AND m.member_kind = 'MEMORY'
           AND m.target_id = v_root_memory_id
           AND m.target_revision_ref IS NULL
           AND m.disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_closure_member AS m
         WHERE m.closure_id = NEW.closure_id
           AND m.disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
           AND NOT EXISTS (
                SELECT 1
                  FROM memory.deletion_fence AS f
                 WHERE f.closure_id = m.closure_id
                   AND f.target_kind = m.member_kind
                   AND f.target_id = m.target_id
                   AND f.target_revision_ref IS NOT DISTINCT FROM m.target_revision_ref
           )
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_fence AS f
         WHERE f.closure_id = NEW.closure_id
           AND NOT EXISTS (
                SELECT 1
                  FROM memory.deletion_closure_member AS m
                 WHERE m.closure_id = f.closure_id
                   AND m.disposition IN ('DELETE_REQUESTED', 'DELETE_CANDIDATE')
                   AND m.member_kind = f.target_kind
                   AND m.target_id = f.target_id
                   AND m.target_revision_ref IS NOT DISTINCT FROM f.target_revision_ref
           )
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_fence AS f
         WHERE f.closure_id = NEW.closure_id
           AND f.created_by_decision_id IS DISTINCT FROM v_confirmed_decision_id
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_fence AS f
         WHERE f.closure_id = NEW.closure_id
         GROUP BY f.target_kind, f.target_id, f.target_revision_ref
        HAVING count(*) > 1
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_closure_member AS m
         WHERE m.closure_id = NEW.closure_id
         GROUP BY m.member_kind, m.target_id, m.target_revision_ref
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

-- ============================================================
-- 6. execute_confirmed_deletion_database_phase (shared-aware)
--    Preserves the V016 checks, locks, erasure order, return values and
--    internal diagnostic identifiers verbatim, except:
--      * the AFFECTED_PENDING_CHOICE "choice required" fail-closed block is
--        removed (RETAIN_SHARED is a valid, non-blocking disposition now);
--      * fence↔member bidirectional equality scopes to DELETE_REQUESTED /
--        DELETE_CANDIDATE members (RETAIN_SHARED members carry no fence);
--      * the shared-reference rejection scopes to DELETE_CANDIDATE objects
--        only (RETAIN_SHARED anchors/units/payloads are expected to be shared).
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
