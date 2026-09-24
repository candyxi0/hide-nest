package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.EventKind;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Limits;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Material;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.MemoryType;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Reason;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.RelatedRevisionMaterial;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Result;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.RevisionRef;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Reads one event and its formal predecessor in a single read-only PostgreSQL snapshot. */
public final class JdbcProjectionMaterialReader implements ProjectionMaterialRead.Reader {
    private final DataSource dataSource;

    public JdbcProjectionMaterialReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public Result read(String worldRef, UUID eventId, Limits limits) {
        Objects.requireNonNull(worldRef);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(limits);
        long deadline = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        try (Connection c = dataSource.getConnection()) {
            c.setReadOnly(true);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                Result result = readSnapshot(c, worldRef, eventId, limits, deadline);
                c.rollback();
                return result;
            } catch (Incomplete failure) {
                c.rollback();
                return Result.incomplete(failure.reason);
            } catch (SQLException failure) {
                c.rollback();
                return Result.incomplete(sqlReason(failure));
            }
        } catch (SQLException failure) {
            return Result.incomplete(sqlReason(failure));
        }
    }

    private static Result readSnapshot(Connection c, String world, UUID eventId, Limits limits, long deadline)
            throws SQLException, Incomplete {
        try (Statement s = c.createStatement()) {
            s.execute("SET TRANSACTION READ ONLY");
            s.execute("SET LOCAL statement_timeout = '" + limits.timeoutMillis() + "ms'");
        }
        Budget budget = new Budget(limits.maxQueries(), deadline);
        budget.use();
        try (PreparedStatement p = c.prepareStatement("SELECT world_ref FROM memory.world_binding WHERE singleton=1");
                ResultSet r = p.executeQuery()) {
            if (!r.next() || !world.equals(r.getString(1))) return Result.incomplete(Reason.WORLD_MISMATCH);
            if (r.next()) return Result.incomplete(Reason.WORLD_MISMATCH);
        }
        Event event = loadEvent(c, eventId, budget);
        if (event == null) return Result.incomplete(Reason.EVENT_MISSING);
        Revision primary = loadRevision(c, event.revisionId, budget, Reason.EVENT_INCONSISTENT);
        if (!event.recordId.equals(primary.recordId)) throw new Incomplete(Reason.EVENT_INCONSISTENT);
        Revision predecessor = null;
        if (event.kind == EventKind.MEMORY_CREATED) {
            if (event.predecessorRecordId != null || event.previousRevisionId != null || primary.revisionNo != 1)
                throw new Incomplete(Reason.EVENT_INCONSISTENT);
        } else {
            if (event.predecessorRecordId == null || event.previousRevisionId == null)
                throw new Incomplete(Reason.PREDECESSOR_MISSING);
            predecessor = loadRevision(c, event.previousRevisionId, budget, Reason.PREDECESSOR_MISSING);
            if (!event.predecessorRecordId.equals(predecessor.recordId)
                    || event.previousRevisionId.equals(event.revisionId))
                throw new Incomplete(Reason.EVENT_INCONSISTENT);
            if (event.kind == EventKind.MEMORY_REVISED
                    && (!event.recordId.equals(predecessor.recordId)
                            || primary.revisionNo != predecessor.revisionNo + 1))
                throw new Incomplete(Reason.EVENT_INCONSISTENT);
            if (event.kind == EventKind.MEMORY_SUPERSEDED
                    && (event.recordId.equals(predecessor.recordId) || primary.revisionNo != 1))
                throw new Incomplete(Reason.EVENT_INCONSISTENT);
        }
        Relations primaryRelations = loadRelations(c, primary.revisionId, budget);
        List<RelatedRevisionMaterial> related = List.of();
        RevisionRef previousRef = null;
        if (predecessor != null) {
            Relations oldRelations = loadRelations(c, predecessor.revisionId, budget);
            related = List.of(predecessor.related(oldRelations));
            previousRef = new RevisionRef(recordRef(predecessor.recordId), revisionRef(predecessor.revisionId));
        }
        return Result.complete(primary.material(event.kind, primaryRelations, previousRef, related));
    }

    private static Event loadEvent(Connection c, UUID eventId, Budget budget) throws SQLException, Incomplete {
        budget.use();
        try (PreparedStatement p = c.prepareStatement("SELECT event_kind,record_id,revision_id,"
                + "predecessor_record_id,previous_revision_id FROM memory.projection_outbox WHERE event_id=?")) {
            p.setObject(1, eventId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return null;
                EventKind kind;
                try {
                    kind = EventKind.valueOf(r.getString(1));
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new Incomplete(Reason.EVENT_INCONSISTENT);
                }
                Event event = new Event(
                        kind,
                        r.getObject(2, UUID.class),
                        r.getObject(3, UUID.class),
                        r.getObject(4, UUID.class),
                        r.getObject(5, UUID.class));
                if (r.next()) throw new Incomplete(Reason.EVENT_INCONSISTENT);
                return event;
            }
        }
    }

    private static Revision loadRevision(Connection c, UUID revisionId, Budget budget, Reason missing)
            throws SQLException, Incomplete {
        budget.use();
        try (PreparedStatement p = c.prepareStatement("SELECT v.record_id,v.revision_id,v.revision_no,r.type,"
                + "v.content,v.subject,v.scope,v.perspective,v.conditions,v.time_context,v.uncertainty "
                + "FROM memory.revision v JOIN memory.record r ON r.record_id=v.record_id "
                + "WHERE v.revision_id=?")) {
            p.setObject(1, revisionId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new Incomplete(missing);
                MemoryType type;
                try {
                    type = MemoryType.valueOf(r.getString(4));
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new Incomplete(Reason.MATERIAL_OUT_OF_BOUNDS);
                }
                Revision revision = new Revision(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getInt(3),
                        type,
                        r.getString(5),
                        r.getString(6),
                        r.getString(7),
                        r.getString(8),
                        r.getString(9),
                        r.getString(10),
                        r.getString(11));
                if (r.next()) throw new Incomplete(Reason.EVENT_INCONSISTENT);
                if (revision.revisionNo < 1
                        || !required(revision.content, 16384)
                        || !required(revision.subject, 512)
                        || !required(revision.scope, 512)
                        || !required(revision.perspective, 512)
                        || !optional(revision.conditions, 2048)
                        || !optional(revision.timeContext, 2048)
                        || !optional(revision.uncertainty, 2048)) throw new Incomplete(Reason.MATERIAL_OUT_OF_BOUNDS);
                return revision;
            }
        }
    }

    private static Relations loadRelations(Connection c, UUID revisionId, Budget budget)
            throws SQLException, Incomplete {
        budget.use();
        List<String> support = new ArrayList<>();
        List<String> counter = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        try (PreparedStatement p = c.prepareStatement("SELECT rr.target_revision_id,rr.relation_kind,"
                + "v.record_id,r.record_id FROM memory.revision_relation rr "
                + "LEFT JOIN memory.revision v ON v.revision_id=rr.target_revision_id "
                + "LEFT JOIN memory.record r ON r.record_id=v.record_id "
                + "WHERE rr.revision_id=? ORDER BY rr.target_revision_id,rr.relation_kind")) {
            p.setObject(1, revisionId);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    if (support.size() + counter.size() >= 100) throw new Incomplete(Reason.RELATIONS_INVALID);
                    UUID target = r.getObject(1, UUID.class);
                    UUID owner = r.getObject(3, UUID.class);
                    if (target == null
                            || target.equals(revisionId)
                            || owner == null
                            || !owner.equals(r.getObject(4, UUID.class))
                            || !seen.add(target)) throw new Incomplete(Reason.RELATIONS_INVALID);
                    String ref = revisionRef(target);
                    switch (r.getString(2)) {
                        case "SUPPORT" -> support.add(ref);
                        case "COUNTER" -> counter.add(ref);
                        default -> throw new Incomplete(Reason.RELATIONS_INVALID);
                    }
                }
            }
        }
        support.sort(String::compareTo);
        counter.sort(String::compareTo);
        return new Relations(List.copyOf(support), List.copyOf(counter));
    }

    private static boolean required(String value, int maximum) {
        return value != null && !value.isEmpty() && value.codePointCount(0, value.length()) <= maximum;
    }

    private static boolean optional(String value, int maximum) {
        return value == null || value.codePointCount(0, value.length()) <= maximum;
    }

    private static String recordRef(UUID id) {
        return "record:" + id;
    }

    private static String revisionRef(UUID id) {
        return "revision:" + id;
    }

    private static Reason sqlReason(SQLException failure) {
        return "57014".equals(failure.getSQLState()) ? Reason.READ_TIMEOUT : Reason.READ_FAILURE;
    }

    private record Event(
            EventKind kind, UUID recordId, UUID revisionId, UUID predecessorRecordId, UUID previousRevisionId) {}

    private record Relations(List<String> support, List<String> counter) {}

    private record Revision(
            UUID recordId,
            UUID revisionId,
            int revisionNo,
            MemoryType type,
            String content,
            String subject,
            String scope,
            String perspective,
            String conditions,
            String timeContext,
            String uncertainty) {
        private RelatedRevisionMaterial related(Relations relations) {
            return new RelatedRevisionMaterial(
                    recordRef(recordId),
                    revisionRef(revisionId),
                    type,
                    content,
                    subject,
                    scope,
                    perspective,
                    conditions,
                    timeContext,
                    uncertainty,
                    relations.support,
                    relations.counter);
        }

        private Material material(
                EventKind kind, Relations relations, RevisionRef predecessor, List<RelatedRevisionMaterial> related) {
            return new Material(
                    kind,
                    recordRef(recordId),
                    revisionRef(revisionId),
                    revisionNo,
                    type,
                    content,
                    subject,
                    scope,
                    perspective,
                    conditions,
                    timeContext,
                    uncertainty,
                    relations.support,
                    relations.counter,
                    predecessor,
                    List.of(),
                    related);
        }
    }

    private static final class Budget {
        private int remaining;
        private final long deadline;

        private Budget(int maxQueries, long deadline) {
            remaining = maxQueries;
            this.deadline = deadline;
        }

        private void use() throws Incomplete {
            if (remaining-- <= 0 || System.nanoTime() >= deadline) throw new Incomplete(Reason.BUDGET_EXHAUSTED);
        }
    }

    private static final class Incomplete extends Exception {
        private final Reason reason;

        private Incomplete(Reason reason) {
            this.reason = reason;
        }
    }
}
