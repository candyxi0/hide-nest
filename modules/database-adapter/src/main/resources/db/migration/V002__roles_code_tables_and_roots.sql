DO $$
BEGIN
    IF current_user <> 'hide_nest_migrator'
       OR pg_catalog.to_regrole('hide_nest_migrator') IS NULL
       OR pg_catalog.to_regrole('hide_nest_api') IS NULL
       OR pg_catalog.to_regrole('hide_nest_worker') IS NULL THEN
        RAISE EXCEPTION 'HDM005_REQUIRED_DATABASE_ROLES_MISSING'
            USING ERRCODE = '55000';
    END IF;
END
$$;

CREATE TABLE runtime.failure_code_registry (
    failure_code text COLLATE "C" PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

INSERT INTO runtime.failure_code_registry (failure_code) VALUES
    ('REQUEST_SCHEMA_INVALID'),
    ('REQUEST_TOO_LARGE'),
    ('UNSUPPORTED_CONTRACT_VERSION'),
    ('IDEMPOTENCY_KEY_REQUIRED'),
    ('IDEMPOTENCY_KEY_REUSED'),
    ('DEVICE_CREDENTIAL_INVALID'),
    ('DEVICE_CREDENTIAL_EXPIRED'),
    ('IDENTITY_ENTRY_UNREGISTERED'),
    ('IDENTITY_BASELINE_STALE'),
    ('MODEL_ROUTE_UNACCEPTED'),
    ('IDENTITY_SESSION_EXPIRED'),
    ('IDENTITY_SESSION_REVOKED'),
    ('CAPABILITY_REQUIRED'),
    ('CAPABILITY_EXPIRED'),
    ('CAPABILITY_ALREADY_CONSUMED'),
    ('CAPABILITY_SCOPE_MISMATCH'),
    ('ACCESS_DENIED'),
    ('POLICY_REVISION_STALE'),
    ('DERIVATION_BLOCKED'),
    ('DELETION_FENCED'),
    ('THREAD_READER_UNSUPPORTED'),
    ('THREAD_SCHEMA_UNKNOWN'),
    ('SOURCE_RANGE_GAP'),
    ('SOURCE_ORDER_INVALID'),
    ('SOURCE_PAYLOAD_UNAVAILABLE'),
    ('CAPTURE_SCOPE_STALE'),
    ('REVIEW_SESSION_NOT_OPEN'),
    ('REVIEW_MEMBER_MISMATCH'),
    ('REVIEW_FINAL_INCOMPLETE'),
    ('USER_CONFIRMATION_PROOF_INVALID'),
    ('EXPECTED_REVISION_STALE'),
    ('PROPOSAL_CONFLICT'),
    ('CANONICAL_COMMIT_FAILED'),
    ('RETRIEVAL_NO_MATCH'),
    ('FTS_ROUTE_UNAVAILABLE'),
    ('VECTOR_ROUTE_UNAVAILABLE'),
    ('RANK_OUTPUT_INVALID'),
    ('CONTEXT_PACK_EXPIRED'),
    ('CONTEXT_PACK_INVALIDATED'),
    ('DELETION_PREVIEW_STALE'),
    ('DELETION_CLOSURE_MISMATCH'),
    ('DELETION_EXECUTION_FAILED'),
    ('RESTORE_CHECKPOINT_INVALID'),
    ('RESTORE_GOVERNANCE_INCOMPLETE'),
    ('RESTORE_TRUST_ANCHOR_INVALID'),
    ('DATABASE_UNAVAILABLE'),
    ('PAYLOAD_STORE_UNAVAILABLE'),
    ('MODEL_PROVIDER_UNAVAILABLE'),
    ('OUTBOX_FINAL_FAILED'),
    ('INTERNAL_FAILURE');

CREATE TABLE runtime.event_type_registry (
    event_type text COLLATE "C" PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

INSERT INTO runtime.event_type_registry (event_type) VALUES
    ('closeout.received.v1'),
    ('review.opened.v1'),
    ('review.decisions-committed.v1'),
    ('memory.canonical-committed.v1'),
    ('memory.state-changed.v1'),
    ('memory.policy-changed.v1'),
    ('deletion.authorized.v1'),
    ('deletion.completed.v1'),
    ('deletion.failed.v1'),
    ('index.sync-completed.v1'),
    ('index.sync-final-failed.v1'),
    ('retrieval.route-health-changed.v1');

CREATE TABLE memory.actor_ref (
    actor_id uuid PRIMARY KEY,
    actor_kind text COLLATE "C" NOT NULL,
    stable_ref text COLLATE "C" NOT NULL,
    display_label text,
    created_at timestamptz NOT NULL,
    UNIQUE (actor_kind, stable_ref)
);

CREATE TABLE memory.proposal (
    proposal_id uuid PRIMARY KEY,
    proposal_kind text COLLATE "C" NOT NULL,
    target_memory_id uuid,
    created_at timestamptz NOT NULL
);

CREATE TABLE memory.review_session (
    review_session_id uuid PRIMARY KEY,
    state text COLLATE "C" NOT NULL,
    idempotency_key text COLLATE "C" NOT NULL UNIQUE,
    request_hash bytea NOT NULL,
    opened_at timestamptz NOT NULL,
    terminal_at timestamptz,
    CONSTRAINT review_session_state_check
        CHECK (state IN ('OPEN', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT review_session_request_hash_check
        CHECK (octet_length(request_hash) = 32),
    CONSTRAINT review_session_terminal_check
        CHECK ((state = 'OPEN' AND terminal_at IS NULL)
            OR (state <> 'OPEN' AND terminal_at IS NOT NULL))
);

CREATE TABLE memory.access_policy (
    policy_id uuid PRIMARY KEY,
    owner_kind text COLLATE "C" NOT NULL,
    owner_id uuid NOT NULL,
    current_revision_no bigint NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT access_policy_revision_positive_check CHECK (current_revision_no >= 1),
    CONSTRAINT access_policy_exact_owner_unique UNIQUE (owner_kind, owner_id)
);

CREATE TABLE runtime.idempotency_receipt (
    idempotency_key text COLLATE "C" PRIMARY KEY,
    operation_code text COLLATE "C" NOT NULL,
    request_hash bytea NOT NULL,
    state text COLLATE "C" NOT NULL,
    resource_kind text COLLATE "C",
    resource_id uuid,
    response_manifest jsonb,
    created_at timestamptz NOT NULL,
    committed_at timestamptz NOT NULL,
    CONSTRAINT idempotency_receipt_request_hash_check CHECK (octet_length(request_hash) = 32),
    CONSTRAINT idempotency_receipt_state_check CHECK (state = 'COMMITTED'),
    CONSTRAINT idempotency_receipt_manifest_check
        CHECK (response_manifest IS NULL OR jsonb_typeof(response_manifest) = 'object')
);
