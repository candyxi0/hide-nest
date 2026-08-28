package io.github.candyxi0.hidenest.application.coordinator;

import io.github.candyxi0.hidenest.application.model.LocalV1BubbleItem;
import io.github.candyxi0.hidenest.application.model.LocalV1BubblePolicy;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleResolveRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleResult;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleRoomPurgeRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleRoomPurgeResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BEvidenceResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryDetail;
import io.github.candyxi0.hidenest.application.model.LocalV1VectorMatch;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.runtime.domain.BubbleDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.BubbleRoomRevisionLedgerEntry;
import io.github.candyxi0.hidenest.runtime.domain.BubbleTurnReceipt;
import io.github.candyxi0.hidenest.runtime.port.BubbleQueryPort;
import io.github.candyxi0.hidenest.runtime.port.BubbleTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Platform-neutral Bubble V1 resolve and room-purge coordinator. */
public final class LocalV1BubbleCoordinator {

    public static final String POLICY_VERSION = "BUBBLE_V1_R1";

    private static final int MAX_KEY_BYTES = 128;
    private static final int MAX_QUERY_BYTES = 480;
    private static final int CANDIDATE_LIMIT = 20;
    private static final double MIN_CONFIG_SCORE = 0.40d;
    private static final double MAX_CONFIG_SCORE = 0.95d;
    private static final long SECONDS_PER_DAY = 86_400L;
    private static final String READY = "BUBBLE_READY";
    private static final String NO_MATCH = "NO_MATCH";
    private static final String ACTIVE = "ACTIVE";

    private final LocalV1VectorCoordinator vector;
    private final LocalV1S2BQueryCoordinator s2b;
    private final MemoryReadPort memoryRead;
    private final BubbleTransactionPort bubbleTx;
    private final BubbleQueryPort bubbleQuery;
    private final TransactionExecutor transactions;
    private final Clock clock;
    private final LocalV1BubblePolicy policy;

    public LocalV1BubbleCoordinator(
            LocalV1VectorCoordinator vector,
            LocalV1S2BQueryCoordinator s2b,
            MemoryReadPort memoryRead,
            BubbleTransactionPort bubbleTx,
            BubbleQueryPort bubbleQuery,
            TransactionExecutor transactions,
            Clock clock,
            LocalV1BubblePolicy policy) {
        this.vector = Objects.requireNonNull(vector, "vector");
        this.s2b = Objects.requireNonNull(s2b, "s2b");
        this.memoryRead = Objects.requireNonNull(memoryRead, "memoryRead");
        this.bubbleTx = Objects.requireNonNull(bubbleTx, "bubbleTx");
        this.bubbleQuery = Objects.requireNonNull(bubbleQuery, "bubbleQuery");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.policy = validatePolicy(policy);
    }

    public LocalV1BubbleResult resolve(LocalV1BubbleResolveRequest request) {
        validateRequestShape(request);
        // Space gate precedes any turn lock, receipt probe or Embedding arrival: a non-default
        // space must never reveal whether a turnKey already has a receipt.
        requireDefaultSpace(request.spaceKey());
        byte[] requestHash = requestHash(request);

        boolean replay = transactions.executeInTransaction(() -> {
            bubbleTx.lockTurnKey(request.turnKey());
            BubbleTurnReceipt existing = bubbleQuery.findReceiptByTurnKey(request.turnKey());
            if (existing == null) {
                return false;
            }
            requireSameRequest(existing, requestHash);
            return true;
        });
        if (replay) {
            return replay(request, requestHash);
        }

        Set<UUID> initiallyDelivered = bubbleQuery.findDeliveredRevisionIds(request.spaceKey(), request.roomKey());
        List<LocalV1VectorMatch> candidates;
        try {
            candidates = vector.searchSimilarExcluding(request.queryText(), CANDIDATE_LIMIT, initiallyDelivered);
        } catch (LocalV1VectorException exception) {
            throw embeddingFailure(exception);
        } catch (RuntimeException exception) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        }

