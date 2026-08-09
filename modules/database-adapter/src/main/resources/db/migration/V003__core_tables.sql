CREATE TABLE memory.proposal_revision (
    proposal_revision_id uuid PRIMARY KEY,
    proposal_id uuid NOT NULL,
    revision_no bigint NOT NULL,
    action_code text COLLATE "C" NOT NULL,
    body_text text,
    memory_type text COLLATE "C",
    perspective_actor_id uuid,
    expected_memory_revision_id uuid,
    expected_policy_revision_no bigint,
    body_hash bytea,
    created_at timestamptz NOT NULL,
    CONSTRAINT proposal_revision_number_check CHECK (revision_no >= 1),
    CONSTRAINT proposal_revision_memory_type_check CHECK (
        memory_type IS NULL OR memory_type IN (
            'Event', 'Claim', 'Quote', 'Interpretation', 'Calibration', 'Principle'
        )
    ),
    CONSTRAINT proposal_revision_expected_policy_check
        CHECK (expected_policy_revision_no IS NULL OR expected_policy_revision_no >= 1),
    CONSTRAINT proposal_revision_body_hash_check
        CHECK (body_hash IS NULL OR octet_length(body_hash) = 32),
    CONSTRAINT proposal_revision_number_unique UNIQUE (proposal_id, revision_no)
);

CREATE TABLE memory.review_member (
    review_session_id uuid NOT NULL,
    proposal_revision_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    PRIMARY KEY (review_session_id, proposal_revision_id),
    CONSTRAINT review_member_ordinal_check CHECK (ordinal >= 1),
    CONSTRAINT review_member_ordinal_unique UNIQUE (review_session_id, ordinal)
);

CREATE TABLE memory.decision (
    decision_id uuid PRIMARY KEY,
    decision_kind text COLLATE "C" NOT NULL,
    actor_id uuid NOT NULL,
    actor_role text COLLATE "C" NOT NULL,
    proposal_revision_id uuid,
    review_session_id uuid,
    target_kind text COLLATE "C" NOT NULL,
    target_id uuid NOT NULL,
    target_revision_ref bigint,
    authorization_ref text COLLATE "C" NOT NULL,
    idempotency_key text COLLATE "C" NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    CONSTRAINT decision_kind_check CHECK (decision_kind IN (
        'HIDE_SELECT',
        'USER_CONFIRM',
        'USER_REJECT',
        'USER_DEFER',
        'USER_ARCHIVE',
        'USER_ISOLATE',
        'USER_RESTORE',
        'USER_DELETE_CONFIRM'
    )),
    CONSTRAINT decision_target_revision_check
        CHECK (target_revision_ref IS NULL OR target_revision_ref >= 1)
);

CREATE TABLE memory.access_policy_revision (
    policy_id uuid NOT NULL,
    revision_no bigint NOT NULL,
    companion_allowed boolean NOT NULL,
    maintenance_allowed boolean NOT NULL,
    export_allowed boolean NOT NULL,
    external_provider_allowed boolean NOT NULL,
    isolated boolean NOT NULL,
    created_by_decision_id uuid,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (policy_id, revision_no),
    CONSTRAINT access_policy_revision_number_check CHECK (revision_no >= 1)
);

CREATE TABLE memory.access_policy_grant (
    policy_id uuid NOT NULL,
    revision_no bigint NOT NULL,
    actor_role text COLLATE "C" NOT NULL,
    purpose text COLLATE "C" NOT NULL,
    effect text COLLATE "C" NOT NULL,
    object_scope text COLLATE "C" NOT NULL,
    PRIMARY KEY (policy_id, revision_no, actor_role, purpose, effect, object_scope),
    CONSTRAINT access_policy_grant_scope_check CHECK (object_scope = 'EXACT_OBJECT')
);

