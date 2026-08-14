package io.github.candyxi0.hidenest.application.coordinator;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalV1DeletionCanonicalizerTest {

    private static final UUID TARGET = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");

    @Test
    void requestHashIsDeterministicAndExactly32Bytes() {
        byte[] a = LocalV1DeletionCanonicalizer.requestHash(TARGET, 1L, 1L);
        byte[] b = LocalV1DeletionCanonicalizer.requestHash(TARGET, 1L, 1L);
        assertArrayEquals(a, b);
        assertEquals(32, a.length);
    }

    @Test
    void eachBoundFieldChangesTheHash() {
        byte[] base = LocalV1DeletionCanonicalizer.requestHash(TARGET, 1L, 1L);
        UUID other = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        assertFalse(java.util.Arrays.equals(base, LocalV1DeletionCanonicalizer.requestHash(other, 1L, 1L)));
        assertFalse(java.util.Arrays.equals(base, LocalV1DeletionCanonicalizer.requestHash(TARGET, 2L, 1L)));
        assertFalse(java.util.Arrays.equals(base, LocalV1DeletionCanonicalizer.requestHash(TARGET, 1L, 2L)));
    }

    @Test
    void requestHashHexMatchesConstantTimeComparison() {
        String hex = LocalV1DeletionCanonicalizer.requestHashHex(TARGET, 3L, 4L);
        assertTrue(LocalV1DeletionCanonicalizer.is64LowerHex(hex));
        byte[] expected = LocalV1DeletionCanonicalizer.requestHash(TARGET, 3L, 4L);
        assertTrue(LocalV1DeletionCanonicalizer.constantTimeEqualsHex(hex, expected));
        assertFalse(LocalV1DeletionCanonicalizer.constantTimeEqualsHex(hex, LocalV1DeletionCanonicalizer.requestHash(TARGET, 3L, 5L)));
    }

    @Test
    void is64LowerHexRejectsMalformed() {
        assertFalse(LocalV1DeletionCanonicalizer.is64LowerHex(null));
        assertFalse(LocalV1DeletionCanonicalizer.is64LowerHex(""));
        assertFalse(LocalV1DeletionCanonicalizer.is64LowerHex("ab".repeat(31)));
        assertFalse(LocalV1DeletionCanonicalizer.is64LowerHex("AB".repeat(32)));
        assertFalse(LocalV1DeletionCanonicalizer.is64LowerHex("g".repeat(64)));
        assertTrue(LocalV1DeletionCanonicalizer.is64LowerHex("0f".repeat(32)));
    }

    @Test
    void hexToBytesRoundTrips() {
        byte[] original = new byte[] {0x00, 0x01, (byte) 0xff, 0x7f};
        String hex = LocalV1DeletionCanonicalizer.bytesToHex(original);
        assertArrayEquals(original, LocalV1DeletionCanonicalizer.hexToBytes(hex));
    }
}