        CommitResult committed = transactions.executeInTransaction(() -> {
            bubbleTx.lockTurnKey(request.turnKey());
            BubbleTurnReceipt existing = bubbleQuery.findReceiptByTurnKey(request.turnKey());
            if (existing != null) {
                requireSameRequest(existing, requestHash);
                return CommitResult.replayResult();
            }
            bubbleTx.lockRoom(request.spaceKey(), request.roomKey());
            Set<UUID> delivered = bubbleQuery.findDeliveredRevisionIds(request.spaceKey(), request.roomKey());
            OffsetDateTime issuedAt = normalize(OffsetDateTime.now(clock));

            VerifiedMemory selected = null;
            for (LocalV1VectorMatch candidate : candidates) {
                if (candidate.score() < policy.minScore()) {
                    continue;
                }
                if (delivered.contains(candidate.memoryRevisionId())) {
                    continue;
                }
                VerifiedMemory verified = verifyFreshCandidate(candidate, issuedAt);
                if (verified != null) {
                    selected = verified;
                    break;
                }
            }

            String status = selected == null ? NO_MATCH : READY;
            BubbleDeliveryItem item = selected == null ? null : toDeliveryItem(request, selected);
            byte[] manifestHash = resultManifestHash(requestHash, status, policy, issuedAt, item);
            BubbleTurnReceipt receipt = new BubbleTurnReceipt(
                    request.spaceKey(),
                    request.roomKey(),
                    request.turnKey(),
                    requestHash,
                    request.queryText().getBytes(StandardCharsets.UTF_8).length,
                    status,
                    policy.policyVersion(),
                    policy.minScore(),
                    manifestHash,
                    issuedAt);
            bubbleTx.insertReceipt(receipt);
            if (item != null) {
                bubbleTx.insertDeliveryItem(item);
                bubbleTx.insertLedgerEntry(new BubbleRoomRevisionLedgerEntry(
                        request.spaceKey(), request.roomKey(), item.memoryRevisionId(), request.turnKey(), issuedAt));
            }
            return CommitResult.fresh(status, selected);
        });