CREATE TABLE memory.memory_record (
    memory_id uuid PRIMARY KEY,
    state text COLLATE "C" NOT NULL,
    current_revision_id uuid NOT NULL UNIQUE,
    policy_id uuid NOT NULL,
    current_policy_revision_no bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT memory_record_state_check CHECK (state IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT memory_record_policy_revision_check CHECK (current_policy_revision_no >= 1)
);

CREATE TABLE memory.memory_revision (
    memory_revision_id uuid PRIMARY KEY,
    memory_id uuid NOT NULL,
    revision_no bigint NOT NULL,
    memory_type text COLLATE "C" NOT NULL,
    perspective_actor_id uuid,
    body_text text NOT NULL,
    valid_from timestamptz,
    valid_to timestamptz,
    uncertainty_code text COLLATE "C",
    created_by_decision_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    CONSTRAINT memory_revision_number_check CHECK (revision_no >= 1),
    CONSTRAINT memory_revision_type_check CHECK (memory_type IN (
        'Event', 'Claim', 'Quote', 'Interpretation', 'Calibration', 'Principle'
    )),
    CONSTRAINT memory_revision_validity_check CHECK (
        valid_from IS NULL OR valid_to IS NULL OR valid_from <= valid_to
    ),
    CONSTRAINT memory_revision_number_unique UNIQUE (memory_id, revision_no),
    CONSTRAINT memory_revision_owner_identity_unique UNIQUE (memory_id, memory_revision_id)
);

CREATE TABLE memory.change_event (
    change_event_id uuid PRIMARY KEY,
    sequence_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_type text COLLATE "C" NOT NULL,
    actor_id uuid,
    target_kind text COLLATE "C" NOT NULL,
    target_id uuid NOT NULL,
    target_revision_ref bigint,
    decision_id uuid,
    occurred_at timestamptz NOT NULL,
    detail_manifest jsonb,
    CONSTRAINT change_event_target_revision_check
        CHECK (target_revision_ref IS NULL OR target_revision_ref >= 1),
    CONSTRAINT change_event_manifest_check
        CHECK (detail_manifest IS NULL OR jsonb_typeof(detail_manifest) = 'object')
);

CREATE TABLE runtime.outbox_event (
    event_id uuid PRIMARY KEY,
    idempotency_key text COLLATE "C" NOT NULL,
    sequence_no bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    event_category text COLLATE "C" NOT NULL,
    event_type text COLLATE "C" NOT NULL,
    aggregate_kind text COLLATE "C" NOT NULL,
    aggregate_id uuid NOT NULL,
    aggregate_revision bigint,
    contract_version text COLLATE "C" NOT NULL,
    purpose text COLLATE "C" NOT NULL,
    policy_revision bigint NOT NULL,
    manifest_hash bytea NOT NULL,
    payload_manifest jsonb NOT NULL,
    change_event_id uuid,
    state text COLLATE "C" NOT NULL DEFAULT 'READY',
    available_at timestamptz NOT NULL,
    lease_owner text COLLATE "C",
    lease_until timestamptz,
    attempt_count smallint NOT NULL DEFAULT 0,
    max_attempts smallint NOT NULL DEFAULT 8,
    last_failure_code text COLLATE "C",
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CONSTRAINT outbox_event_category_check CHECK (event_category IN ('GOVERNED', 'OPERATIONAL')),
    CONSTRAINT outbox_event_category_identity_check CHECK (
        (event_category = 'GOVERNED' AND change_event_id IS NOT NULL)
        OR (event_category = 'OPERATIONAL' AND change_event_id IS NULL AND aggregate_revision IS NOT NULL)
    ),
    CONSTRAINT outbox_event_revision_check CHECK (aggregate_revision IS NULL OR aggregate_revision >= 1),
    CONSTRAINT outbox_event_contract_check CHECK (contract_version = 'pink.event.v1'),
    CONSTRAINT outbox_event_purpose_check CHECK (char_length(purpose) BETWEEN 1 AND 64),
    CONSTRAINT outbox_event_policy_revision_check CHECK (policy_revision >= 0),
    CONSTRAINT outbox_event_manifest_hash_check CHECK (octet_length(manifest_hash) = 32),
    CONSTRAINT outbox_event_payload_object_check CHECK (jsonb_typeof(payload_manifest) = 'object'),
    CONSTRAINT outbox_event_state_check CHECK (state IN ('READY', 'LEASED', 'SUCCEEDED', 'FINAL_FAILED')),
    CONSTRAINT outbox_event_attempt_check CHECK (
        max_attempts = 8 AND attempt_count BETWEEN 0 AND max_attempts
    ),
    CONSTRAINT outbox_event_lease_check CHECK (
        (state = 'LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND completed_at IS NULL)
        OR (state <> 'LEASED' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT outbox_event_completion_check CHECK (
        (state IN ('SUCCEEDED', 'FINAL_FAILED') AND completed_at IS NOT NULL)
        OR (state IN ('READY', 'LEASED') AND completed_at IS NULL)
    ),
    CONSTRAINT outbox_event_idempotency_unique UNIQUE (idempotency_key)
);
