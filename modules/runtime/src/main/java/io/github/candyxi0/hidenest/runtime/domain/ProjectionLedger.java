package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Internal, persisted single-event projection delivery facts. No Worker JSON is parsed here. */
public final class ProjectionLedger {
    private ProjectionLedger() {}

    public record SchemaManifest(String schemaRef, String version) {}

    public record EmbeddingManifest(String modelRef, int dimensions, String version) {}

    public record Target(
            String worldRef, int projectionGeneration, SchemaManifest schema, EmbeddingManifest embedding) {}

    public record Event(UUID eventId, UUID expectedRevisionId, String state) {}

    public record Binding(
            UUID taskId,
            String worldRef,
            UUID eventId,
            int projectionGeneration,
            int attemptGeneration,
            SchemaManifest schema,
            EmbeddingManifest embedding,
            String materialDigest,
            UUID expectedRevisionId) {}

    public record Claim(UUID attemptId, String ownerRef, OffsetDateTime leaseUntil, Binding binding) {}

    public enum ResultKind {
        APPLIED,
        TEMPORARY_FAILURE,
        PERMANENT_FAILURE
    }

    /** receiptDigest identifies the complete synthetic result; A1-B will validate real JSON. */
    public record Result(
            UUID attemptId,
            String ownerRef,
            Binding binding,
            ResultKind kind,
            String receiptDigest,
            UUID appliedRevisionId,
            String failureCode) {}

    public enum Settlement {
        APPLIED,
        RETRY_WAIT,
        ATTENTION_REQUIRED,
        REJECTED
    }

    public record Attention(String worldRef, int projectionGeneration, UUID eventId, String failureCode) {}
}
