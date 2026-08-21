package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryDetail;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryItem;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchor;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchorUnit;
import io.github.candyxi0.hidenest.evidence.domain.SourcePayload;
import io.github.candyxi0.hidenest.evidence.domain.SourceUnit;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.MemoryReadFilter;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Coordinates the local, read-only S2B memory and complete-evidence path. */
public class LocalV1S2BQueryCoordinator {

    private static final int MAX_LIST_LIMIT = 50;
    private static final int MAX_EVIDENCE_MESSAGES = 100;
    private static final long MAX_EVIDENCE_BYTES = 4L * 1024L * 1024L;
    private static final String TEXT_PAYLOAD_KIND = "TEXT";
    private static final String MINIMUM_EVIDENCE_RETENTION = "MINIMUM_EVIDENCE";
    private static final String LOCAL_FILE_STORE = "LOCAL_FILE";
    private static final String UTF8_CONTENT_TYPE = "text/plain; charset=UTF-8";

    private static final java.util.Map<String, String> MEMORY_TYPE_BY_WIRE = java.util.Map.of(
            "EVENT", "Event",
            "CLAIM", "Claim",
            "QUOTE", "Quote",
            "INTERPRETATION", "Interpretation",
            "CALIBRATION", "Calibration",
            "PRINCIPLE", "Principle");

    private final MemoryReadPort memoryReadPort;
    private final EvidenceReferencePort evidenceReferencePort;
    private final PayloadStore payloadStore;
    private final DeletionFencePort deletionFencePort;

    public LocalV1S2BQueryCoordinator(
            MemoryReadPort memoryReadPort,
            EvidenceReferencePort evidenceReferencePort,
            PayloadStore payloadStore,
            DeletionFencePort deletionFencePort) {
        this.memoryReadPort = Objects.requireNonNull(memoryReadPort, "memoryReadPort");
        this.evidenceReferencePort = Objects.requireNonNull(evidenceReferencePort, "evidenceReferencePort");
        this.payloadStore = Objects.requireNonNull(payloadStore, "payloadStore");
        this.deletionFencePort = Objects.requireNonNull(deletionFencePort, "deletionFencePort");
    }

    public LocalV1S2BListResult listMemories(LocalV1S2BListRequest request) {
        if (request == null) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
        String state = validateState(request.state());
        String keyword = normalizeKeyword(request.keyword());
        String memoryType = mapMemoryType(request.memoryType());
        validateLimit(request.limit());
        validateAfterPair(request.afterUpdatedAt(), request.afterMemoryId());

        List<LocalV1S2BMemoryItem> items = new ArrayList<>();
        List<MemoryRecord> records = memoryReadPort.listCurrentMemoryRecords(new MemoryReadFilter(
                state,
                keyword,
                memoryType,
                request.limit(),
                request.afterUpdatedAt(),
                request.afterMemoryId()));
        for (MemoryRecord record : records) {
            CurrentMemory current = readCurrent(record.memoryId());
            // Fail closed on a fence appearing after the DB list query; pointer/owner damage is
            // already fail-closed inside readCurrent.
            rejectMemoryFence(record.memoryId());
            // Final recheck closes the TOCTOU window between the DB list query and per-item assembly:
            // if the current revision/record no longer satisfies state/type/keyword or the ordering
            // facts changed, skip this page's item and let a fresh first page/refetch converge.
            if (!stillMatchesFilter(record, current, state, keyword, memoryType)) {
                continue;
            }
            int evidenceCount = evidenceCount(current.revision().memoryRevisionId());
            boolean sourceAvailable = evidenceCount > 0
                    && !readFullEvidence(current).messages().isEmpty();
            items.add(new LocalV1S2BMemoryItem(
                    record.memoryId(),
                    current.revision().memoryRevisionId(),
                    record.state(),
                    current.revision().revisionNo(),
                    current.revision().memoryType(),
                    current.revision().perspectiveActorId(),
                    current.revision().bodyText(),
                    current.revision().uncertaintyCode(),
                    record.updatedAt(),
                    evidenceCount,
                    sourceAvailable));
        }
        return new LocalV1S2BListResult(items);
    }

