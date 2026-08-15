-- V018 | Local V1 | memory revision embedding vector (pgvector)
--
-- A single, explicitly named "memory revision vector" table inside the existing
-- `memory` schema. No fifth business schema is introduced, and vectors are not
-- stored in runtime.retrieval_trace.
--
-- Embedding rows are derived facts with no independent governance authority:
-- a row is keyed by the current memory revision plus the exact model fingerprint
-- (model name, GGUF SHA-256, dimension, normalization). Permanent deletion of a
-- MemoryRevision erases its vector in the same transaction via a precise FK
-- ON DELETE CASCADE. That CASCADE exception is scoped strictly to this derived,
-- rebuildable table and must not spread to any business fact table.
--
-- V1 uses exact cosine (<=>) only; no HNSW/IVFFlat index is created.

-- ============================================================
-- memory.memory_revision_embedding
-- ============================================================
CREATE TABLE memory.memory_revision_embedding (
    memory_revision_embedding_id uuid PRIMARY KEY,
    memory_revision_id uuid NOT NULL,
    model_name text COLLATE "C" NOT NULL,
    gguf_sha256 bytea NOT NULL,
    dimension integer NOT NULL,
    normalization text COLLATE "C" NOT NULL,
    embedded_body_sha256 bytea NOT NULL,
    embedding public.vector(512) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT memory_revision_embedding_revision_fk
        FOREIGN KEY (memory_revision_id)
        REFERENCES memory.memory_revision (memory_revision_id)
        ON DELETE CASCADE,
    CONSTRAINT memory_revision_embedding_dimension_check CHECK (dimension = 512),
    CONSTRAINT memory_revision_embedding_normalization_check CHECK (normalization = 'CALLER_L2'),
    CONSTRAINT memory_revision_embedding_gguf_sha256_check CHECK (octet_length(gguf_sha256) = 32),
    CONSTRAINT memory_revision_embedding_body_sha256_check CHECK (octet_length(embedded_body_sha256) = 32),
    CONSTRAINT memory_revision_embedding_vector_nonzero_check CHECK (public.vector_norm(embedding) > 0),
    CONSTRAINT memory_revision_embedding_fingerprint_unique
        UNIQUE (memory_revision_id, model_name, gguf_sha256, dimension, normalization)
);

-- ============================================================
-- Privileges (reuse existing roles; no new role is created)
-- worker writes + reads; api read-only; PUBLIC has no access.
-- ============================================================
REVOKE ALL ON memory.memory_revision_embedding FROM PUBLIC;
GRANT SELECT, INSERT ON memory.memory_revision_embedding TO hide_nest_worker;
GRANT SELECT ON memory.memory_revision_embedding TO hide_nest_api;
