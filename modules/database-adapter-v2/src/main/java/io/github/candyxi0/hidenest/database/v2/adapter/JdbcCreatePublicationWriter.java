package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.application.v2.CreatePublication;
import io.github.candyxi0.hidenest.application.v2.CreatePublication.Prepared;
import io.github.candyxi0.hidenest.application.v2.CreatePublication.Verified;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.CreateItem;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.RevisionRef;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Joins the S02-B JDBC transaction. It neither opens a connection nor rereads a source. */
public final class JdbcCreatePublicationWriter implements CreatePublication.Writer {
    public record PublishedItem(int index, UUID recordId, UUID revisionId, String action, UUID expectedRevisionId) {}

    public record Receipt(UUID taskId, UUID attemptId, byte[] requestHash, List<PublishedItem> items) {
        public Receipt {
            requestHash = requestHash.clone();
            items = List.copyOf(items);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }
    }

    /** Read a committed outcome after a lost response, using the original key and hash. */
    public Optional<Receipt> findReceipt(Connection c, UUID key, byte[] expectedHash) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "SELECT task_id,attempt_id,request_hash " + "FROM memory.create_receipt WHERE idempotency_key=?")) {
            bind(p, key);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                byte[] actual = r.getBytes("request_hash");
                if (!Arrays.equals(actual, expectedHash)) throw new SQLException("IDEMPOTENCY_CONFLICT");
                List<PublishedItem> items = new ArrayList<>();
                try (PreparedStatement q =
                        c.prepareStatement("SELECT item_index,record_id,revision_id,action,expected_revision_id "
                                + "FROM memory.create_receipt_item WHERE idempotency_key=? ORDER BY item_index")) {
                    bind(q, key);
                    try (ResultSet rows = q.executeQuery()) {
                        while (rows.next())
                            items.add(new PublishedItem(
                                    rows.getInt(1),
                                    rows.getObject(2, UUID.class),
                                    rows.getObject(3, UUID.class),
                                    rows.getString(4),
                                    rows.getObject(5, UUID.class)));
                    }
                }
                return Optional.of(new Receipt(
                        r.getObject("task_id", UUID.class), r.getObject("attempt_id", UUID.class), actual, items));
            }
        }
    }

    @Override
    public void commit(Connection c, FormationSettlementCandidate candidate, Prepared request) throws SQLException {
        if (!Arrays.equals(candidate.resultHash(), request.hash())
                || !candidate.sourceId().equals(request.writeSet().sourceId())
                || !candidate
                        .toInclusive()
                        .sourceVersion()
                        .equals(request.writeSet().sourceVersion()))
            throw new SQLException("RESULT_HASH_OR_SOURCE_MISMATCH");
        String world = string(c, "SELECT world_ref FROM memory.world_binding WHERE singleton=1 FOR SHARE");
        if (!request.writeSet().worldRef().equals(world)) throw new SQLException("WORLD_NOT_BOUND_OR_MISMATCH");
        String source = string(
                c,
                "SELECT external_ref FROM runtime.source_registration WHERE source_id=? FOR SHARE",
                candidate.sourceId());
        if (!request.writeSet().sourceRef().equals(source)) throw new SQLException("SOURCE_BINDING_MISMATCH");
        String gate = string(
                c,
                "SELECT state FROM runtime.source_write_gate " + "WHERE source_id=? AND source_version=? FOR SHARE",
                candidate.sourceId(),
                request.writeSet().sourceVersion());
        if (!"ALLOWED".equals(gate)) throw new SQLException("SOURCE_WRITE_NOT_ALLOWED");
        byte[] prior = bytes(
                c,
                "SELECT request_hash FROM memory.create_receipt WHERE idempotency_key=? FOR SHARE",
                candidate.idempotencyKey());
        if (prior != null) {
            if (!Arrays.equals(prior, request.hash())) throw new SQLException("IDEMPOTENCY_CONFLICT");
            UUID priorTask = uuid(
                    c, "SELECT task_id FROM memory.create_receipt WHERE idempotency_key=?", candidate.idempotencyKey());
            if (!candidate.taskId().equals(priorTask)) throw new SQLException("IDEMPOTENCY_TASK_CONFLICT");
            return;
        }
        if (uuid(c, "SELECT idempotency_key FROM memory.create_receipt WHERE task_id=?", candidate.taskId()) != null)
            throw new SQLException("TASK_ALREADY_PUBLISHED");
        UUID unit = UUID.randomUUID();
        execute(
                c,
                "INSERT INTO evidence.source_unit(unit_id,source_id,source_ref,source_version) "
                        + "VALUES(?,?,?,?) ON CONFLICT(source_id,source_ref,source_version) DO NOTHING",
                unit,
                candidate.sourceId(),
                request.writeSet().sourceRef(),
                request.writeSet().sourceVersion());
        unit = uuid(
                c,
                "SELECT unit_id FROM evidence.source_unit WHERE source_id=? AND source_ref=? AND source_version=?",
                candidate.sourceId(),
                request.writeSet().sourceRef(),
                request.writeSet().sourceVersion());
        execute(
                c,
                "INSERT INTO memory.create_receipt(idempotency_key,request_hash,task_id,attempt_id,created_at) "
                        + "VALUES(?,?,?,?,?)",
                candidate.idempotencyKey(),
                request.hash(),
                candidate.taskId(),
                candidate.attemptId(),
                OffsetDateTime.now(ZoneOffset.UTC));
        for (int index = 0; index < request.writeSet().items().size(); index++) {
            CreateItem item = request.writeSet().items().get(index);
            boolean revise = "REVISE".equals(item.action());
            UUID recordId = revise ? item.expectedCurrent().recordId() : UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            int revisionNo = 1;
            if (revise) {
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT type,current_revision_id,participation_state FROM memory.record WHERE record_id=? FOR UPDATE")) {
                    bind(p, recordId);
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()
                                || !"ACTIVE".equals(r.getString("participation_state"))
                                || !item.type().name().equals(r.getString("type")))
                            throw new SQLException("REVISE_TARGET_INVALID");
                        if (!item.expectedCurrent().revisionId().equals(r.getObject("current_revision_id", UUID.class)))
                            throw new SQLException("CURRENT_CONFLICT");
                    }
                }
                try (PreparedStatement p = c.prepareStatement(
                        "SELECT revision_no FROM memory.revision WHERE record_id=? AND revision_id=?")) {
                    bind(p, recordId, item.expectedCurrent().revisionId());
                    try (ResultSet r = p.executeQuery()) {
                        if (!r.next()) throw new SQLException("CURRENT_CONFLICT");
                        revisionNo = Math.addExact(r.getInt(1), 1);
                    }
                }
            }
            for (Verified anchor : request.anchors().get(index)) {
                if (anchor.source().frame() != null
                        && !item.scope().equals(anchor.source().frame()))
                    throw new SQLException("FRAME_SCOPE_MISMATCH");
            }
            if (!revise)
                execute(
                        c,
                        "INSERT INTO memory.record(record_id,type,current_revision_id) VALUES(?,?,?)",
                        recordId,
                        item.type().name(),
                        revisionId);
            execute(
                    c,
                    "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                            + "perspective,conditions,time_context,uncertainty,formation_ref,task_id,created_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    revisionId,
                    recordId,
                    revisionNo,
                    item.content(),
                    item.subject(),
                    item.scope(),
                    item.perspective(),
                    item.conditions(),
                    item.timeContext(),
                    item.uncertainty(),
                    item.formationRef(),
                    candidate.taskId(),
                    OffsetDateTime.now(ZoneOffset.UTC));
            for (Verified verified : request.anchors().get(index)) {
                UUID anchorId = UUID.randomUUID();
                execute(
                        c,
                        "INSERT INTO evidence.source_anchor(anchor_id,unit_id,locator,exact_text,text_hash,frame,actor,speaking_as) "
                                + "VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(unit_id,locator) DO NOTHING",
                        anchorId,
                        unit,
                        verified.source().locator(),
                        verified.source().exactText(),
                        verified.textHash(),
                        verified.source().frame(),
                        verified.source().actor(),
                        verified.source().speakingAs());
                try (PreparedStatement ps =
                        c.prepareStatement("SELECT anchor_id,exact_text,text_hash,frame,actor,speaking_as "
                                + "FROM evidence.source_anchor WHERE unit_id=? AND locator=?")) {
                    bind(ps, unit, verified.source().locator());
                    try (ResultSet r = ps.executeQuery()) {
                        if (!r.next()
                                || !verified.source().exactText().equals(r.getString("exact_text"))
                                || !Arrays.equals(verified.textHash(), r.getBytes("text_hash"))
                                || !java.util.Objects.equals(verified.source().frame(), r.getString("frame"))
                                || !java.util.Objects.equals(verified.source().actor(), r.getString("actor"))
                                || !java.util.Objects.equals(
                                        verified.source().speakingAs(), r.getString("speaking_as")))
                            throw new SQLException("ANCHOR_IDENTITY_CONFLICT");
                        anchorId = r.getObject("anchor_id", UUID.class);
                    }
                }
                execute(
                        c,
                        "INSERT INTO memory.revision_anchor(revision_id,anchor_id,relation_kind) "
                                + "VALUES(?,?,'SUPPORT') ON CONFLICT DO NOTHING",
                        revisionId,
                        anchorId);
            }
            for (RevisionRef relation : item.relations()) {
                if (revisionId.equals(relation.revisionId())
                        || !exists(c, "SELECT 1 FROM memory.revision WHERE revision_id=?", relation.revisionId()))
                    throw new SQLException("INVALID_REVISION_RELATION");
                if (relation.kind() == io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.RelationKind.SUPPORT
                        && (!hasAnchorPath(c, relation.revisionId()) || hasSupportCycle(c, relation.revisionId())))
                    throw new SQLException("SUPPORT_PATH_REQUIRED");
                if (exists(
                        c,
                        "SELECT 1 FROM memory.revision_relation WHERE revision_id=? AND target_revision_id=?",
                        revisionId,
                        relation.revisionId())) throw new SQLException("DUPLICATE_OR_CONFLICTING_RELATION");
                execute(
                        c,
                        "INSERT INTO memory.revision_relation(revision_id,target_revision_id,relation_kind) "
                                + "VALUES(?,?,?)",
                        revisionId,
                        relation.revisionId(),
                        relation.kind().name());
            }
            if (item.type() == io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.MemoryType.UNDERSTANDING
                    && !hasAnchorPath(c, revisionId)) throw new SQLException("SUPPORT_PATH_REQUIRED");
            if (revise) {
                int switched;
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE memory.record SET current_revision_id=? WHERE record_id=? AND current_revision_id=?")) {
                    bind(p, revisionId, recordId, item.expectedCurrent().revisionId());
                    switched = p.executeUpdate();
                }
                if (switched != 1) throw new SQLException("CURRENT_CONFLICT");
            }
            execute(
                    c,
                    "INSERT INTO memory.create_receipt_item(idempotency_key,item_index,record_id,revision_id,action,expected_revision_id) "
                            + "VALUES(?,?,?,?,?,?)",
                    candidate.idempotencyKey(),
                    index,
                    recordId,
                    revisionId,
                    item.action(),
                    revise ? item.expectedCurrent().revisionId() : null);
            execute(
                    c,
                    "INSERT INTO memory.projection_outbox(event_id,event_kind,record_id,revision_id,request_hash,created_at,previous_revision_id) "
                            + "VALUES(?,?,?,?,?,?,?)",
                    UUID.randomUUID(),
                    revise ? "MEMORY_REVISED" : "MEMORY_CREATED",
                    recordId,
                    revisionId,
                    request.hash(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    revise ? item.expectedCurrent().revisionId() : null);
        }
    }

    private static boolean hasAnchorPath(Connection c, UUID revision) throws SQLException {
        return exists(
                c,
                "WITH RECURSIVE reach(id) AS (SELECT ?::uuid UNION SELECT r.target_revision_id "
                        + "FROM memory.revision_relation r JOIN reach x ON x.id=r.revision_id "
                        + "WHERE r.relation_kind='SUPPORT') SELECT 1 FROM reach x "
                        + "JOIN memory.revision_anchor a ON a.revision_id=x.id LIMIT 1",
                revision);
    }

    private static boolean hasSupportCycle(Connection c, UUID revision) throws SQLException {
        return exists(
                c,
                "WITH RECURSIVE reach(id,path,cycle) AS ("
                        + "SELECT ?::uuid,ARRAY[?::uuid],false UNION ALL "
                        + "SELECT r.target_revision_id,x.path || r.target_revision_id,"
                        + "r.target_revision_id=ANY(x.path) FROM memory.revision_relation r "
                        + "JOIN reach x ON x.id=r.revision_id WHERE r.relation_kind='SUPPORT' AND NOT x.cycle) "
                        + "SELECT 1 FROM reach WHERE cycle LIMIT 1",
                revision,
                revision);
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            p.executeUpdate();
        }
    }

    private static boolean exists(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    private static String string(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getString(1) : null;
            }
        }
    }

    private static byte[] bytes(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getBytes(1) : null;
            }
        }
    }

    private static UUID uuid(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getObject(1, UUID.class) : null;
            }
        }
    }

    private static void bind(PreparedStatement p, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object v = args[i];
            if (v == null) p.setNull(i + 1, Types.OTHER);
            else if (v instanceof UUID id) p.setObject(i + 1, id);
            else if (v instanceof byte[] bytes) p.setBytes(i + 1, bytes);
            else p.setObject(i + 1, v);
        }
    }
}
