package io.github.candyxi0.hidenest.application.outbox;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.*;

class BackoffCalculatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-11T10:00:00Z"), ZoneOffset.UTC);

    static long delayMs(BackoffCalculator c, int attempt) {
        return Duration.between(OffsetDateTime.now(FIXED), c.nextAvailableAt(attempt)).toMillis();
    }

    // T02: controlled jitter with callCount tracking
    static class JitterGen implements RandomGenerator {
        final long val; int callCount;
        JitterGen(long val) { this.val = val; }
        public long nextLong() { callCount++; return 0; }
        public long nextLong(long b) { callCount++; return 0; }
        public long nextLong(long o, long b) { callCount++; return val; }
        public double nextDouble() { return 0; } public boolean nextBoolean() { return false; }
        public void nextBytes(byte[] b) {} public float nextFloat() { return 0; }
        public int nextInt() { return 0; } public int nextInt(int b) { return 0; }
        public int nextInt(int o, int b) { return 0; } public double nextGaussian() { return 0; }
        public double nextDouble(double b) { return 0; } public double nextDouble(double o, double b) { return 0; }
        public float nextFloat(float b) { return 0; } public float nextFloat(float o, float b) { return 0; }
        public double nextExponential() { return 0; }
    }

    static void assertDelayAndCall(int attempt, long jitter, long expectedMs) {
        JitterGen g = new JitterGen(jitter);
        BackoffCalculator c = new BackoffCalculator(FIXED, g);
        assertEquals(expectedMs, delayMs(c, attempt), "attempt " + attempt + " jitter " + jitter);
        assertEquals(1, g.callCount, "callCount must be 1 for attempt " + attempt);
    }

    // T02: 21 groups with call count assertions
    @Test void a0lower()  { assertDelayAndCall(0, -250, 750); }
    @Test void a0center() { assertDelayAndCall(0, 0, 1000); }
    @Test void a0upper()  { assertDelayAndCall(0, 250, 1250); }
    @Test void a1lower()  { assertDelayAndCall(1, -500, 1500); }
    @Test void a1center() { assertDelayAndCall(1, 0, 2000); }
    @Test void a1upper()  { assertDelayAndCall(1, 500, 2500); }
    @Test void a2lower()  { assertDelayAndCall(2, -1000, 3000); }
    @Test void a2center() { assertDelayAndCall(2, 0, 4000); }
    @Test void a2upper()  { assertDelayAndCall(2, 1000, 5000); }
    @Test void a3lower()  { assertDelayAndCall(3, -2000, 6000); }
    @Test void a3center() { assertDelayAndCall(3, 0, 8000); }
    @Test void a3upper()  { assertDelayAndCall(3, 2000, 10000); }
    @Test void a4lower()  { assertDelayAndCall(4, -4000, 12000); }
    @Test void a4center() { assertDelayAndCall(4, 0, 16000); }
    @Test void a4upper()  { assertDelayAndCall(4, 4000, 20000); }
    @Test void a5lower()  { assertDelayAndCall(5, -8000, 24000); }
    @Test void a5center() { assertDelayAndCall(5, 0, 32000); }
    @Test void a5upper()  { assertDelayAndCall(5, 8000, 40000); }
    @Test void a6lower()  { assertDelayAndCall(6, -16000, 48000); }
    @Test void a6center() { assertDelayAndCall(6, 0, 64000); }
    @Test void a6upper()  { assertDelayAndCall(6, 16000, 80000); }

    // T02: invalid attempts → randomCalls=0
    @Test @DisplayName("T02: invalid -1 → callCount=0")
    void invalidMinus1() { JitterGen g = new JitterGen(0); BackoffCalculator c = new BackoffCalculator(FIXED, g);
        assertThrows(IllegalArgumentException.class, () -> c.nextAvailableAt(-1)); assertEquals(0, g.callCount); }
    @Test @DisplayName("T02: invalid 7 → callCount=0")
    void invalid7() { JitterGen g = new JitterGen(0); BackoffCalculator c = new BackoffCalculator(FIXED, g);
        assertThrows(IllegalArgumentException.class, () -> c.nextAvailableAt(7)); assertEquals(0, g.callCount); }

    @Test @DisplayName("delay >= 0 for all valid attempts")
    void delayNonNegative() {
        BackoffCalculator c = new BackoffCalculator(FIXED, RandomGenerator.getDefault());
        for (int a = 0; a <= 6; a++) assertTrue(delayMs(c, a) >= 0);
    }
}
