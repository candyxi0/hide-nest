package io.github.candyxi0.hidenest.application.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.candyxi0.hidenest.application.model.LocalV1BubblePolicy;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleResolveRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleRoomPurgeRequest;
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
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LocalV1BubbleCoordinatorTest {

    private static final String SPACE = "family-space";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-28T10:00:00Z");

    private LocalV1VectorCoordinator vector;
    private LocalV1S2BQueryCoordinator s2b;
    private MemoryReadPort memoryRead;
    private FakeBubbleStore store;
    private LocalV1BubbleCoordinator coordinator;

    @BeforeEach
    void setUp() {
        vector = mock(LocalV1VectorCoordinator.class);
        s2b = mock(LocalV1S2BQueryCoordinator.class);
        memoryRead = mock(MemoryReadPort.class);
        store = new FakeBubbleStore();
        TransactionExecutor direct = new TransactionExecutor() {
            @Override
            public <T> T executeInTransaction(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        coordinator = new LocalV1BubbleCoordinator(
                vector,
                s2b,
                memoryRead,
                store,
                store,
                direct,
                Clock.fixed(Instant.parse("2026-08-28T10:00:00Z"), ZoneOffset.UTC),
                new LocalV1BubblePolicy(SPACE, 0.70d, LocalV1BubbleCoordinator.POLICY_VERSION));
    }

    @Test
    void verbatimUnicodeQueryIsEmbeddedAndNeverStored() {
        String query = "第一行\t🙂\r\n第二行";
        when(vector.searchSimilarExcluding(eq(query), eq(20), anySet())).thenReturn(List.of());

        var result = coordinator.resolve(request("room-a", "turn-a", query));

        assertEquals("NO_MATCH", result.status());
        assertEquals(List.of(), result.items());
        assertEquals(1, store.receipts.size());
        BubbleTurnReceipt receipt = store.receipts.get("turn-a");
        assertEquals(query.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, receipt.queryUtf8Bytes());
        assertEquals(0, store.items.size());
    }

    @Test
    void thresholdIsExactAndNextDownDoesNotPass() {
        UUID memory = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        visible(memory, revision, 1, "exact threshold");
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision, 1L, 0.70d)))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision, 1L, Math.nextDown(0.70d))));

        assertEquals(
                "BUBBLE_READY",
                coordinator.resolve(request("room-a", "turn-a", "query-a")).status());
        assertEquals(
                "NO_MATCH",
                coordinator.resolve(request("room-b", "turn-b", "query-b")).status());
    }

    @Test
    void usedTopRevisionFallsThroughToNextCandidate() {
        UUID firstMemory = UUID.randomUUID();
        UUID firstRevision = UUID.randomUUID();
        UUID secondMemory = UUID.randomUUID();
        UUID secondRevision = UUID.randomUUID();
        visible(firstMemory, firstRevision, 1, "first");
        visible(secondMemory, secondRevision, 1, "second");
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(new LocalV1VectorMatch(firstMemory, firstRevision, 1L, 0.9d)))
                .thenReturn(List.of(
                        new LocalV1VectorMatch(firstMemory, firstRevision, 1L, 0.9d),
                        new LocalV1VectorMatch(secondMemory, secondRevision, 1L, 0.8d)));

        coordinator.resolve(request("room-a", "turn-a", "query-a"));
        var second = coordinator.resolve(request("room-a", "turn-b", "query-b"));

        assertEquals("second", second.items().getFirst().bodyText());
        ArgumentCaptor<Set<UUID>> exclusions = ArgumentCaptor.forClass(Set.class);
        verify(vector, times(2)).searchSimilarExcluding(any(), eq(20), exclusions.capture());
        assertEquals(Set.of(firstRevision), exclusions.getAllValues().get(1));
    }

    @Test
    void visibilityChangeAfterSearchSkipsCandidateAndContinues() {
        UUID changedMemory = UUID.randomUUID();
        UUID changedRevision = UUID.randomUUID();
        UUID visibleMemory = UUID.randomUUID();
        UUID visibleRevision = UUID.randomUUID();
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(
                        new LocalV1VectorMatch(changedMemory, changedRevision, 1L, 0.95),
                        new LocalV1VectorMatch(visibleMemory, visibleRevision, 1L, 0.80)));
        when(s2b.getMemoryDetail(changedMemory)).thenThrow(new LocalV1S2BException(LocalV1S2BException.Code.NOT_FOUND));
        visible(visibleMemory, visibleRevision, 1, "fallback visible");

        var result = coordinator.resolve(request("room-change", "turn-change", "query"));

        assertEquals("BUBBLE_READY", result.status());
        assertEquals("fallback visible", result.items().getFirst().bodyText());
        assertEquals(Set.of(visibleRevision), store.findDeliveredRevisionIds(SPACE, "room-change"));
    }

    @Test
    void sameRevisionAcrossRoomsAndNewRevisionInSameRoomCanAppear() {
        UUID memory = UUID.randomUUID();
        UUID revision1 = UUID.randomUUID();
        UUID revision2 = UUID.randomUUID();
        visible(memory, revision1, 1, "revision one");
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision1, 1L, 0.9d)))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision1, 1L, 0.9d)))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision2, 2L, 0.9d)));

        assertEquals(
                "BUBBLE_READY",
                coordinator.resolve(request("room-a", "turn-a", "q1")).status());
        assertEquals(
                "BUBBLE_READY",
                coordinator.resolve(request("room-b", "turn-b", "q2")).status());
        visible(memory, revision2, 2, "revision two");
        assertEquals(
                "revision two",
                coordinator
                        .resolve(request("room-a", "turn-c", "q3"))
                        .items()
                        .getFirst()
                        .bodyText());
    }

    @Test
    void exactReplayDoesNoEmbeddingAndChangedValueConflicts() {
        UUID memory = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        visible(memory, revision, 1, "stable body");
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision, 1L, 0.9d)));
        when(memoryRead.findMemoryRevisionById(revision))
                .thenReturn(new MemoryRevision(
                        revision,
                        memory,
                        1L,
                        "Event",
                        UUID.randomUUID(),
                        "stable body",
                        NOW,
                        null,
                        "NONE",
                        UUID.randomUUID(),
                        NOW));
        LocalV1BubbleResolveRequest request = request("room-a", "turn-a", "same query");

        var first = coordinator.resolve(request);
        var replay = coordinator.resolve(request);

        assertEquals(first, replay);
        verify(vector, times(1)).searchSimilarExcluding(any(), anyInt(), anySet());
        assertEquals(1, store.receipts.size());
        assertEquals(1, store.items.size());
        assertThrows(
                LocalV1BubbleException.class, () -> coordinator.resolve(request("room-a", "turn-a", "changed query")));
        verify(vector, times(1)).searchSimilarExcluding(any(), anyInt(), anySet());
    }

    @Test
    void tamperedDeliveryItemMakesReplayFailClosedWithoutNewFacts() {
        UUID memory = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        visible(memory, revision, 1, "stable body");
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet()))
                .thenReturn(List.of(new LocalV1VectorMatch(memory, revision, 1L, 0.9d)));
        when(memoryRead.findMemoryRevisionById(revision))
                .thenReturn(new MemoryRevision(
                        revision,
                        memory,
                        1L,
                        "Event",
                        UUID.randomUUID(),
                        "stable body",
                        NOW,
                        null,
                        "NONE",
                        UUID.randomUUID(),
                        NOW));
        LocalV1BubbleResolveRequest request = request("room-tamper", "turn-tamper", "query");
        coordinator.resolve(request);
        BubbleDeliveryItem original = store.items.get("turn-tamper");
        store.items.put(
                "turn-tamper",
                new BubbleDeliveryItem(
                        original.spaceKey(),
                        original.roomKey(),
                        original.turnKey(),
                        original.memoryId(),
                        original.memoryRevisionId(),
                        original.revisionNo(),
                        original.policyRevisionNo(),
                        original.score(),
                        original.memoryType(),
                        original.evidenceAgeDays() + 1));

        assertEquals(
                LocalV1BubbleException.Code.INTERNAL_FAILURE,
                assertThrows(LocalV1BubbleException.class, () -> coordinator.resolve(request))
                        .code());
        assertEquals(1, store.receipts.size());
        assertEquals(1, store.items.size());
        verify(vector, times(1)).searchSimilarExcluding(any(), anyInt(), anySet());
    }

    @Test
    void requestClosureAndDefaultSpaceRejectBeforeEmbedding() {
        for (String illegal : List.of("", "   ", "bad\u0001text", "\uD800")) {
            LocalV1BubbleException exception = assertThrows(
                    LocalV1BubbleException.class,
                    () -> coordinator.resolve(
                            request("room-a", UUID.randomUUID().toString(), illegal)));
            assertEquals(LocalV1BubbleException.Code.REQUEST_SCHEMA_INVALID, exception.code());
        }
        String tooLong = "🙂".repeat(121);
        assertEquals(
                LocalV1BubbleException.Code.REQUEST_SCHEMA_INVALID,
                assertThrows(
                                LocalV1BubbleException.class,
                                () -> coordinator.resolve(request("room-a", "turn-long", tooLong)))
                        .code());
        assertEquals(
                LocalV1BubbleException.Code.SPACE_KEY_MISMATCH,
                assertThrows(
                                LocalV1BubbleException.class,
                                () -> coordinator.resolve(
                                        new LocalV1BubbleResolveRequest("other", "room", "turn", "query")))
                        .code());
        verify(vector, never()).searchSimilarExcluding(any(), anyInt(), anySet());
    }

    @Test
    void wrongSpaceRejectsBeforeAnyFactProbeEvenWithExistingReceipt() {
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet())).thenReturn(List.of());
        coordinator.resolve(request("room-a", "turn-existing", "query"));
        assertEquals(1, store.receipts.size());
        store.resetProbeCounters();

        LocalV1BubbleException exception = assertThrows(
                LocalV1BubbleException.class,
                () -> coordinator.resolve(
                        new LocalV1BubbleResolveRequest("other-space", "room-a", "turn-existing", "query")));

        assertEquals(LocalV1BubbleException.Code.SPACE_KEY_MISMATCH, exception.code());
        assertEquals(0, store.turnLockCalls.get());
        assertEquals(0, store.receiptQueryCalls.get());
        verify(vector, times(1)).searchSimilarExcluding(any(), anyInt(), anySet());
        assertEquals(1, store.receipts.size());
        assertEquals(0, store.items.size());
        assertEquals(0, store.ledger.size());
    }

    @Test
    void purgeIsExactAndNaturallyIdempotent() {
        when(vector.searchSimilarExcluding(any(), anyInt(), anySet())).thenReturn(List.of());
        coordinator.resolve(request("room-a", "turn-a", "q"));
        coordinator.resolve(request("room-b", "turn-b", "q"));

        assertEquals(
                "PURGED",
                coordinator
                        .purge(new LocalV1BubbleRoomPurgeRequest(SPACE, "room-a"))
                        .status());
        assertEquals(
                "PURGED",
                coordinator
                        .purge(new LocalV1BubbleRoomPurgeRequest(SPACE, "room-a"))
                        .status());
        assertEquals(Set.of("turn-b"), store.receipts.keySet());
    }

    @Test
    void policyConfigurationFailsClosedForPlaceholderAndNumericEdges() {
        TransactionExecutor direct = new TransactionExecutor() {
            @Override
            public <T> T executeInTransaction(java.util.function.Supplier<T> work) {
                return work.get();
            }
        };
        for (LocalV1BubblePolicy invalid : List.of(
                new LocalV1BubblePolicy("default", 0.70, LocalV1BubbleCoordinator.POLICY_VERSION),
                new LocalV1BubblePolicy("family", Math.nextDown(0.40), LocalV1BubbleCoordinator.POLICY_VERSION),
                new LocalV1BubblePolicy("family", Math.nextUp(0.95), LocalV1BubbleCoordinator.POLICY_VERSION),
                new LocalV1BubblePolicy("family", Double.NaN, LocalV1BubbleCoordinator.POLICY_VERSION),
                new LocalV1BubblePolicy("family", Double.POSITIVE_INFINITY, LocalV1BubbleCoordinator.POLICY_VERSION))) {
            assertThrows(
                    IllegalStateException.class,
                    () -> new LocalV1BubbleCoordinator(
                            vector, s2b, memoryRead, store, store, direct, Clock.systemUTC(), invalid));
        }
    }

    private void visible(UUID memoryId, UUID revisionId, long revisionNo, String body) {
        when(s2b.getMemoryDetail(memoryId))
                .thenReturn(new LocalV1S2BMemoryDetail(
                        memoryId,
                        revisionId,
                        "ACTIVE",
                        revisionNo,
                        1L,
                        "Event",
                        UUID.randomUUID(),
                        body,
                        "NONE",
                        NOW,
                        1));
        when(s2b.getFullEvidence(memoryId))
                .thenReturn(new LocalV1S2BEvidenceResult(
                        memoryId,
                        revisionId,
                        revisionNo,
                        List.of(new LocalV1S2BEvidenceMessage(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                1L,
                                UUID.randomUUID(),
                                "PERSON",
                                "actor",
                                "actor",
                                NOW.minusHours(48),
                                "evidence"))));
    }

    private static LocalV1BubbleResolveRequest request(String room, String turn, String query) {
        return new LocalV1BubbleResolveRequest(SPACE, room, turn, query);
    }

    private static final class FakeBubbleStore implements BubbleQueryPort, BubbleTransactionPort {
        final Map<String, BubbleTurnReceipt> receipts = new HashMap<>();
        final Map<String, BubbleDeliveryItem> items = new HashMap<>();
        final Set<BubbleRoomRevisionLedgerEntry> ledger = new LinkedHashSet<>();
        final java.util.concurrent.atomic.AtomicInteger turnLockCalls =
                new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger receiptQueryCalls =
                new java.util.concurrent.atomic.AtomicInteger();

        void resetProbeCounters() {
            turnLockCalls.set(0);
            receiptQueryCalls.set(0);
        }

        @Override
        public BubbleTurnReceipt findReceiptByTurnKey(String turnKey) {
            receiptQueryCalls.incrementAndGet();
            return receipts.get(turnKey);
        }

        @Override
        public BubbleDeliveryItem findDeliveryItem(String spaceKey, String roomKey, String turnKey) {
            BubbleDeliveryItem item = items.get(turnKey);
            return item != null
                            && item.spaceKey().equals(spaceKey)
                            && item.roomKey().equals(roomKey)
                    ? item
                    : null;
        }

        @Override
        public Set<UUID> findDeliveredRevisionIds(String spaceKey, String roomKey) {
            Set<UUID> result = new LinkedHashSet<>();
            ledger.stream()
                    .filter(entry ->
                            entry.spaceKey().equals(spaceKey) && entry.roomKey().equals(roomKey))
                    .map(BubbleRoomRevisionLedgerEntry::memoryRevisionId)
                    .forEach(result::add);
            return result;
        }

        @Override
        public void lockTurnKey(String turnKey) {
            turnLockCalls.incrementAndGet();
        }

        @Override
        public void lockRoom(String spaceKey, String roomKey) {}

        @Override
        public void insertReceipt(BubbleTurnReceipt receipt) {
            receipts.put(receipt.turnKey(), receipt);
        }

        @Override
        public void insertDeliveryItem(BubbleDeliveryItem item) {
            items.put(item.turnKey(), item);
        }

        @Override
        public void insertLedgerEntry(BubbleRoomRevisionLedgerEntry entry) {
            ledger.add(entry);
        }

        @Override
        public void purgeRoom(String spaceKey, String roomKey) {
            Set<String> turns = new LinkedHashSet<>();
            receipts.values().stream()
                    .filter(receipt -> receipt.spaceKey().equals(spaceKey)
                            && receipt.roomKey().equals(roomKey))
                    .map(BubbleTurnReceipt::turnKey)
                    .forEach(turns::add);
            turns.forEach(receipts::remove);
            turns.forEach(items::remove);
            ledger.removeIf(entry ->
                    entry.spaceKey().equals(spaceKey) && entry.roomKey().equals(roomKey));
        }
    }
}
