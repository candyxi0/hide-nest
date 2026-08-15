package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1VectorIndexResult;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorMatch;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local V1 vector coordinator. Reads and locks the current MemoryRevision, validates the
 * input, calls the embedding port, L2-normalizes the response in double precision, persists
 * via the vector store port, and runs exact-cosine similarity queries.
 *
 * <p>No body or vector content is ever carried in a result or logged. Failures are
 * fail-closed via {@link LocalV1VectorException}.
 */
public class LocalV1VectorCoordinator {

    private static final int MAX_UTF8_BYTES = 480;
    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 20;
    private static final double NORM_TOLERANCE = 1e-6;

    private final EmbeddingProviderPort embeddingProvider;
    private final MemoryVectorStorePort vectorStore;
    private final MemoryGovernancePort governance;
    private final TransactionExecutor transactions;
    private final ModelFingerprint fingerprint;
    private final Clock clock;

    public LocalV1VectorCoordinator(
            EmbeddingProviderPort embeddingProvider,
            MemoryVectorStorePort vectorStore,
            MemoryGovernancePort governance,
            TransactionExecutor transactions,
            ModelFingerprint fingerprint,
            Clock clock) {
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider, "embeddingProvider");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.governance = Objects.requireNonNull(governance, "governance");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalV1VectorIndexResult indexCurrentRevision(UUID memoryId) {
        if (memoryId == null) {
            throw failure(LocalV1VectorException.Code.INVALID_ARGUMENT);
        }
        RevisionSnapshot snapshot = transactions.executeInTransaction(() -> readAndLock(memoryId));
        validateText(snapshot.bodyText());
        double[] unit = embedAndNormalize(snapshot.bodyText());
        return transactions.executeInTransaction(() -> persist(memoryId, snapshot, unit));
    }

    public List<LocalV1VectorMatch> searchSimilar(String query, int limit) {
        validateQuery(query, limit);
        double[] unit = embedAndNormalize(query);
        List<MemoryVectorStorePort.VectorMatch> matches = vectorStore.searchSimilar(
                new MemoryVectorStorePort.VectorSearchRequest(
                        fingerprint.modelName(),
                        fingerprint.ggufSha256(),
                        fingerprint.dimension(),
                        fingerprint.normalization(),
                        unit,
                        limit));
        return matches.stream()
                .map(match -> new LocalV1VectorMatch(
                        match.memoryId(), match.memoryRevisionId(), match.revisionNo(), match.score()))
                .toList();
    }

    private LocalV1VectorIndexResult persist(UUID memoryId, RevisionSnapshot snapshot, double[] unit) {
        MemoryRecord record = governance.lockMemoryRecordForWrite(memoryId);
        if (record == null || !"ACTIVE".equals(record.state())) {
            throw failure(LocalV1VectorException.Code.NOT_ACTIVE);
        }
        MemoryRevision revision = governance.lockMemoryRevisionForWrite(memoryId);
        if (revision == null
                || record.currentRevisionId() == null
                || !record.currentRevisionId().equals(snapshot.revisionId())
                || !snapshot.revisionId().equals(revision.memoryRevisionId())
                || !snapshot.memoryId().equals(revision.memoryId())
                || !snapshot.revisionNo().equals(revision.revisionNo())) {
            throw failure(LocalV1VectorException.Code.CURRENT_POINTER_CHANGED);
        }
        byte[] currentHash = sha256(revision.bodyText());
        if (!Arrays.equals(snapshot.bodyHash(), currentHash)) {
            throw failure(LocalV1VectorException.Code.BODY_HASH_CHANGED);
        }

        MemoryVectorStorePort.VectorUpsertResult result;
        try {
            result = vectorStore.upsertEmbedding(new MemoryVectorStorePort.VectorEmbeddingDraft(
                    snapshot.revisionId(),
                    fingerprint.modelName(),
                    fingerprint.ggufSha256(),
                    fingerprint.dimension(),
                    fingerprint.normalization(),
                    snapshot.bodyHash(),
                    unit,
                    OffsetDateTime.now(clock)));
        } catch (IllegalStateException exception) {
            throw failure(LocalV1VectorException.Code.VECTOR_CONFLICT);
        }
        boolean idempotent = result instanceof MemoryVectorStorePort.VectorUpsertResult.AlreadyPresent;
        return new LocalV1VectorIndexResult(
                memoryId,
                snapshot.revisionId(),
                snapshot.revisionNo(),
                fingerprint.modelName(),
                fingerprint.dimension(),
                hex(snapshot.bodyHash()),
                VectorMath.l2Norm(unit),
                idempotent);
    }

    private RevisionSnapshot readAndLock(UUID memoryId) {
        MemoryRecord record = governance.lockMemoryRecordForWrite(memoryId);
        if (record == null) {
            throw failure(LocalV1VectorException.Code.NOT_FOUND);
        }
        if (!"ACTIVE".equals(record.state())) {
            throw failure(LocalV1VectorException.Code.NOT_ACTIVE);
        }
        MemoryRevision revision = governance.lockMemoryRevisionForWrite(memoryId);
        if (revision == null || record.currentRevisionId() == null) {
            throw failure(LocalV1VectorException.Code.CURRENT_POINTER_INVALID);
        }
        if (!record.currentRevisionId().equals(revision.memoryRevisionId())
                || !memoryId.equals(revision.memoryId())
                || revision.revisionNo() == null) {
            throw failure(LocalV1VectorException.Code.OWNER_BINDING_INVALID);
        }
        if (revision.bodyText() == null) {
            throw failure(LocalV1VectorException.Code.BODY_INVALID);
        }
        return new RevisionSnapshot(
                memoryId,
                revision.memoryRevisionId(),
                revision.revisionNo(),
                revision.bodyText(),
                sha256(revision.bodyText()));
    }

    private double[] embedAndNormalize(String text) {
        EmbeddingProviderPort.EmbeddingResult result;
        try {
            result = embeddingProvider.embed(List.of(text));
        } catch (RuntimeException exception) {
            throw failure(LocalV1VectorException.Code.EMBEDDING_UNAVAILABLE);
        }
        if (result == null || result.vectors() == null || result.vectors().size() != 1) {
            throw failure(LocalV1VectorException.Code.EMBEDDING_RESPONSE_INVALID);
        }
        double[] raw = result.vectors().get(0);
        if (raw == null || raw.length != fingerprint.dimension()) {
            throw failure(LocalV1VectorException.Code.EMBEDDING_RESPONSE_INVALID);
        }
        double[] unit;
        try {
            unit = VectorMath.normalize(raw);
        } catch (IllegalArgumentException exception) {
            throw failure(LocalV1VectorException.Code.EMBEDDING_RESPONSE_INVALID);
        }
        if (Math.abs(VectorMath.l2Norm(unit) - 1.0) > NORM_TOLERANCE) {
            throw failure(LocalV1VectorException.Code.EMBEDDING_RESPONSE_INVALID);
        }
        return unit;
    }

    private void validateQuery(String query, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw failure(LocalV1VectorException.Code.INVALID_ARGUMENT);
        }
        validateText(query);
    }

    private static void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw failure(LocalV1VectorException.Code.INVALID_ARGUMENT);
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw failure(LocalV1VectorException.Code.INVALID_ARGUMENT);
        }
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private static LocalV1VectorException failure(LocalV1VectorException.Code code) {
        return new LocalV1VectorException(code);
    }

    private record RevisionSnapshot(
            UUID memoryId, UUID revisionId, Long revisionNo, String bodyText, byte[] bodyHash) {}
}
