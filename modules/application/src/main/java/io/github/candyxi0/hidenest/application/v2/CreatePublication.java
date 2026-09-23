package io.github.candyxi0.hidenest.application.v2;

import io.github.candyxi0.hidenest.evidence.v2.SourceResolver;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.AnchorRef;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.CreateItem;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.RevisionRef;
import io.github.candyxi0.hidenest.runtime.domain.FormationResultKind;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementOutcome;
import io.github.candyxi0.hidenest.runtime.port.FormationControlPort;
import io.github.candyxi0.hidenest.runtime.port.FormationStopPort;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Prepares a fully verified immutable request before entering the S02-B settlement transaction. */
public final class CreatePublication {
    public static final int MAX_ANCHOR_BYTES = 8192;
    public static final int MAX_CONTENT_BYTES = 16384;

    private CreatePublication() {}

    public interface Writer {
        void commit(java.sql.Connection connection, FormationSettlementCandidate candidate, Prepared request)
                throws java.sql.SQLException;
    }

    public record Verified(AnchorRef proposed, SourceResolver.VerifiedAnchor source, byte[] textHash) {
        public Verified {
            textHash = textHash.clone();
        }

        @Override
        public byte[] textHash() {
            return textHash.clone();
        }
    }

    public static final class Prepared {
        private final CreateWriteSet writeSet;
        private final List<List<Verified>> anchors;
        private final byte[] hash;
        private final CandidateBinding binding;

        private Prepared(CreateWriteSet writeSet, List<List<Verified>> anchors, byte[] hash, CandidateBinding binding) {
            this.writeSet = writeSet;
            this.anchors = anchors.stream().map(List::copyOf).toList();
            this.hash = hash.clone();
            this.binding = binding;
        }

        public CreateWriteSet writeSet() {
            return writeSet;
        }

        public List<List<Verified>> anchors() {
            return anchors;
        }

        public byte[] hash() {
            return hash.clone();
        }

        public CandidateBinding binding() {
            return binding;
        }
    }

    public record CandidateBinding(
            UUID taskId,
            UUID attemptId,
            UUID sourceId,
            io.github.candyxi0.hidenest.runtime.domain.SourceBoundary fromExclusive,
            io.github.candyxi0.hidenest.runtime.domain.SourceBoundary toInclusive,
            io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding readBinding) {}

