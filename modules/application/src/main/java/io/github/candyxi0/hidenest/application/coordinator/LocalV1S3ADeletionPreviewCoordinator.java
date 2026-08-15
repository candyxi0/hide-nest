package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1DeletionEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewResult;
import io.github.candyxi0.hidenest.application.model.LocalV1SharedMemoryReference;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** S3A application orchestration: validation, canonical manifest, and safe output. */
public final class LocalV1S3ADeletionPreviewCoordinator {

    private static final long PREVIEW_TTL_MINUTES = 30L;
    private static final int MAX_NODES = 1000;
    private static final int MAX_PREVIEW_CODE_POINTS = 120;
    private static final long MAX_EVIDENCE_BYTES = 4L * 1024L * 1024L;
    private static final String TEXT_PAYLOAD_KIND = "TEXT";
    private static final String MINIMUM_EVIDENCE_RETENTION = "MINIMUM_EVIDENCE";
    private static final String LOCAL_FILE_STORE = "LOCAL_FILE";
    private static final String UTF8_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final DeletionPreviewPort port;
    private final TransactionExecutor transactions;
    private final Clock clock;
    private final DeletionFencePort deletionFencePort;
    private final PayloadStore payloadStore;

    public LocalV1S3ADeletionPreviewCoordinator(
            DeletionPreviewPort port, TransactionExecutor transactions, Clock clock,
            DeletionFencePort deletionFencePort, PayloadStore payloadStore) {
        this.port = Objects.requireNonNull(port, "port");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deletionFencePort = Objects.requireNonNull(deletionFencePort, "deletionFencePort");
        this.payloadStore = Objects.requireNonNull(payloadStore, "payloadStore");
    }

    public LocalV1S3ADeletionPreviewResult preview(LocalV1S3ADeletionPreviewRequest request) {
        validateRequest(request);
        return transactions.executeInTransaction(() -> previewInTransaction(request));
    }

    private LocalV1S3ADeletionPreviewResult previewInTransaction(
            LocalV1S3ADeletionPreviewRequest request) {
        if (deletionFencePort.isFenced("MEMORY", request.memoryId(), null)) {
            throw failure(LocalV1S3AException.Code.DELETION_FENCED);
        }
        DeletionPreviewPort.ExistingPreview existing = port.findByIdempotencyKey(request.idempotencyKey());
        if (existing != null) {
            if (!request.memoryId().equals(existing.rootMemoryId())
                    || !Arrays.equals(request.requestHash(), existing.requestHash())) {
                throw failure(LocalV1S3AException.Code.IDEMPOTENCY_CONFLICT);
            }
            DeletionPreviewGraph graph = readGraph(request.memoryId());
            validateGraph(graph);
            return replayExisting(request, existing, graph);
        }

        DeletionPreviewGraph graph = readGraph(request.memoryId());
        validateGraph(graph);
        // The root row is locked now. Re-read the idempotency key after the lock
        // so a concurrent same-root request cannot create a second closure.
        existing = port.findByIdempotencyKey(request.idempotencyKey());
        if (existing != null) {
            return replayExisting(request, existing, graph);
        }
        long previewRevision = port.nextPreviewRevision(request.memoryId());
        List<DeletionPreviewPort.Member> members = buildMembers(graph);
        byte[] manifestHash = manifestHash(graph, members);
        OffsetDateTime createdAt = OffsetDateTime.now(clock);
        OffsetDateTime expiresAt = createdAt.plusMinutes(PREVIEW_TTL_MINUTES);
        UUID previewId = UUID.randomUUID();
        try {
            port.insertPreview(new DeletionPreviewPort.PreviewDraft(
                    previewId, graph.rootMemory().memoryId(), previewRevision,
                    graph.rootMemory().currentRevisionId(), graph.currentRevision().revisionNo(),
                    graph.rootMemory().policyId(), graph.policyRevision().revisionNo(),
                    request.idempotencyKey(), request.requestHash(), manifestHash, "PREVIEWED",
                    createdAt, expiresAt, members));
        } catch (LocalV1S3AException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw failure(LocalV1S3AException.Code.PERSISTENCE_CONFLICT);
        }
        return toResult(graph, previewId, previewRevision, manifestHash, expiresAt, "PREVIEWED", members);
    }

