package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pure-function canonical closure-member and manifest computation shared by S3A and S3B2B. */
public final class CanonicalClosureComputer {

    private CanonicalClosureComputer() {}

    public static List<DeletionPreviewPort.Member> buildMembers(DeletionPreviewGraph graph) {
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

    public static byte[] manifestHash(DeletionPreviewGraph graph, List<DeletionPreviewPort.Member> members) {
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
                            .thenComparing(CanonicalClosureComputer::hashComparator))
                    .forEach(member -> writeMember(out, member));
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
    }

    // -- serialization helpers -------------------------------------------------

    static void writeUuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    static void writeNullableLong(DataOutputStream out, Long value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) out.writeLong(value);
    }

    static void writeNullableBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeInt(value.length);
            out.write(value);
        }
    }

    static int hashComparator(DeletionPreviewPort.Member left, DeletionPreviewPort.Member right) {
        return compareBytes(left.contentHash(), right.contentHash());
    }

    static int compareBytes(byte[] left, byte[] right) {
        if (left == right) return 0;
        if (left == null) return -1;
        if (right == null) return 1;
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int comparison = Byte.compare(left[i], right[i]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(left.length, right.length);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
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

    private record RawMember(
            String kind,
            UUID targetId,
            Long targetRevisionRef,
            String disposition,
            Long sizeBytes,
            byte[] contentHash) {
        static final Comparator<RawMember> ORDER = Comparator.comparing(RawMember::kind)
                .thenComparing(RawMember::targetId)
                .thenComparing(member -> member.targetRevisionRef(), Comparator.nullsFirst(Long::compareTo))
                .thenComparing(RawMember::disposition)
                .thenComparing(member -> member.sizeBytes(), Comparator.nullsFirst(Long::compareTo))
                .thenComparing((left, right) -> compareBytes(left.contentHash(), right.contentHash()));
    }
}
