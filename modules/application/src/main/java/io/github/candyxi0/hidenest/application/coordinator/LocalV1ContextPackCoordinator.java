package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackMemory;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryDetail;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorMatch;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.runtime.domain.ContextDelivery;
import io.github.candyxi0.hidenest.runtime.domain.ContextPackDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.IdempotencyReceipt;
import io.github.candyxi0.hidenest.runtime.domain.RetrievalTrace;
import io.github.candyxi0.hidenest.runtime.port.RuntimeQueryPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Local V1 context pack retrieval facade.
 *
 * <p>Reuses {@link LocalV1VectorCoordinator#searchSimilar} for exact pgvector cosine search and
 * {@link LocalV1S2BQueryCoordinator#getMemoryDetail} for the S2B visibility re-check. Every
 * delivered memory is audited in one transaction: a retrieval trace, a context delivery and the
 * idempotency receipt are written together, plus minimal delivery-item snapshot facts. A same-key
 * same-value replay reconstructs the identical response from structured database facts without
 * re-embedding or re-searching.</p>
 */
public class LocalV1ContextPackCoordinator {

    private static final int LIMIT = 5;
    private static final int MAX_PURPOSE_BYTES = 128;
    private static final int MAX_QUERY_BYTES = 480;
    private static final int MAX_IDEMPOTENCY_KEY_BYTES = 128;
    private static final int EXPIRES_MINUTES = 10;
    private static final String PURPOSE_CODE = "CONTEXT_PACK";
    private static final String OPERATION_CODE = "LOCAL_V1_CONTEXT_PACK";
    private static final String RESOURCE_KIND = "CONTEXT_DELIVERY";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String NO_RELEVANT_RESULT = "NO_RELEVANT_RESULT";
    private static final String ACTIVE = "ACTIVE";

    private final LocalV1VectorCoordinator vector;
    private final LocalV1S2BQueryCoordinator s2b;
    private final MemoryReadPort memoryRead;
    private final RuntimeTransactionPort runtimeTx;
    private final RuntimeQueryPort runtimeQuery;
    private final TransactionExecutor transactions;
    private final Clock clock;

    public LocalV1ContextPackCoordinator(
            LocalV1VectorCoordinator vector,
            LocalV1S2BQueryCoordinator s2b,
            MemoryReadPort memoryRead,
            RuntimeTransactionPort runtimeTx,
            RuntimeQueryPort runtimeQuery,
            TransactionExecutor transactions,
            Clock clock) {
        this.vector = Objects.requireNonNull(vector, "vector");
        this.s2b = Objects.requireNonNull(s2b, "s2b");
        this.memoryRead = Objects.requireNonNull(memoryRead, "memoryRead");
        this.runtimeTx = Objects.requireNonNull(runtimeTx, "runtimeTx");
        this.runtimeQuery = Objects.requireNonNull(runtimeQuery, "runtimeQuery");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public LocalV1ContextPackResult create(LocalV1ContextPackRequest request, String idempotencyKey) {
        validate(request, idempotencyKey);
        byte[] requestHash = computeRequestHash(request);

        Gate gate = transactions.executeInTransaction(() -> {
            runtimeTx.lockIdempotencyKey(idempotencyKey);
            IdempotencyReceipt existing = runtimeTx.findReceiptByKey(idempotencyKey);
            if (existing == null) {
                return Gate.PROCEED;
            }
            if (!Arrays.equals(existing.requestHash(), requestHash)) {
                throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REUSED);
            }
            return Gate.REPLAY;
        });

        if (gate == Gate.REPLAY) {
            return replay(request, idempotencyKey);
        }
        return createNew(request, idempotencyKey, requestHash);
    }

    // ── fresh creation ─────────────────────────────────────────────────────

    private LocalV1ContextPackResult createNew(
            LocalV1ContextPackRequest request, String idempotencyKey, byte[] requestHash) {
        List<LocalV1VectorMatch> candidates;
        try {
            candidates = vector.searchSimilar(request.query(), LIMIT);
        } catch (LocalV1VectorException exception) {
            throw embeddingFailure(exception);
        }

        List<UUID> consideredIds = new ArrayList<>();
        List<DeliveredMemory> delivered = new ArrayList<>();
        for (LocalV1VectorMatch candidate : candidates) {
            consideredIds.add(candidate.memoryId());
            DeliveredMemory verified = verifyCandidate(candidate);
            if (verified != null) {
                delivered.add(verified);
            }
        }

        String resultCategory = delivered.isEmpty() ? NO_RELEVANT_RESULT : SUCCEEDED;
        List<String> policySet = policySet(delivered);
        byte[] policySetHash = canonicalHash(policySet);

        UUID requestId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        UUID traceId = UUID.randomUUID();
        OffsetDateTime issuedAt = OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        OffsetDateTime expiresAt = issuedAt.plusMinutes(EXPIRES_MINUTES);

        List<String> manifestFields = manifestFields(
                requestId, request, resultCategory, policySet, delivered);
        byte[] manifestHash = canonicalHash(manifestFields);

        List<UUID> deliveredIds = delivered.stream().map(DeliveredMemory::memoryId).toList();

        boolean committed = transactions.executeInTransaction(() -> {
            runtimeTx.lockIdempotencyKey(idempotencyKey);
            if (runtimeTx.findReceiptByKey(idempotencyKey) != null) {
                return Boolean.FALSE;
            }
            runtimeTx.insertRetrievalTrace(new RetrievalTrace(
                    traceId,
                    requestId,
                    request.threadId(),
                    request.turnId(),
                    PURPOSE_CODE,
                    resultCategory,
                    policySetHash,
                    consideredIds,
                    deliveredIds,
                    issuedAt,
                    expiresAt));
            runtimeTx.insertContextDelivery(new ContextDelivery(
                    deliveryId,
                    requestId,
                    request.threadId(),
                    request.turnId(),
                    PURPOSE_CODE,
                    policySetHash,
                    manifestHash,
                    issuedAt,
                    expiresAt,
                    null,
                    null));
            runtimeTx.insertContextPackDeliveryItems(toItems(deliveryId, delivered));
            runtimeTx.commitReceipt(
                    idempotencyKey,
                    OPERATION_CODE,
                    requestHash,
                    deliveryId,
                    RESOURCE_KIND,
                    receiptManifest(requestId, resultCategory));
            return Boolean.TRUE;
        });

        if (!committed) {
            return replay(request, idempotencyKey);
        }

        return buildResult(
                requestId,
                resultCategory,
                deliveryId,
                request,
                policySet,
                issuedAt,
                expiresAt,
                delivered);
    }

    // ── replay ─────────────────────────────────────────────────────────────

    private LocalV1ContextPackResult replay(LocalV1ContextPackRequest request, String idempotencyKey) {
        UUID deliveryId = transactions.executeInTransaction(() -> {
            runtimeTx.lockIdempotencyKey(idempotencyKey);
            IdempotencyReceipt receipt = runtimeTx.findReceiptByKey(idempotencyKey);
            if (receipt == null
                    || !OPERATION_CODE.equals(receipt.operationCode())
                    || !RESOURCE_KIND.equals(receipt.resourceKind())
                    || receipt.resourceId() == null) {
                throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
            }
            if (!Arrays.equals(receipt.requestHash(), computeRequestHash(request))) {
                throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REUSED);
            }
            return receipt.resourceId();
        });

        ContextDelivery delivery = runtimeQuery.findContextDeliveryById(deliveryId);
        if (delivery == null
                || !deliveryId.equals(delivery.deliveryId())
                || !request.threadId().equals(delivery.threadId())
                || !request.turnId().equals(delivery.turnId())) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
        }
        if (delivery.invalidatedAt() != null) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.CONTEXT_PACK_INVALIDATED);
        }
        if (OffsetDateTime.now(clock).isAfter(delivery.expiresAt())) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.CONTEXT_PACK_EXPIRED);
        }

        List<ContextPackDeliveryItem> items = runtimeQuery.findContextPackDeliveryItemsByDeliveryId(deliveryId);
        List<DeliveredMemory> delivered = items.stream().map(this::replayVerifyItem).toList();

        String resultCategory = delivered.isEmpty() ? NO_RELEVANT_RESULT : SUCCEEDED;
        List<String> policySet = policySet(delivered);
        if (!Arrays.equals(canonicalHash(policySet), delivery.policyRevisionSetHash())) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
        }
        List<String> manifestFields = manifestFields(
                delivery.requestId(), request, resultCategory, policySet, delivered);
        if (!Arrays.equals(canonicalHash(manifestFields), delivery.manifestHash())) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
        }

        return buildResult(
                delivery.requestId(),
                resultCategory,
                deliveryId,
                request,
                policySet,
                delivery.deliveredAt(),
                delivery.expiresAt(),
                delivered);
    }

    // ── S2B visibility re-check ────────────────────────────────────────────

    private DeliveredMemory verifyCandidate(LocalV1VectorMatch candidate) {
        LocalV1S2BMemoryDetail detail;
        try {
            detail = s2b.getMemoryDetail(candidate.memoryId());
        } catch (LocalV1S2BException exception) {
            if (isVisibleChange(exception.code())) {
                return null;
            }
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE, exception);
        } catch (RuntimeException exception) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE, exception);
        }
        if (!ACTIVE.equals(detail.state())
                || !candidate.memoryId().equals(detail.memoryId())
                || !candidate.memoryRevisionId().equals(detail.currentRevisionId())
                || candidate.revisionNo() == null
                || !candidate.revisionNo().equals(detail.revisionNo())
                || detail.revisionNo() == null
                || detail.currentPolicyRevisionNo() == null
                || detail.currentPolicyRevisionNo() < 1
                || detail.bodyText() == null) {
            return null;
        }
        return new DeliveredMemory(
                detail.memoryId(),
                detail.currentRevisionId(),
                detail.revisionNo(),
                detail.currentPolicyRevisionNo(),
                toWireMemoryType(detail.memoryType()),
                detail.bodyText(),
                candidate.score());
    }

    /** Replay-time visibility gate: reject (never return stale body) if governance facts changed. */
    private DeliveredMemory replayVerifyItem(ContextPackDeliveryItem item) {
        MemoryRevision revision = memoryRead.findMemoryRevisionById(item.memoryRevisionId());
        if (revision == null
                || revision.memoryId() == null
                || revision.revisionNo() == null
                || revision.bodyText() == null
                || !item.memoryRevisionId().equals(revision.memoryRevisionId())) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
        }
        LocalV1S2BMemoryDetail detail;
        try {
            detail = s2b.getMemoryDetail(revision.memoryId());
        } catch (LocalV1S2BException exception) {
            if (isVisibleChange(exception.code())) {
                throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.CONTEXT_PACK_STALE, exception);
            }
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE, exception);
        } catch (RuntimeException exception) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE, exception);
        }
        if (!ACTIVE.equals(detail.state())
                || !revision.memoryId().equals(detail.memoryId())
                || !item.memoryRevisionId().equals(detail.currentRevisionId())
                || !revision.revisionNo().equals(detail.revisionNo())
                || detail.currentPolicyRevisionNo() == null
                || item.policyRevisionNo() != detail.currentPolicyRevisionNo().longValue()) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.CONTEXT_PACK_STALE);
        }
        return new DeliveredMemory(
                revision.memoryId(),
                item.memoryRevisionId(),
                revision.revisionNo(),
                item.policyRevisionNo(),
                toWireMemoryType(revision.memoryType()),
                revision.bodyText(),
                item.score());
    }

    /** Only an explicit visibility change may be treated as "not deliverable"; anything else is damage. */
    private static boolean isVisibleChange(LocalV1S2BException.Code code) {
        return switch (code) {
            case NOT_FOUND, DELETION_FENCED, CURRENT_POINTER_INVALID, OWNER_BINDING_INVALID -> true;
            default -> false;
        };
    }

    private static String toWireMemoryType(String persisted) {
        return switch (persisted) {
            case "Event" -> "EVENT";
            case "Claim" -> "CLAIM";
            case "Quote" -> "QUOTE";
            case "Interpretation" -> "INTERPRETATION";
            case "Calibration" -> "CALIBRATION";
            case "Principle" -> "PRINCIPLE";
            default -> throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE);
        };
    }

    // ── mapping ────────────────────────────────────────────────────────────

    private static List<String> policySet(List<DeliveredMemory> delivered) {
        return delivered.stream()
                .map(item -> "MEMORY:" + item.memoryId() + ":" + item.policyRevisionNo())
                .sorted()
                .toList();
    }

    private static List<ContextPackDeliveryItem> toItems(UUID deliveryId, List<DeliveredMemory> delivered) {
        List<ContextPackDeliveryItem> items = new ArrayList<>(delivered.size());
        long ordinal = 0;
        for (DeliveredMemory memory : delivered) {
            items.add(new ContextPackDeliveryItem(
                    deliveryId,
                    ordinal++,
                    memory.memoryRevisionId(),
                    memory.policyRevisionNo(),
                    memory.score()));
        }
        return items;
    }

    private static LocalV1ContextPackResult buildResult(
            UUID requestId,
            String resultCategory,
            UUID deliveryId,
            LocalV1ContextPackRequest request,
            List<String> policySet,
            OffsetDateTime issuedAt,
            OffsetDateTime expiresAt,
            List<DeliveredMemory> delivered) {
        List<LocalV1ContextPackMemory> memories = delivered.stream()
                .map(item -> new LocalV1ContextPackMemory(
                        item.memoryId(),
                        item.memoryRevisionId(),
                        item.revisionNo(),
                        item.policyRevisionNo(),
                        item.memoryType(),
                        item.bodyText(),
                        item.score()))
                .toList();
        return new LocalV1ContextPackResult(
                requestId,
                resultCategory,
                deliveryId,
                request.threadId(),
                request.turnId(),
                request.purpose(),
                policySet,
                utc(issuedAt),
                utc(expiresAt),
                false,
                memories);
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    // ── canonical hashing ──────────────────────────────────────────────────

    private static List<String> manifestFields(
            UUID requestId,
            LocalV1ContextPackRequest request,
            String resultCategory,
            List<String> policySet,
            List<DeliveredMemory> delivered) {
        List<String> fields = new ArrayList<>();
        fields.add(requestId.toString());
        fields.add(request.threadId().toString());
        fields.add(request.turnId().toString());
        fields.add(request.purpose());
        fields.add(resultCategory);
        fields.addAll(policySet);
        for (DeliveredMemory memory : delivered) {
            fields.add(memory.memoryId().toString());
            fields.add(memory.memoryRevisionId().toString());
            fields.add(Long.toString(memory.revisionNo()));
            fields.add(Double.toString(memory.score()));
        }
        return fields;
    }

    private static byte[] canonicalHash(List<String> fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static byte[] computeRequestHash(LocalV1ContextPackRequest request) {
        return canonicalHash(List.of(
                request.threadId().toString(),
                request.turnId().toString(),
                request.purpose(),
                request.query()));
    }

    private static String receiptManifest(UUID requestId, String resultCategory) {
        return "{\"type\":\"urn:pink:response:local-v1-context-pack\","
                + "\"requestId\":\""
                + requestId
                + "\",\"resultCategory\":\""
                + resultCategory
                + "\",\"retryable\":false}";
    }

    // ── validation ─────────────────────────────────────────────────────────

    private static void validate(LocalV1ContextPackRequest request, String idempotencyKey) {
        if (request == null
                || request.threadId() == null
                || request.turnId() == null
                || request.purpose() == null
                || request.query() == null) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID);
        }
        if (idempotencyKey == null
                || idempotencyKey.isBlank()
                || idempotencyKey.getBytes(StandardCharsets.UTF_8).length > MAX_IDEMPOTENCY_KEY_BYTES) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (request.purpose().isBlank()
                || request.purpose().getBytes(StandardCharsets.UTF_8).length > MAX_PURPOSE_BYTES) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID);
        }
        if (request.query().isBlank()
                || request.query().getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw new LocalV1ContextPackException(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID);
        }
    }

    private static LocalV1ContextPackException embeddingFailure(LocalV1VectorException cause) {
        return switch (cause.code()) {
            case EMBEDDING_UNAVAILABLE, EMBEDDING_RESPONSE_INVALID ->
                new LocalV1ContextPackException(LocalV1ContextPackException.Code.EMBEDDING_UNAVAILABLE, cause);
            default -> new LocalV1ContextPackException(LocalV1ContextPackException.Code.INTERNAL_FAILURE, cause);
        };
    }

    private enum Gate {
        PROCEED,
        REPLAY
    }

    private record DeliveredMemory(
            UUID memoryId,
            UUID memoryRevisionId,
            long revisionNo,
            long policyRevisionNo,
            String memoryType,
            String bodyText,
            double score) {}
}
