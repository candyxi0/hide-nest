CREATE FUNCTION memory.reject_immutable_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'HDM005_IMMUTABLE_TABLE table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$$;

CREATE FUNCTION runtime.reject_registry_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'HDM005_IMMUTABLE_REGISTRY table=%.% operation=%', TG_TABLE_SCHEMA, TG_TABLE_NAME, TG_OP
        USING ERRCODE = '55000';
END
$$;

CREATE TRIGGER actor_ref_immutable
    BEFORE UPDATE OR DELETE ON memory.actor_ref
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER proposal_immutable
    BEFORE UPDATE OR DELETE ON memory.proposal
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER proposal_revision_immutable
    BEFORE UPDATE OR DELETE ON memory.proposal_revision
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER review_member_immutable
    BEFORE UPDATE OR DELETE ON memory.review_member
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER decision_immutable
    BEFORE UPDATE OR DELETE ON memory.decision
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER access_policy_revision_immutable
    BEFORE UPDATE OR DELETE ON memory.access_policy_revision
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER access_policy_grant_immutable
    BEFORE UPDATE OR DELETE ON memory.access_policy_grant
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER memory_revision_immutable
    BEFORE UPDATE OR DELETE ON memory.memory_revision
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER change_event_immutable
    BEFORE UPDATE OR DELETE ON memory.change_event
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER idempotency_receipt_immutable
    BEFORE UPDATE OR DELETE ON runtime.idempotency_receipt
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();
CREATE TRIGGER failure_code_registry_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON runtime.failure_code_registry
    FOR EACH ROW EXECUTE FUNCTION runtime.reject_registry_mutation();
CREATE TRIGGER event_type_registry_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON runtime.event_type_registry
    FOR EACH ROW EXECUTE FUNCTION runtime.reject_registry_mutation();

CREATE FUNCTION memory.enforce_review_session_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.review_session_id IS DISTINCT FROM NEW.review_session_id
       OR OLD.idempotency_key IS DISTINCT FROM NEW.idempotency_key
       OR OLD.request_hash IS DISTINCT FROM NEW.request_hash
       OR OLD.opened_at IS DISTINCT FROM NEW.opened_at THEN
        RAISE EXCEPTION 'HDM005_REVIEW_SESSION_IDENTITY_IMMUTABLE'
            USING ERRCODE = '55000';
    END IF;
    IF OLD.state <> 'OPEN' OR NEW.state NOT IN ('COMPLETED', 'CANCELLED', 'EXPIRED') THEN
        RAISE EXCEPTION 'HDM005_REVIEW_SESSION_TRANSITION_FORBIDDEN'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER review_session_transition_guard
    BEFORE UPDATE ON memory.review_session
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_review_session_transition();
CREATE TRIGGER review_session_delete_guard
    BEFORE DELETE ON memory.review_session
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

CREATE FUNCTION memory.enforce_access_policy_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.policy_id IS DISTINCT FROM NEW.policy_id
       OR OLD.owner_kind IS DISTINCT FROM NEW.owner_kind
       OR OLD.owner_id IS DISTINCT FROM NEW.owner_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR NEW.current_revision_no <> OLD.current_revision_no + 1 THEN
        RAISE EXCEPTION 'HDM005_ACCESS_POLICY_UPDATE_FORBIDDEN'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER access_policy_update_guard
    BEFORE UPDATE ON memory.access_policy
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_access_policy_update();
CREATE TRIGGER access_policy_delete_guard
    BEFORE DELETE ON memory.access_policy
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

CREATE FUNCTION memory.enforce_memory_record_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    old_rev_no bigint;
    new_rev_no bigint;
    dec_valid boolean;
    pol_valid boolean;