    public LocalV1S2BMemoryDetail getMemoryDetail(UUID memoryId) {
        rejectMemoryFence(memoryId);
        CurrentMemory current = readCurrent(memoryId);
        return new LocalV1S2BMemoryDetail(
                current.record().memoryId(),
                current.revision().memoryRevisionId(),
                current.record().state(),
                current.revision().revisionNo(),
                current.record().currentPolicyRevisionNo(),
                current.revision().memoryType(),
                current.revision().perspectiveActorId(),
                current.revision().bodyText(),
                current.revision().uncertaintyCode(),
                current.record().updatedAt(),
                evidenceCount(current.revision().memoryRevisionId()));
    }

    /** Read complete evidence only for an explicitly supplied memory id. */
    public LocalV1S2BEvidenceResult getFullEvidence(UUID memoryId) {
        rejectMemoryFence(memoryId);
        CurrentMemory current = readCurrent(memoryId);
        return readFullEvidence(current);
    }

    private LocalV1S2BEvidenceResult readFullEvidence(CurrentMemory current) {
        List<MemoryRelation> relations = memoryReadPort.findRelationsByFromRevisionId(
                current.revision().memoryRevisionId());
        if (relations == null) {
            throw failure(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID);
        }
        for (MemoryRelation relation : relations) {
            if (relation == null
                    || relation.relationId() == null
                    || relation.createdAt() == null) {
                throw failure(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID);
            }
        }
        relations = relations.stream()
                .filter(relation -> "EVIDENCED_BY".equals(relation.relationType()))
                .sorted(Comparator.comparing(MemoryRelation::createdAt)
                        .thenComparing(MemoryRelation::relationId))
                .toList();

        List<OrderedEvidenceMessage> orderedMessages = new ArrayList<>();
        Set<UUID> seenAnchors = new HashSet<>();
        long totalBytes = 0L;
        for (MemoryRelation relation : relations) {
            if (relation.relationId() == null
                    || relation.createdAt() == null
                    || relation.toAnchorId() == null
                    || relation.toRevisionId() != null
                    || !seenAnchors.add(relation.toAnchorId())) {
                throw failure(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID);
            }
            SourceAnchor anchor = evidenceReferencePort.findSourceAnchorById(relation.toAnchorId());
            if (deletionFencePort.isFenced("SOURCE_ANCHOR", relation.toAnchorId(), null)) {
                throw failure(LocalV1S2BException.Code.DELETION_FENCED);
            }
            if (anchor == null
                    || anchor.anchorId() == null
                    || !relation.toAnchorId().equals(anchor.anchorId())
                    || anchor.sourceId() == null) {
                throw failure(LocalV1S2BException.Code.ANCHOR_INVALID);
            }
            List<SourceAnchorUnit> anchorUnits = evidenceReferencePort
                    .findSourceAnchorUnitsByAnchorId(anchor.anchorId());
            if (anchorUnits == null) {
                throw failure(LocalV1S2BException.Code.ANCHOR_INVALID);
            }
            Set<Long> anchorOrdinals = new HashSet<>();
            for (SourceAnchorUnit anchorUnit : anchorUnits) {
                if (anchorUnit == null
                        || anchorUnit.anchorId() == null
                        || !anchor.anchorId().equals(anchorUnit.anchorId())
                        || anchorUnit.sourceUnitId() == null
                        || anchorUnit.ordinal() == null
                        || !anchorOrdinals.add(anchorUnit.ordinal())) {
                    throw failure(LocalV1S2BException.Code.ANCHOR_INVALID);
                }
            }
            anchorUnits = anchorUnits.stream()
                    .sorted(Comparator.comparing(SourceAnchorUnit::ordinal))
                    .toList();
            if (anchorUnits.isEmpty()) {
                throw failure(LocalV1S2BException.Code.ANCHOR_INVALID);
            }
            for (SourceAnchorUnit anchorUnit : anchorUnits) {
                if (orderedMessages.size() >= MAX_EVIDENCE_MESSAGES) {
                    throw failure(LocalV1S2BException.Code.EVIDENCE_LIMIT_EXCEEDED);
                }
                SourceUnit sourceUnit = evidenceReferencePort.findSourceUnitById(
                        anchorUnit.sourceUnitId());
                if (deletionFencePort.isFenced("SOURCE_UNIT", anchorUnit.sourceUnitId(), null)) {
                    throw failure(LocalV1S2BException.Code.DELETION_FENCED);
                }
                if (sourceUnit == null
                        || !anchor.sourceId().equals(sourceUnit.sourceId())
                        || sourceUnit.occurredAt() == null) {
                    throw failure(LocalV1S2BException.Code.SOURCE_UNIT_INVALID);
                }
                if (sourceUnit.actorId() == null) {
                    throw failure(LocalV1S2BException.Code.ACTOR_INVALID);
                }
                ActorRef actor = memoryReadPort.findActorRefById(sourceUnit.actorId());
                if (actor == null
                        || !sourceUnit.actorId().equals(actor.actorId())
                        || blank(actor.actorKind())
                        || blank(actor.stableRef())) {
                    throw failure(LocalV1S2BException.Code.ACTOR_INVALID);
                }
                SourcePayload payload = exactPayload(sourceUnit);
                if (deletionFencePort.isFenced("SOURCE_PAYLOAD", payload.payloadId(), null)) {
                    throw failure(LocalV1S2BException.Code.DELETION_FENCED);
                }
                validatePayloadMetadata(payload);

                byte[] bytes;
                try {
                    bytes = payloadStore.get(
                            payload.objectRef(), payload.contentHash(),
                            Math.min(payload.sizeBytes(), MAX_EVIDENCE_BYTES));
                } catch (RuntimeException ex) {
                    throw failure(LocalV1S2BException.Code.PAYLOAD_UNAVAILABLE);
                }
                if (bytes == null
                        || bytes.length != payload.sizeBytes()
                        || totalBytes > MAX_EVIDENCE_BYTES - bytes.length) {
                    throw failure(LocalV1S2BException.Code.PAYLOAD_INVALID);
                }
                totalBytes += bytes.length;
                String text = decodeUtf8(bytes);
                orderedMessages.add(new OrderedEvidenceMessage(
                        relation.createdAt(),
                        anchorUnit.ordinal(),
                        relation.relationId(),
                        new LocalV1S2BEvidenceMessage(
                                anchor.anchorId(),
                                sourceUnit.sourceUnitId(),
                                anchorUnit.ordinal(),
                                actor.actorId(),
                                actor.actorKind(),
                                actor.stableRef(),
                                actor.displayLabel(),
                                sourceUnit.occurredAt(),
                                text)));
            }
        }
        List<LocalV1S2BEvidenceMessage> messages = orderedMessages.stream()
                .sorted(Comparator.comparing(OrderedEvidenceMessage::relationCreatedAt)
                        .thenComparing(OrderedEvidenceMessage::anchorOrdinal)
                        .thenComparing(OrderedEvidenceMessage::relationId)
                        .thenComparing(value -> value.message().sourceUnitId()))
                .map(OrderedEvidenceMessage::message)
                .toList();
        return new LocalV1S2BEvidenceResult(
                current.record().memoryId(),
                current.revision().memoryRevisionId(),
                current.revision().revisionNo(),
                messages);
    }

