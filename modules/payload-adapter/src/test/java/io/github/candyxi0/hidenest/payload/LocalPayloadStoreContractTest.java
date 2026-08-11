package io.github.candyxi0.hidenest.payload;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalPayloadStoreContractTest {

    private static final String CANARY = "CANARY-S2A-PAYLOAD-XXX";

    @TempDir
    Path tempDir;

    private LocalPayloadStore store;
    private Path root;

    @BeforeEach
    void setUp() {
        root = tempDir.resolve("payload-store");
        store = new LocalPayloadStore(root);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 1: normal UTF-8 small object put / head / get / delete
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    void putHeadGetDeleteLifecycle() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("证据消息：协作者表达了对项目的担忧");
        byte[] hash = sha256(body);
        String contentType = "text/plain; charset=UTF-8";

        // put
        PayloadPutResult putResult = store.put(payloadId, contentType, body, hash);
        assertNotNull(putResult);
        assertEquals(payloadId, putResult.payloadId());
        assertNotNull(putResult.objectRef());
        assertEquals(body.length, putResult.sizeBytes());
        assertArrayEquals(hash, putResult.contentHash());
        assertEquals("LOCAL_FILE", putResult.storeAdapter());
        assertTrue(putResult.created());

        // head
        PayloadHeadResult headResult = store.head(putResult.objectRef());
        assertNotNull(headResult);
        assertEquals(body.length, headResult.sizeBytes());
        assertArrayEquals(hash, headResult.contentHash());
        assertEquals(contentType, headResult.contentType());

        // get
        byte[] got = store.get(putResult.objectRef(), hash, 1024 * 1024);
        assertArrayEquals(body, got);
        // defensive copy: modify returned array
        got[0] = (byte) 'X';
        byte[] got2 = store.get(putResult.objectRef(), hash, 1024 * 1024);
        assertArrayEquals(body, got2);

        // delete
        store.delete(putResult.objectRef(), hash);

        // delete non-existent → NOT_FOUND
        var ex = assertThrows(PayloadStoreException.class,
                () -> store.delete(putResult.objectRef(), hash));
        assertEquals("NOT_FOUND", ex.errorCode());

        // head non-existent → NOT_FOUND
        var ex2 = assertThrows(PayloadStoreException.class,
                () -> store.head(putResult.objectRef()));
        assertEquals("NOT_FOUND", ex2.errorCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 2: idempotent put (same ID + same content), conflict (same ID + diff content)
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    void idempotentSameContentConflictDifferentContent() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("第二条证据：技术方案需要调整");
        byte[] hash = sha256(body);
        String ct = "text/plain; charset=UTF-8";

        PayloadPutResult r1 = store.put(payloadId, ct, body, hash);
        PayloadPutResult r2 = store.put(payloadId, ct, body, hash);

        assertEquals(r1.objectRef(), r2.objectRef());
        assertEquals(r1.sizeBytes(), r2.sizeBytes());
        assertArrayEquals(r1.contentHash(), r2.contentHash());
        assertTrue(r1.created());
        assertFalse(r2.created());

        // same ID, different content → CONFLICT
        byte[] body2 = bytes("不同的内容");
        byte[] hash2 = sha256(body2);
        var ex = assertThrows(PayloadStoreException.class,
                () -> store.put(payloadId, ct, body2, hash2));
        assertEquals("CONFLICT", ex.errorCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 3: hash mismatch, tampered disk, oversized, invalid ref, path traversal
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(3)
    void hashMismatchRejected() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("hash test body");
        byte[] correctHash = sha256(body);
        byte[] wrongHash = new byte[32];
        Arrays.fill(wrongHash, (byte) 0xFF);

        var ex = assertThrows(PayloadStoreException.class,
                () -> store.put(payloadId, "text/plain", body, wrongHash));
        assertEquals("HASH_MISMATCH", ex.errorCode());
    }

    @Test
    @Order(4)
    void tamperedDiskContentRejected() throws Exception {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("original content for tamper test");
        byte[] hash = sha256(body);
        PayloadPutResult putResult = store.put(
                payloadId, "text/plain; charset=UTF-8", body, hash);

        // Tamper the file on disk: read, modify body bytes, write back
        Path filePath = root.resolve(putResult.objectRef());
        byte[] originalFile = Files.readAllBytes(filePath);
        // Parse header: first 2 bytes = ctLen (big-endian)
        int ctLen = ((originalFile[0] & 0xFF) << 8) | (originalFile[1] & 0xFF);
        byte[] tamperedBody = bytes("TAMPERED CONTENT!!!");
        byte[] tamperedFile = new byte[2 + ctLen + tamperedBody.length];
        System.arraycopy(originalFile, 0, tamperedFile, 0, 2 + ctLen);
        System.arraycopy(tamperedBody, 0, tamperedFile, 2 + ctLen, tamperedBody.length);
        Files.write(filePath, tamperedFile);

        // get should reject
        var ex = assertThrows(PayloadStoreException.class,
                () -> store.get(putResult.objectRef(), hash, 1024 * 1024));
        assertEquals("HASH_MISMATCH", ex.errorCode());

        // delete with correct original hash should also reject
        var ex2 = assertThrows(PayloadStoreException.class,
                () -> store.delete(putResult.objectRef(), hash));
        assertEquals("HASH_MISMATCH", ex2.errorCode());
    }

    @Test
    @Order(5)
    void oversizedPayloadRejected() {
        UUID payloadId = UUID.randomUUID();
        byte[] big = new byte[1024 * 1024 + 1]; // 1 MiB + 1
        Arrays.fill(big, (byte) 'A');
        byte[] hash = sha256(big);

        var ex = assertThrows(PayloadStoreException.class,
                () -> store.put(payloadId, "text/plain", big, hash));
        assertEquals("SIZE_EXCEEDED", ex.errorCode());
    }

    @Test
    @Order(6)
    void invalidObjectRefRejected() {
        byte[] hash = new byte[32];
        String[] invalid = {
                null,
                "",
                "../etc/passwd",
                "aa/../../../etc/passwd",
                "C:\\Windows\\System32",
                "aa/bb/something.payload",
                "ZZ/00000000000000000000000000000000.payload", // non-hex
                "ab/1234567890abcdef1234567890abcdef.payload", // too short
        };

        for (String ref : invalid) {
            if (ref == null) {
                assertThrows(PayloadStoreException.class,
                        () -> store.get(null, hash, 1024));
            } else {
                var ex = assertThrows(PayloadStoreException.class,
                        () -> store.get(ref, hash, 1024),
                        "should reject: " + ref);
                assertTrue(
                        "INVALID_REF".equals(ex.errorCode())
                                || "NOT_FOUND".equals(ex.errorCode()),
                        "expected INVALID_REF or NOT_FOUND for: " + ref
                                + " got: " + ex.errorCode());
            }
        }
    }

    @Test
    @Order(7)
    void pathTraversalRejected() {
        byte[] hash = new byte[32];
        String[] traversal = {
                "aa/..%2f..%2f..%2fetc%2fpasswd.payload",
                "aa/..\\..\\..\\windows\\system32.payload",
                "./aa/00000000000000000000000000000000.payload",
        };
        for (String ref : traversal) {
            var ex = assertThrows(PayloadStoreException.class,
                    () -> store.get(ref, hash, 1024),
                    "should reject traversal: " + ref);
            assertEquals("INVALID_REF", ex.errorCode(),
                    "traversal ref must be INVALID_REF: " + ref);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 4: delete hash mismatch, delete non-existent → NOT_FOUND
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(8)
    void deleteHashMismatchRejectedDeleteNotFound() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("delete test body");
        byte[] hash = sha256(body);

        // non-existent → NOT_FOUND
        String fakeRef = "00/00000000000000000000000000000000.payload";
        var ex = assertThrows(PayloadStoreException.class,
                () -> store.delete(fakeRef, hash));
        assertEquals("NOT_FOUND", ex.errorCode());

        // Write and delete with wrong hash
        PayloadPutResult r = store.put(payloadId, "text/plain", body, hash);
        byte[] wrongHash = new byte[32];
        Arrays.fill(wrongHash, (byte) 0xAA);
        var ex2 = assertThrows(PayloadStoreException.class,
                () -> store.delete(r.objectRef(), wrongHash));
        assertEquals("HASH_MISMATCH", ex2.errorCode());

        // Clean up with correct hash
        store.delete(r.objectRef(), hash);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 5: get returns defensive copy
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(9)
    void getReturnsDefensiveCopy() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("防御性复制测试");
        byte[] hash = sha256(body);
        PayloadPutResult r = store.put(payloadId, "text/plain; charset=UTF-8", body, hash);

        byte[] got1 = store.get(r.objectRef(), hash, 1024 * 1024);
        byte[] got2 = store.get(r.objectRef(), hash, 1024 * 1024);

        // Same content
        assertArrayEquals(body, got1);
        assertArrayEquals(body, got2);

        // Different array objects
        assertNotSame(got1, got2);

        // Modify got1, verify got2 is unaffected
        got1[0] = (byte) 'Z';
        byte[] got3 = store.get(r.objectRef(), hash, 1024 * 1024);
        assertArrayEquals(body, got3);
        assertNotEquals(got1[0], got3[0]);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 6: concurrent writes same ID
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(10)
    void concurrentSameContentConverges() throws Exception {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("并发测试相同内容");
        byte[] hash = sha256(body);
        String ct = "text/plain; charset=UTF-8";
        int threads = 4;

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        var barrier = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var ok = new AtomicInteger(0);
        var results = new ConcurrentLinkedQueue<PayloadPutResult>();

        for (int i = 0; i < threads; i++) {
            exec.submit(() -> {
                try {
                    barrier.countDown();
                    go.await();
                    PayloadPutResult r = store.put(payloadId, ct, body, hash);
                    results.add(r);
                    ok.incrementAndGet();
                } catch (Exception ignored) {
                }
            });
        }
        barrier.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(threads, ok.get(), "all concurrent puts of same content must succeed");

        // All results must be identical
        PayloadPutResult first = results.peek();
        for (var r : results) {
            assertEquals(first.objectRef(), r.objectRef());
            assertArrayEquals(first.contentHash(), r.contentHash());
            assertEquals(first.sizeBytes(), r.sizeBytes());
        }

        // Verify only 1 file exists
        Path filePath = root.resolve(first.objectRef());
        assertTrue(Files.exists(filePath));
    }

    @Test
    @Order(11)
    void concurrentDifferentContentAtMostOneSucceeds() throws Exception {
        UUID payloadId = UUID.randomUUID();
        String ct = "text/plain; charset=UTF-8";
        int threads = 4;

        ExecutorService exec = Executors.newFixedThreadPool(threads);
        var barrier = new CountDownLatch(threads);
        var go = new CountDownLatch(1);
        var ok = new AtomicInteger(0);
        var conflicts = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            exec.submit(() -> {
                try {
                    byte[] body = bytes("concurrent-different-" + idx);
                    byte[] hash = sha256(body);
                    barrier.countDown();
                    go.await();
                    store.put(payloadId, ct, body, hash);
                    ok.incrementAndGet();
                } catch (PayloadStoreException e) {
                    if ("CONFLICT".equals(e.errorCode())) {
                        conflicts.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            });
        }
        barrier.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(1, ok.get(), "exactly one concurrent put of different content must succeed");
        assertEquals(threads - 1, conflicts.get(), "remaining must get CONFLICT");
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 7: canary 0 in errors, logs, and reports
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(12)
    void canaryNeverInStoreOrErrors() {
        UUID payloadId = UUID.randomUUID();
        byte[] canaryBody = bytes("消息包含-" + CANARY);
        byte[] hash = sha256(canaryBody);

        // The canary message body enters the store (it's test data)
        PayloadPutResult r = store.put(payloadId, "text/plain; charset=UTF-8", canaryBody, hash);
        byte[] got = store.get(r.objectRef(), hash, 1024 * 1024);
        assertArrayEquals(canaryBody, got);

        // But error messages must not contain body text or canary
        try {
            store.put(payloadId, "text/plain", canaryBody, new byte[32]);
            fail("should throw");
        } catch (PayloadStoreException e) {
            String msg = e.getMessage();
            assertNotNull(msg);
            // Error message must not contain the canary
            assertFalse(msg.contains(CANARY),
                    "error message must not leak canary body text");
            // Error message must not contain body bytes as string
            assertFalse(msg.contains("消息包含"),
                    "error message must not leak body text content");
        }
    }

    @Test
    @Order(13)
    void errorMessagesNeverLeakBodyOrRootPath() {
        UUID payloadId = UUID.randomUUID();
        byte[] body = bytes("secret evidence: alice told bob the password is hunter2");

        // Hash mismatch error
        byte[] wrongHash = new byte[32];
        try {
            store.put(payloadId, "text/plain", body, wrongHash);
            fail("should throw");
        } catch (PayloadStoreException e) {
            String msg = e.getMessage();
            assertFalse(msg.contains("hunter2"), "must not leak body");
            assertFalse(msg.contains("alice"), "must not leak body");
            assertFalse(msg.contains("password"), "must not leak body");
            assertFalse(msg.contains("secret evidence"), "must not leak body");
            assertFalse(msg.contains(root.toAbsolutePath().toString()),
                    "must not expose absolute root path");
        }

        // NOT_FOUND error
        try {
            store.get("00/00000000000000000000000000000000.payload", new byte[32], 1024);
            fail("should throw");
        } catch (PayloadStoreException e) {
            String msg = e.getMessage();
            assertFalse(msg.contains(root.toAbsolutePath().toString()),
                    "must not expose absolute root path");
        }
    }
}
