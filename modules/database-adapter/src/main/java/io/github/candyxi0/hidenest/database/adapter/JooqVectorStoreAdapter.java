package io.github.candyxi0.hidenest.database.adapter;

import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * PostgreSQL/jOOQ implementation of the memory vector store/search port.
 *
 * <p>The pgvector {@code vector} column is bound as a canonical text literal
 * ({@code ?::public.vector}) and searched with the exact cosine operator
 * ({@code OPERATOR(public.<=>)}), because jOOQ has no built-in binding for the
 * {@code public.vector} user-defined type and no third-party binding dependency
 * is introduced. Every write is fail-closed: dimension, normalization mode,
 * SHA-256 lengths, finiteness and unit norm are re-verified before any INSERT.
 */
public class JooqVectorStoreAdapter implements MemoryVectorStorePort {

    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final double NORM_TOLERANCE = 1e-6;

    private final DSLContext dsl;

    public JooqVectorStoreAdapter(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public VectorUpsertResult upsertEmbedding(VectorEmbeddingDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("draft must not be null");
        }
        validateDraft(draft);

        int inserted = dsl.execute(
                "INSERT INTO memory.memory_revision_embedding ("
                        + "memory_revision_embedding_id, memory_revision_id, model_name, "
                        + "gguf_sha256, dimension, normalization, embedded_body_sha256, "
                        + "embedding, created_at) "
                        + "VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?::public.vector, ?::timestamptz) "
                        + "ON CONFLICT ON CONSTRAINT memory_revision_embedding_fingerprint_unique DO NOTHING",
                UUID.randomUUID(),
                draft.memoryRevisionId(),
                draft.modelName(),
                draft.ggufSha256(),
                draft.dimension(),
                draft.normalization(),
                draft.embeddedBodySha256(),
                vectorLiteral(draft.vector()),
                draft.createdAt());

        if (inserted == 1) {
            return new VectorUpsertResult.Inserted(draft.memoryRevisionId());
        }

        Record existing = dsl.fetchOne(
                "SELECT embedded_body_sha256 "
                        + "FROM memory.memory_revision_embedding "
                        + "WHERE memory_revision_id = ? AND model_name = ? "
                        + "AND gguf_sha256 = ? AND dimension = ? AND normalization = ?",
                draft.memoryRevisionId(),
                draft.modelName(),
                draft.ggufSha256(),
                draft.dimension(),
                draft.normalization());

        if (existing != null && Arrays.equals(existing.get(0, byte[].class), draft.embeddedBodySha256())) {
            return new VectorUpsertResult.AlreadyPresent(draft.memoryRevisionId());
        }
        throw new IllegalStateException("HDM018_VECTOR_BODY_HASH_CONFLICT");
    }

    @Override
    public List<VectorMatch> searchSimilar(VectorSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        validateSearchRequest(request);

        List<Record> rows = dsl.fetch(
                "SELECT e.memory_revision_id, mr.memory_id, rev.revision_no, "
                        + "1 - (e.embedding OPERATOR(public.<=>) ?::public.vector) AS score "
                        + "FROM memory.memory_revision_embedding e "
                        + "JOIN memory.memory_record mr ON mr.current_revision_id = e.memory_revision_id "
                        + "JOIN memory.memory_revision rev "
                        + "  ON rev.memory_revision_id = e.memory_revision_id "
                        + " AND rev.memory_id = mr.memory_id "
                        + "WHERE e.model_name = ? AND e.gguf_sha256 = ? "
                        + "AND e.dimension = ? AND e.normalization = ? "
                        + "AND mr.state = 'ACTIVE' "
                        + "AND NOT EXISTS ("
                        + "  SELECT 1 FROM memory.deletion_fence f "
                        + "  WHERE f.target_kind = 'MEMORY' AND f.target_id = mr.memory_id "
                        + "  AND f.target_revision_ref IS NULL) "
                        + "ORDER BY score DESC, mr.memory_id ASC "
                        + "LIMIT ?",
                vectorLiteral(request.queryVector()),
                request.modelName(),
                request.ggufSha256(),
                request.dimension(),
                request.normalization(),
                request.limit());

        List<VectorMatch> result = new ArrayList<>(rows.size());
        for (Record row : rows) {
            result.add(new VectorMatch(
                    row.get(1, UUID.class),
                    row.get(0, UUID.class),
                    row.get(2, Long.class),
                    row.get(3, Double.class)));
        }
        return result;
    }