    /**
     * Fail-closed/retry-safe final recheck of an assembled item against the filter that the DB list
     * query already applied. It only closes the TOCTOU window between the list query and per-item
     * assembly; the database remains the primary filter. A returned {@code false} means this page
     * must skip the item (the result may then be below {@code limit}).
     */
    private static boolean stillMatchesFilter(
            MemoryRecord listRecord, CurrentMemory current, String state, String keyword, String memoryType) {
        MemoryRecord freshRecord = current.record();
        MemoryRevision freshRevision = current.revision();
        boolean stateOk = !"ALL".equals(state) ? state.equals(freshRecord.state()) : true;
        boolean typeOk = memoryType == null || memoryType.equals(freshRevision.memoryType());
        boolean kwOk = keyword == null || keyword.isEmpty()
                || freshRevision.bodyText().toLowerCase(Locale.ROOT).contains(keyword);
        // Compare timestamps by instant: the same DB timestamptz may be surfaced with a different
        // offset representation between queries, but must still count as the same ordering fact.
        boolean orderingOk = listRecord.updatedAt().isEqual(freshRecord.updatedAt())
                && listRecord.currentRevisionId().equals(freshRecord.currentRevisionId());
        return stateOk && typeOk && kwOk && orderingOk;
    }

    private CurrentMemory readCurrent(UUID memoryId) {
        if (memoryId == null) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
        MemoryRecord record = memoryReadPort.findMemoryRecordById(memoryId);
        if (record == null) {
            throw failure(LocalV1S2BException.Code.NOT_FOUND);
        }
        MemoryRevision revision = memoryReadPort.findCurrentRevisionByMemoryId(memoryId);
        if (revision == null || record.currentRevisionId() == null) {
            throw failure(LocalV1S2BException.Code.CURRENT_POINTER_INVALID);
        }
        if (!memoryId.equals(record.memoryId())
                || !record.currentRevisionId().equals(revision.memoryRevisionId())
                || !memoryId.equals(revision.memoryId())
                || revision.revisionNo() == null
                || revision.bodyText() == null) {
            throw failure(LocalV1S2BException.Code.OWNER_BINDING_INVALID);
        }
        return new CurrentMemory(record, revision);
    }

