package io.github.candyxi0.hidenest.application.outbox;

import static io.github.candyxi0.hidenest.application.outbox.OutboxWorkerCoordinator.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.outbox.OutboxHandlerRegistry.Resolution;
import io.github.candyxi0.hidenest.runtime.domain.*;
import io.github.candyxi0.hidenest.runtime.port.CompletionGuardPort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.*;

class OutboxWorkerCoordinatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);

    static ClaimedOutboxEvent makeEvent(int attempt) {
        return new ClaimedOutboxEvent(UUID.randomUUID(), 100L + attempt, "OPERATIONAL",
                "closeout.received.v1", "RUN", UUID.randomUUID(), 1L, "DATABASE_TEST", 0L,
                new byte[32], "{\"aggregateId\":\"" + UUID.randomUUID() + "\"}", null,
                (short) attempt, (short) 8);
    }

    // ── R1A-01: AlreadyTerminal/Terminal state mapping ─────────────────────────

    @Test @DisplayName("R1A-01: AlreadyTerminal(SUCCEEDED) → alreadySettled")
    void alreadyTerminalSucceededToAlreadySettled() {
        var c = new Counts();
        mapTerminal(new OutboxTerminalSettlement.AlreadyTerminal("SUCCEEDED"), c);
        assertEquals(1, c.alreadySettled); assertEquals(0, c.rejected); assertEquals(0, c.finalFailed);
    }

    @Test @DisplayName("R1A-01: AlreadyTerminal(FINAL_FAILED) → finalFailed")
    void alreadyTerminalFinalFailedToFinalFailed() {
        var c = new Counts(); mapTerminal(new OutboxTerminalSettlement.AlreadyTerminal("FINAL_FAILED"), c);
        assertEquals(1, c.finalFailed); assertEquals(0, c.alreadySettled); assertEquals(0, c.rejected);
    }

    @Test @DisplayName("R1A-01: Terminal(SUCCEEDED) → alreadySettled")
    void terminalSucceededToAlreadySettled() {
        var c = new Counts(); mapFailure(new OutboxFailureSettlement.Terminal("SUCCEEDED", (short) 1), c);
        assertEquals(1, c.alreadySettled); assertEquals(0, c.finalFailed);
    }

    @Test @DisplayName("R1A-01: Terminal(FINAL_FAILED) → finalFailed")
    void terminalFinalFailedToFinalFailed() {
        var c = new Counts(); mapFailure(new OutboxFailureSettlement.Terminal("FINAL_FAILED", (short) 8), c);
        assertEquals(1, c.finalFailed); assertEquals(0, c.alreadySettled);
    }

    // ── T03: Exception zero-interaction ────────────────────────────────────────
    @Test @DisplayName("T03: Guard throws → handler supports=0, process=0, settlements=0")
    void guardThrowsZeroInteraction() {
        var tx = countedFakeTx(); tx.claimedEvents.add(makeEvent(0));
        AtomicInteger supportsCalls = new AtomicInteger(0);
        AtomicInteger processCalls = new AtomicInteger(0);
        var handler = new OutboxEffectHandler() {
            public boolean supports(String et) { supportsCalls.incrementAndGet(); return true; }
            public ProcessedEffect process(ClaimedOutboxEvent e) { processCalls.incrementAndGet(); return new ProcessedEffect("C1", "ek"); }
        };
        var guard = (CompletionGuardPort) (eid, ak, aid, ar, purp) -> { throw new RuntimeException("guard-boom"); };
        var coord = new OutboxWorkerCoordinator(tx, guard,
                new OutboxHandlerRegistry(List.of(handler)),
                new BackoffCalculator(FIXED, RandomGenerator.getDefault()), FIXED);
        assertThrows(RuntimeException.class, () -> coord.runOnce("w1", Duration.ofMinutes(5), 10));
        assertEquals(0, supportsCalls.get(), "handler.supports must not be called when guard throws");
        assertEquals(0, processCalls.get(), "handler.process must not be called when guard throws");
        assertEquals(0, tx.successCalls + tx.failureCalls + tx.rejectedCalls, "no settlement when guard throws");
    }

    @Test @DisplayName("T03: handler.supports() throws → process=0, settlements=0")
    void handlerSupportsThrowsZeroProcess() {
        var tx = countedFakeTx(); tx.claimedEvents.add(makeEvent(0));
        AtomicInteger processCalls = new AtomicInteger(0);
        var handler = new OutboxEffectHandler() {
            public boolean supports(String et) { throw new RuntimeException("supports-boom"); }
            public ProcessedEffect process(ClaimedOutboxEvent e) { processCalls.incrementAndGet(); return null; }
        };
        var coord = new OutboxWorkerCoordinator(tx, passingGuard(),
                new OutboxHandlerRegistry(List.of(handler)),
                new BackoffCalculator(FIXED, RandomGenerator.getDefault()), FIXED);
        // supports() threw → event processing fails → INTERNAL_FAILURE for that event
        // handler.process must NOT have been called
        assertThrows(RuntimeException.class, () -> coord.runOnce("w1", Duration.ofMinutes(5), 10));
        assertEquals(0, processCalls.get(), "handler.process must not be called");
    }

    // ── T04: batch isolation (1st fails, 2nd succeeds) ─────────────────────────
    @Test @DisplayName("T04: 2 events, first fails→RetryScheduled, second succeeds→SETTLED")
    void batchIsolationFirstFailsSecondSucceeds() throws Exception {
        var tx = countedFakeTx();
        var e1 = makeEvent(0); var e2 = makeEvent(1);
        tx.claimedEvents.add(e1); tx.claimedEvents.add(e2);
        tx.failureOutcomes.put(e1.eventId(), new OutboxFailureSettlement.RetryScheduled((short) 1));
        tx.successOutcomes.put(e2.eventId(), OutboxSuccessOutcome.SETTLED);

        var handler = new OutboxEffectHandler() {
            public boolean supports(String et) { return true; }
            public ProcessedEffect process(ClaimedOutboxEvent e) throws OutboxProcessingException {
                if (e.eventId().equals(e1.eventId()))
                    throw new OutboxProcessingException(OutboxProcessingException.Code.DATABASE_UNAVAILABLE);
                return new ProcessedEffect("CONSUMER1", "ek-ok");
            }
        };
        var r = coordinator(tx, passingGuard(), handler).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(2, r.claimed());
        assertEquals(1, r.retryScheduled());
        assertEquals(1, r.settled());
        assertEquals(0, r.alreadySettled()); assertEquals(0, r.finalFailed());
        assertEquals(0, r.rejected()); assertEquals(0, r.leaseLost());
        assertEquals(2, r.totalProcessed());
    }

    // ── T05: real null-in-list ─────────────────────────────────────────────────
    @Test @DisplayName("T05: eventIds containing null element rejected")
    void eventIdsContainingNullRejected() {
        // claimed=1 balances the size check; null element fails List.copyOf()
        assertThrows(NullPointerException.class, () ->
                new OutboxBatchResult(1, 0, 0, 0, 0, 0, 1, 0, 0,
                        new ArrayList<>(Arrays.asList((UUID) null))));
    }

    @Test @DisplayName("T05: eventIds null reference rejected")
    void eventIdsNullRejected() {
        assertThrows(NullPointerException.class, () ->
                new OutboxBatchResult(0, 0, 0, 0, 0, 0, 0, 0, 0, null));
    }

    // ── existing tests (retained, working) ─────────────────────────────────────
    @Test @DisplayName("R1A-02: same eventId twice → invocations=2, effects=1")
    void sameEventTwiceTwoInvocationsOneEffect() throws Exception {
        var event = makeEvent(0);
        AtomicInteger invocations = new AtomicInteger(0);
        Set<String> appliedEffects = new HashSet<>();
        var handler = new OutboxEffectHandler() {
            public boolean supports(String et) { return true; }
            public ProcessedEffect process(ClaimedOutboxEvent e) {
                invocations.incrementAndGet();
                String key = "idem-" + e.eventId(); appliedEffects.add(key);
                return new ProcessedEffect("CONSUMER1", key);
            }
        };
        var tx1 = fakeTx(); tx1.claimedEvents.add(event);
        var r1 = coordinator(tx1, passingGuard(), handler).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(1, invocations.get()); assertEquals(1, appliedEffects.size());
        assertEquals(1, r1.settled()); assertEquals(0, r1.alreadySettled());

        var tx2 = fakeTx(); tx2.claimedEvents.add(event);
        tx2.successOutcomes.put(event.eventId(), OutboxSuccessOutcome.ALREADY_SETTLED);
        var r2 = coordinator(tx2, passingGuard(), handler).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(2, invocations.get()); assertEquals(1, appliedEffects.size());
        assertEquals(0, r2.settled()); assertEquals(1, r2.alreadySettled());
    }

    @Test @DisplayName("R1A-03: attempt=7 → randomCalls=0, finalFailed=1")
    void attempt7NoRandomFinalFailed() throws Exception {
        var tx = fakeTx(); var event = makeEvent(7); tx.claimedEvents.add(event);
        tx.failureOutcomes.put(event.eventId(), new OutboxFailureSettlement.FinalFailed((short) 8));
        AtomicInteger randomCalls = new AtomicInteger(0);
        var countingRandom = new RandomGenerator() {
            public long nextLong() { randomCalls.incrementAndGet(); return 0; }
            public long nextLong(long b) { randomCalls.incrementAndGet(); return 0; }
            public long nextLong(long o, long b) { randomCalls.incrementAndGet(); return 0; }
            public double nextDouble() { return 0; } public boolean nextBoolean() { return false; }
            public void nextBytes(byte[] b) {} public float nextFloat() { return 0; }
            public int nextInt() { return 0; } public int nextInt(int b) { return 0; }
            public int nextInt(int o, int b) { return 0; } public double nextGaussian() { return 0; }
            public double nextDouble(double b) { return 0; } public double nextDouble(double o, double b) { return 0; }
            public float nextFloat(float b) { return 0; } public float nextFloat(float o, float b) { return 0; }
            public double nextExponential() { return 0; }
        };
        var backoff = new BackoffCalculator(FIXED, countingRandom);
        var coord = new OutboxWorkerCoordinator(tx, passingGuard(),
                new OutboxHandlerRegistry(List.of(failingHandler("DATABASE_UNAVAILABLE"))), backoff, FIXED);
        var r = coord.runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(0, randomCalls.get());
        assertEquals(1, r.finalFailed());
    }

    @Test @DisplayName("Pass → handler → SETTLED")
    void passHandlerSettled() throws Exception {
        var tx = fakeTx(); tx.claimedEvents.add(makeEvent(0));
        var r = coordinator(tx, passingGuard(), passingHandler()).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(1, r.settled());
    }

    @Test @DisplayName("Failure RetryScheduled → retryScheduled")
    void failureRetryScheduled() throws Exception {
        var tx = fakeTx(); var e = makeEvent(2); tx.claimedEvents.add(e);
        tx.failureOutcomes.put(e.eventId(), new OutboxFailureSettlement.RetryScheduled((short) 3));
        var r = coordinator(tx, passingGuard(), failingHandler("DATABASE_UNAVAILABLE")).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(1, r.retryScheduled());
    }

    @Test @DisplayName("Stale → handler 0 times")
    void staleZeroHandler() throws Exception {
        var tx = fakeTx(); tx.claimedEvents.add(makeEvent(0));
        var guard = (CompletionGuardPort) (eid, ak, aid, ar, purp) ->
                new CompletionGuardResult.Stale("EXPECTED_REVISION_STALE");
        var r = coordinator(tx, guard, passingHandler()).runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(1, r.rejected()); assertEquals(0, r.settled());
    }

    @Test @DisplayName("0 handlers → handlerMissing + rejected")
    void zeroHandlers() throws Exception {
        var tx = fakeTx(); tx.claimedEvents.add(makeEvent(0));
        var coord = new OutboxWorkerCoordinator(tx, passingGuard(),
                new OutboxHandlerRegistry(List.of()),
                new BackoffCalculator(FIXED, RandomGenerator.getDefault()), FIXED);
        var r = coord.runOnce("w1", Duration.ofMinutes(5), 10);
        assertEquals(1, r.handlerMissing()); assertEquals(1, r.rejected());
    }

    @Test @DisplayName("ProcessedEffect effectKey canary")
    void effectKeyCanary() {
        assertThrows(IllegalArgumentException.class, () -> new ProcessedEffect("C1", "key with spaces"));
        assertThrows(IllegalArgumentException.class, () -> new ProcessedEffect("C1", "中文"));
        assertDoesNotThrow(() -> new ProcessedEffect("CONSUMER1", "my.effect/key:123-456"));
    }

    // ── helpers ────────────────────────────────────────────────────────────────
    static class FakeTxPort implements RuntimeTransactionPort {
        final List<ClaimedOutboxEvent> claimedEvents = new ArrayList<>();
        final Map<UUID, OutboxSuccessOutcome> successOutcomes = new HashMap<>();
        final Map<UUID, OutboxFailureSettlement> failureOutcomes = new HashMap<>();
        final Map<UUID, OutboxTerminalSettlement> terminalOutcomes = new HashMap<>();

        @Override public List<ClaimedOutboxEvent> claimAndLeaseOutboxEvents(
                String lo, OffsetDateTime n, OffsetDateTime lu, int bs) {
            List<ClaimedOutboxEvent> batch = new ArrayList<>(claimedEvents); claimedEvents.clear(); return batch;
        }
        @Override public OutboxSuccessOutcome settleOutboxSuccess(UUID eid, String lo, String cc, String ek, OffsetDateTime ca) {
            return successOutcomes.getOrDefault(eid, OutboxSuccessOutcome.SETTLED); }
        @Override public OutboxFailureSettlement settleOutboxFailure(UUID eid, String lo, OffsetDateTime na, OffsetDateTime ca, String fc) {
            return failureOutcomes.getOrDefault(eid, new OutboxFailureSettlement.RetryScheduled((short) 1)); }
        @Override public OutboxTerminalSettlement settleOutboxRejected(UUID eid, String lo, OffsetDateTime ca, String fc) {
            return terminalOutcomes.getOrDefault(eid, new OutboxTerminalSettlement.Rejected()); }
        @Override public void lockIdempotencyKey(String k) {}
        @Override public void lockContextPackThread(UUID threadId) {}
        @Override public IdempotencyReceipt findReceiptByKey(String k) { return null; }
        @Override public void commitReceipt(String k, String oc, byte[] rh, UUID rid, String rk, String rm) {}
        @Override public void insertGovernedOutbox(OutboxEvent e) {}
        @Override public void insertCaptureScope(CaptureScope s) {}
        @Override public void insertCaptureScopeUnits(List<CaptureScopeUnit> u) {}
        @Override public boolean freezeCaptureScope(UUID s, OffsetDateTime f) { return true; }
        @Override public void insertCloseoutRun(CloseoutRun r) {}
        @Override public boolean transitionCloseoutRun(UUID r, String es, String ns, OffsetDateTime sa, OffsetDateTime ta, String fc) { return true; }
        @Override public void insertCheckpoint(Checkpoint ck) {}
        @Override public void insertWorkArtifact(WorkArtifact a) {}
        @Override public void insertModelRun(ModelRun r) {}
        @Override public boolean transitionModelRun(UUID m, String es, String ns, byte[] omh, OffsetDateTime ta, String fc) { return true; }
        @Override public void insertRetrievalTrace(RetrievalTrace t) {}
        @Override public void insertContextDelivery(ContextDelivery d) {}
        @Override public void insertContextPackDeliveryItems(List<ContextPackDeliveryItem> items) {}
        @Override public boolean invalidateContextDelivery(UUID d, OffsetDateTime ia, String ir) { return true; }
        @Override public void insertConsumerEffect(ConsumerEffect e) {}
        @Override public List<UUID> purgeExpiredWorkArtifacts(OffsetDateTime cutoff, int batchSize) { return List.of(); }
    }

    static class CountedFakeTxPort extends FakeTxPort {
        int successCalls, failureCalls, rejectedCalls;
        @Override public OutboxSuccessOutcome settleOutboxSuccess(UUID eid, String lo, String cc, String ek, OffsetDateTime ca) {
            successCalls++; return super.settleOutboxSuccess(eid, lo, cc, ek, ca); }
        @Override public OutboxFailureSettlement settleOutboxFailure(UUID eid, String lo, OffsetDateTime na, OffsetDateTime ca, String fc) {
            failureCalls++; return super.settleOutboxFailure(eid, lo, na, ca, fc); }
        @Override public OutboxTerminalSettlement settleOutboxRejected(UUID eid, String lo, OffsetDateTime ca, String fc) {
            rejectedCalls++; return super.settleOutboxRejected(eid, lo, ca, fc); }
    }

    static FakeTxPort fakeTx() { return new FakeTxPort(); }
    static CountedFakeTxPort countedFakeTx() { return new CountedFakeTxPort(); }
    static CompletionGuardPort passingGuard() {
        return (eid, ak, aid, ar, purp) -> new CompletionGuardResult.Pass(); }
    static OutboxEffectHandler passingHandler() {
        return new OutboxEffectHandler() {
            public boolean supports(String et) { return true; }
            public ProcessedEffect process(ClaimedOutboxEvent e) { return new ProcessedEffect("CONSUMER1", "ek1"); }
        }; }
    static OutboxEffectHandler failingHandler(String code) {
        return new OutboxEffectHandler() {
            public boolean supports(String et) { return true; }
            public ProcessedEffect process(ClaimedOutboxEvent e) throws OutboxProcessingException {
                throw new OutboxProcessingException(OutboxProcessingException.Code.valueOf(code)); }
        }; }
    static OutboxWorkerCoordinator coordinator(FakeTxPort tx, CompletionGuardPort guard, OutboxEffectHandler h) {
        return new OutboxWorkerCoordinator(tx, guard, new OutboxHandlerRegistry(List.of(h)),
                new BackoffCalculator(FIXED, RandomGenerator.getDefault()), FIXED);
    }
}