BEGIN
    IF OLD.memory_id IS DISTINCT FROM NEW.memory_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR NEW.updated_at < OLD.updated_at THEN
        RAISE EXCEPTION 'HDM005_MEMORY_RECORD_UPDATE_FORBIDDEN'
            USING ERRCODE = '23514';
    END IF;
    IF NEW.current_revision_id IS DISTINCT FROM OLD.current_revision_id THEN
        SELECT revision_no INTO old_rev_no FROM memory.memory_revision WHERE memory_revision_id = OLD.current_revision_id;
        SELECT revision_no INTO new_rev_no FROM memory.memory_revision WHERE memory_revision_id = NEW.current_revision_id;
        IF new_rev_no IS NULL OR old_rev_no IS NULL OR new_rev_no <= old_rev_no THEN
            RAISE EXCEPTION 'HDM005_MEMORY_CURRENT_REVISION_CAS_FAILED' USING ERRCODE = '23514';
        END IF;
        SELECT EXISTS (
            SELECT 1 FROM memory.decision d
            JOIN memory.memory_revision mr ON mr.created_by_decision_id = d.decision_id
            WHERE mr.memory_revision_id = NEW.current_revision_id
              AND d.decision_kind = 'USER_CONFIRM' AND d.target_kind = 'MEMORY'
              AND d.target_id = NEW.memory_id AND d.target_revision_ref = new_rev_no
        ) INTO dec_valid;
        IF NOT dec_valid THEN
            RAISE EXCEPTION 'HDM005_MEMORY_CURRENT_REVISION_DECISION_INVALID' USING ERRCODE = '23514';
        END IF;
    END IF;
    IF NEW.policy_id IS DISTINCT FROM OLD.policy_id
       OR NEW.current_policy_revision_no IS DISTINCT FROM OLD.current_policy_revision_no THEN
        SELECT EXISTS (
            SELECT 1 FROM memory.access_policy
            WHERE policy_id = NEW.policy_id AND owner_kind = 'MEMORY' AND owner_id = NEW.memory_id
        ) INTO pol_valid;
        IF NOT pol_valid THEN
            RAISE EXCEPTION 'HDM005_MEMORY_POLICY_OWNER_MISMATCH' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER memory_record_update_guard
    BEFORE UPDATE ON memory.memory_record
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_update();
CREATE TRIGGER memory_record_delete_guard
    BEFORE DELETE ON memory.memory_record
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

-- R3-03: State change governance with exact Decision kind + target_revision_ref binding
CREATE FUNCTION memory.enforce_memory_record_governance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    current_rev_no bigint;
    expected_decision_kind text;
    pol_decision_id uuid;
BEGIN
    SELECT revision_no INTO current_rev_no
    FROM memory.memory_revision WHERE memory_revision_id = NEW.current_revision_id;

    IF NEW.state IS DISTINCT FROM OLD.state THEN
        IF OLD.state = 'ACTIVE' AND NEW.state = 'ARCHIVED' THEN
            expected_decision_kind := 'USER_ARCHIVE';
        ELSIF OLD.state = 'ARCHIVED' AND NEW.state = 'ACTIVE' THEN
            expected_decision_kind := 'USER_RESTORE';
        ELSE
            RAISE EXCEPTION 'HDM005_MEMORY_STATE_TRANSITION_FORBIDDEN old=% new=%', OLD.state, NEW.state
                USING ERRCODE = '23514';
        END IF;

        -- R3-03: Verify governed outbox+ChangeEvent whose Decision matches kind, target, AND target_revision_ref
        IF NOT EXISTS (
            SELECT 1 FROM runtime.outbox_event AS event
            JOIN memory.change_event AS change ON change.change_event_id = event.change_event_id
            JOIN memory.decision AS dec ON dec.decision_id = change.decision_id
            WHERE event.event_category = 'GOVERNED'
              AND event.event_type = 'memory.state-changed.v1'
              AND event.aggregate_kind = 'MEMORY' AND event.aggregate_id = NEW.memory_id
              AND event.aggregate_revision = current_rev_no
              AND change.target_kind = 'MEMORY' AND change.target_id = NEW.memory_id
              AND change.target_revision_ref = current_rev_no
              AND dec.decision_kind = expected_decision_kind
              AND dec.target_kind = 'MEMORY' AND dec.target_id = NEW.memory_id
              AND dec.target_revision_ref = current_rev_no
        ) THEN
            RAISE EXCEPTION 'HDM005_MEMORY_STATE_CHANGE_OUTBOX_OR_DECISION_REQUIRED' USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.policy_id IS DISTINCT FROM OLD.policy_id
       OR NEW.current_policy_revision_no IS DISTINCT FROM OLD.current_policy_revision_no THEN
        SELECT apr.created_by_decision_id INTO pol_decision_id
        FROM memory.access_policy_revision apr
        WHERE apr.policy_id = NEW.policy_id AND apr.revision_no = NEW.current_policy_revision_no;
        IF pol_decision_id IS NULL THEN
            RAISE EXCEPTION 'HDM005_MEMORY_POLICY_CHANGE_DECISION_REQUIRED' USING ERRCODE = '23514';
        END IF;
        PERFORM memory.require_governed_outbox(
            'MEMORY', NEW.memory_id, current_rev_no, 'memory.policy-changed.v1', pol_decision_id);
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER memory_record_governance_guard
    AFTER UPDATE ON memory.memory_record
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_record_governance();

