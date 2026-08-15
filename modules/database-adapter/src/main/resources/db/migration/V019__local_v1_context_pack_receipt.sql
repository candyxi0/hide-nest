-- V019 | Local V1 | context pack delivery item snapshot
--
-- Minimal, immutable delivery-item facts for an idempotent context pack response.
--
-- A context pack response carries per-memory facts (identity, revision, policy
-- revision, wire memory type, body text and similarity score). Of these, only the
-- memory revision identity, the delivery-time policy revision and the delivery-time
-- score are not otherwise recoverable after the fact; the memory identity, revision
-- number, memory type and body text are reconstructed from the immutable
-- memory.memory_revision row by memory_revision_id. Storing only these three facts
-- keeps body content out of the runtime domain and lets a same-key replay rebuild
-- the identical response from structured database rows without re-embedding,
-- re-searching, or stuffing a full response JSON into the receipt manifest.
--
-- It is a derived audit fact with no independent governance authority. Rows are
-- keyed by (delivery_id, ordinal) and are immutable; no UPDATE/DELETE is granted.

-- ============================================================
-- runtime.context_pack_delivery_item
-- ============================================================
CREATE TABLE runtime.context_pack_delivery_item (
    delivery_id uuid NOT NULL,
    ordinal bigint NOT NULL,
    memory_revision_id uuid NOT NULL,
    policy_revision_no bigint NOT NULL,
    score double precision NOT NULL,
    CONSTRAINT context_pack_delivery_item_pk PRIMARY KEY (delivery_id, ordinal),
    CONSTRAINT context_pack_delivery_item_delivery_fk
        FOREIGN KEY (delivery_id)
        REFERENCES runtime.context_delivery (delivery_id)
        ON DELETE NO ACTION,
    CONSTRAINT context_pack_delivery_item_ordinal_check CHECK (ordinal >= 0),
    CONSTRAINT context_pack_delivery_item_policy_revision_no_check CHECK (policy_revision_no >= 1),
    CONSTRAINT context_pack_delivery_item_score_check CHECK (score >= -1.0 AND score <= 1.0)
);

-- ============================================================
-- Privileges (reuse existing roles; no new role is created)
-- worker writes + reads; PUBLIC has no access.
--
-- R1-01: the Local V1 context pack path runs synchronously in the API process and
-- writes the three synchronous audit tables (retrieval_trace, context_delivery,
-- context_pack_delivery_item) plus the existing idempotency receipt. The receipt was
-- already INSERT-granted to hide_nest_api by V006; the other three were worker-only
-- from V009. Grant the API role the minimal SELECT, INSERT on exactly those three
-- tables here — no UPDATE/DELETE, no SECURITY DEFINER, no change to V009, and no
-- rollback of the worker's existing privileges.
-- ============================================================
REVOKE ALL ON runtime.context_pack_delivery_item FROM PUBLIC;
GRANT SELECT, INSERT ON runtime.context_pack_delivery_item TO hide_nest_worker;
GRANT SELECT ON runtime.context_pack_delivery_item TO hide_nest_api;

GRANT SELECT, INSERT ON
    runtime.retrieval_trace,
    runtime.context_delivery,
    runtime.context_pack_delivery_item
TO hide_nest_api;

-- The idempotency receipt was already INSERT-granted to hide_nest_api by V006, but its
-- manifest CHECK constraint invokes runtime.valid_receipt_manifest, which V006 deliberately
-- left EXECUTE-less for API/Worker. The synchronous Local V1 path writes the receipt in the
-- API process, so grant EXECUTE on exactly that one validation function (no other function).
GRANT EXECUTE ON FUNCTION runtime.valid_receipt_manifest(jsonb) TO hide_nest_api;
