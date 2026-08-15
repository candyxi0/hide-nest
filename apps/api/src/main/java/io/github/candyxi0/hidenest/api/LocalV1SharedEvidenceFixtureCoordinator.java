package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;

/**
 * Synthetic-only shared-evidence fixture bootstrap (Task33B4 §6).
 *
 * <p>The formal single-candidate closeout API cannot produce two memories sharing the same
 * SourceUnit/SourcePayload, so this fixture builds A/B/C through the real S1 governance
 * coordinator and the real PostgreSQL evidence tables, then re-points B's EVIDENCED_BY relation to
 * A's first anchor and precisely removes B's now-orphaned exclusive anchor/unit/payload metadata
 * and payload file. A and B therefore share exactly one SourceUnit and one SourcePayload
 * (objectRef + contentHash); C is completely unrelated. This is a test/QA-only path — it is not
 * registered in any production profile, does not open a pass-through endpoint, and is explicitly
 * not proof of multi-candidate closeout (SHARED_FIXTURE_NOT_PROOF_OF_MULTI_CANDIDATE_CLOSEOUT).</p>
 */
public final class LocalV1SharedEvidenceFixtureCoordinator {

    private static final String MEMORY_TYPE = "Interpretation";

    private final LocalV1S1WindowCloseCoordinator s1;
    private final DSLContext dsl;
    private final PayloadStore payloadStore;
    private final Clock clock;

    public LocalV1SharedEvidenceFixtureCoordinator(
            LocalV1S1WindowCloseCoordinator s1, DSLContext dsl, PayloadStore payloadStore, Clock clock) {
        this.s1 = s1;
        this.dsl = dsl;
        this.payloadStore = payloadStore;
        this.clock = clock;
    }

    public record Fixture(UUID memoryA, UUID memoryB, UUID memoryC) {}

    public Fixture createSharedEvidenceFixture() {
        OffsetDateTime now = OffsetDateTime.now(clock);

        UUID xiaolinA = UUID.randomUUID();
        UUID hideA = UUID.randomUUID();
        UUID unitA1 = UUID.randomUUID();
        UUID unitA2 = UUID.randomUUID();
        UUID anchorA1 = UUID.randomUUID();
        UUID anchorA2 = UUID.randomUUID();
        Built a = createMemory(
                "qa-shared-a",
                "QA-共享原文删除目标\n共享原文验收：删除后当前记忆消失，共享原文仍保留。",
                xiaolinA,
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitA1, hideA, 1L, "qa-shared-a-unit-1", now, "这段原文被两条记忆共同引用。"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitA2, xiaolinA, 2L, "qa-shared-a-unit-2", now, "确认这段原文需要保留。")),
                List.of(
                        anchor(anchorA1, unitA1, 1L),
                        anchor(anchorA2, unitA2, 2L)));

        UUID xiaolinB = UUID.randomUUID();
        UUID unitB1 = UUID.randomUUID();
        UUID anchorB1 = UUID.randomUUID();
        Built b = createMemory(
                "qa-shared-b",
                "QA-共享原文保留目标\n共享原文验收：删除 A 后本条记忆及其证据原文保持可用。",
                xiaolinB,
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        unitB1, xiaolinB, 1L, "qa-shared-b-unit-1", now, "B 的原始证据（将被重指向共享原文）。")),
                List.of(anchor(anchorB1, unitB1, 1L)));

        UUID xiaolinC = UUID.randomUUID();
        UUID unitC1 = UUID.randomUUID();
        UUID anchorC1 = UUID.randomUUID();
        Built c = createMemory(
                "qa-control-c",
                "QA-无关对照样本\n无关对照：与 A、B 不共享任何证据。",
                xiaolinC,
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        unitC1, xiaolinC, 1L, "qa-control-c-unit-1", now, "无关对照样本的原文。")),
                List.of(anchor(anchorC1, unitC1, 1L)));

        repointEvidence(b.revisionId(), anchorA1);
        cleanOrphanedEvidence(anchorB1, unitB1);

        return new Fixture(a.memoryId(), b.memoryId(), c.memoryId());
    }

    private Built createMemory(
            String marker,
            String body,
            UUID perspectiveActorId,
            List<LocalV1S1PrepareRequest.EvidenceMessage> messages,
            List<LocalV1S1PrepareRequest.AnchorInput> anchors) {
        String key = "qa-fixture-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key,
                sha256(key.getBytes(StandardCharsets.UTF_8)),
                perspectiveActorId,
                MEMORY_TYPE,
                body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                messages,
                anchors));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key,
                sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(),
                prepared.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                new byte[32]));
        return new Built(memoryId, confirmed.currentRevisionId(), anchors.get(0).anchorId());
    }

    private void repointEvidence(UUID fromRevisionId, UUID toAnchorId) {
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id = ?", fromRevisionId);
        UUID decisionId = dsl.fetch(
                        "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id = ?",
                        fromRevisionId)
                .get(0).get(0, UUID.class);
        dsl.execute(
                "INSERT INTO memory.memory_relation"
                        + "(relation_id, from_revision_id, relation_type, to_anchor_id, created_by_decision_id, created_at)"
                        + " VALUES (?, ?, 'EVIDENCED_BY', ?, ?, clock_timestamp())",
                UUID.randomUUID(), fromRevisionId, toAnchorId, decisionId);
    }

    private static LocalV1S1PrepareRequest.AnchorInput anchor(UUID anchorId, UUID unitId, long ordinal) {
        return new LocalV1S1PrepareRequest.AnchorInput(
                anchorId, List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                        unitId, 0L, 1L, ordinal)));
    }

    /**
     * Precisely removes B's now-orphaned exclusive anchor/unit/payload metadata (in FK order) and
     * the corresponding payload file, so the shared-evidence fixture leaves no invisible orphans.
     */
    private void cleanOrphanedEvidence(UUID anchorId, UUID unitId) {
        var payloadRow = dsl.fetchOne(
                "SELECT payload_id, object_ref, content_hash FROM evidence.source_payload WHERE source_unit_id = ?",
                unitId);
        UUID payloadId = payloadRow == null ? null : payloadRow.get(0, UUID.class);
        String objectRef = payloadRow == null ? null : payloadRow.get(1, String.class);
        byte[] contentHash = payloadRow == null ? null : payloadRow.get(2, byte[].class);

        if (payloadId != null) {
            dsl.execute("DELETE FROM evidence.source_payload WHERE payload_id = ?", payloadId);
        }
        dsl.execute("DELETE FROM evidence.source_anchor_unit WHERE anchor_id = ?", anchorId);
        dsl.execute("DELETE FROM evidence.source_anchor WHERE anchor_id = ?", anchorId);
        dsl.execute("DELETE FROM evidence.source_unit WHERE source_unit_id = ?", unitId);

        if (objectRef != null && contentHash != null) {
            try {
                payloadStore.delete(objectRef, contentHash);
            } catch (RuntimeException ex) {
                // The payload file may already be absent; the metadata cleanup is the authoritative gate.
            }
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private record Built(UUID memoryId, UUID revisionId, UUID sharedAnchorId) {}
}
