-- V012 | Local V1 S3B1 | immutable deletion fences and immediate access denial

CREATE TABLE memory.deletion_fence (
    fence_id uuid PRIMARY KEY,
    closure_id uuid NOT NULL,
    target_kind text COLLATE "C" NOT NULL,
    target_id uuid NOT NULL,
    target_revision_ref bigint,
    created_by_decision_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT deletion_fence_closure_fk
        FOREIGN KEY (closure_id)
        REFERENCES memory.deletion_closure (closure_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_fence_decision_fk
        FOREIGN KEY (created_by_decision_id)
        REFERENCES memory.decision (decision_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_fence_target_kind_check CHECK (target_kind IN (
        'MEMORY', 'MEMORY_REVISION', 'SOURCE_ANCHOR', 'SOURCE_UNIT', 'SOURCE_PAYLOAD'
    )),
    CONSTRAINT deletion_fence_revision_check
        CHECK (target_revision_ref IS NULL OR target_revision_ref >= 1),
    CONSTRAINT deletion_fence_semantic_unique
        UNIQUE NULLS NOT DISTINCT (closure_id, target_kind, target_id, target_revision_ref)
);

CREATE INDEX deletion_fence_target_lookup
    ON memory.deletion_fence (target_kind, target_id, target_revision_ref);

CREATE FUNCTION memory.deletion_fence_exists(
    p_target_kind text,
    p_target_id uuid,
    p_target_revision_ref bigint)
RETURNS boolean
LANGUAGE sql
STABLE
SET search_path = pg_catalog, memory
AS $$
    SELECT EXISTS (
        SELECT 1
        FROM memory.deletion_fence
        WHERE target_kind = p_target_kind
          AND target_id = p_target_id
          AND target_revision_ref IS NOT DISTINCT FROM p_target_revision_ref
    )
$$;

CREATE FUNCTION memory.validate_deletion_fence_insert()
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
              AND disposition <> 'AFFECTED_PENDING_CHOICE'
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

CREATE FUNCTION memory.reject_deletion_fence_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER deletion_fence_insert_guard
    BEFORE INSERT ON memory.deletion_fence
    FOR EACH ROW EXECUTE FUNCTION memory.validate_deletion_fence_insert();
CREATE TRIGGER deletion_fence_immutable
    BEFORE UPDATE OR DELETE ON memory.deletion_fence
    FOR EACH ROW EXECUTE FUNCTION memory.reject_deletion_fence_mutation();

CREATE FUNCTION memory.enforce_memory_record_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND EXISTS (
        SELECT 1 FROM memory.deletion_fence
        WHERE target_kind = 'MEMORY' AND target_id = OLD.memory_id
          AND target_revision_ref IS NULL
    ) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM memory.deletion_fence
        WHERE target_kind = 'MEMORY' AND target_id = NEW.memory_id
          AND target_revision_ref IS NULL
    ) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION memory.enforce_memory_revision_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND (memory.deletion_fence_exists('MEMORY', OLD.memory_id, NULL)
            OR memory.deletion_fence_exists('MEMORY_REVISION', OLD.memory_revision_id, OLD.revision_no)) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    IF memory.deletion_fence_exists('MEMORY', NEW.memory_id, NULL)
       OR memory.deletion_fence_exists('MEMORY_REVISION', NEW.memory_revision_id, NEW.revision_no) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION memory.reject_if_revision_fenced_checked(
    checked_revision_id uuid
)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, memory
AS $$
DECLARE
    checked_memory_id uuid;
    checked_revision_no bigint;
BEGIN
    IF checked_revision_id IS NULL THEN
        RETURN;
    END IF;
    SELECT memory_id, revision_no INTO checked_memory_id, checked_revision_no
    FROM memory.memory_revision WHERE memory_revision_id = checked_revision_id;
    IF memory.deletion_fence_exists('MEMORY', checked_memory_id, NULL)
       OR memory.deletion_fence_exists('MEMORY_REVISION', checked_revision_id, checked_revision_no) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
END
$$;

CREATE FUNCTION memory.reject_if_relation_endpoint_fenced_checked(
    checked_from_revision_id uuid,
    checked_to_revision_id uuid,
    checked_to_anchor_id uuid
)
RETURNS void
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, evidence
AS $$
BEGIN
    PERFORM memory.reject_if_revision_fenced_checked(checked_from_revision_id);
    PERFORM memory.reject_if_revision_fenced_checked(checked_to_revision_id);
    IF memory.deletion_fence_exists('SOURCE_ANCHOR', checked_to_anchor_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
END
$$;

CREATE FUNCTION memory.enforce_memory_relation_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, evidence
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        PERFORM memory.reject_if_relation_endpoint_fenced_checked(
                OLD.from_revision_id, OLD.to_revision_id, OLD.to_anchor_id);
    END IF;
    PERFORM memory.reject_if_relation_endpoint_fenced_checked(
            NEW.from_revision_id, NEW.to_revision_id, NEW.to_anchor_id);
    RETURN NEW;
END
$$;

CREATE FUNCTION evidence.enforce_source_anchor_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, evidence
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND memory.deletion_fence_exists('SOURCE_ANCHOR', OLD.anchor_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    IF memory.deletion_fence_exists('SOURCE_ANCHOR', NEW.anchor_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION evidence.enforce_source_unit_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, evidence
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND memory.deletion_fence_exists('SOURCE_UNIT', OLD.source_unit_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    IF memory.deletion_fence_exists('SOURCE_UNIT', NEW.source_unit_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION evidence.enforce_source_payload_deletion_fence()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, memory, evidence
AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND (memory.deletion_fence_exists('SOURCE_PAYLOAD', OLD.payload_id, NULL)
            OR memory.deletion_fence_exists('SOURCE_UNIT', OLD.source_unit_id, NULL)) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    IF memory.deletion_fence_exists('SOURCE_PAYLOAD', NEW.payload_id, NULL)
       OR memory.deletion_fence_exists('SOURCE_UNIT', NEW.source_unit_id, NULL) THEN
        RAISE EXCEPTION 'HDM012_DELETION_FENCED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER memory_record_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON memory.memory_record
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_deletion_fence();
CREATE TRIGGER memory_revision_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON memory.memory_revision
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_revision_deletion_fence();
CREATE TRIGGER memory_relation_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON memory.memory_relation
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_relation_deletion_fence();
CREATE TRIGGER source_anchor_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON evidence.source_anchor
    FOR EACH ROW EXECUTE FUNCTION evidence.enforce_source_anchor_deletion_fence();
CREATE TRIGGER source_unit_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON evidence.source_unit
    FOR EACH ROW EXECUTE FUNCTION evidence.enforce_source_unit_deletion_fence();
CREATE TRIGGER source_payload_deletion_fence_guard
    BEFORE INSERT OR UPDATE ON evidence.source_payload
    FOR EACH ROW EXECUTE FUNCTION evidence.enforce_source_payload_deletion_fence();

REVOKE ALL ON FUNCTION memory.deletion_fence_exists(text, uuid, bigint),
    memory.validate_deletion_fence_insert(), memory.reject_deletion_fence_mutation(),
    memory.reject_if_revision_fenced_checked(uuid),
    memory.reject_if_relation_endpoint_fenced_checked(uuid, uuid, uuid),
    memory.enforce_memory_record_deletion_fence(),
    memory.enforce_memory_revision_deletion_fence(), memory.enforce_memory_relation_deletion_fence()
FROM PUBLIC;
REVOKE ALL ON FUNCTION evidence.enforce_source_anchor_deletion_fence(),
    evidence.enforce_source_unit_deletion_fence(), evidence.enforce_source_payload_deletion_fence()
FROM PUBLIC;

GRANT USAGE ON SCHEMA memory TO hide_nest_api, hide_nest_worker;
REVOKE ALL ON memory.deletion_fence FROM hide_nest_api, hide_nest_worker;
GRANT SELECT ON memory.deletion_fence TO hide_nest_api, hide_nest_worker;