    private LocalV1S3ADeletionPreviewResult replayExisting(
            LocalV1S3ADeletionPreviewRequest request,
            DeletionPreviewPort.ExistingPreview existing,
            DeletionPreviewGraph graph) {
        if (!request.memoryId().equals(existing.rootMemoryId())
                || !Arrays.equals(request.requestHash(), existing.requestHash())) {
            throw failure(LocalV1S3AException.Code.IDEMPOTENCY_CONFLICT);
        }
        List<DeletionPreviewPort.Member> currentMembers = buildMembers(graph);
        byte[] currentManifestHash = manifestHash(graph, currentMembers);
        if (!Arrays.equals(currentManifestHash, existing.manifestHash())) {
            throw failure(LocalV1S3AException.Code.PREVIEW_STALE);
        }
        // The closure/member rows are the immutable response snapshot. The graph is
        // used only to prove that the current facts still hash to that snapshot.
        return toResult(graph, existing.previewId(), existing.previewRevision(), existing.manifestHash(),
                existing.expiresAt(), existing.state(), existing.members());
    }

    private DeletionPreviewGraph readGraph(UUID memoryId) {
        try {
            DeletionPreviewGraph graph = port.lockAndReadGraph(memoryId);
            if (graph == null) throw failure(LocalV1S3AException.Code.NOT_FOUND);
            return graph;
        } catch (LocalV1S3AException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw failure(LocalV1S3AException.Code.NOT_FOUND);
        } catch (IllegalStateException ex) {
            throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
        }
    }

    private static void validateRequest(LocalV1S3ADeletionPreviewRequest request) {
        if (request == null
                || request.memoryId() == null
                || request.idempotencyKey() == null
                || request.idempotencyKey().isBlank()
                || request.requestHash() == null
                || request.requestHash().length != 32) {
            throw failure(LocalV1S3AException.Code.INVALID_ARGUMENT);
        }
    }