CREATE FUNCTION runtime.valid_outbox_manifest(
    candidate jsonb, expected_aggregate_id uuid, expected_aggregate_revision bigint,
    expected_policy_revision bigint, expected_purpose text, expected_hash bytea
)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT jsonb_typeof(candidate) = 'object'
       AND candidate ? 'aggregateId' AND candidate ? 'aggregateRevision'
       AND candidate ? 'policyRevision' AND candidate ? 'purpose' AND candidate ? 'manifestHash'
       AND NOT EXISTS (SELECT 1 FROM jsonb_object_keys(candidate) AS key_name
           WHERE key_name NOT IN ('aggregateId','aggregateRevision','policyRevision','purpose','manifestHash','memoryRevisionId'))
       AND jsonb_typeof(candidate->'aggregateId') = 'string'
       AND candidate->>'aggregateId' = expected_aggregate_id::text
       AND ((jsonb_typeof(candidate->'aggregateRevision') = 'null' AND expected_aggregate_revision IS NULL)
            OR (jsonb_typeof(candidate->'aggregateRevision') = 'number'
                AND (candidate->>'aggregateRevision')::bigint = expected_aggregate_revision
                AND expected_aggregate_revision >= 1))
       AND jsonb_typeof(candidate->'policyRevision') = 'number'
       AND (candidate->>'policyRevision')::bigint = expected_policy_revision AND expected_policy_revision >= 0
       AND jsonb_typeof(candidate->'purpose') = 'string' AND candidate->>'purpose' = expected_purpose
       AND jsonb_typeof(candidate->'manifestHash') = 'string'
       AND candidate->>'manifestHash' = encode(expected_hash, 'hex')
       AND (NOT candidate ? 'memoryRevisionId'
            OR (jsonb_typeof(candidate->'memoryRevisionId') = 'string'
                AND candidate->>'memoryRevisionId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
       AND NOT EXISTS (SELECT 1 FROM jsonb_each(candidate) WHERE jsonb_typeof(value) IN ('object','array'));
$$;

ALTER TABLE runtime.outbox_event DROP CONSTRAINT IF EXISTS outbox_event_manifest_shape_check;
ALTER TABLE runtime.outbox_event
    ADD CONSTRAINT outbox_event_manifest_shape_check
    CHECK (runtime.valid_outbox_manifest(payload_manifest, aggregate_id, aggregate_revision,
        policy_revision, purpose, manifest_hash));

CREATE FUNCTION runtime.enforce_outbox_change_event_match()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE matched boolean; change_decision_id uuid;
BEGIN
    IF NEW.event_category = 'OPERATIONAL' THEN
        IF NEW.change_event_id IS NOT NULL OR NEW.aggregate_revision IS NULL THEN
            RAISE EXCEPTION 'HDM005_OPERATIONAL_OUTBOX_IDENTITY_INVALID' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    SELECT EXISTS (
        SELECT 1 FROM memory.change_event AS change
        WHERE change.change_event_id = NEW.change_event_id
          AND change.event_type = NEW.event_type AND change.target_kind = NEW.aggregate_kind
          AND change.target_id = NEW.aggregate_id
          AND change.target_revision_ref IS NOT DISTINCT FROM NEW.aggregate_revision
    ) INTO matched;
    IF NOT matched THEN
        RAISE EXCEPTION 'HDM005_GOVERNED_OUTBOX_CHANGE_EVENT_MISMATCH' USING ERRCODE = '23514';
    END IF;
    SELECT change.decision_id INTO change_decision_id FROM memory.change_event AS change
    WHERE change.change_event_id = NEW.change_event_id;
    IF change_decision_id IS NULL THEN
        RAISE EXCEPTION 'HDM005_GOVERNED_CHANGE_EVENT_DECISION_REQUIRED' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER outbox_change_event_match_guard
    AFTER INSERT OR UPDATE OF event_category, event_type, aggregate_kind,
        aggregate_id, aggregate_revision, change_event_id
    ON runtime.outbox_event
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_outbox_change_event_match();

CREATE FUNCTION memory.require_governed_outbox(
    required_kind text, required_id uuid, required_revision bigint,
    required_event_type text, p_decision_id uuid
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE found_decision_id uuid;
BEGIN
    SELECT change.decision_id INTO found_decision_id
    FROM runtime.outbox_event AS event
    JOIN memory.change_event AS change ON change.change_event_id = event.change_event_id
    WHERE event.event_category = 'GOVERNED'
      AND event.event_type = required_event_type
      AND event.aggregate_kind = required_kind AND event.aggregate_id = required_id
      AND event.aggregate_revision IS NOT DISTINCT FROM required_revision
      AND change.target_kind = required_kind AND change.target_id = required_id
      AND change.target_revision_ref IS NOT DISTINCT FROM required_revision
    LIMIT 1;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HDM005_GOVERNED_WRITE_OUTBOX_REQUIRED kind=% id=% revision=% event=%',
            required_kind, required_id, required_revision, required_event_type USING ERRCODE = '23514';
    END IF;
    IF p_decision_id IS NOT NULL AND found_decision_id IS DISTINCT FROM p_decision_id THEN
        RAISE EXCEPTION 'HDM005_GOVERNED_DECISION_BINDING_MISMATCH expected=% found=%',
            p_decision_id, found_decision_id USING ERRCODE = '23514';
    END IF;
END
$$;

-- R3-01/R3-02: Memory revision governance with mandatory ReviewSession + stale policy from memory_record
CREATE FUNCTION memory.enforce_memory_revision_governance()
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
    IF NOT FOUND OR prop_record.target_memory_id IS DISTINCT FROM NEW.memory_id THEN
        RAISE EXCEPTION 'HDM005_MEMORY_REVISION_PROPOSAL_TARGET_MISMATCH' USING ERRCODE = '23514';
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

CREATE CONSTRAINT TRIGGER memory_revision_governance_guard
    AFTER INSERT ON memory.memory_revision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_memory_revision_governance();

CREATE FUNCTION memory.enforce_policy_revision_governance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM memory.require_governed_outbox(
        'ACCESS_POLICY', NEW.policy_id, NEW.revision_no, 'memory.policy-changed.v1', NEW.created_by_decision_id);
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER policy_revision_governance_guard
    AFTER INSERT ON memory.access_policy_revision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_policy_revision_governance();

CREATE FUNCTION memory.enforce_review_decision_governance()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.review_session_id IS NOT NULL THEN
        PERFORM memory.require_governed_outbox(
            'REVIEW_SESSION', NEW.review_session_id, NEW.target_revision_ref,
            'review.decisions-committed.v1', NEW.decision_id);
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER review_decision_governance_guard
    AFTER INSERT ON memory.decision
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_review_decision_governance();

-- R3-04: Review session completion with bidirectional set equality
CREATE FUNCTION memory.enforce_review_session_completion()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    member_count bigint; verdict_count bigint; non_member_count bigint;
    extra_event_count bigint; missing_verdict_count bigint;
BEGIN
    IF NEW.state <> 'COMPLETED' OR OLD.state = 'COMPLETED' THEN RETURN NEW; END IF;

    SELECT count(*) INTO member_count FROM memory.review_member WHERE review_session_id = NEW.review_session_id;
    IF member_count = 0 THEN
        RAISE EXCEPTION 'HDM005_REVIEW_COMPLETION_EMPTY_MEMBERS' USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO verdict_count FROM memory.decision
    WHERE review_session_id = NEW.review_session_id
      AND decision_kind IN ('USER_CONFIRM','USER_REJECT','USER_DEFER');
    IF member_count <> verdict_count THEN
        RAISE EXCEPTION 'HDM005_REVIEW_COMPLETION_SET_MISMATCH members=% verdicts=%',
            member_count, verdict_count USING ERRCODE = '23514';
    END IF;

    SELECT count(*) INTO non_member_count FROM memory.decision d
    WHERE d.review_session_id = NEW.review_session_id
      AND d.decision_kind IN ('USER_CONFIRM','USER_REJECT','USER_DEFER')
      AND NOT EXISTS (SELECT 1 FROM memory.review_member rm
          WHERE rm.review_session_id = d.review_session_id AND rm.proposal_revision_id = d.proposal_revision_id);
    IF non_member_count > 0 THEN
        RAISE EXCEPTION 'HDM005_REVIEW_COMPLETION_NON_MEMBER_VERDICT' USING ERRCODE = '23514';
    END IF;

    -- R3-04: Bidirectional set equality — governed events must ONLY reference member decisions
    -- No extra decision_ids in governed events that aren't member verdicts
    SELECT count(*) INTO extra_event_count FROM runtime.outbox_event AS event
    JOIN memory.change_event AS change ON change.change_event_id = event.change_event_id
    WHERE event.event_category = 'GOVERNED'
      AND event.event_type = 'review.decisions-committed.v1'
      AND event.aggregate_kind = 'REVIEW_SESSION' AND event.aggregate_id = NEW.review_session_id
      AND change.decision_id IS NOT NULL
      AND change.decision_id NOT IN (
          SELECT decision_id FROM memory.decision
          WHERE review_session_id = NEW.review_session_id
            AND decision_kind IN ('USER_CONFIRM','USER_REJECT','USER_DEFER'));
    IF extra_event_count > 0 THEN
        RAISE EXCEPTION 'HDM005_REVIEW_COMPLETION_EXTRA_EVENT_DECISION' USING ERRCODE = '23514';
    END IF;

    -- No member verdicts missing from governed events
    SELECT count(*) INTO missing_verdict_count FROM memory.decision d
    WHERE d.review_session_id = NEW.review_session_id
      AND d.decision_kind IN ('USER_CONFIRM','USER_REJECT','USER_DEFER')
      AND NOT EXISTS (
          SELECT 1 FROM runtime.outbox_event AS event
          JOIN memory.change_event AS change ON change.change_event_id = event.change_event_id
          WHERE event.event_category = 'GOVERNED'
            AND event.event_type = 'review.decisions-committed.v1'
            AND event.aggregate_kind = 'REVIEW_SESSION' AND event.aggregate_id = NEW.review_session_id
            AND change.decision_id = d.decision_id);
    IF missing_verdict_count > 0 THEN
        RAISE EXCEPTION 'HDM005_REVIEW_COMPLETION_MISSING_EVENT_DECISION' USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER review_session_completion_guard
    AFTER UPDATE ON memory.review_session
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION memory.enforce_review_session_completion();

-- R3-05: JSON value format validation — allowlist keys + value format checks
CREATE FUNCTION runtime.valid_change_event_manifest(candidate jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT candidate IS NULL OR (
        jsonb_typeof(candidate) = 'object'
        AND NOT EXISTS (SELECT 1 FROM jsonb_object_keys(candidate) AS key_name
            WHERE key_name NOT IN ('manifestHash','memoryRevisionId'))
        AND (NOT candidate ? 'manifestHash'
             OR (jsonb_typeof(candidate->'manifestHash') = 'string'
                 AND candidate->>'manifestHash' ~ '^[0-9a-f]{64}$'))
        AND (NOT candidate ? 'memoryRevisionId'
             OR jsonb_typeof(candidate->'memoryRevisionId') = 'null'
             OR (jsonb_typeof(candidate->'memoryRevisionId') = 'string'
                 AND candidate->>'memoryRevisionId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(candidate) WHERE jsonb_typeof(value) IN ('object','array'))
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(candidate)
            WHERE jsonb_typeof(value) = 'string' AND char_length(value::text) > 256)
    );
$$;

CREATE FUNCTION runtime.valid_receipt_manifest(candidate jsonb)
RETURNS boolean
LANGUAGE sql
IMMUTABLE
AS $$
    SELECT candidate IS NULL OR (
        jsonb_typeof(candidate) = 'object'
        AND NOT EXISTS (SELECT 1 FROM jsonb_object_keys(candidate) AS key_name
            WHERE key_name NOT IN ('type','title','status','requestId','resultCategory','failureCode','retryable','affectedScope'))
        AND (NOT candidate ? 'type'
             OR (jsonb_typeof(candidate->'type') = 'string' AND candidate->>'type' ~ '^urn:'))
        AND (NOT candidate ? 'title' OR jsonb_typeof(candidate->'title') = 'string')
        AND (NOT candidate ? 'status'
             OR (jsonb_typeof(candidate->'status') = 'number'
                 AND (candidate->>'status')::integer BETWEEN 100 AND 599))
        AND (NOT candidate ? 'requestId'
             OR (jsonb_typeof(candidate->'requestId') = 'string'
                 AND candidate->>'requestId' ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'))
        AND (NOT candidate ? 'resultCategory'
             OR (jsonb_typeof(candidate->'resultCategory') = 'string'
                 AND candidate->>'resultCategory' IN ('SUCCEEDED','NO_RELEVANT_RESULT','DENIED','STALE','FAILED','PARTIALLY_DEGRADED')))
        AND (NOT candidate ? 'failureCode'
             OR (jsonb_typeof(candidate->'failureCode') = 'string'))
        AND (NOT candidate ? 'retryable' OR jsonb_typeof(candidate->'retryable') = 'boolean')
        AND (NOT candidate ? 'affectedScope' OR jsonb_typeof(candidate->'affectedScope') = 'string')
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(candidate) WHERE jsonb_typeof(value) IN ('object','array'))
        AND NOT EXISTS (SELECT 1 FROM jsonb_each(candidate)
            WHERE jsonb_typeof(value) = 'string' AND char_length(value::text) > 256)
    );
$$;

ALTER TABLE memory.change_event DROP CONSTRAINT IF EXISTS change_event_manifest_body_check;
ALTER TABLE memory.change_event
    ADD CONSTRAINT change_event_manifest_body_check
    CHECK (runtime.valid_change_event_manifest(detail_manifest));

ALTER TABLE runtime.idempotency_receipt DROP CONSTRAINT IF EXISTS idempotency_receipt_manifest_body_check;
ALTER TABLE runtime.idempotency_receipt
    ADD CONSTRAINT idempotency_receipt_manifest_body_check
    CHECK (runtime.valid_receipt_manifest(response_manifest));

-- R3-01: USER_CONFIRM/USER_REJECT/USER_DEFER must have BOTH review_session_id AND proposal_revision_id
ALTER TABLE memory.decision DROP CONSTRAINT IF EXISTS decision_final_verdict_non_null_check;
ALTER TABLE memory.decision
    ADD CONSTRAINT decision_final_verdict_non_null_check
    CHECK (decision_kind NOT IN ('USER_CONFIRM','USER_REJECT','USER_DEFER')
           OR (review_session_id IS NOT NULL AND proposal_revision_id IS NOT NULL));

CREATE UNIQUE INDEX change_event_business_fact_unique
    ON memory.change_event (event_type, target_kind, target_id, target_revision_ref, decision_id)
    WHERE decision_id IS NOT NULL;

CREATE UNIQUE INDEX outbox_event_change_unique
    ON runtime.outbox_event (change_event_id)
    WHERE change_event_id IS NOT NULL;