        if (committed.replay()) {
            return replay(request, requestHash);
        }
        return result(committed.status(), committed.selected());
    }

    public LocalV1BubbleRoomPurgeResult purge(LocalV1BubbleRoomPurgeRequest request) {
        if (request == null) {
            throw schemaInvalid();
        }
        validateKey(request.spaceKey());
        validateKey(request.roomKey());
        requireDefaultSpace(request.spaceKey());
        transactions.executeInTransaction(() -> {
            bubbleTx.lockRoom(request.spaceKey(), request.roomKey());
            bubbleTx.purgeRoom(request.spaceKey(), request.roomKey());
            return null;
        });
        return new LocalV1BubbleRoomPurgeResult("PURGED");
    }

    private LocalV1BubbleResult replay(LocalV1BubbleResolveRequest request, byte[] requestHash) {
        return transactions.executeInTransaction(() -> {
            bubbleTx.lockTurnKey(request.turnKey());
            BubbleTurnReceipt receipt = bubbleQuery.findReceiptByTurnKey(request.turnKey());
            if (receipt == null) {
                throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
            }
            requireSameRequest(receipt, requestHash);
            bubbleTx.lockRoom(receipt.spaceKey(), receipt.roomKey());
            BubbleDeliveryItem item =
                    bubbleQuery.findDeliveryItem(receipt.spaceKey(), receipt.roomKey(), receipt.turnKey());
            validateStoredClosure(receipt, item);
            if (!Arrays.equals(
                    receipt.resultManifestHash(),
                    resultManifestHash(
                            receipt.requestHash(),
                            receipt.resultCategory(),
                            new LocalV1BubblePolicy(receipt.spaceKey(), receipt.minScore(), receipt.policyVersion()),
                            receipt.issuedAt(),
                            item))) {
                throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
            }
            if (item == null) {
                return result(NO_MATCH, null);
            }
            return result(READY, verifyReplayItem(item));
        });
    }

    private VerifiedMemory verifyFreshCandidate(LocalV1VectorMatch candidate, OffsetDateTime issuedAt) {
        LocalV1S2BMemoryDetail detail;
        try {
            detail = s2b.getMemoryDetail(candidate.memoryId());
        } catch (LocalV1S2BException exception) {
            if (isVisibilityChange(exception.code())) {
                return null;
            }
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        } catch (RuntimeException exception) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        }
        if (!ACTIVE.equals(detail.state())
                || !candidate.memoryId().equals(detail.memoryId())
                || !candidate.memoryRevisionId().equals(detail.currentRevisionId())
                || candidate.revisionNo() == null
                || !candidate.revisionNo().equals(detail.revisionNo())
                || detail.currentPolicyRevisionNo() == null
                || detail.currentPolicyRevisionNo() < 1
                || detail.bodyText() == null) {
            return null;
        }
        int ageDays = evidenceAgeDays(issuedAt, latestEvidenceOccurredAt(candidate.memoryId()));
        return new VerifiedMemory(
                detail.memoryId(),
                detail.currentRevisionId(),
                detail.revisionNo(),
                detail.currentPolicyRevisionNo(),
                toWireMemoryType(detail.memoryType()),
                detail.bodyText(),
                candidate.score(),
                ageDays);
    }

    private VerifiedMemory verifyReplayItem(BubbleDeliveryItem item) {
        MemoryRevision revision = memoryRead.findMemoryRevisionById(item.memoryRevisionId());
        if (revision == null
                || revision.memoryId() == null
                || revision.revisionNo() == null
                || revision.bodyText() == null
                || !item.memoryId().equals(revision.memoryId())
                || item.revisionNo() != revision.revisionNo().longValue()) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.BUBBLE_STALE);
        }
        LocalV1S2BMemoryDetail detail;
        try {
            detail = s2b.getMemoryDetail(item.memoryId());
        } catch (LocalV1S2BException exception) {
            if (isVisibilityChange(exception.code())) {
                throw new LocalV1BubbleException(LocalV1BubbleException.Code.BUBBLE_STALE, exception);
            }
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        } catch (RuntimeException exception) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        }
        if (!ACTIVE.equals(detail.state())
                || !item.memoryId().equals(detail.memoryId())
                || !item.memoryRevisionId().equals(detail.currentRevisionId())
                || item.revisionNo() != detail.revisionNo().longValue()
                || item.policyRevisionNo() != detail.currentPolicyRevisionNo().longValue()
                || !item.memoryType().equals(toWireMemoryType(detail.memoryType()))
                || detail.bodyText() == null) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.BUBBLE_STALE);
        }
        return new VerifiedMemory(
                item.memoryId(),
                item.memoryRevisionId(),
                item.revisionNo(),
                item.policyRevisionNo(),
                item.memoryType(),
                detail.bodyText(),
                item.score(),
                item.evidenceAgeDays());
    }

    private OffsetDateTime latestEvidenceOccurredAt(UUID memoryId) {
        LocalV1S2BEvidenceResult evidence;
        try {
            evidence = s2b.getFullEvidence(memoryId);
        } catch (RuntimeException exception) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, exception);
        }
        if (evidence == null
                || evidence.messages() == null
                || evidence.messages().isEmpty()) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
        OffsetDateTime latest = null;
        for (LocalV1S2BEvidenceMessage message : evidence.messages()) {
            if (message == null || message.occurredAt() == null) {
                throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
            }
            if (latest == null || message.occurredAt().isAfter(latest)) {
                latest = message.occurredAt();
            }
        }
        return latest;
    }

    private static BubbleDeliveryItem toDeliveryItem(LocalV1BubbleResolveRequest request, VerifiedMemory selected) {
        return new BubbleDeliveryItem(
                request.spaceKey(),
                request.roomKey(),
                request.turnKey(),
                selected.memoryId(),
                selected.memoryRevisionId(),
                selected.revisionNo(),
                selected.policyRevisionNo(),
                selected.score(),
                selected.memoryType(),
                selected.evidenceAgeDays());
    }

    private static LocalV1BubbleResult result(String status, VerifiedMemory selected) {
        List<LocalV1BubbleItem> items = selected == null
                ? List.of()
                : List.of(
                        new LocalV1BubbleItem(selected.bodyText(), selected.memoryType(), selected.evidenceAgeDays()));
        if ((READY.equals(status) && items.size() != 1) || (NO_MATCH.equals(status) && !items.isEmpty())) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
        return new LocalV1BubbleResult(status, items);
    }

    private static void validateStoredClosure(BubbleTurnReceipt receipt, BubbleDeliveryItem item) {
        if (!(READY.equals(receipt.resultCategory()) || NO_MATCH.equals(receipt.resultCategory()))
                || (READY.equals(receipt.resultCategory()) != (item != null))
                || receipt.requestHash().length != 32
                || receipt.resultManifestHash().length != 32
                || receipt.queryUtf8Bytes() < 1
                || receipt.queryUtf8Bytes() > MAX_QUERY_BYTES
                || !Double.isFinite(receipt.minScore())
                || receipt.minScore() < MIN_CONFIG_SCORE
                || receipt.minScore() > MAX_CONFIG_SCORE) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
        if (item != null
                && (!receipt.spaceKey().equals(item.spaceKey())
                        || !receipt.roomKey().equals(item.roomKey())
                        || !receipt.turnKey().equals(item.turnKey())
                        || item.score() < receipt.minScore()
                        || item.evidenceAgeDays() < 0)) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
    }

    private static boolean isVisibilityChange(LocalV1S2BException.Code code) {
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
            default -> throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        };
    }

    private static int evidenceAgeDays(OffsetDateTime issuedAt, OffsetDateTime evidenceOccurredAt) {
        if (evidenceOccurredAt.toInstant().isAfter(issuedAt.toInstant())) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
        long days = Duration.between(evidenceOccurredAt, issuedAt).getSeconds() / SECONDS_PER_DAY;
        if (days < 0 || days > Integer.MAX_VALUE) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE);
        }
        return (int) days;
    }

    private static byte[] requestHash(LocalV1BubbleResolveRequest request) {
        return canonicalHash(List.of(
                "BUBBLE_RESOLVE_V1", request.spaceKey(), request.roomKey(), request.turnKey(), request.queryText()));
    }

    private static byte[] resultManifestHash(
            byte[] requestHash,
            String status,
            LocalV1BubblePolicy policy,
            OffsetDateTime issuedAt,
            BubbleDeliveryItem item) {
        List<String> fields = new ArrayList<>();
        fields.add(hex(requestHash));
        fields.add(status);
        fields.add(policy.policyVersion());
        fields.add(canonicalScore(policy.minScore()));
        fields.add(normalize(issuedAt).toString());
        if (item != null) {
            fields.add(item.memoryId().toString());
            fields.add(item.memoryRevisionId().toString());
            fields.add(Long.toString(item.revisionNo()));
            fields.add(Long.toString(item.policyRevisionNo()));
            fields.add(canonicalScore(item.score()));
            fields.add(item.memoryType());
            fields.add(Integer.toString(item.evidenceAgeDays()));
        }
        return canonicalHash(fields);
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
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format("%02x", value));
        }
        return builder.toString();
    }

    private static String canonicalScore(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private static OffsetDateTime normalize(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static LocalV1BubblePolicy validatePolicy(LocalV1BubblePolicy value) {
        if (value == null) {
            throw new IllegalStateException("Bubble policy is required");
        }
        try {
            validateKey(value.defaultSpaceKey());
        } catch (LocalV1BubbleException exception) {
            throw new IllegalStateException("Bubble default space key is invalid", exception);
        }
        String lowered = value.defaultSpaceKey().toLowerCase(java.util.Locale.ROOT);
        if (lowered.equals("default")
                || lowered.equals("placeholder")
                || lowered.equals("changeme")
                || lowered.equals("change-me")
                || lowered.contains("${")) {
            throw new IllegalStateException("Bubble default space key is a weak placeholder");
        }
        if (!Double.isFinite(value.minScore())
                || value.minScore() < MIN_CONFIG_SCORE
                || value.minScore() > MAX_CONFIG_SCORE) {
            throw new IllegalStateException("Bubble min score is invalid");
        }
        if (!POLICY_VERSION.equals(value.policyVersion())) {
            throw new IllegalStateException("Bubble policy version is invalid");
        }
        return value;
    }

    private void requireDefaultSpace(String requestedSpaceKey) {
        if (!policy.defaultSpaceKey().equals(requestedSpaceKey)) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.SPACE_KEY_MISMATCH);
        }
    }

    private static void validateRequestShape(LocalV1BubbleResolveRequest request) {
        if (request == null) {
            throw schemaInvalid();
        }
        validateKey(request.spaceKey());
        validateKey(request.roomKey());
        validateKey(request.turnKey());
        validateQuery(request.queryText());
    }

    private static void validateKey(String value) {
        if (value == null
                || value.isBlank()
                || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_KEY_BYTES) {
            throw schemaInvalid();
        }
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint)) {
                throw schemaInvalid();
            }
        });
    }

    private static void validateQuery(String value) {
        if (value == null
                || value.isBlank()
                || hasUnpairedSurrogate(value)
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_QUERY_BYTES) {
            throw schemaInvalid();
        }
        value.codePoints().forEach(codePoint -> {
            if (Character.isISOControl(codePoint) && codePoint != '\t' && codePoint != '\n' && codePoint != '\r') {
                throw schemaInvalid();
            }
        });
    }

    private static boolean hasUnpairedSurrogate(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    return true;
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return true;
            }
        }
        return false;
    }

    private static void requireSameRequest(BubbleTurnReceipt receipt, byte[] requestHash) {
        if (!Arrays.equals(receipt.requestHash(), requestHash)) {
            throw new LocalV1BubbleException(LocalV1BubbleException.Code.TURN_KEY_REUSED);
        }
    }

    private static LocalV1BubbleException schemaInvalid() {
        return new LocalV1BubbleException(LocalV1BubbleException.Code.REQUEST_SCHEMA_INVALID);
    }

    private static LocalV1BubbleException embeddingFailure(LocalV1VectorException cause) {
        return switch (cause.code()) {
            case EMBEDDING_UNAVAILABLE, EMBEDDING_RESPONSE_INVALID ->
                new LocalV1BubbleException(LocalV1BubbleException.Code.EMBEDDING_UNAVAILABLE, cause);
            default -> new LocalV1BubbleException(LocalV1BubbleException.Code.INTERNAL_FAILURE, cause);
        };
    }

    private record VerifiedMemory(
            UUID memoryId,
            UUID memoryRevisionId,
            long revisionNo,
            long policyRevisionNo,
            String memoryType,
            String bodyText,
            double score,
            int evidenceAgeDays) {}

    private record CommitResult(boolean replay, String status, VerifiedMemory selected) {
        static CommitResult replayResult() {
            return new CommitResult(true, null, null);
        }

        static CommitResult fresh(String status, VerifiedMemory selected) {
            return new CommitResult(false, status, selected);
        }
    }
}
