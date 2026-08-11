-- V011 | Local V1 S3A | immutable deletion preview closure and members

CREATE TABLE memory.deletion_closure (
    closure_id uuid PRIMARY KEY,
    root_memory_id uuid NOT NULL,
    preview_revision bigint NOT NULL,
    root_current_revision_id uuid NOT NULL,
    root_revision_no bigint NOT NULL,
    root_policy_id uuid NOT NULL,
    root_policy_revision_no bigint NOT NULL,
    request_idempotency_key text COLLATE "C" NOT NULL UNIQUE,
    request_hash bytea NOT NULL,
    manifest_hash bytea NOT NULL,
    state text COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    CONSTRAINT deletion_closure_preview_revision_check CHECK (preview_revision >= 1),
    CONSTRAINT deletion_closure_root_revision_check CHECK (root_revision_no >= 1),
    CONSTRAINT deletion_closure_policy_revision_check CHECK (root_policy_revision_no >= 1),
    CONSTRAINT deletion_closure_key_not_blank CHECK (char_length(trim(request_idempotency_key)) > 0),
    CONSTRAINT deletion_closure_request_hash_check CHECK (octet_length(request_hash) = 32),
    CONSTRAINT deletion_closure_manifest_hash_check CHECK (octet_length(manifest_hash) = 32),
    CONSTRAINT deletion_closure_state_check CHECK (state = 'PREVIEWED'),
    CONSTRAINT deletion_closure_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT deletion_closure_root_revision_unique UNIQUE (root_memory_id, preview_revision)
);

CREATE TABLE memory.deletion_closure_member (
    closure_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    member_kind text COLLATE "C" NOT NULL,
    target_id uuid NOT NULL,
    target_revision_ref bigint,
    disposition text COLLATE "C" NOT NULL,
    size_bytes bigint,
    content_hash bytea,
    PRIMARY KEY (closure_id, ordinal),
    CONSTRAINT deletion_closure_member_closure_fk
        FOREIGN KEY (closure_id)
        REFERENCES memory.deletion_closure (closure_id)
        ON DELETE NO ACTION,
    CONSTRAINT deletion_closure_member_ordinal_check CHECK (ordinal >= 1),
    CONSTRAINT deletion_closure_member_kind_check CHECK (member_kind IN (
        'MEMORY', 'MEMORY_REVISION', 'SOURCE_ANCHOR', 'SOURCE_UNIT',
        'SOURCE_PAYLOAD', 'AFFECTED_MEMORY'
    )),
    CONSTRAINT deletion_closure_member_revision_check
        CHECK (target_revision_ref IS NULL OR target_revision_ref >= 1),
    CONSTRAINT deletion_closure_member_disposition_check CHECK (disposition IN (
        'DELETE_REQUESTED', 'DELETE_CANDIDATE', 'AFFECTED_PENDING_CHOICE'
    )),
    CONSTRAINT deletion_closure_member_payload_size_check CHECK (
        (member_kind = 'SOURCE_PAYLOAD' AND size_bytes IS NOT NULL AND size_bytes >= 0)
        OR (member_kind <> 'SOURCE_PAYLOAD' AND size_bytes IS NULL)
    ),
    CONSTRAINT deletion_closure_member_payload_hash_check CHECK (
        (member_kind = 'SOURCE_PAYLOAD' AND content_hash IS NOT NULL AND octet_length(content_hash) = 32)
        OR (member_kind <> 'SOURCE_PAYLOAD' AND content_hash IS NULL)
    ),
    CONSTRAINT deletion_closure_member_disposition_kind_check CHECK (
        (member_kind IN ('MEMORY', 'MEMORY_REVISION') AND disposition = 'DELETE_REQUESTED')
        OR (member_kind IN ('SOURCE_ANCHOR', 'SOURCE_UNIT', 'SOURCE_PAYLOAD')
            AND disposition = 'DELETE_CANDIDATE')
        OR (member_kind = 'AFFECTED_MEMORY' AND disposition = 'AFFECTED_PENDING_CHOICE')
    ),
    CONSTRAINT deletion_closure_member_semantic_unique
        UNIQUE NULLS NOT DISTINCT (closure_id, member_kind, target_id, target_revision_ref)
);

CREATE TRIGGER deletion_closure_immutable
    BEFORE UPDATE OR DELETE ON memory.deletion_closure
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

CREATE TRIGGER deletion_closure_member_immutable
    BEFORE UPDATE OR DELETE ON memory.deletion_closure_member
    FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation();

CREATE INDEX deletion_closure_root_lookup
    ON memory.deletion_closure (root_memory_id, preview_revision DESC);
CREATE INDEX deletion_closure_member_target_lookup
    ON memory.deletion_closure_member (target_id, member_kind);

-- API/worker are preview readers only. The existing controlled write role owns inserts.
GRANT SELECT ON memory.deletion_closure, memory.deletion_closure_member
TO hide_nest_api, hide_nest_worker;
