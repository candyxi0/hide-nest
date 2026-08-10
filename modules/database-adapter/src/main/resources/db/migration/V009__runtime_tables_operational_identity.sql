-- V009 | HDM-006 Slice B R1 | runtime tables + OPERATIONAL identity CHECK
-- 9 tables: capture_scope, capture_scope_unit, closeout_run, checkpoint,
--   work_artifact, model_run, retrieval_trace, context_delivery, consumer_effect
-- (7 logical runtime objects → 8 physical tables + consumer_effect = 9)
-- R1-02: CaptureScope born-frozen at commit (DEFERRABLE constraint trigger)
-- R1-03: CloseoutRun terminal same-state immutability
-- R1-04: ModelRun terminal same-state immutability
-- R1-05: ContextDelivery one-way invalidation
-- OPERATIONAL CHECK on runtime.outbox_event: event_category=OPERATIONAL → aggregate_kind IN (RUN, EFFECT, FACT)
-- No domain/port/adapter. No business producers. All producers remain PRODUCTION_DISABLED.

-- ============================================================
-- runtime.capture_scope
-- ============================================================
CREATE TABLE runtime.capture_scope (
    scope_id uuid PRIMARY KEY,
    source_id uuid NOT NULL,
    from_ordinal bigint NOT NULL,
    to_ordinal bigint NOT NULL,
    rule_version text NOT NULL,
    coverage_code text COLLATE "C" NOT NULL,
    frozen_at timestamptz,
    manifest_hash bytea NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT capture_scope_from_ordinal_non_negative CHECK (from_ordinal >= 0),
    CONSTRAINT capture_scope_to_ordinal_ge_from CHECK (to_ordinal >= from_ordinal),
    CONSTRAINT capture_scope_rule_version_not_blank CHECK (char_length(trim(rule_version)) > 0),
    CONSTRAINT capture_scope_coverage_code_format CHECK (coverage_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT capture_scope_manifest_hash_length CHECK (octet_length(manifest_hash) = 32)
);

-- ============================================================
-- runtime.capture_scope_unit
-- ============================================================
CREATE TABLE runtime.capture_scope_unit (
    scope_id uuid NOT NULL,
    source_unit_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    exclusion_reason text COLLATE "C",
    PRIMARY KEY (scope_id, ordinal),
    CONSTRAINT capture_scope_unit_ordinal_non_negative CHECK (ordinal >= 0),
    CONSTRAINT capture_scope_unit_exclusion_reason_format CHECK (
        exclusion_reason IS NULL OR exclusion_reason ~ '^[A-Z][A-Z0-9_]{0,63}$'
    ),
    CONSTRAINT capture_scope_unit_scope_unit_unique UNIQUE (scope_id, source_unit_id)
);

-- ============================================================
-- runtime.closeout_run
-- ============================================================
CREATE TABLE runtime.closeout_run (
    run_id uuid PRIMARY KEY,
    scope_id uuid NOT NULL,
    state text COLLATE "C" NOT NULL DEFAULT 'READY',
    retry_of uuid,
    submission_id uuid UNIQUE,
    started_at timestamptz,
    terminal_at timestamptz,
    failure_code text COLLATE "C",
    created_at timestamptz NOT NULL,
    CONSTRAINT closeout_run_state_check CHECK (state IN ('READY', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT closeout_run_state_field_consistency CHECK (
        (state = 'READY' AND started_at IS NULL AND terminal_at IS NULL AND failure_code IS NULL)
        OR (state = 'RUNNING' AND started_at IS NOT NULL AND terminal_at IS NULL AND failure_code IS NULL)
        OR (state = 'COMPLETED' AND started_at IS NOT NULL AND terminal_at IS NOT NULL AND failure_code IS NULL)
        OR (state = 'FAILED' AND terminal_at IS NOT NULL AND failure_code IS NOT NULL)
        OR (state = 'CANCELLED' AND terminal_at IS NOT NULL AND failure_code IS NULL)
    )
);

-- ============================================================
-- runtime.checkpoint
-- ============================================================
CREATE TABLE runtime.checkpoint (
    checkpoint_id uuid PRIMARY KEY,
    run_kind text COLLATE "C" NOT NULL,
    run_id uuid NOT NULL,
    sequence_no bigint NOT NULL,
    manifest_hash bytea NOT NULL,
    object_ref text COLLATE "C",
    created_at timestamptz NOT NULL,
    CONSTRAINT checkpoint_run_kind_format CHECK (run_kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT checkpoint_sequence_no_non_negative CHECK (sequence_no >= 0),
    CONSTRAINT checkpoint_manifest_hash_length CHECK (octet_length(manifest_hash) = 32),
    CONSTRAINT checkpoint_object_ref_not_blank CHECK (
        object_ref IS NULL OR char_length(trim(object_ref)) > 0
    ),
    CONSTRAINT checkpoint_run_kind_run_id_sequence_unique UNIQUE (run_kind, run_id, sequence_no)
);

-- ============================================================
-- runtime.work_artifact
-- ============================================================
CREATE TABLE runtime.work_artifact (
    artifact_id uuid PRIMARY KEY,
    run_id uuid,
    artifact_kind text COLLATE "C" NOT NULL,
    object_ref text COLLATE "C" NOT NULL,
    content_hash bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT work_artifact_kind_format CHECK (artifact_kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT work_artifact_object_ref_not_blank CHECK (char_length(trim(object_ref)) > 0),
    CONSTRAINT work_artifact_content_hash_length CHECK (octet_length(content_hash) = 32),
    CONSTRAINT work_artifact_expires_after_created CHECK (expires_at > created_at)
);

-- ============================================================
-- runtime.model_run
-- ============================================================
CREATE TABLE runtime.model_run (
    model_run_id uuid PRIMARY KEY,
    role_code text COLLATE "C" NOT NULL,
    provider_manifest_id text NOT NULL,
    state text COLLATE "C" NOT NULL,
    input_manifest_hash bytea NOT NULL,
    output_manifest_hash bytea,
    retry_of uuid,
    started_at timestamptz NOT NULL,
    terminal_at timestamptz,
    failure_code text COLLATE "C",
    CONSTRAINT model_run_role_code_format CHECK (role_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT model_run_provider_manifest_id_not_blank CHECK (char_length(trim(provider_manifest_id)) > 0),
    CONSTRAINT model_run_state_check CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT model_run_input_hash_length CHECK (octet_length(input_manifest_hash) = 32),
    CONSTRAINT model_run_output_hash_length CHECK (
        output_manifest_hash IS NULL OR octet_length(output_manifest_hash) = 32
    ),
    CONSTRAINT model_run_state_field_consistency CHECK (
        (state = 'RUNNING' AND terminal_at IS NULL AND failure_code IS NULL AND output_manifest_hash IS NULL)
        OR (state = 'SUCCEEDED' AND terminal_at IS NOT NULL AND output_manifest_hash IS NOT NULL AND failure_code IS NULL)
        OR (state = 'FAILED' AND terminal_at IS NOT NULL AND failure_code IS NOT NULL AND output_manifest_hash IS NULL)
        OR (state = 'CANCELLED' AND terminal_at IS NOT NULL AND failure_code IS NULL AND output_manifest_hash IS NULL)
    )
);

-- ============================================================
-- runtime.retrieval_trace
-- ============================================================
CREATE TABLE runtime.retrieval_trace (
    trace_id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    thread_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    purpose text COLLATE "C" NOT NULL,
    result_category text COLLATE "C" NOT NULL,
    policy_revision_set_hash bytea NOT NULL,
    considered_ids uuid[],
    delivered_ids uuid[],
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT retrieval_trace_purpose_format CHECK (purpose ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT retrieval_trace_result_category_check CHECK (result_category IN (
        'SUCCEEDED', 'NO_RELEVANT_RESULT', 'DENIED', 'STALE', 'FAILED', 'PARTIALLY_DEGRADED'
    )),
    CONSTRAINT retrieval_trace_policy_hash_length CHECK (octet_length(policy_revision_set_hash) = 32),
    CONSTRAINT retrieval_trace_considered_no_null CHECK (
        considered_ids IS NULL OR array_position(considered_ids, NULL) IS NULL
    ),
    CONSTRAINT retrieval_trace_delivered_no_null CHECK (
        delivered_ids IS NULL OR array_position(delivered_ids, NULL) IS NULL
    ),
    CONSTRAINT retrieval_trace_expires_after_created CHECK (expires_at > created_at)
);

-- ============================================================
-- runtime.context_delivery
-- ============================================================
CREATE TABLE runtime.context_delivery (
    delivery_id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    thread_id uuid NOT NULL,
    turn_id uuid NOT NULL,
    purpose text COLLATE "C" NOT NULL,
    policy_revision_set_hash bytea NOT NULL,
    manifest_hash bytea NOT NULL,
    delivered_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    invalidated_at timestamptz,
    invalidation_reason text COLLATE "C",
    CONSTRAINT context_delivery_purpose_format CHECK (purpose ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT context_delivery_policy_hash_length CHECK (octet_length(policy_revision_set_hash) = 32),
    CONSTRAINT context_delivery_manifest_hash_length CHECK (octet_length(manifest_hash) = 32),
    CONSTRAINT context_delivery_expires_after_delivered CHECK (expires_at > delivered_at),
    CONSTRAINT context_delivery_expires_within_10_minutes CHECK (
        expires_at <= delivered_at + interval '10 minutes'
    ),
    CONSTRAINT context_delivery_invalidation_paired CHECK (
        (invalidated_at IS NULL AND invalidation_reason IS NULL)
        OR (invalidated_at IS NOT NULL AND invalidation_reason IS NOT NULL)
    ),
    CONSTRAINT context_delivery_invalidated_after_delivered CHECK (
        invalidated_at IS NULL OR invalidated_at >= delivered_at
    ),
    CONSTRAINT context_delivery_invalidation_reason_format CHECK (
        invalidation_reason IS NULL OR invalidation_reason ~ '^[A-Z][A-Z0-9_]{0,63}$'
    )
);

-- ============================================================
-- runtime.consumer_effect
-- ============================================================
CREATE TABLE runtime.consumer_effect (
    consumer_code text COLLATE "C" NOT NULL,
    event_id uuid NOT NULL,
    effect_key text COLLATE "C" NOT NULL,
    recorded_at timestamptz NOT NULL,
    CONSTRAINT consumer_effect_consumer_code_format CHECK (consumer_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT consumer_effect_effect_key_not_blank CHECK (char_length(trim(effect_key)) > 0),
    CONSTRAINT consumer_effect_pk PRIMARY KEY (consumer_code, event_id, effect_key)
);

-- ============================================================
-- Foreign keys (all ON DELETE NO ACTION)
-- ============================================================

-- capture_scope → evidence.source
ALTER TABLE runtime.capture_scope
    ADD CONSTRAINT capture_scope_source_fk
    FOREIGN KEY (source_id)
    REFERENCES evidence.source (source_id)
    ON DELETE NO ACTION;

-- capture_scope_unit → capture_scope
ALTER TABLE runtime.capture_scope_unit
    ADD CONSTRAINT capture_scope_unit_scope_fk
    FOREIGN KEY (scope_id)
    REFERENCES runtime.capture_scope (scope_id)
    ON DELETE NO ACTION;

-- capture_scope_unit → evidence.source_unit
ALTER TABLE runtime.capture_scope_unit
    ADD CONSTRAINT capture_scope_unit_source_unit_fk
    FOREIGN KEY (source_unit_id)
    REFERENCES evidence.source_unit (source_unit_id)
    ON DELETE NO ACTION;

-- closeout_run → capture_scope
ALTER TABLE runtime.closeout_run
    ADD CONSTRAINT closeout_run_scope_fk
    FOREIGN KEY (scope_id)
    REFERENCES runtime.capture_scope (scope_id)
    ON DELETE NO ACTION;

-- closeout_run → closeout_run (self, retry_of)
ALTER TABLE runtime.closeout_run
    ADD CONSTRAINT closeout_run_retry_of_fk
    FOREIGN KEY (retry_of)
    REFERENCES runtime.closeout_run (run_id)
    ON DELETE NO ACTION;

-- closeout_run → failure_code_registry
ALTER TABLE runtime.closeout_run
    ADD CONSTRAINT closeout_run_failure_code_fk
    FOREIGN KEY (failure_code)
    REFERENCES runtime.failure_code_registry (failure_code)
    ON DELETE NO ACTION;

-- work_artifact → closeout_run
ALTER TABLE runtime.work_artifact
    ADD CONSTRAINT work_artifact_run_fk
    FOREIGN KEY (run_id)
    REFERENCES runtime.closeout_run (run_id)
    ON DELETE NO ACTION;

-- model_run → model_run (self, retry_of)
ALTER TABLE runtime.model_run
    ADD CONSTRAINT model_run_retry_of_fk
    FOREIGN KEY (retry_of)
    REFERENCES runtime.model_run (model_run_id)
    ON DELETE NO ACTION;

-- model_run → failure_code_registry
ALTER TABLE runtime.model_run
    ADD CONSTRAINT model_run_failure_code_fk
    FOREIGN KEY (failure_code)
    REFERENCES runtime.failure_code_registry (failure_code)
    ON DELETE NO ACTION;

-- consumer_effect → outbox_event
ALTER TABLE runtime.consumer_effect
    ADD CONSTRAINT consumer_effect_event_fk
    FOREIGN KEY (event_id)
    REFERENCES runtime.outbox_event (event_id)
    ON DELETE NO ACTION;

-- ============================================================
-- Trigger functions
-- ============================================================

-- R1-02: CaptureScope born-frozen at commit.
-- Within a transaction: INSERT scope (frozen_at=NULL), INSERT units, UPDATE frozen_at, COMMIT.
-- At commit: DEFERRABLE trigger rejects any scope with frozen_at=NULL.
-- After commit: all scopes frozen; UPDATE/DELETE rejected.

CREATE FUNCTION runtime.enforce_capture_scope_frozen()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_DELETE_FORBIDDEN scope_id=%', OLD.scope_id
            USING ERRCODE = '23514';
    END IF;
    -- UPDATE: only allow frozen_at NULL→non-NULL; identity columns must not change
    IF TG_OP = 'UPDATE' THEN
        IF OLD.frozen_at IS NULL AND NEW.frozen_at IS NOT NULL THEN
            IF OLD.scope_id IS DISTINCT FROM NEW.scope_id
               OR OLD.source_id IS DISTINCT FROM NEW.source_id
               OR OLD.from_ordinal IS DISTINCT FROM NEW.from_ordinal
               OR OLD.to_ordinal IS DISTINCT FROM NEW.to_ordinal
               OR OLD.rule_version IS DISTINCT FROM NEW.rule_version
               OR OLD.coverage_code IS DISTINCT FROM NEW.coverage_code
               OR OLD.manifest_hash IS DISTINCT FROM NEW.manifest_hash
               OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
                RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_IDENTITY_IMMUTABLE scope_id=%', OLD.scope_id
                    USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
        END IF;
        IF OLD.frozen_at IS NOT NULL THEN
            RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_FROZEN scope_id=%', OLD.scope_id
                USING ERRCODE = '23514';
        END IF;
        RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_IDENTITY_IMMUTABLE scope_id=%', OLD.scope_id
            USING ERRCODE = '23514';
    END IF;
    RETURN COALESCE(NEW, OLD);
END
$$;

CREATE TRIGGER capture_scope_frozen_guard
    BEFORE UPDATE OR DELETE ON runtime.capture_scope
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_capture_scope_frozen();

CREATE FUNCTION runtime.enforce_capture_scope_commit_frozen()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
DECLARE
    unfrozen_count bigint;
BEGIN
    SELECT count(*) INTO unfrozen_count
    FROM runtime.capture_scope
    WHERE frozen_at IS NULL;
    IF unfrozen_count > 0 THEN
        RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_NOT_FROZEN_AT_COMMIT count=%', unfrozen_count
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER capture_scope_commit_frozen_guard
    AFTER INSERT OR UPDATE ON runtime.capture_scope
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_capture_scope_commit_frozen();

-- R1-02: Reject INSERT/UPDATE/DELETE on capture_scope_unit when parent scope is frozen.
-- Within transaction, scope.frozen_at is NULL so units can be assembled.
-- After commit, scope is always frozen so all unit mutations are rejected.

CREATE FUNCTION runtime.enforce_capture_scope_unit_frozen()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
DECLARE
    scope_frozen timestamptz;
BEGIN
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

CREATE TRIGGER capture_scope_unit_frozen_guard
    BEFORE INSERT OR UPDATE OR DELETE ON runtime.capture_scope_unit
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_capture_scope_unit_frozen();

-- Cross-source prevention: capture_scope_unit.source_unit.source_id must equal capture_scope.source_id
CREATE FUNCTION runtime.enforce_capture_scope_unit_same_source()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime, evidence
AS $$
DECLARE
    scope_source_id uuid;
    unit_source_id uuid;
BEGIN
    SELECT source_id INTO scope_source_id
    FROM runtime.capture_scope WHERE scope_id = NEW.scope_id;
    SELECT source_id INTO unit_source_id
    FROM evidence.source_unit WHERE source_unit_id = NEW.source_unit_id;
    IF scope_source_id IS DISTINCT FROM unit_source_id THEN
        RAISE EXCEPTION 'HDM006_CAPTURE_SCOPE_UNIT_SOURCE_MISMATCH scope=% unit=% scope_source=% unit_source=%',
            NEW.scope_id, NEW.source_unit_id, scope_source_id, unit_source_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER capture_scope_unit_same_source_guard
    AFTER INSERT OR UPDATE ON runtime.capture_scope_unit
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_capture_scope_unit_same_source();

-- R1-03: CloseoutRun state machine with terminal same-state immutability.
-- COMPLETED/FAILED/CANCELLED: any non-no-op UPDATE rejected.
-- submission_id immutable from creation (including NULL→non-null).
-- Only state+timestamps change during one legal transition.

CREATE FUNCTION runtime.enforce_closeout_run_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
BEGIN
    -- Identity columns must not change
    IF OLD.run_id IS DISTINCT FROM NEW.run_id
       OR OLD.scope_id IS DISTINCT FROM NEW.scope_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.retry_of IS DISTINCT FROM NEW.retry_of THEN
        RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_IDENTITY_IMMUTABLE run_id=%', OLD.run_id
            USING ERRCODE = '23514';
    END IF;

    -- submission_id: immutable from creation
    IF OLD.submission_id IS DISTINCT FROM NEW.submission_id THEN
        RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_SUBMISSION_ID_IMMUTABLE run_id=%', OLD.run_id
            USING ERRCODE = '23514';
    END IF;

    -- Terminal states: reject any non-no-op UPDATE
    IF OLD.state IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
        IF OLD.state IS DISTINCT FROM NEW.state
           OR OLD.started_at IS DISTINCT FROM NEW.started_at
           OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
           OR OLD.failure_code IS DISTINCT FROM NEW.failure_code THEN
            RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_TERMINAL_STATE_IMMUTABLE state=%', OLD.state
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- State transitions
    IF OLD.state IS DISTINCT FROM NEW.state THEN
        CASE
            WHEN OLD.state = 'READY' THEN
                IF NEW.state NOT IN ('RUNNING', 'FAILED', 'CANCELLED') THEN
                    RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_TRANSITION_FORBIDDEN from=% to=%',
                        OLD.state, NEW.state USING ERRCODE = '23514';
                END IF;
            WHEN OLD.state = 'RUNNING' THEN
                IF NEW.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED') THEN
                    RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_TRANSITION_FORBIDDEN from=% to=%',
                        OLD.state, NEW.state USING ERRCODE = '23514';
                END IF;
            ELSE
                RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_TRANSITION_FORBIDDEN from=% to=%',
                    OLD.state, NEW.state USING ERRCODE = '23514';
        END CASE;
    ELSE
        -- Same state: reject changes to started_at/terminal_at/failure_code
        IF OLD.started_at IS DISTINCT FROM NEW.started_at
           OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
           OR OLD.failure_code IS DISTINCT FROM NEW.failure_code THEN
            RAISE EXCEPTION 'HDM006_CLOSEOUT_RUN_SAME_STATE_MUTATION state=%', OLD.state
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER closeout_run_transition_guard
    BEFORE UPDATE ON runtime.closeout_run
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_closeout_run_transition();

-- R1-04: ModelRun state machine with terminal same-state immutability.
-- SUCCEEDED/FAILED/CANCELLED: any non-no-op UPDATE rejected.
-- RUNNING same-state: no changes to identity/timing columns.

CREATE FUNCTION runtime.enforce_model_run_transition()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
BEGIN
    -- Identity columns must not change
    IF OLD.model_run_id IS DISTINCT FROM NEW.model_run_id
       OR OLD.role_code IS DISTINCT FROM NEW.role_code
       OR OLD.provider_manifest_id IS DISTINCT FROM NEW.provider_manifest_id
       OR OLD.input_manifest_hash IS DISTINCT FROM NEW.input_manifest_hash
       OR OLD.retry_of IS DISTINCT FROM NEW.retry_of
       OR OLD.started_at IS DISTINCT FROM NEW.started_at THEN
        RAISE EXCEPTION 'HDM006_MODEL_RUN_IDENTITY_IMMUTABLE model_run_id=%', OLD.model_run_id
            USING ERRCODE = '23514';
    END IF;

    -- Terminal states: reject any non-no-op UPDATE
    IF OLD.state IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN
        IF OLD.state IS DISTINCT FROM NEW.state
           OR OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
           OR OLD.failure_code IS DISTINCT FROM NEW.failure_code
           OR OLD.output_manifest_hash IS DISTINCT FROM NEW.output_manifest_hash THEN
            RAISE EXCEPTION 'HDM006_MODEL_RUN_TERMINAL_STATE_IMMUTABLE state=%', OLD.state
                USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;

    -- State transitions
    IF OLD.state IS DISTINCT FROM NEW.state THEN
        IF OLD.state = 'RUNNING' THEN
            IF NEW.state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED') THEN
                RAISE EXCEPTION 'HDM006_MODEL_RUN_TRANSITION_FORBIDDEN from=% to=%',
                    OLD.state, NEW.state USING ERRCODE = '23514';
            END IF;
        ELSE
            RAISE EXCEPTION 'HDM006_MODEL_RUN_TERMINAL_STATE_IMMUTABLE state=%', OLD.state
                USING ERRCODE = '23514';
        END IF;
    ELSE
        -- Same state (RUNNING→RUNNING): reject changes to terminal/output/failure
        IF OLD.terminal_at IS DISTINCT FROM NEW.terminal_at
           OR OLD.failure_code IS DISTINCT FROM NEW.failure_code
           OR OLD.output_manifest_hash IS DISTINCT FROM NEW.output_manifest_hash THEN
            RAISE EXCEPTION 'HDM006_MODEL_RUN_SAME_STATE_MUTATION state=%', OLD.state
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER model_run_transition_guard
    BEFORE UPDATE ON runtime.model_run
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_model_run_transition();

-- R1-05: ContextDelivery one-way invalidation.
-- NULL/NULL → non-null/non-null once; once invalidated, cannot clear or modify.
-- Identity fields (request/thread/turn/purpose/hash/delivered/expires) always immutable.

CREATE FUNCTION runtime.enforce_context_delivery_invalidation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, runtime
AS $$
BEGIN
    -- Identity columns must not change
    IF OLD.delivery_id IS DISTINCT FROM NEW.delivery_id
       OR OLD.request_id IS DISTINCT FROM NEW.request_id
       OR OLD.thread_id IS DISTINCT FROM NEW.thread_id
       OR OLD.turn_id IS DISTINCT FROM NEW.turn_id
       OR OLD.purpose IS DISTINCT FROM NEW.purpose
       OR OLD.policy_revision_set_hash IS DISTINCT FROM NEW.policy_revision_set_hash
       OR OLD.manifest_hash IS DISTINCT FROM NEW.manifest_hash
       OR OLD.delivered_at IS DISTINCT FROM NEW.delivered_at
       OR OLD.expires_at IS DISTINCT FROM NEW.expires_at THEN
        RAISE EXCEPTION 'HDM006_CONTEXT_DELIVERY_IDENTITY_IMMUTABLE delivery_id=%', OLD.delivery_id
            USING ERRCODE = '23514';
    END IF;

    -- Invalidation: one-way only
    IF OLD.invalidated_at IS NULL AND OLD.invalidation_reason IS NULL THEN
        -- Allow NULL/NULL → non-null/non-null (one-way transition)
        IF NEW.invalidated_at IS NOT NULL AND NEW.invalidation_reason IS NOT NULL THEN
            RETURN NEW;
        END IF;
        -- Staying NULL/NULL: allow no-op (CHECK constraint rejects unpaired)
        IF NEW.invalidated_at IS NULL AND NEW.invalidation_reason IS NULL THEN
            RETURN NEW;
        END IF;
    ELSE
        -- Already invalidated: reject any changes
        IF OLD.invalidated_at IS DISTINCT FROM NEW.invalidated_at
           OR OLD.invalidation_reason IS DISTINCT FROM NEW.invalidation_reason THEN
            RAISE EXCEPTION 'HDM006_CONTEXT_DELIVERY_INVALIDATION_IMMUTABLE delivery_id=%', OLD.delivery_id
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END
$$;

CREATE TRIGGER context_delivery_invalidation_guard
    BEFORE UPDATE ON runtime.context_delivery
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_context_delivery_invalidation();

-- Checkpoint immutability (no UPDATE/DELETE)
CREATE TRIGGER checkpoint_immutable
    BEFORE UPDATE OR DELETE ON runtime.checkpoint
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

-- ConsumerEffect immutability (no UPDATE/DELETE)
CREATE TRIGGER consumer_effect_immutable
    BEFORE UPDATE OR DELETE ON runtime.consumer_effect
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

-- ============================================================
-- OPERATIONAL identity CHECK on runtime.outbox_event
-- ============================================================
ALTER TABLE runtime.outbox_event
    ADD CONSTRAINT outbox_event_operational_kind_check
    CHECK (
        event_category != 'OPERATIONAL'
        OR aggregate_kind IN ('RUN', 'EFFECT', 'FACT')
    );

-- ============================================================
-- Query indexes (minimal necessary; no FTS/pgvector)
-- ============================================================
CREATE INDEX capture_scope_source_lookup ON runtime.capture_scope (source_id);
CREATE INDEX capture_scope_unit_unit_lookup ON runtime.capture_scope_unit (source_unit_id);
CREATE INDEX closeout_run_scope_lookup ON runtime.closeout_run (scope_id);
CREATE INDEX closeout_run_retry_lookup ON runtime.closeout_run (retry_of) WHERE retry_of IS NOT NULL;
CREATE INDEX checkpoint_run_lookup ON runtime.checkpoint (run_kind, run_id, sequence_no DESC);
CREATE INDEX work_artifact_run_lookup ON runtime.work_artifact (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX work_artifact_expires_lookup ON runtime.work_artifact (expires_at);
CREATE INDEX model_run_retry_lookup ON runtime.model_run (retry_of) WHERE retry_of IS NOT NULL;
CREATE INDEX retrieval_trace_request_lookup ON runtime.retrieval_trace (request_id, thread_id, turn_id);
CREATE INDEX retrieval_trace_expires_lookup ON runtime.retrieval_trace (expires_at);
CREATE INDEX context_delivery_request_lookup ON runtime.context_delivery (request_id, thread_id, turn_id);
CREATE INDEX consumer_effect_event_lookup ON runtime.consumer_effect (event_id);

-- ============================================================
-- Privileges (extends V006/V008 API/Worker separation to new runtime tables)
-- ============================================================

-- API role: SELECT on all new tables
GRANT SELECT ON
    runtime.capture_scope,
    runtime.capture_scope_unit,
    runtime.closeout_run,
    runtime.checkpoint,
    runtime.work_artifact,
    runtime.model_run,
    runtime.retrieval_trace,
    runtime.context_delivery,
    runtime.consumer_effect
TO hide_nest_api;

-- API role: INSERT on capture_scope, capture_scope_unit, closeout_run
GRANT INSERT ON
    runtime.capture_scope,
    runtime.capture_scope_unit,
    runtime.closeout_run
TO hide_nest_api;

-- API role: UPDATE frozen_at on capture_scope (born-frozen assembly)
GRANT UPDATE (frozen_at) ON runtime.capture_scope TO hide_nest_api;

-- API role: UPDATE only state-transition columns on closeout_run
GRANT UPDATE (state, started_at, terminal_at, failure_code)
    ON runtime.closeout_run TO hide_nest_api;

-- Worker role: SELECT on all new tables
GRANT SELECT ON
    runtime.capture_scope,
    runtime.capture_scope_unit,
    runtime.closeout_run,
    runtime.checkpoint,
    runtime.work_artifact,
    runtime.model_run,
    runtime.retrieval_trace,
    runtime.context_delivery,
    runtime.consumer_effect
TO hide_nest_worker;

-- Worker role: INSERT on checkpoint, work_artifact, model_run, retrieval_trace, context_delivery, consumer_effect
GRANT INSERT ON
    runtime.checkpoint,
    runtime.work_artifact,
    runtime.model_run,
    runtime.retrieval_trace,
    runtime.context_delivery,
    runtime.consumer_effect
TO hide_nest_worker;

-- Worker role: UPDATE precise state/terminal/invalidation columns
GRANT UPDATE (state, started_at, terminal_at, failure_code)
    ON runtime.closeout_run TO hide_nest_worker;
GRANT UPDATE (state, terminal_at, failure_code, output_manifest_hash)
    ON runtime.model_run TO hide_nest_worker;
GRANT UPDATE (invalidated_at, invalidation_reason)
    ON runtime.context_delivery TO hide_nest_worker;

-- consumer_effect: no UPDATE/DELETE grants (immutable)

-- Revoke PUBLIC EXECUTE on new runtime functions (consistent with V006 pattern)
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA runtime FROM PUBLIC;
