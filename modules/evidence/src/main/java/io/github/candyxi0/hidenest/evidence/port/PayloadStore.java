package io.github.candyxi0.hidenest.evidence.port;

import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import java.util.UUID;

/** Vendor-neutral payload storage port. Implementations must be filesystem/cloud agnostic. */
public interface PayloadStore {

    /**
     * Atomically write payload bytes. Same payloadId + same content must be idempotent;
     * same payloadId + different content must conflict.
     */
    PayloadPutResult put(UUID payloadId, String contentType, byte[] bytes, byte[] expectedHash);

    /** Read payload body (defensive copy). Fails closed on hash/size mismatch. */
    byte[] get(String objectRef, byte[] expectedHash, long maxBytes);

    /** Return metadata only (size, hash, contentType), never the body. */
    PayloadHeadResult head(String objectRef);

    /**
     * Conditional delete. Must verify expectedHash before deleting.
     * Repeat delete must return deterministic NOT_FOUND.
     */
    void delete(String objectRef, byte[] expectedHash);
}