    @Override
    public boolean hasEmbedding(
            UUID memoryRevisionId,
            String modelName,
            byte[] ggufSha256,
            int dimension,
            String normalization,
            byte[] embeddedBodySha256) {
        if (memoryRevisionId == null
                || modelName == null
                || ggufSha256 == null
                || normalization == null
                || embeddedBodySha256 == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        if (dimension != DIMENSION) {
            throw new IllegalArgumentException("dimension must be 512");
        }
        if (!NORMALIZATION.equals(normalization)) {
            throw new IllegalArgumentException("normalization must be CALLER_L2");
        }
        if (ggufSha256.length != 32) {
            throw new IllegalArgumentException("gguf_sha256 must be 32 bytes");
        }
        if (embeddedBodySha256.length != 32) {
            throw new IllegalArgumentException("embedded_body_sha256 must be 32 bytes");
        }
        Record existing = dsl.fetchOne(
                "SELECT 1 FROM memory.memory_revision_embedding "
                        + "WHERE memory_revision_id = ? AND model_name = ? "
                        + "AND gguf_sha256 = ? AND dimension = ? AND normalization = ? "
                        + "AND embedded_body_sha256 = ?",
                memoryRevisionId,
                modelName,
                ggufSha256,
                dimension,
                normalization,
                embeddedBodySha256);
        return existing != null;
    }

    private static void validateDraft(VectorEmbeddingDraft draft) {
        if (draft.dimension() != DIMENSION) {
            throw new IllegalArgumentException("dimension must be 512");
        }
        if (!NORMALIZATION.equals(draft.normalization())) {
            throw new IllegalArgumentException("normalization must be CALLER_L2");
        }
        if (draft.ggufSha256().length != 32) {
            throw new IllegalArgumentException("gguf_sha256 must be 32 bytes");
        }
        if (draft.embeddedBodySha256().length != 32) {
            throw new IllegalArgumentException("embedded_body_sha256 must be 32 bytes");
        }
        validateUnitVector(draft.vector());
    }

    private static void validateSearchRequest(VectorSearchRequest request) {
        if (request.dimension() != DIMENSION) {
            throw new IllegalArgumentException("dimension must be 512");
        }
        if (!NORMALIZATION.equals(request.normalization())) {
            throw new IllegalArgumentException("normalization must be CALLER_L2");
        }
        if (request.ggufSha256().length != 32) {
            throw new IllegalArgumentException("gguf_sha256 must be 32 bytes");
        }
        if (request.limit() < 1 || request.limit() > 20) {
            throw new IllegalArgumentException("limit must be 1..20");
        }
        validateUnitVector(request.queryVector());
    }

    private static void validateUnitVector(double[] vector) {
        if (vector == null || vector.length != DIMENSION) {
            throw new IllegalArgumentException("vector must have 512 elements");
        }
        double sumSquares = 0.0;
        for (double value : vector) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("vector must contain only finite values");
            }
            sumSquares += value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (!Double.isFinite(norm) || norm <= 0.0) {
            throw new IllegalArgumentException("vector norm must be positive");
        }
        if (Math.abs(norm - 1.0) > NORM_TOLERANCE) {
            throw new IllegalArgumentException("vector must be L2-normalized");
        }
    }

    private static String vectorLiteral(double[] vector) {
        StringBuilder builder = new StringBuilder(vector.length * 16);
        builder.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString((float) vector[i]));
        }
        builder.append(']');
        return builder.toString();
    }
}
