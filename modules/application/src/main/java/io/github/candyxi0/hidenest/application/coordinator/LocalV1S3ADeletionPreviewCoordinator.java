package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1S3AAffectedMemory;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewResult;
import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** S3A application orchestration: validation, canonical manifest, and safe output. */
public final class LocalV1S3ADeletionPreviewCoordinator {

    private static final long PREVIEW_TTL_MINUTES = 30L;
    private static final int MAX_NODES = 1000;
    private static final int MAX_PREVIEW_CODE_POINTS = 120;
    private static final String TEXT_PAYLOAD_KIND = "TEXT";
    private static final String MINIMUM_EVIDENCE_RETENTION = "MINIMUM_EVIDENCE";
    private static final String LOCAL_FILE_STORE = "LOCAL_FILE";
    private static final String UTF8_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private final DeletionPreviewPort port;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public LocalV1S3ADeletionPreviewCoordinator(
            DeletionPreviewPort port, TransactionExecutor transactions, Clock clock) {
        this.port = port;
        this.transactions = transactions;
        this.clock = clock;
    }

    public LocalV1S3ADeletionPreviewResult preview(LocalV1S3ADeletionPreviewRequest request) {
        validateRequest(request);
        return transactions.executeInTransaction(() -> previewInTransaction(request));
    }

    private LocalV1S3ADeletionPreviewResult previewInTransaction(
            LocalV1S3ADeletionPreviewRequest request) {
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

        Set<UUID> affected = new HashSet<>();
        for (DeletionPreviewGraph.DeletionPreviewAffectedMemory item : graph.affectedMemories()) {
            if (item == null || item.memory() == null || item.currentRevision() == null
                    || item.memory().memoryId() == null
                    || graph.rootMemory().memoryId().equals(item.memory().memoryId())
                    || !affected.add(item.memory().memoryId())
                    || !item.memory().memoryId().equals(item.currentRevision().memoryId())
                    || !item.memory().currentRevisionId().equals(item.currentRevision().memoryRevisionId())
                    || item.currentRevision().revisionNo() == null
                    || item.currentRevision().bodyText() == null
                    || item.affectedPayloadCount() < 1) {
                throw failure(LocalV1S3AException.Code.GRAPH_INVALID);
            }
        }
        long nodeCount = 1L + graph.allRevisions().size() + anchors.size() + units.size()
                + payloadIds.size() + affected.size();
        if (nodeCount > MAX_NODES) throw failure(LocalV1S3AException.Code.GRAPH_LIMIT_EXCEEDED);
    }

    private static List<DeletionPreviewPort.Member> buildMembers(DeletionPreviewGraph graph) {
        List<RawMember> raw = new ArrayList<>();
        UUID rootId = graph.rootMemory().memoryId();
        raw.add(new RawMember("MEMORY", rootId, null, "DELETE_REQUESTED", null, null));
        graph.allRevisions().stream()
                .sorted(Comparator.comparing(MemoryRevision::revisionNo).thenComparing(MemoryRevision::memoryRevisionId))
                .forEach(revision -> raw.add(new RawMember("MEMORY_REVISION", revision.memoryRevisionId(),
                        revision.revisionNo(), "DELETE_REQUESTED", null, null)));

        graph.anchors().stream().sorted(Comparator.comparing(DeletionPreviewGraph.Anchor::anchorId))
                .forEach(anchor -> raw.add(new RawMember("SOURCE_ANCHOR", anchor.anchorId(), null,
                        "DELETE_CANDIDATE", null, null)));
        Set<UUID> seenUnits = new HashSet<>();
        Set<UUID> seenPayloads = new HashSet<>();
        graph.anchors().stream().sorted(Comparator.comparing(DeletionPreviewGraph.Anchor::anchorId))
                .forEach(anchor -> anchor.sourceUnits().stream()
                        .sorted(Comparator.comparing(DeletionPreviewGraph.SourceUnit::sourceUnitId))
                        .forEach(unit -> {
                            if (seenUnits.add(unit.sourceUnitId())) {
                                raw.add(new RawMember("SOURCE_UNIT", unit.sourceUnitId(), null,
                                        "DELETE_CANDIDATE", null, null));
                            }
                            DeletionPreviewGraph.Payload payload = unit.payloads().get(0);
                            if (seenPayloads.add(payload.payloadId())) {
                                raw.add(new RawMember("SOURCE_PAYLOAD", payload.payloadId(), null,
                                        "DELETE_CANDIDATE", payload.sizeBytes(), payload.contentHash()));
                            }
                        }));
        graph.affectedMemories().stream()
                .sorted(Comparator.comparing(item -> item.memory().memoryId()))
                .forEach(item -> raw.add(new RawMember("AFFECTED_MEMORY", item.memory().memoryId(),
                        item.currentRevision().revisionNo(), "AFFECTED_PENDING_CHOICE", null, null)));

        raw.sort(RawMember.ORDER);
        List<DeletionPreviewPort.Member> result = new ArrayList<>();
        long ordinal = 1L;
        for (RawMember member : raw) {
            result.add(new DeletionPreviewPort.Member(ordinal++, member.kind(), member.targetId(),
                    member.targetRevisionRef(), member.disposition(), member.sizeBytes(), member.contentHash()));
        }
        return result;
    }

