-- A target is explicit; no active generation or real model is selected here.
CREATE TABLE runtime.projection_target (
    world_ref text COLLATE "C" NOT NULL CHECK (octet_length(world_ref) BETWEEN 1 AND 128),
    projection_generation integer NOT NULL CHECK (projection_generation BETWEEN 0 AND 2147483647),
    schema_ref text COLLATE "C" NOT NULL CHECK (octet_length(schema_ref) BETWEEN 1 AND 128),
    schema_version text COLLATE "C" NOT NULL CHECK (octet_length(schema_version) BETWEEN 1 AND 128),
    embedding_ref text COLLATE "C" NOT NULL CHECK (octet_length(embedding_ref) BETWEEN 1 AND 128),
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions BETWEEN 1 AND 4096),
    embedding_version text COLLATE "C" NOT NULL CHECK (octet_length(embedding_version) BETWEEN 1 AND 128),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (world_ref, projection_generation)
);

CREATE TABLE runtime.projection_delivery (
    world_ref text COLLATE "C" NOT NULL,
    projection_generation integer NOT NULL,
    event_id uuid NOT NULL REFERENCES memory.projection_outbox(event_id) ON DELETE NO ACTION,
    state text COLLATE "C" NOT NULL CHECK (state IN
        ('PENDING','ATTEMPTING','RETRY_WAIT','ATTENTION_REQUIRED','APPLIED')),
    attempt_generation integer NOT NULL DEFAULT 0 CHECK (attempt_generation BETWEEN 0 AND 2147483647),
    budget_used integer NOT NULL DEFAULT 0 CHECK (budget_used BETWEEN 0 AND 2147483647),
    current_attempt_id uuid,
    ready_at timestamptz,
    applied_revision_id uuid,
    attention_code text COLLATE "C",
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (world_ref, projection_generation, event_id),
    FOREIGN KEY (world_ref, projection_generation)
        REFERENCES runtime.projection_target(world_ref, projection_generation) ON DELETE NO ACTION,
    CHECK ((state = 'ATTEMPTING') = (current_attempt_id IS NOT NULL)),
    CHECK ((state = 'APPLIED') = (applied_revision_id IS NOT NULL)),
    CHECK ((state = 'ATTENTION_REQUIRED') = (attention_code IS NOT NULL)),
    CHECK ((state = 'RETRY_WAIT') = (ready_at IS NOT NULL))
);
CREATE INDEX projection_delivery_ready ON runtime.projection_delivery
    (world_ref, projection_generation, state, ready_at, event_id);

CREATE TABLE runtime.projection_attempt (
    attempt_id uuid PRIMARY KEY,
    world_ref text COLLATE "C" NOT NULL,
    projection_generation integer NOT NULL,
    event_id uuid NOT NULL,
    attempt_generation integer NOT NULL CHECK (attempt_generation BETWEEN 1 AND 2147483647),
    task_id uuid NOT NULL UNIQUE,
    owner_ref text COLLATE "C" NOT NULL CHECK (octet_length(owner_ref) BETWEEN 1 AND 128),
    schema_ref text COLLATE "C" NOT NULL,
    schema_version text COLLATE "C" NOT NULL,
    embedding_ref text COLLATE "C" NOT NULL,
    embedding_dimensions integer NOT NULL,
    embedding_version text COLLATE "C" NOT NULL,
    material_digest text COLLATE "C" NOT NULL CHECK (material_digest ~ '^sha256:[0-9a-f]{64}$'),
    expected_revision_id uuid NOT NULL,
    lease_until timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    state text COLLATE "C" NOT NULL CHECK (state IN ('RUNNING','EXPIRED','APPLIED','RETRY_WAIT','ATTENTION_REQUIRED')),
    failure_code text COLLATE "C",
    receipt_digest text COLLATE "C" CHECK (receipt_digest IS NULL OR receipt_digest ~ '^sha256:[0-9a-f]{64}$'),
    UNIQUE (world_ref, projection_generation, event_id, attempt_generation),
    FOREIGN KEY (world_ref, projection_generation, event_id)
        REFERENCES runtime.projection_delivery(world_ref, projection_generation, event_id) ON DELETE NO ACTION
);
CREATE INDEX projection_attempt_delivery ON runtime.projection_attempt
    (world_ref, projection_generation, event_id, attempt_generation);

REVOKE ALL ON runtime.projection_target, runtime.projection_delivery, runtime.projection_attempt
    FROM PUBLIC, hide_nest_worker;
GRANT SELECT, INSERT ON runtime.projection_target TO hide_nest_api;
GRANT SELECT, INSERT, UPDATE ON runtime.projection_delivery, runtime.projection_attempt TO hide_nest_api;