    private static void validateGraph(DeletionPreviewGraph graph) {
        if (graph.rootMemory() == null
                || graph.currentRevision() == null
                || graph.policy() == null
                || graph.policyRevision() == null
                || graph.rootMemory().memoryId() == null
                || graph.rootMemory().currentRevisionId() == null
                || graph.rootMemory().policyId() == null
                || graph.rootMemory().currentPolicyRevisionNo() == null) {
            throw failure(LocalV1S3AException.Code.CURRENT_POINTER_INVALID);
        }
        if (!graph.rootMemory().memoryId().equals(graph.currentRevision().memoryId())
                || !graph.rootMemory().currentRevisionId().equals(graph.currentRevision().memoryRevisionId())
                || !graph.rootMemory().policyId().equals(graph.policy().policyId())
                || !"MEMORY".equals(graph.policy().ownerKind())
                || !graph.rootMemory().memoryId().equals(graph.policy().ownerId())
                || !graph.rootMemory().currentPolicyRevisionNo().equals(graph.policy().currentRevisionNo())
                || !graph.rootMemory().policyId().equals(graph.policyRevision().policyId())
                || !graph.rootMemory().currentPolicyRevisionNo().equals(graph.policyRevision().revisionNo())
                || graph.currentRevision().revisionNo() == null
                || graph.currentRevision().revisionNo() < 1
                || graph.currentRevision().bodyText() == null) {
            throw failure(LocalV1S3AException.Code.CURRENT_POINTER_INVALID);
        }

        Set<UUID> revisions = new HashSet<>();
        Set<Long> revisionNumbers = new HashSet<>();
        for (MemoryRevision revision : graph.allRevisions()) {
            if (revision == null
                    || revision.memoryRevisionId() == null
                    || !graph.rootMemory().memoryId().equals(revision.memoryId())
                    || revision.revisionNo() == null
                    || revision.revisionNo() < 1
                    || revision.bodyText() == null
                    || !revisions.add(revision.memoryRevisionId())
                    || !revisionNumbers.add(revision.revisionNo())) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
        }
        if (revisions.size() != graph.allRevisions().size()
                || !revisions.contains(graph.currentRevision().memoryRevisionId())) {
            throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
        }

        Set<UUID> anchors = new HashSet<>();
        Set<UUID> units = new HashSet<>();
        Set<UUID> payloadIds = new HashSet<>();
        for (DeletionPreviewGraph.Anchor anchor : graph.anchors()) {
            if (anchor == null || anchor.anchorId() == null || anchor.sourceId() == null || !anchors.add(anchor.anchorId())
                    || anchor.sourceUnits().isEmpty()) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
            for (DeletionPreviewGraph.SourceUnit unit : anchor.sourceUnits()) {
                if (unit == null || unit.sourceUnitId() == null || unit.sourceId() == null
                        || !anchor.sourceId().equals(unit.sourceId()) || unit.payloads().size() != 1) {
                    throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
                }
                // A source unit may be referenced by more than one anchor. It is one
                // closure member, while each occurrence must still carry identical metadata.
                if (unit.payloads().size() != 1) {
                    throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
                }
                units.add(unit.sourceUnitId());
                DeletionPreviewGraph.Payload payload = unit.payloads().get(0);
                if (payload == null
                        || payload.payloadId() == null
                        || !unit.sourceUnitId().equals(payload.sourceUnitId())
                        || !TEXT_PAYLOAD_KIND.equals(payload.payloadKind())
                        || !MINIMUM_EVIDENCE_RETENTION.equals(payload.retentionClass())
                        || !LOCAL_FILE_STORE.equals(payload.storeAdapter())
                        || !UTF8_CONTENT_TYPE.equals(payload.contentType())
                        || payload.objectVersionRef() != null
                        || payload.sizeBytes() == null
                        || payload.sizeBytes() < 0
                        || payload.contentHash() == null
                        || payload.contentHash().length != 32) {
                    throw failure(LocalV1S3AException.Code.PAYLOAD_INVALID);
                }
                payloadIds.add(payload.payloadId());
            }
        }
        if (units.isEmpty() || payloadIds.isEmpty()) {
            throw failure(LocalV1S3AException.Code.PAYLOAD_INVALID);
        }

        Set<UUID> shared = new HashSet<>();
        for (DeletionPreviewGraph.SharedMemory memory : graph.sharedMemories()) {
            if (memory == null
                    || memory.memoryId() == null
                    || memory.revisionNo() == null
                    || memory.revisionNo() < 1
                    || memory.title() == null
                    || graph.rootMemory().memoryId().equals(memory.memoryId())
                    || !shared.add(memory.memoryId())) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
        }
        for (DeletionPreviewGraph.EvidenceUnit unit : graph.evidenceUnits()) {
            if (unit == null
                    || unit.sourceUnitId() == null
                    || unit.ordinal() == null
                    || unit.ordinal() < 0
                    || unit.actorId() == null
                    || unit.actorKind() == null || unit.actorKind().isBlank()
                    || unit.actorStableRef() == null || unit.actorStableRef().isBlank()
                    || unit.occurredAt() == null
                    || unit.objectRef() == null || unit.objectRef().isBlank()
                    || unit.sizeBytes() == null
                    || unit.sizeBytes() < 0
                    || unit.contentHash() == null
                    || unit.contentHash().length != 32
                    || !units.contains(unit.sourceUnitId())) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
        }
        if (graph.evidenceUnits().size() != units.size()) {
            throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
        }

        // R5: sharedByMemoryIds must precisely attribute every RETAIN_SHARED payload to authorized
        // memories, reference only dictionary entries, never the root, and be sorted + unique.
        Map<UUID, UUID> payloadByUnit = new HashMap<>();
        for (DeletionPreviewGraph.Anchor anchor : graph.anchors()) {
            for (DeletionPreviewGraph.SourceUnit sourceUnit : anchor.sourceUnits()) {
                DeletionPreviewGraph.Payload payload = sourceUnit.payloads().get(0);
                payloadByUnit.put(sourceUnit.sourceUnitId(), payload.payloadId());
            }
        }
        Set<UUID> referencedMemoryIds = new HashSet<>();
        for (DeletionPreviewGraph.EvidenceUnit unit : graph.evidenceUnits()) {
            List<UUID> sharedBy = unit.sharedByMemoryIds();
            if (new HashSet<>(sharedBy).size() != sharedBy.size()) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
            for (int i = 1; i < sharedBy.size(); i++) {
                if (sharedBy.get(i - 1).compareTo(sharedBy.get(i)) >= 0) {
                    throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
                }
            }
            for (UUID memoryId : sharedBy) {
                if (memoryId == null
                        || graph.rootMemory().memoryId().equals(memoryId)
                        || !shared.contains(memoryId)) {
                    throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
                }
                referencedMemoryIds.add(memoryId);
            }
            UUID payloadId = payloadByUnit.get(unit.sourceUnitId());
            boolean retained = graph.sharedPayloadIds().contains(payloadId);
            if (retained != !sharedBy.isEmpty()) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
        }
        if (!shared.equals(referencedMemoryIds)) {
            throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
        }

        long nodeCount = 1L + graph.allRevisions().size() + anchors.size() + units.size()
                + payloadIds.size() + shared.size();
        if (nodeCount > MAX_NODES) throw failure(LocalV1S3AException.Code.GRAPH_LIMIT_EXCEEDED);
    }