    private static byte[] manifestHash(DeletionPreviewGraph graph, List<DeletionPreviewPort.Member> members) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeUuid(out, graph.rootMemory().memoryId());
            writeUuid(out, graph.rootMemory().currentRevisionId());
            out.writeLong(graph.currentRevision().revisionNo());
            writeUuid(out, graph.rootMemory().policyId());
            out.writeLong(graph.policyRevision().revisionNo());
            out.writeInt(members.size());
            members.stream().sorted(Comparator.comparing(DeletionPreviewPort.Member::memberKind)
                            .thenComparing(DeletionPreviewPort.Member::targetId)
                            .thenComparing(m -> m.targetRevisionRef(), Comparator.nullsFirst(Long::compareTo))
                            .thenComparing(DeletionPreviewPort.Member::disposition)
                            .thenComparing(m -> m.sizeBytes(), Comparator.nullsFirst(Long::compareTo))
                            .thenComparing(LocalV1S3ADeletionPreviewCoordinator::hashComparator))
                    .forEach(member -> writeMember(out, member));
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void writeMember(DataOutputStream out, DeletionPreviewPort.Member member) {
        try {
            writeString(out, member.memberKind());
            writeUuid(out, member.targetId());
            writeNullableLong(out, member.targetRevisionRef());
            writeString(out, member.disposition());
            writeNullableLong(out, member.sizeBytes());
            writeNullableBytes(out, member.contentHash());
        } catch (IOException ex) {
            throw new IllegalStateException("manifest serialization failed");
        }
    }

    private static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeLong(value);
    }

    private static void writeNullableBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value.length);
            out.write(value);
        }
    }

    private static int hashComparator(DeletionPreviewPort.Member left, DeletionPreviewPort.Member right) {
        return compareBytes(left.contentHash(), right.contentHash());
    }

    private static int compareBytes(byte[] left, byte[] right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int comparison = Byte.compare(left[i], right[i]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static LocalV1S3ADeletionPreviewResult toResult(
            DeletionPreviewGraph graph,
            UUID previewId,
            long previewRevision,
            byte[] manifestHash,
            OffsetDateTime expiresAt,
            String state,
            List<DeletionPreviewPort.Member> members) {
        long payloadCount = members.stream().filter(member -> "SOURCE_PAYLOAD".equals(member.memberKind())).count();
        long payloadBytes = members.stream().filter(member -> "SOURCE_PAYLOAD".equals(member.memberKind()))
                .map(DeletionPreviewPort.Member::sizeBytes).mapToLong(Long::longValue).sum();
        List<LocalV1S3AAffectedMemory> affected = graph.affectedMemories().stream()
                .sorted(Comparator.comparing(item -> item.memory().memoryId()))
                .map(item -> new LocalV1S3AAffectedMemory(item.memory().memoryId(), item.memory().state(),
                        item.currentRevision().revisionNo(), preview(item.currentRevision().bodyText()),
                        item.affectedPayloadCount()))
                .toList();
        return new LocalV1S3ADeletionPreviewResult(
                previewId, previewRevision, manifestHash, expiresAt.withOffsetSameInstant(ZoneOffset.UTC),
                graph.rootMemory().memoryId(), state,
                graph.currentRevision().revisionNo(), graph.policyRevision().revisionNo(),
                preview(graph.currentRevision().bodyText()), List.of(graph.rootMemory().memoryId()),
                payloadCount, payloadBytes, affected, !affected.isEmpty());
    }

    private static String preview(String value) {
        int count = value.codePointCount(0, value.length());
        return count <= MAX_PREVIEW_CODE_POINTS
                ? value
                : value.substring(0, value.offsetByCodePoints(0, MAX_PREVIEW_CODE_POINTS));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError(ex);
        }
    }

    private static LocalV1S3AException failure(LocalV1S3AException.Code code) {
        return new LocalV1S3AException(code);
    }

    private record RawMember(
            String kind,
            UUID targetId,
            Long targetRevisionRef,
            String disposition,
            Long sizeBytes,
            byte[] contentHash) {
        private static final Comparator<RawMember> ORDER = Comparator.comparing(RawMember::kind)
                .thenComparing(RawMember::targetId)
                .thenComparing(member -> member.targetRevisionRef(), Comparator.nullsFirst(Long::compareTo))
                .thenComparing(RawMember::disposition)
                .thenComparing(member -> member.sizeBytes(), Comparator.nullsFirst(Long::compareTo))
                .thenComparing((left, right) -> compareBytes(left.contentHash(), right.contentHash()));
    }
}
