-- First V2 canonical CREATE publication. All tables are owned by the migrator;
-- only the API role may use them. No source files or WRITE_SET drafts are stored.
CREATE SCHEMA evidence AUTHORIZATION hide_nest_migrator;
CREATE SCHEMA memory AUTHORIZATION hide_nest_migrator;
REVOKE ALL ON SCHEMA evidence, memory FROM PUBLIC, hide_nest_worker;
GRANT USAGE ON SCHEMA evidence, memory TO hide_nest_api;

CREATE TABLE memory.world_binding (
    singleton smallint PRIMARY KEY DEFAULT 1 CHECK (singleton = 1),
    world_ref text COLLATE "C" NOT NULL CHECK (octet_length(world_ref) BETWEEN 1 AND 128),
    bound_at timestamptz NOT NULL
);

CREATE TABLE runtime.source_write_gate (
    source_id uuid PRIMARY KEY REFERENCES runtime.source_registration(source_id) ON DELETE NO ACTION,
    source_version text COLLATE "C" NOT NULL CHECK (octet_length(source_version) BETWEEN 1 AND 128),
    state text COLLATE "C" NOT NULL CHECK (state IN ('ALLOWED','BLOCKED')),
    checked_at timestamptz NOT NULL
);

CREATE TABLE evidence.source_unit (
    unit_id uuid PRIMARY KEY,
    source_id uuid NOT NULL REFERENCES runtime.source_registration(source_id) ON DELETE NO ACTION,
    source_ref text COLLATE "C" NOT NULL CHECK (octet_length(source_ref) BETWEEN 1 AND 512),
    source_version text COLLATE "C" NOT NULL CHECK (octet_length(source_version) BETWEEN 1 AND 128),
    UNIQUE (source_id, source_ref, source_version)
);
CREATE TABLE evidence.source_anchor (
    anchor_id uuid PRIMARY KEY,
    unit_id uuid NOT NULL REFERENCES evidence.source_unit(unit_id) ON DELETE NO ACTION,
    locator text COLLATE "C" NOT NULL CHECK (octet_length(locator) BETWEEN 1 AND 512),
    exact_text text NOT NULL CHECK (octet_length(exact_text) BETWEEN 1 AND 8192),
    text_hash bytea NOT NULL CHECK (octet_length(text_hash) = 32),
    frame text, actor text, speaking_as text,
    UNIQUE (unit_id, locator),
    CHECK (frame IS NULL OR octet_length(frame) BETWEEN 1 AND 512),
    CHECK (actor IS NULL OR octet_length(actor) BETWEEN 1 AND 512),
    CHECK (speaking_as IS NULL OR octet_length(speaking_as) BETWEEN 1 AND 512)
);

CREATE TABLE memory.record (
    record_id uuid PRIMARY KEY,
    type text COLLATE "C" NOT NULL CHECK (type IN ('EVENT','CLAIM','QUOTE','UNDERSTANDING')),
    current_revision_id uuid NOT NULL UNIQUE,
    participation_state text COLLATE "C" NOT NULL DEFAULT 'ACTIVE' CHECK (participation_state = 'ACTIVE')
);
CREATE TABLE memory.revision (
    revision_id uuid PRIMARY KEY,
    record_id uuid NOT NULL REFERENCES memory.record(record_id) DEFERRABLE INITIALLY DEFERRED,
    revision_no integer NOT NULL CHECK (revision_no = 1),
    content text NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 16384),
    subject text NOT NULL CHECK (octet_length(subject) BETWEEN 1 AND 512),
    scope text NOT NULL CHECK (octet_length(scope) BETWEEN 1 AND 512),
    perspective text NOT NULL CHECK (octet_length(perspective) BETWEEN 1 AND 512),
    conditions text, time_context text, uncertainty text,
    formation_ref text NOT NULL CHECK (octet_length(formation_ref) BETWEEN 1 AND 512),
    task_id uuid NOT NULL REFERENCES runtime.formation_task(task_id),
    created_at timestamptz NOT NULL,
    UNIQUE (record_id, revision_no),
    UNIQUE (record_id, revision_id)
);
ALTER TABLE memory.record ADD CONSTRAINT record_current_revision_fk
    FOREIGN KEY (record_id, current_revision_id) REFERENCES memory.revision(record_id, revision_id)
    DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE memory.revision_anchor (
    revision_id uuid NOT NULL REFERENCES memory.revision(revision_id),
    anchor_id uuid NOT NULL REFERENCES evidence.source_anchor(anchor_id),
    relation_kind text COLLATE "C" NOT NULL CHECK (relation_kind = 'SUPPORT'),
    PRIMARY KEY (revision_id, anchor_id)
);
CREATE TABLE memory.revision_relation (
    revision_id uuid NOT NULL REFERENCES memory.revision(revision_id),
    target_revision_id uuid NOT NULL REFERENCES memory.revision(revision_id),
    relation_kind text COLLATE "C" NOT NULL CHECK (relation_kind IN ('SUPPORT','COUNTER')),
    PRIMARY KEY (revision_id, target_revision_id, relation_kind),
    CHECK (revision_id <> target_revision_id)
);
CREATE INDEX revision_relation_target ON memory.revision_relation(target_revision_id);

CREATE TABLE memory.create_receipt (
    idempotency_key uuid PRIMARY KEY,
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    task_id uuid NOT NULL UNIQUE REFERENCES runtime.formation_task(task_id),
    attempt_id uuid NOT NULL UNIQUE REFERENCES runtime.formation_attempt(attempt_id),
    created_at timestamptz NOT NULL
);
CREATE TABLE memory.create_receipt_item (
    idempotency_key uuid NOT NULL REFERENCES memory.create_receipt(idempotency_key),
    item_index integer NOT NULL CHECK (item_index >= 0),
    record_id uuid NOT NULL UNIQUE REFERENCES memory.record(record_id),
    revision_id uuid NOT NULL UNIQUE REFERENCES memory.revision(revision_id),
    PRIMARY KEY (idempotency_key, item_index)
);
CREATE TABLE memory.projection_outbox (
    event_id uuid PRIMARY KEY,
    event_kind text COLLATE "C" NOT NULL CHECK (event_kind = 'MEMORY_CREATED'),
    record_id uuid NOT NULL REFERENCES memory.record(record_id),
    revision_id uuid NOT NULL REFERENCES memory.revision(revision_id),
    request_hash bytea NOT NULL CHECK (octet_length(request_hash) = 32),
    created_at timestamptz NOT NULL,
    UNIQUE (revision_id, event_kind)
);

REVOKE ALL ON ALL TABLES IN SCHEMA evidence, memory FROM PUBLIC, hide_nest_worker;
REVOKE ALL ON runtime.source_write_gate FROM PUBLIC, hide_nest_worker;
GRANT SELECT, INSERT ON evidence.source_unit, evidence.source_anchor TO hide_nest_api;
GRANT SELECT, INSERT ON memory.record, memory.revision, memory.revision_anchor,
    memory.revision_relation, memory.create_receipt, memory.create_receipt_item,
    memory.projection_outbox TO hide_nest_api;
GRANT SELECT ON memory.world_binding TO hide_nest_api;
GRANT SELECT, INSERT, UPDATE ON runtime.source_write_gate TO hide_nest_api;