    private static List<DeletionPreviewPort.Member> buildMembers(DeletionPreviewGraph graph) {
        return CanonicalClosureComputer.buildMembers(graph);
    }

    private static byte[] manifestHash(DeletionPreviewGraph graph, List<DeletionPreviewPort.Member> members) {
        return CanonicalClosureComputer.manifestHash(graph, members);
    }

    private LocalV1S3ADeletionPreviewResult toResult(
            DeletionPreviewGraph graph,
            UUID previewId,
            long previewRevision,
            byte[] manifestHash,
            OffsetDateTime expiresAt,
            String state,
            List<DeletionPreviewPort.Member> members) {
        long payloadCount = members.stream()
                .filter(member -> "SOURCE_PAYLOAD".equals(member.memberKind()) && "DELETE_CANDIDATE".equals(member.disposition()))
                .count();
        long payloadBytes = members.stream()
                .filter(member -> "SOURCE_PAYLOAD".equals(member.memberKind()) && "DELETE_CANDIDATE".equals(member.disposition()))
                .map(DeletionPreviewPort.Member::sizeBytes).mapToLong(Long::longValue).sum();
        List<LocalV1DeletionEvidenceMessage> evidence = readEvidence(graph.evidenceUnits());
        List<LocalV1SharedMemoryReference> sharedMemories = graph.sharedMemories().stream()
                .sorted(Comparator.comparing(DeletionPreviewGraph.SharedMemory::memoryId))
                .map(memory -> new LocalV1SharedMemoryReference(memory.memoryId(), memory.revisionNo(), memory.title()))
                .toList();
        return new LocalV1S3ADeletionPreviewResult(
                previewId, previewRevision, manifestHash, expiresAt.withOffsetSameInstant(ZoneOffset.UTC),
                graph.rootMemory().memoryId(), state,
                graph.currentRevision().revisionNo(), graph.policyRevision().revisionNo(),
                preview(graph.currentRevision().bodyText()), List.of(graph.rootMemory().memoryId()),
                payloadCount, payloadBytes, evidence, sharedMemories);
    }

    private List<LocalV1DeletionEvidenceMessage> readEvidence(List<DeletionPreviewGraph.EvidenceUnit> units) {
        List<LocalV1DeletionEvidenceMessage> messages = new ArrayList<>();
        for (DeletionPreviewGraph.EvidenceUnit unit : units) {
            byte[] bytes;
            try {
                bytes = payloadStore.get(unit.objectRef(), unit.contentHash(),
                        Math.min(unit.sizeBytes(), MAX_EVIDENCE_BYTES));
            } catch (RuntimeException ex) {
                throw failure(LocalV1S3AException.Code.PAYLOAD_INVALID);
            }
            if (bytes == null || bytes.length != unit.sizeBytes()) {
                throw failure(LocalV1S3AException.Code.PAYLOAD_INVALID);
            }
            messages.add(new LocalV1DeletionEvidenceMessage(
                    unit.anchorId(), unit.ordinal(), unit.actorId(), unit.actorStableRef(), unit.displayLabel(),
                    unit.occurredAt(), decodeUtf8(bytes), unit.sharedByMemoryIds()));
        }
        return messages;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw failure(LocalV1S3AException.Code.PAYLOAD_INVALID);
        }
    }

    private static String preview(String value) {
        int count = value.codePointCount(0, value.length());
        return count <= MAX_PREVIEW_CODE_POINTS
                ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS));
    }

    private static LocalV1S3AException failure(LocalV1S3AException.Code code) {
        return new LocalV1S3AException(code);
    }

}
