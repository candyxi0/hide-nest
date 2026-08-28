package io.github.candyxi0.hidenest.memory.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Memory vector store/search port for the Local V1 embedding vertical. */
public interface MemoryVectorStorePort {

    /**
     * Persist a normalized embedding for a memory revision. Idempotent on the exact
     * (revision + model fingerprint + embedded body hash); fails closed if the same
     * revision + fingerprint already holds a different body hash.
     */
    VectorUpsertResult upsertEmbedding(VectorEmbeddingDraft draft);

    /**
     * Exact cosine similarity search over the current ACTIVE revision of each memory,
     * filtered by the exact model fingerprint and the deletion fence. Ordered by
     * descending similarity, then ascending memory id.
     */
    List<VectorMatch> searchSimilar(VectorSearchRequest request);

    /**
     * Exact cosine search that excludes already-delivered revision identities in storage before
     * LIMIT is applied. Bubble requires this to happen in the store, rather than filtering a
     * truncated top-N result in application memory.
     */
    List<VectorMatch> searchSimilarExcluding(
            VectorSearchRequest request, Set<UUID> excludedMemoryRevisionIds);

    /**
     * Read-only exact verification: returns {@code true} iff a derived vector fact exists for the
     * exact memory revision + model fingerprint + embedded body SHA-256. It never re-embeds and
     * never mutates storage; it is the readiness probe used by run status.
     */
    boolean hasEmbedding(
            UUID memoryRevisionId,
            String modelName,
            byte[] ggufSha256,
            int dimension,
            String normalization,
            byte[] embeddedBodySha256);

    /** Immutable write draft for one normalized embedding row. */
    record VectorEmbeddingDraft(
            UUID memoryRevisionId,
            String modelName,
            byte[] ggufSha256,
            int dimension,
            String normalization,
            byte[] embeddedBodySha256,
            double[] vector,
            OffsetDateTime createdAt) {
        public VectorEmbeddingDraft {
            memoryRevisionId = Objects.requireNonNull(memoryRevisionId, "memoryRevisionId");
            modelName = Objects.requireNonNull(modelName, "modelName");
            ggufSha256 = Objects.requireNonNull(ggufSha256, "ggufSha256").clone();
            normalization = Objects.requireNonNull(normalization, "normalization");
            embeddedBodySha256 =
                    Objects.requireNonNull(embeddedBodySha256, "embeddedBodySha256").clone();
            vector = Objects.requireNonNull(vector, "vector").clone();
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
        }

        @Override
        public byte[] ggufSha256() {
            return ggufSha256.clone();
        }

        @Override
        public byte[] embeddedBodySha256() {
            return embeddedBodySha256.clone();
        }

        @Override
        public double[] vector() {
            return vector.clone();
        }
    }

    /** Immutable exact-cosine search request over one model fingerprint. */
    record VectorSearchRequest(
            String modelName,
            byte[] ggufSha256,
            int dimension,
            String normalization,
            double[] queryVector,
            int limit) {
        public VectorSearchRequest {
            modelName = Objects.requireNonNull(modelName, "modelName");
            ggufSha256 = Objects.requireNonNull(ggufSha256, "ggufSha256").clone();
            normalization = Objects.requireNonNull(normalization, "normalization");
            queryVector = Objects.requireNonNull(queryVector, "queryVector").clone();
        }

        @Override
        public byte[] ggufSha256() {
            return ggufSha256.clone();
        }

        @Override
        public double[] queryVector() {
            return queryVector.clone();
        }
    }

    /** Minimal similarity match. Never carries body or vector content. */
    record VectorMatch(UUID memoryId, UUID memoryRevisionId, Long revisionNo, double score) {}

    /** Discriminated upsert outcome: new row versus exact idempotent replay. */
    sealed interface VectorUpsertResult {
        UUID memoryRevisionId();

        record Inserted(UUID memoryRevisionId) implements VectorUpsertResult {}

        record AlreadyPresent(UUID memoryRevisionId) implements VectorUpsertResult {}
    }
}