    private void rejectMemoryFence(UUID memoryId) {
        if (memoryId == null) throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        if (deletionFencePort.isFenced("MEMORY", memoryId, null)) {
            throw failure(LocalV1S2BException.Code.DELETION_FENCED);
        }
    }

    private int evidenceCount(UUID revisionId) {
        List<MemoryRelation> relations = memoryReadPort.findRelationsByFromRevisionId(revisionId);
        if (relations == null) {
            throw failure(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID);
        }
        int count = 0;
        for (MemoryRelation relation : relations) {
            if (relation == null) {
                throw failure(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID);
            }
            if ("EVIDENCED_BY".equals(relation.relationType())) {
                count = Math.incrementExact(count);
            }
        }
        return count;
    }

    private SourcePayload exactPayload(SourceUnit sourceUnit) {
        List<SourcePayload> payloads = evidenceReferencePort
                .findSourcePayloadsBySourceUnitId(sourceUnit.sourceUnitId());
        if (payloads == null || payloads.size() != 1) {
            throw failure(LocalV1S2BException.Code.PAYLOAD_INVALID);
        }
        SourcePayload payload = payloads.get(0);
        if (payload == null
                || !sourceUnit.sourceUnitId().equals(payload.sourceUnitId())
                || !TEXT_PAYLOAD_KIND.equals(payload.payloadKind())
                || !MINIMUM_EVIDENCE_RETENTION.equals(payload.retentionClass())) {
            throw failure(LocalV1S2BException.Code.PAYLOAD_INVALID);
        }
        return payload;
    }

    private void validatePayloadMetadata(SourcePayload payload) {
        if (!LOCAL_FILE_STORE.equals(payload.storeAdapter())
                || !UTF8_CONTENT_TYPE.equals(payload.contentType())
                || payload.objectVersionRef() != null
                || blank(payload.objectRef())
                || payload.sizeBytes() == null
                || payload.sizeBytes() < 0
                || payload.sizeBytes() > MAX_EVIDENCE_BYTES
                || payload.contentHash() == null
                || payload.contentHash().length != 32) {
            throw failure(LocalV1S2BException.Code.PAYLOAD_INVALID);
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException ex) {
            throw failure(LocalV1S2BException.Code.UTF8_INVALID);
        }
    }

    private static String validateState(String state) {
        if (!("ALL".equals(state) || "ACTIVE".equals(state) || "ARCHIVED".equals(state))) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
        return state;
    }

    private static String normalizeKeyword(String keyword) {
        String normalized = keyword == null ? "" : keyword.strip();
        if (normalized.codePoints().count() > 100) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAX_LIST_LIMIT) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
    }

    private static void validateAfterPair(OffsetDateTime afterUpdatedAt, UUID afterMemoryId) {
        if ((afterUpdatedAt == null) != (afterMemoryId == null)) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
    }

    private static String mapMemoryType(String wireType) {
        if (wireType == null) {
            return null;
        }
        String persistence = MEMORY_TYPE_BY_WIRE.get(wireType);
        if (persistence == null) {
            throw failure(LocalV1S2BException.Code.INVALID_ARGUMENT);
        }
        return persistence;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static LocalV1S2BException failure(LocalV1S2BException.Code code) {
        return new LocalV1S2BException(code);
    }

    private record CurrentMemory(MemoryRecord record, MemoryRevision revision) {}

    private record OrderedEvidenceMessage(
            OffsetDateTime relationCreatedAt,
            Long anchorOrdinal,
            UUID relationId,
            LocalV1S2BEvidenceMessage message) {}
}
