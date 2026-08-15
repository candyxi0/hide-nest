package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutReceipt;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * Local V1 closeout vector projection decorator.
 *
 * <p>It composes the frozen {@link LocalV1CloseoutWriteCoordinator} main chain with
 * {@link LocalV1VectorCoordinator}. Canonical facts are committed first, then the current
 * MemoryRevision is indexed in a separate transaction boundary. An embedding, transport,
 * response or vector-write failure never rolls back, deletes, or downgrades the already-committed
 * closeout: the receipt falls back to {@code CANONICAL_COMMITTED}. A same-value replay re-attempts
 * the missing index and converges to {@code INDEX_READY}; an already-indexed replay never produces a
 * second vector fact.</p>
 */
public class LocalV1CloseoutVectorProjectionCoordinator {

    private static final String INDEX_READY = "INDEX_READY";
    private static final String CANONICAL_COMMITTED = "CANONICAL_COMMITTED";
    private static final String ACTIVE = "ACTIVE";

    private final LocalV1CloseoutWriteCoordinator closeout;
    private final LocalV1VectorCoordinator vector;
    private final MemoryReadPort memoryReadPort;
    private final MemoryVectorStorePort vectorStore;
    private final ModelFingerprint fingerprint;

    public LocalV1CloseoutVectorProjectionCoordinator(
            LocalV1CloseoutWriteCoordinator closeout,
            LocalV1VectorCoordinator vector,
            MemoryReadPort memoryReadPort,
            MemoryVectorStorePort vectorStore,
            ModelFingerprint fingerprint) {
        this.closeout = Objects.requireNonNull(closeout, "closeout");
        this.vector = Objects.requireNonNull(vector, "vector");
        this.memoryReadPort = Objects.requireNonNull(memoryReadPort, "memoryReadPort");
        this.vectorStore = Objects.requireNonNull(vectorStore, "vectorStore");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
    }

    // ── submit ────────────────────────────────────────────────────────────

    public LocalV1CloseoutReceipt submit(LocalV1CloseoutSubmission request) {
        LocalV1CloseoutReceipt committed = closeout.submit(request);
        try {
            vector.indexCurrentRevision(committed.memoryId());
            return LocalV1CloseoutReceipt.of(committed.runId(), committed.memoryId(), INDEX_READY);
        } catch (RuntimeException exception) {
            return committed;
        }
    }

    // ── run status ────────────────────────────────────────────────────────

    public LocalV1RunStatus findRunStatus(UUID runId) {
        LocalV1RunStatus status = closeout.findRunStatus(runId);
        if (status == null || !CANONICAL_COMMITTED.equals(status.phase())) {
            return status;
        }
        if (isCurrentRevisionIndexed(memoryIdOf(runId))) {
            return new LocalV1RunStatus(
                    status.runId(),
                    INDEX_READY,
                    status.failureCode(),
                    status.startedAt(),
                    status.terminalAt(),
                    status.retryable());
        }
        return status;
    }

    // ── read-only exact verification ─────────────────────────────────────

    private boolean isCurrentRevisionIndexed(UUID memoryId) {
        MemoryRecord record = memoryReadPort.findMemoryRecordById(memoryId);
        if (record == null || !ACTIVE.equals(record.state()) || record.currentRevisionId() == null) {
            return false;
        }
        MemoryRevision revision = memoryReadPort.findCurrentRevisionByMemoryId(memoryId);
        if (revision == null
                || !record.currentRevisionId().equals(revision.memoryRevisionId())
                || !memoryId.equals(revision.memoryId())
                || revision.bodyText() == null) {
            return false;
        }
        return vectorStore.hasEmbedding(
                revision.memoryRevisionId(),
                fingerprint.modelName(),
                fingerprint.ggufSha256(),
                fingerprint.dimension(),
                fingerprint.normalization(),
                sha256(revision.bodyText()));
    }

    private static UUID memoryIdOf(UUID runId) {
        return UUID.nameUUIDFromBytes(("memory:" + runId).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
