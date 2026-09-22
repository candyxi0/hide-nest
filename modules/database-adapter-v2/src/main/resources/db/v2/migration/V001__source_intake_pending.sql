-- Nest V2 V001 | clean, body-free source registration and Formation intake.
-- No Worker claim, lease, settlement, processed advancement, source body or Memory write.

-- This migration is for a fresh V2 database only. It does not upgrade or copy V1 data.
DO $$
BEGIN
    IF current_setting('server_version_num')::integer / 10000 <> 18 THEN
        RAISE EXCEPTION 'NEST_V2_POSTGRES_MAJOR_MISMATCH' USING ERRCODE = '55000';
    END IF;
    IF current_user <> 'hide_nest_migrator'
       OR pg_catalog.to_regrole('hide_nest_api') IS NULL
       OR pg_catalog.to_regrole('hide_nest_worker') IS NULL THEN
        RAISE EXCEPTION 'NEST_V2_REQUIRED_DATABASE_ROLES_MISSING' USING ERRCODE = '55000';
    END IF;
END
$$;

CREATE SCHEMA runtime AUTHORIZATION hide_nest_migrator;
REVOKE ALL ON SCHEMA runtime FROM PUBLIC;
GRANT USAGE ON SCHEMA runtime TO hide_nest_api;

-- Operational source identity. This is not Evidence and carries no source body.
CREATE TABLE runtime.source_registration (
    source_id uuid PRIMARY KEY,
    source_kind text COLLATE "C" NOT NULL,
    platform text COLLATE "C" NOT NULL,
    external_ref text COLLATE "C" NOT NULL,
    registered_at timestamptz NOT NULL,
    CONSTRAINT source_registration_kind_format CHECK (source_kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT source_registration_platform_nonblank CHECK (length(btrim(platform)) BETWEEN 1 AND 128),
    CONSTRAINT source_registration_external_ref_nonblank CHECK (length(btrim(external_ref)) BETWEEN 1 AND 512),
    CONSTRAINT source_registration_external_unique UNIQUE (platform, external_ref)
);

CREATE TABLE runtime.source_progress (
    source_id uuid PRIMARY KEY,
    last_notification_sequence bigint NOT NULL,
    last_notification_hash bytea NOT NULL,
    discovered_sequence bigint NOT NULL,
    discovered_cursor text COLLATE "C" NOT NULL,
    discovered_source_version text COLLATE "C" NOT NULL,
    stable_sequence bigint,
    stable_cursor text COLLATE "C",
    stable_source_version text COLLATE "C",
    processed_sequence bigint,
    processed_cursor text COLLATE "C",
    processed_source_version text COLLATE "C",
    read_kind text COLLATE "C" NOT NULL,
    read_ref text COLLATE "C",
    read_version text COLLATE "C",
    read_expires_at timestamptz,
    last_real_change_at timestamptz NOT NULL,
    quiet_until timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT source_progress_source_fk
        FOREIGN KEY (source_id) REFERENCES runtime.source_registration (source_id) ON DELETE NO ACTION,
    CONSTRAINT source_progress_notification_positive CHECK (last_notification_sequence >= 1),
    CONSTRAINT source_progress_notification_hash CHECK (octet_length(last_notification_hash) = 32),
    CONSTRAINT source_progress_discovered_positive CHECK (discovered_sequence >= 1),
    CONSTRAINT source_progress_discovered_cursor CHECK (
        octet_length(discovered_cursor) BETWEEN 1 AND 512 AND discovered_cursor = btrim(discovered_cursor)),
    CONSTRAINT source_progress_discovered_version CHECK (
        octet_length(discovered_source_version) BETWEEN 1 AND 128
        AND discovered_source_version = btrim(discovered_source_version)),
    CONSTRAINT source_progress_stable_group CHECK (
        (stable_sequence IS NULL AND stable_cursor IS NULL AND stable_source_version IS NULL)
        OR (stable_sequence >= 1
            AND octet_length(stable_cursor) BETWEEN 1 AND 512 AND stable_cursor = btrim(stable_cursor)
            AND octet_length(stable_source_version) BETWEEN 1 AND 128
            AND stable_source_version = btrim(stable_source_version))),
    CONSTRAINT source_progress_processed_group CHECK (
        (processed_sequence IS NULL AND processed_cursor IS NULL AND processed_source_version IS NULL)
        OR (processed_sequence >= 1
            AND octet_length(processed_cursor) BETWEEN 1 AND 512 AND processed_cursor = btrim(processed_cursor)
            AND octet_length(processed_source_version) BETWEEN 1 AND 128
            AND processed_source_version = btrim(processed_source_version))),
    CONSTRAINT source_progress_order CHECK (
        (stable_sequence IS NULL OR stable_sequence <= discovered_sequence)
        AND (processed_sequence IS NULL OR stable_sequence IS NOT NULL AND processed_sequence <= stable_sequence)),
    CONSTRAINT source_progress_read_kind CHECK (
        read_kind IN ('STABLE_REREAD', 'BOUNDED_SNAPSHOT', 'UNAVAILABLE')),
    CONSTRAINT source_progress_read_binding CHECK (
        (read_kind = 'STABLE_REREAD'
            AND read_ref IS NOT NULL AND octet_length(read_ref) BETWEEN 1 AND 512 AND read_ref = btrim(read_ref)
            AND read_version IS NOT NULL AND octet_length(read_version) BETWEEN 1 AND 128
            AND read_version = btrim(read_version) AND read_expires_at IS NULL)
        OR (read_kind = 'BOUNDED_SNAPSHOT'
            AND read_ref IS NOT NULL AND octet_length(read_ref) BETWEEN 1 AND 512 AND read_ref = btrim(read_ref)
            AND read_version IS NOT NULL AND octet_length(read_version) BETWEEN 1 AND 128
            AND read_version = btrim(read_version) AND read_expires_at IS NOT NULL)
        OR (read_kind = 'UNAVAILABLE'
            AND read_ref IS NULL AND read_version IS NULL AND read_expires_at IS NULL)),
    CONSTRAINT source_progress_quiet_window CHECK (
        quiet_until = last_real_change_at + interval '90 minutes'),
    CONSTRAINT source_progress_updated_after_change CHECK (updated_at >= last_real_change_at)
);

CREATE TABLE runtime.formation_task (
    task_id uuid PRIMARY KEY,
    source_id uuid NOT NULL,
    state text COLLATE "C" NOT NULL,
    from_sequence bigint,
    from_cursor text COLLATE "C",
    from_source_version text COLLATE "C",
    to_sequence bigint NOT NULL,
    to_cursor text COLLATE "C" NOT NULL,
    to_source_version text COLLATE "C" NOT NULL,
    read_kind text COLLATE "C" NOT NULL,
    read_ref text COLLATE "C",
    read_version text COLLATE "C",
    read_expires_at timestamptz,
    ready_at timestamptz NOT NULL,
    generation bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT formation_task_source_progress_fk
        FOREIGN KEY (source_id) REFERENCES runtime.source_progress (source_id) ON DELETE NO ACTION,
    CONSTRAINT formation_task_state_check CHECK (state = 'PENDING'),
    CONSTRAINT formation_task_from_group CHECK (
        (from_sequence IS NULL AND from_cursor IS NULL AND from_source_version IS NULL)
        OR (from_sequence >= 1
            AND octet_length(from_cursor) BETWEEN 1 AND 512 AND from_cursor = btrim(from_cursor)
            AND octet_length(from_source_version) BETWEEN 1 AND 128
            AND from_source_version = btrim(from_source_version))),
    CONSTRAINT formation_task_to_check CHECK (
        to_sequence >= 1
        AND octet_length(to_cursor) BETWEEN 1 AND 512 AND to_cursor = btrim(to_cursor)
        AND octet_length(to_source_version) BETWEEN 1 AND 128
        AND to_source_version = btrim(to_source_version)),
    CONSTRAINT formation_task_range_check CHECK (from_sequence IS NULL OR from_sequence < to_sequence),
    CONSTRAINT formation_task_read_kind CHECK (
        read_kind IN ('STABLE_REREAD', 'BOUNDED_SNAPSHOT', 'UNAVAILABLE')),
    CONSTRAINT formation_task_read_binding CHECK (
        (read_kind = 'STABLE_REREAD'
            AND read_ref IS NOT NULL AND octet_length(read_ref) BETWEEN 1 AND 512 AND read_ref = btrim(read_ref)
            AND read_version IS NOT NULL AND octet_length(read_version) BETWEEN 1 AND 128
            AND read_version = btrim(read_version) AND read_expires_at IS NULL)
        OR (read_kind = 'BOUNDED_SNAPSHOT'
            AND read_ref IS NOT NULL AND octet_length(read_ref) BETWEEN 1 AND 512 AND read_ref = btrim(read_ref)
            AND read_version IS NOT NULL AND octet_length(read_version) BETWEEN 1 AND 128
            AND read_version = btrim(read_version) AND read_expires_at IS NOT NULL)
        OR (read_kind = 'UNAVAILABLE'
            AND read_ref IS NULL AND read_version IS NULL AND read_expires_at IS NULL)),
    CONSTRAINT formation_task_generation_zero CHECK (generation = 0),
    CONSTRAINT formation_task_updated_after_created CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX formation_task_one_pending_per_source
    ON runtime.formation_task (source_id) WHERE state = 'PENDING';
CREATE INDEX formation_task_ready_lookup
    ON runtime.formation_task (ready_at, task_id) WHERE state = 'PENDING';

REVOKE ALL ON runtime.source_registration FROM PUBLIC, hide_nest_worker;
REVOKE ALL ON runtime.source_progress FROM PUBLIC, hide_nest_worker;
REVOKE ALL ON runtime.formation_task FROM PUBLIC, hide_nest_worker;
GRANT SELECT, INSERT ON runtime.source_registration TO hide_nest_api;
GRANT SELECT, INSERT, UPDATE ON runtime.source_progress TO hide_nest_api;
GRANT SELECT, INSERT, UPDATE ON runtime.formation_task TO hide_nest_api;
