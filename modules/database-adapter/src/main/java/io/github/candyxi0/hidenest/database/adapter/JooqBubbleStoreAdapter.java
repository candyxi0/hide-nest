package io.github.candyxi0.hidenest.database.adapter;

import io.github.candyxi0.hidenest.runtime.domain.BubbleDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.BubbleRoomRevisionLedgerEntry;
import io.github.candyxi0.hidenest.runtime.domain.BubbleTurnReceipt;
import io.github.candyxi0.hidenest.runtime.port.BubbleQueryPort;
import io.github.candyxi0.hidenest.runtime.port.BubbleTransactionPort;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;

/** PostgreSQL implementation of the narrow, body-free Bubble store. */
public final class JooqBubbleStoreAdapter implements BubbleQueryPort, BubbleTransactionPort {

    private static final long TURN_LOCK_SEED = 220_021L;
    private static final long ROOM_LOCK_SEED = 220_022L;

    private final DSLContext dsl;

    public JooqBubbleStoreAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public void lockTurnKey(String turnKey) {
        dsl.fetch(
                "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(?, ?))", turnKey, TURN_LOCK_SEED);
    }

    @Override
    public void lockRoom(String spaceKey, String roomKey) {
        dsl.fetch(
                "SELECT pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(? || chr(31) || ?, ?))",
                spaceKey,
                roomKey,
                ROOM_LOCK_SEED);
    }

    @Override
    public BubbleTurnReceipt findReceiptByTurnKey(String turnKey) {
        var row = dsl.fetchOne(
                "SELECT space_key, room_key, turn_key, request_hash, query_utf8_bytes, "
                        + "result_category, policy_version, min_score, result_manifest_hash, issued_at "
                        + "FROM runtime.bubble_turn_receipt WHERE turn_key = ?",
                turnKey);
        if (row == null) {
            return null;
        }
        return new BubbleTurnReceipt(
                row.get(0, String.class),
                row.get(1, String.class),
                row.get(2, String.class),
                row.get(3, byte[].class),
                row.get(4, Integer.class),
                row.get(5, String.class),
                row.get(6, String.class),
                row.get(7, Double.class),
                row.get(8, byte[].class),
                row.get(9, java.time.OffsetDateTime.class));
    }

    @Override
    public BubbleDeliveryItem findDeliveryItem(String spaceKey, String roomKey, String turnKey) {
        var row = dsl.fetchOne(
                "SELECT space_key, room_key, turn_key, memory_id, memory_revision_id, revision_no, "
                        + "policy_revision_no, score, memory_type, evidence_age_days "
                        + "FROM runtime.bubble_delivery_item "
                        + "WHERE space_key = ? AND room_key = ? AND turn_key = ?",
                spaceKey,
                roomKey,
                turnKey);
        if (row == null) {
            return null;
        }
        return new BubbleDeliveryItem(
                row.get(0, String.class),
                row.get(1, String.class),
                row.get(2, String.class),
                row.get(3, UUID.class),
                row.get(4, UUID.class),
                row.get(5, Long.class),
                row.get(6, Long.class),
                row.get(7, Double.class),
                row.get(8, String.class),
                row.get(9, Integer.class));
    }

    @Override
    public Set<UUID> findDeliveredRevisionIds(String spaceKey, String roomKey) {
        var rows = dsl.fetch(
                "SELECT memory_revision_id FROM runtime.bubble_room_revision_ledger "
                        + "WHERE space_key = ? AND room_key = ? ORDER BY memory_revision_id",
                spaceKey,
                roomKey);
        Set<UUID> result = new LinkedHashSet<>();
        for (var row : rows) {
            result.add(row.get(0, UUID.class));
        }
        return result;
    }

    @Override
    public void insertReceipt(BubbleTurnReceipt receipt) {
        dsl.execute(
                "INSERT INTO runtime.bubble_turn_receipt (space_key, room_key, turn_key, "
                        + "request_hash, query_utf8_bytes, result_category, policy_version, min_score, "
                        + "result_manifest_hash, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz)",
                receipt.spaceKey(),
                receipt.roomKey(),
                receipt.turnKey(),
                receipt.requestHash(),
                receipt.queryUtf8Bytes(),
                receipt.resultCategory(),
                receipt.policyVersion(),
                receipt.minScore(),
                receipt.resultManifestHash(),
                receipt.issuedAt());
    }

    @Override
    public void insertDeliveryItem(BubbleDeliveryItem item) {
        dsl.execute(
                "INSERT INTO runtime.bubble_delivery_item (space_key, room_key, turn_key, memory_id, "
                        + "memory_revision_id, revision_no, policy_revision_no, score, memory_type, "
                        + "evidence_age_days) VALUES (?, ?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)",
                item.spaceKey(),
                item.roomKey(),
                item.turnKey(),
                item.memoryId(),
                item.memoryRevisionId(),
                item.revisionNo(),
                item.policyRevisionNo(),
                item.score(),
                item.memoryType(),
                item.evidenceAgeDays());
    }

    @Override
    public void insertLedgerEntry(BubbleRoomRevisionLedgerEntry entry) {
        dsl.execute(
                "INSERT INTO runtime.bubble_room_revision_ledger (space_key, room_key, "
                        + "memory_revision_id, turn_key, delivered_at) VALUES (?, ?, ?::uuid, ?, ?::timestamptz)",
                entry.spaceKey(),
                entry.roomKey(),
                entry.memoryRevisionId(),
                entry.turnKey(),
                entry.deliveredAt());
    }

    @Override
    public void purgeRoom(String spaceKey, String roomKey) {
        dsl.fetch("SELECT * FROM runtime.purge_bubble_room(?, ?)", spaceKey, roomKey);
    }
}
