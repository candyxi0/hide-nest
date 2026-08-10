-- V008 | HDM-006 Slice A R1 | evidence schema + memory.memory_relation
-- 6 tables: evidence.source, source_unit, source_payload, source_anchor, source_anchor_unit, memory.memory_relation
-- R1-01: anchor-source_unit same-source trigger
-- R1-02: MemoryRelation target type full closure (EVIDENCED_BY→anchor; other 8→revision)
-- R1-03: code format ^[A-Z][A-Z0-9_]{0,63}$ for 5 code columns
-- No domain/port/adapter. No FTS/pgvector indexes. No immutability triggers in this slice.

-- ============================================================
-- evidence.source
-- ============================================================
CREATE TABLE evidence.source (
    source_id uuid PRIMARY KEY,
    source_kind text COLLATE "C" NOT NULL,
    platform text COLLATE "C" NOT NULL,
    external_ref text COLLATE "C" NOT NULL,
    observed_accessible boolean NOT NULL,
    compressed_observed boolean NOT NULL,
    policy_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    CONSTRAINT source_source_kind_format CHECK (source_kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT source_platform_not_blank CHECK (char_length(trim(platform)) > 0),
    CONSTRAINT source_external_ref_not_blank CHECK (char_length(trim(external_ref)) > 0),
    CONSTRAINT source_platform_external_ref_unique UNIQUE (platform, external_ref)
);

-- ============================================================
-- evidence.source_unit
-- ============================================================
CREATE TABLE evidence.source_unit (
    source_unit_id uuid PRIMARY KEY,
    source_id uuid NOT NULL,
    external_unit_ref text COLLATE "C" NOT NULL,
    source_version text COLLATE "C" NOT NULL,
    ordinal bigint NOT NULL,
    actor_id uuid,
    occurred_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT source_unit_external_ref_not_blank CHECK (char_length(trim(external_unit_ref)) > 0),
    CONSTRAINT source_unit_version_not_blank CHECK (char_length(trim(source_version)) > 0),
    CONSTRAINT source_unit_source_external_version_unique UNIQUE (source_id, external_unit_ref, source_version)
);

-- ============================================================
-- evidence.source_payload (metadata only; PayloadStore → HDM-019/020)
-- ============================================================
CREATE TABLE evidence.source_payload (
    payload_id uuid PRIMARY KEY,
    source_unit_id uuid NOT NULL,
    payload_kind text COLLATE "C" NOT NULL,
    store_adapter text COLLATE "C" NOT NULL,
    object_ref text COLLATE "C" NOT NULL,
    object_version_ref text COLLATE "C",
    content_type text COLLATE "C" NOT NULL,
    size_bytes bigint NOT NULL,
    content_hash bytea NOT NULL,
    policy_id uuid NOT NULL,
    current_policy_revision_no bigint NOT NULL,
    retention_class text COLLATE "C" NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT source_payload_kind_format CHECK (payload_kind ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT source_payload_store_adapter_format CHECK (store_adapter ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT source_payload_object_ref_not_blank CHECK (char_length(trim(object_ref)) > 0),
    CONSTRAINT source_payload_size_non_negative CHECK (size_bytes >= 0),
    CONSTRAINT source_payload_content_hash_length CHECK (octet_length(content_hash) = 32),
    CONSTRAINT source_payload_policy_revision_positive CHECK (current_policy_revision_no >= 1),
    CONSTRAINT source_payload_retention_class_format CHECK (retention_class ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

-- ============================================================
-- evidence.source_anchor
-- ============================================================
CREATE TABLE evidence.source_anchor (
    anchor_id uuid PRIMARY KEY,
    source_id uuid NOT NULL,
    anchor_kind text COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT source_anchor_kind_format CHECK (anchor_kind ~ '^[A-Z][A-Z0-9_]{0,63}$')
);

-- ============================================================
-- evidence.source_anchor_unit
-- ============================================================
CREATE TABLE evidence.source_anchor_unit (
    anchor_id uuid NOT NULL,
    source_unit_id uuid NOT NULL,
    from_offset bigint,
    to_offset bigint,
    ordinal bigint NOT NULL,
    PRIMARY KEY (anchor_id, ordinal),
    CONSTRAINT source_anchor_unit_offset_both_or_none CHECK (
        (from_offset IS NULL AND to_offset IS NULL)
        OR (from_offset IS NOT NULL AND to_offset IS NOT NULL)
    ),
    CONSTRAINT source_anchor_unit_from_offset_non_negative CHECK (
        from_offset IS NULL OR from_offset >= 0
    ),
    CONSTRAINT source_anchor_unit_to_offset_ge_from CHECK (
        to_offset IS NULL OR from_offset IS NULL OR to_offset >= from_offset
    )
);

-- ============================================================
-- memory.memory_relation
-- ============================================================
CREATE TABLE memory.memory_relation (
    relation_id uuid PRIMARY KEY,
    from_revision_id uuid NOT NULL,
    relation_type text COLLATE "C" NOT NULL,
    to_revision_id uuid,
    to_anchor_id uuid,
    perspective_actor_id uuid,
    created_by_decision_id uuid NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT memory_relation_type_check CHECK (relation_type IN (
        'EVIDENCED_BY', 'INTERPRETS', 'SUPPORTS', 'REFINES',
        'SUPERSEDES', 'CONFLICTS_WITH', 'CALIBRATES',
        'PART_OF_TRAJECTORY', 'MERGED_INTO'
    )),
    CONSTRAINT memory_relation_target_check CHECK (
        (relation_type = 'EVIDENCED_BY' AND to_anchor_id IS NOT NULL AND to_revision_id IS NULL)
        OR (relation_type <> 'EVIDENCED_BY' AND to_revision_id IS NOT NULL AND to_anchor_id IS NULL)
    )
);

-- ============================================================
-- Foreign keys (all ON DELETE NO ACTION, none deferred)
-- ============================================================

-- evidence.source → memory.access_policy
ALTER TABLE evidence.source
    ADD CONSTRAINT source_policy_fk
    FOREIGN KEY (policy_id)
    REFERENCES memory.access_policy (policy_id)
    ON DELETE NO ACTION;

-- evidence.source_unit → evidence.source
ALTER TABLE evidence.source_unit
    ADD CONSTRAINT source_unit_source_fk
    FOREIGN KEY (source_id)
    REFERENCES evidence.source (source_id)
    ON DELETE NO ACTION;

-- evidence.source_unit → memory.actor_ref
ALTER TABLE evidence.source_unit
    ADD CONSTRAINT source_unit_actor_fk
    FOREIGN KEY (actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

-- evidence.source_payload → evidence.source_unit
ALTER TABLE evidence.source_payload
    ADD CONSTRAINT source_payload_unit_fk
    FOREIGN KEY (source_unit_id)
    REFERENCES evidence.source_unit (source_unit_id)
    ON DELETE NO ACTION;

-- evidence.source_payload → memory.access_policy_revision
ALTER TABLE evidence.source_payload
    ADD CONSTRAINT source_payload_policy_revision_fk
    FOREIGN KEY (policy_id, current_policy_revision_no)
    REFERENCES memory.access_policy_revision (policy_id, revision_no)
    ON DELETE NO ACTION;

-- evidence.source_anchor → evidence.source
ALTER TABLE evidence.source_anchor
    ADD CONSTRAINT source_anchor_source_fk
    FOREIGN KEY (source_id)
    REFERENCES evidence.source (source_id)
    ON DELETE NO ACTION;

-- evidence.source_anchor_unit → evidence.source_anchor
ALTER TABLE evidence.source_anchor_unit
    ADD CONSTRAINT source_anchor_unit_anchor_fk
    FOREIGN KEY (anchor_id)
    REFERENCES evidence.source_anchor (anchor_id)
    ON DELETE NO ACTION;

-- evidence.source_anchor_unit → evidence.source_unit
ALTER TABLE evidence.source_anchor_unit
    ADD CONSTRAINT source_anchor_unit_unit_fk
    FOREIGN KEY (source_unit_id)
    REFERENCES evidence.source_unit (source_unit_id)
    ON DELETE NO ACTION;

-- memory.memory_relation → memory.memory_revision (from)
ALTER TABLE memory.memory_relation
    ADD CONSTRAINT memory_relation_from_revision_fk
    FOREIGN KEY (from_revision_id)
    REFERENCES memory.memory_revision (memory_revision_id)
    ON DELETE NO ACTION;

-- memory.memory_relation → memory.memory_revision (to)
ALTER TABLE memory.memory_relation
    ADD CONSTRAINT memory_relation_to_revision_fk
    FOREIGN KEY (to_revision_id)
    REFERENCES memory.memory_revision (memory_revision_id)
    ON DELETE NO ACTION;

-- memory.memory_relation → evidence.source_anchor
ALTER TABLE memory.memory_relation
    ADD CONSTRAINT memory_relation_to_anchor_fk
    FOREIGN KEY (to_anchor_id)
    REFERENCES evidence.source_anchor (anchor_id)
    ON DELETE NO ACTION;

-- memory.memory_relation → memory.actor_ref
ALTER TABLE memory.memory_relation
    ADD CONSTRAINT memory_relation_actor_fk
    FOREIGN KEY (perspective_actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

-- memory.memory_relation → memory.decision
ALTER TABLE memory.memory_relation
    ADD CONSTRAINT memory_relation_decision_fk
    FOREIGN KEY (created_by_decision_id)
    REFERENCES memory.decision (decision_id)
    ON DELETE NO ACTION;

-- ============================================================
-- R1-01: Anchor-SourceUnit same-source trigger
-- Ensures anchor.source_id = source_unit.source_id for every row
-- ============================================================
CREATE FUNCTION evidence.enforce_anchor_unit_same_source()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog, evidence
AS $$
DECLARE
    anchor_source_id uuid;
    unit_source_id uuid;
BEGIN
    SELECT source_id INTO anchor_source_id
    FROM evidence.source_anchor WHERE anchor_id = NEW.anchor_id;
    SELECT source_id INTO unit_source_id
    FROM evidence.source_unit WHERE source_unit_id = NEW.source_unit_id;
    IF anchor_source_id IS DISTINCT FROM unit_source_id THEN
        RAISE EXCEPTION 'HDM006_ANCHOR_UNIT_SOURCE_MISMATCH anchor=% unit=% source_anchor=% source_unit=%',
            NEW.anchor_id, NEW.source_unit_id, anchor_source_id, unit_source_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER source_anchor_unit_same_source_guard
    AFTER INSERT OR UPDATE ON evidence.source_anchor_unit
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION evidence.enforce_anchor_unit_same_source();

-- ============================================================
-- Query indexes (no FTS/pgvector)
-- ============================================================
CREATE INDEX source_payload_unit_lookup ON evidence.source_payload (source_unit_id);
CREATE INDEX source_payload_policy_lookup ON evidence.source_payload (policy_id, current_policy_revision_no);
CREATE INDEX source_anchor_source_lookup ON evidence.source_anchor (source_id);
CREATE INDEX source_anchor_unit_unit_lookup ON evidence.source_anchor_unit (source_unit_id);
CREATE INDEX memory_relation_from_revision_lookup ON memory.memory_relation (from_revision_id);
CREATE INDEX memory_relation_to_revision_lookup ON memory.memory_relation (to_revision_id);
CREATE INDEX memory_relation_to_anchor_lookup ON memory.memory_relation (to_anchor_id);
CREATE INDEX memory_relation_decision_lookup ON memory.memory_relation (created_by_decision_id);

-- ============================================================
-- Privileges (extends V006 API/Worker separation to evidence schema)
-- ============================================================
GRANT USAGE ON SCHEMA evidence TO hide_nest_api, hide_nest_worker;
REVOKE ALL ON ALL TABLES IN SCHEMA evidence FROM PUBLIC;

GRANT SELECT, INSERT ON
    evidence.source,
    evidence.source_unit,
    evidence.source_payload,
    evidence.source_anchor,
    evidence.source_anchor_unit
TO hide_nest_api;

GRANT SELECT ON
    evidence.source,
    evidence.source_unit,
    evidence.source_payload,
    evidence.source_anchor,
    evidence.source_anchor_unit
TO hide_nest_worker;

GRANT SELECT, INSERT ON memory.memory_relation TO hide_nest_api;
GRANT SELECT ON memory.memory_relation TO hide_nest_worker;

-- Revoke PUBLIC EXECUTE on evidence functions (consistent with V006 memory/runtime pattern)
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA evidence FROM PUBLIC;