    public static Prepared prepare(
            CreateWriteSet set, FormationSettlementCandidate candidate, SourceResolver resolver) {
        Objects.requireNonNull(resolver);
        Objects.requireNonNull(candidate);
        if (set == null
                || set.sourceId() == null
                || set.items() == null
                || set.items().isEmpty()
                || set.items().size() > 100) throw new IllegalArgumentException("INVALID_WRITE_SET");
        required(set.worldRef(), 128);
        required(set.sourceRef(), 512);
        required(set.sourceVersion(), 128);
        if (!set.sourceId().equals(candidate.sourceId())
                || !set.sourceVersion().equals(candidate.toInclusive().sourceVersion()))
            throw new IllegalArgumentException("CANDIDATE_SOURCE_MISMATCH");
        SourceResolver.ResolutionRequest request = new SourceResolver.ResolutionRequest(
                candidate.sourceId(),
                set.sourceRef(),
                set.sourceVersion(),
                resolverBoundary(candidate.fromExclusive()),
                resolverBoundary(candidate.toInclusive()),
                resolverBinding(candidate.readBinding()));
        List<List<Verified>> verified = new ArrayList<>();
        boolean replacement = set.items().stream()
                .anyMatch(i -> i != null && ("REVISE".equals(i.action()) || "SUPERSEDE".equals(i.action())));
        if (replacement && set.items().size() != 1)
            throw new IllegalArgumentException("MIXED_OR_MULTIPLE_REPLACEMENT_UNSUPPORTED");
        boolean localGraph = set.items().stream()
                .filter(Objects::nonNull)
                .flatMap(i -> i.relations() == null ? java.util.stream.Stream.empty() : i.relations().stream())
                .anyMatch(r -> r != null && r.itemRef() != null);
        Set<String> itemRefs = new HashSet<>();
        if (localGraph) {
            for (CreateItem item : set.items()) {
                if (item == null || !"CREATE".equals(item.action()))
                    throw new IllegalArgumentException("MIXED_OR_MULTIPLE_REPLACEMENT_UNSUPPORTED");
                required(item.itemRef(), 128);
                if (!itemRefs.add(item.itemRef())) throw new IllegalArgumentException("DUPLICATE_ITEM_REF");
            }
        }
        for (CreateItem item : set.items()) {
            if (item == null
                    || !("CREATE".equals(item.action())
                            || "REVISE".equals(item.action())
                            || "SUPERSEDE".equals(item.action()))
                    || item.type() == null
                    || item.anchors() == null
                    || item.relations() == null
                    || item.anchors().size() > 100
                    || item.relations().size() > 100) throw new IllegalArgumentException("INVALID_CREATE");
            if ("REVISE".equals(item.action()) || "SUPERSEDE".equals(item.action())) {
                required(item.itemRef(), 128);
                if (item.expectedCurrent() == null
                        || item.expectedCurrent().recordId() == null
                        || item.expectedCurrent().revisionId() == null)
                    throw new IllegalArgumentException("EXPECTED_CURRENT_REQUIRED");
            } else {
                if (item.expectedCurrent() != null) throw new IllegalArgumentException("CREATE_EXPECTED_CURRENT");
                optional(item.itemRef(), 128);
            }
            required(item.content(), MAX_CONTENT_BYTES);
            required(item.subject(), 512);
            required(item.scope(), 512);
            required(item.perspective(), 512);
            required(item.formationRef(), 512);
            optional(item.conditions(), 2048);
            optional(item.timeContext(), 512);
            optional(item.uncertainty(), 2048);
            if (item.type() != CreateWriteSet.MemoryType.UNDERSTANDING
                    && item.anchors().isEmpty()) throw new IllegalArgumentException("ANCHOR_REQUIRED");
            if (item.type() == CreateWriteSet.MemoryType.UNDERSTANDING
                    && item.anchors().isEmpty()
                    && item.relations().stream()
                            .noneMatch(r -> r != null && r.kind() == CreateWriteSet.RelationKind.SUPPORT))
                throw new IllegalArgumentException("SUPPORT_PATH_REQUIRED");
            List<Verified> itemAnchors = new ArrayList<>();
            for (AnchorRef a : item.anchors()) {
                if (a == null) throw new IllegalArgumentException("INVALID_ANCHOR");
                required(a.locator(), 512);
                required(a.exactText(), MAX_ANCHOR_BYTES);
                optional(a.frame(), 512);
                optional(a.actor(), 512);
                optional(a.speakingAs(), 512);
                SourceResolver.VerifiedAnchor v = resolver.resolve(request, a.locator());
                if (v == null) throw new IllegalArgumentException("SOURCE_INCOMPLETE");
                if (!Objects.equals(v.sourceId(), request.sourceId())
                        || !Objects.equals(v.sourceRef(), set.sourceRef())
                        || !Objects.equals(v.sourceVersion(), set.sourceVersion())
                        || !Objects.equals(v.readBinding(), request.readBinding())
                        || !Objects.equals(v.locator(), a.locator())
                        || !Objects.equals(v.exactText(), a.exactText())
                        || !Objects.equals(v.frame(), a.frame())
                        || !Objects.equals(v.actor(), a.actor())
                        || !Objects.equals(v.speakingAs(), a.speakingAs()))
                    throw new IllegalArgumentException("SOURCE_MISMATCH");
                if (v.fromSequence() == null
                        || v.toSequence() == null
                        || v.fromSequence() < 1
                        || v.toSequence() < v.fromSequence()
                        || v.toSequence() > request.toInclusive().sequence())
                    throw new IllegalArgumentException("SOURCE_RANGE_OUT_OF_BOUNDS");
                required(v.exactText(), MAX_ANCHOR_BYTES);
                itemAnchors.add(new Verified(a, v, sha(v.exactText().getBytes(StandardCharsets.UTF_8))));
            }
            Set<String> targets = new HashSet<>();
            for (RevisionRef r : item.relations()) {
                if (r == null || r.kind() == null || (r.revisionId() == null) == (r.itemRef() == null))
                    throw new IllegalArgumentException("INVALID_RELATION");
                String target;
                if (r.itemRef() != null) {
                    required(r.itemRef(), 128);
                    if (!localGraph
                            || !itemRefs.contains(r.itemRef())
                            || r.itemRef().equals(item.itemRef()))
                        throw new IllegalArgumentException("INVALID_ITEM_RELATION");
                    target = "item:" + r.itemRef();
                } else {
                    target = "revision:" + r.revisionId();
                }
                if (!targets.add(target)) throw new IllegalArgumentException("DUPLICATE_OR_CONFLICTING_RELATION");
            }
            verified.add(itemAnchors);
        }
        return new Prepared(
                set,
                verified,
                hash(set),
                new CandidateBinding(
                        candidate.taskId(),
                        candidate.attemptId(),
                        candidate.sourceId(),
                        candidate.fromExclusive(),
                        candidate.toInclusive(),
                        candidate.readBinding()));
    }

