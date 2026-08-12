-- V013 | Local V1 S3B2A | atomic deletion-closure confirmation and complete fences

ALTER TABLE memory.deletion_closure
    ADD COLUMN confirmed_by_decision_id uuid,
    ADD COLUMN confirmed_at timestamptz;

ALTER TABLE memory.deletion_closure
    DROP CONSTRAINT deletion_closure_state_check,
    ADD CONSTRAINT deletion_closure_state_check CHECK (state IN ('PREVIEWED', 'CONFIRMED')),
    ADD CONSTRAINT deletion_closure_confirmation_fields_check CHECK (
        (state = 'PREVIEWED' AND confirmed_by_decision_id IS NULL AND confirmed_at IS NULL)
        OR (state = 'CONFIRMED' AND confirmed_by_decision_id IS NOT NULL AND confirmed_at IS NOT NULL)
    ),
    ADD CONSTRAINT deletion_closure_confirmed_decision_fk
        FOREIGN KEY (confirmed_by_decision_id)
        REFERENCES memory.decision (decision_id)
        ON DELETE NO ACTION;

CREATE UNIQUE INDEX deletion_closure_confirmed_decision_unique
    ON memory.deletion_closure (confirmed_by_decision_id)
    WHERE confirmed_by_decision_id IS NOT NULL;

DROP TRIGGER deletion_closure_immutable ON memory.deletion_closure;

CREATE FUNCTION memory.enforce_deletion_closure_confirmation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.state <> 'PREVIEWED'
           OR NEW.confirmed_by_decision_id IS NOT NULL
           OR NEW.confirmed_at IS NOT NULL THEN
            RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
    END IF;

    IF OLD.state <> 'PREVIEWED'
       OR NEW.state <> 'CONFIRMED'
       OR OLD.confirmed_by_decision_id IS NOT NULL
       OR OLD.confirmed_at IS NOT NULL
       OR NEW.confirmed_by_decision_id IS NULL
       OR NEW.confirmed_at IS NULL
       OR NEW.confirmed_at > NEW.expires_at
       OR NEW.closure_id IS DISTINCT FROM OLD.closure_id
       OR NEW.root_memory_id IS DISTINCT FROM OLD.root_memory_id
       OR NEW.preview_revision IS DISTINCT FROM OLD.preview_revision
       OR NEW.root_current_revision_id IS DISTINCT FROM OLD.root_current_revision_id
       OR NEW.root_revision_no IS DISTINCT FROM OLD.root_revision_no
       OR NEW.root_policy_id IS DISTINCT FROM OLD.root_policy_id
       OR NEW.root_policy_revision_no IS DISTINCT FROM OLD.root_policy_revision_no
       OR NEW.request_idempotency_key IS DISTINCT FROM OLD.request_idempotency_key
       OR NEW.request_hash IS DISTINCT FROM OLD.request_hash
       OR NEW.manifest_hash IS DISTINCT FROM OLD.manifest_hash
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR NEW.expires_at IS DISTINCT FROM OLD.expires_at
       OR NOT EXISTS (
            SELECT 1
            FROM memory.decision AS d
            WHERE d.decision_id = NEW.confirmed_by_decision_id
              AND d.decision_kind = 'USER_DELETE_CONFIRM'
              AND d.target_kind = 'DELETION_CLOSURE'
              AND d.target_id = NEW.closure_id
              AND d.target_revision_ref = NEW.preview_revision
       ) THEN
        RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER deletion_closure_confirmation_guard
    BEFORE INSERT OR UPDATE OR DELETE ON memory.deletion_closure
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_deletion_closure_confirmation();

CREATE FUNCTION memory.reject_confirmed_deletion_closure_member_insert()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM memory.deletion_closure
         WHERE closure_id = NEW.closure_id
           AND state = 'CONFIRMED'
    ) THEN
        RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER deletion_closure_member_confirmation_guard
    BEFORE INSERT ON memory.deletion_closure_member
    FOR EACH ROW EXECUTE FUNCTION memory.reject_confirmed_deletion_closure_member_insert();

CREATE FUNCTION memory.reject_confirmed_deletion_fence_insert()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM memory.deletion_closure
         WHERE closure_id = NEW.closure_id
           AND state = 'CONFIRMED'
    ) THEN
        RAISE EXCEPTION 'HDM013_DELETION_CONFIRMATION_INVALID' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER deletion_fence_confirmation_guard
    BEFORE INSERT ON memory.deletion_fence
    FOR EACH ROW EXECUTE FUNCTION memory.reject_confirmed_deletion_fence_insert();

CREATE FUNCTION memory.validate_deletion_closure_confirmation_fences()
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
           AND m.disposition <> 'AFFECTED_PENDING_CHOICE'
    )
    OR EXISTS (
        SELECT 1
          FROM memory.deletion_closure_member AS m
         WHERE m.closure_id = NEW.closure_id
           AND m.disposition <> 'AFFECTED_PENDING_CHOICE'
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
                   AND m.disposition <> 'AFFECTED_PENDING_CHOICE'
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

CREATE CONSTRAINT TRIGGER deletion_closure_confirmation_fence_guard
    AFTER UPDATE ON memory.deletion_closure
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    WHEN (NEW.state = 'CONFIRMED')
    EXECUTE FUNCTION memory.validate_deletion_closure_confirmation_fences();

REVOKE ALL ON FUNCTION memory.enforce_deletion_closure_confirmation(),
    memory.reject_confirmed_deletion_closure_member_insert(),
    memory.reject_confirmed_deletion_fence_insert(),
    memory.validate_deletion_closure_confirmation_fences()
FROM PUBLIC;