    public static FormationSettlementOutcome settle(
            FormationControlPort control,
            FormationSettlementCandidate candidate,
            Prepared prepared,
            Writer writer,
            FormationStopPort stops) {
        Objects.requireNonNull(control);
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(writer);
        Objects.requireNonNull(stops);
        boolean bindingMatches = candidate.taskId().equals(prepared.binding().taskId())
                && candidate.attemptId().equals(prepared.binding().attemptId())
                && candidate.sourceId().equals(prepared.binding().sourceId())
                && Objects.equals(candidate.fromExclusive(), prepared.binding().fromExclusive())
                && candidate.toInclusive().equals(prepared.binding().toInclusive())
                && candidate.readBinding().equals(prepared.binding().readBinding());
        if (candidate.kind() != FormationResultKind.WRITE_SET
                || !Arrays.equals(candidate.resultHash(), prepared.hash())
                || !bindingMatches
                || !candidate.sourceId().equals(prepared.writeSet().sourceId())
                || !candidate
                        .toInclusive()
                        .sourceVersion()
                        .equals(prepared.writeSet().sourceVersion())) {
            if (bindingMatches) control.fail(candidate.attemptId(), "INVALID_RESULT");
            return FormationSettlementOutcome.INVALID_RESULT;
        }
        return control.settle(
                candidate,
                (connection, result) -> true,
                (connection, result) -> writer.commit(connection, result, prepared),
                stops);
    }

    public static byte[] hash(CreateWriteSet set) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            put(out, set.worldRef());
            put(out, set.sourceId().toString());
            put(out, set.sourceRef());
            put(out, set.sourceVersion());
            out.writeInt(set.items().size());
            for (CreateItem i : set.items()) {
                put(out, i.action());
                put(out, i.type().name());
                put(out, i.content());
                put(out, i.subject());
                put(out, i.scope());
                put(out, i.perspective());
                put(out, i.conditions());
                put(out, i.timeContext());
                put(out, i.uncertainty());
                put(out, i.formationRef());
                out.writeInt(i.anchors().size());
                for (AnchorRef a : i.anchors()) {
                    put(out, a.locator());
                    put(out, a.exactText());
                    put(out, a.frame());
                    put(out, a.actor());
                    put(out, a.speakingAs());
                }
                out.writeInt(i.relations().size());
                for (RevisionRef r : i.relations()) {
                    // Preserve the exact bytes of every previously published revision-only request.
                    put(out, r.itemRef() == null ? r.revisionId().toString() : "item:" + r.itemRef());
                    put(out, r.kind().name());
                }
                if (i.itemRef() != null || i.expectedCurrent() != null) {
                    put(out, i.itemRef());
                    put(
                            out,
                            i.expectedCurrent() == null
                                    ? null
                                    : i.expectedCurrent().recordId().toString());
                    put(
                            out,
                            i.expectedCurrent() == null
                                    ? null
                                    : i.expectedCurrent().revisionId().toString());
                }
            }
            return sha(bytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static SourceResolver.PositionBoundary resolverBoundary(
            io.github.candyxi0.hidenest.runtime.domain.SourceBoundary boundary) {
        return boundary == null
                ? null
                : new SourceResolver.PositionBoundary(boundary.sequence(), boundary.cursor(), boundary.sourceVersion());
    }

    private static SourceResolver.ReadBinding resolverBinding(
            io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding binding) {
        return new SourceResolver.ReadBinding(
                binding.kind().name(), binding.reference(), binding.version(), binding.expiresAt());
    }

    private static void put(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] sha(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void required(String value, int maxBytes) {
        if (value == null
                || value.isBlank()
                || !value.equals(value.trim())
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes)
            throw new IllegalArgumentException("INVALID_FIELD");
    }

    private static void optional(String value, int maxBytes) {
        if (value != null
                && (value.isBlank()
                        || !value.equals(value.trim())
                        || value.getBytes(StandardCharsets.UTF_8).length > maxBytes))
            throw new IllegalArgumentException("INVALID_FIELD");
    }
}
