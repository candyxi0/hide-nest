package io.github.candyxi0.hidenest.database.v2.adapter;

import io.github.candyxi0.hidenest.memory.v2.DependencyRead;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Evidence;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Limits;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Relation;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Result;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Revision;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Witness;
import io.github.candyxi0.hidenest.memory.v2.RecordSuccession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/** Bounded, read-only traversal of committed canonical revisions in one PostgreSQL snapshot. */
public final class JdbcDependencyReader implements DependencyRead.Reader {
    private final DataSource dataSource;

    public JdbcDependencyReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public Result read(String worldRef, UUID recordId, Limits limits) {
        Objects.requireNonNull(worldRef);
        Objects.requireNonNull(recordId);
        Objects.requireNonNull(limits);
        long deadline = System.nanoTime() + limits.timeoutMillis() * 1_000_000L;
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Result result = readSnapshot(connection, worldRef, recordId, limits, deadline);
                connection.rollback();
                return result;
            } catch (SQLException | ReadIncomplete failure) {
                connection.rollback();
                return Result.unavailable(DependencyRead.Status.INCOMPLETE, "READ_UNVERIFIED");
            }
        } catch (SQLException failure) {
            return Result.unavailable(DependencyRead.Status.INCOMPLETE, "READ_UNAVAILABLE");
        }
    }

    private static Result readSnapshot(Connection c, String world, UUID recordId, Limits limits, long deadline)
            throws SQLException, ReadIncomplete {
        try (Statement statement = c.createStatement()) {
            statement.execute("SET TRANSACTION READ ONLY");
            statement.execute("SET LOCAL statement_timeout = '" + limits.timeoutMillis() + "ms'");
        }
        String boundWorld;
        OffsetDateTime snapshotAt;
        checkTime(deadline);
        try (PreparedStatement p = c.prepareStatement(
                        "SELECT world_ref,transaction_timestamp() FROM memory.world_binding WHERE singleton=1");
                ResultSet r = p.executeQuery()) {
            if (!r.next()) throw new ReadIncomplete();
            boundWorld = r.getString(1);
            snapshotAt = r.getObject(2, OffsetDateTime.class);
            if (r.next()) throw new ReadIncomplete();
        }
        if (!world.equals(boundWorld))
            return Result.unavailable(DependencyRead.Status.WORLD_MISMATCH, "WORLD_MISMATCH");
        if (!recordExists(c, recordId, deadline))
            return Result.unavailable(DependencyRead.Status.RECORD_MISSING, "RECORD_MISSING");
        UUID rootRevisionId;
        checkTime(deadline);
        try (PreparedStatement p =
                c.prepareStatement("SELECT current_revision_id FROM memory.record WHERE record_id=?")) {
            p.setObject(1, recordId);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new ReadIncomplete();
                rootRevisionId = r.getObject(1, UUID.class);
            }
        }
        Map<UUID, Revision> loaded = new HashMap<>();
        Revision root = loadRevision(c, rootRevisionId, deadline);
        if (!recordId.equals(root.recordId())) throw new ReadIncomplete();
        loaded.put(rootRevisionId, root);
        List<Revision> revisions = new ArrayList<>();
        List<Relation> relations = new ArrayList<>();
        List<Witness> witnesses = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();
        ArrayDeque<UUID> queue = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        Map<UUID, Relation> parent = new HashMap<>();
        queue.add(rootRevisionId);
        visited.add(rootRevisionId);
        while (!queue.isEmpty()) {
            checkTime(deadline);
            UUID id = queue.remove();
            Revision source = loaded.get(id);
            if (source == null) throw new ReadIncomplete();
            revisions.add(source);
            if ("EVENT".equals(source.type()) || "QUOTE".equals(source.type())) {
                List<Evidence> found = loadEvidence(c, id, limits.maxRelations(), deadline);
                if (evidence.size() + found.size() > limits.maxRelations()) throw new ReadIncomplete();
                evidence.addAll(found);
            }
            for (Relation relation : loadRelations(c, id, loaded, limits, deadline)) {
                if (relations.size() >= limits.maxRelations()) throw new ReadIncomplete();
                relations.add(relation);
                if (!"SUPPORT".equals(relation.kind())) continue;
                UUID targetId = relation.targetRevisionId();
                if (visited.add(targetId)) {
                    if (visited.size() > limits.maxRevisions()) throw new ReadIncomplete();
                    parent.put(targetId, relation);
                    queue.add(targetId);
                    Revision target = relation.target();
                    if (!targetId.equals(target.currentRevisionId()) || "SUPERSEDED".equals(target.participation())) {
                        if (witnesses.size() >= limits.maxWitnesses()) throw new ReadIncomplete();
                        witnesses.add(new Witness(path(parent, rootRevisionId, targetId), target));
                    }
                }
            }
        }
        if (!supportAcyclic(visited, relations)) throw new ReadIncomplete();
        return new Result(
                DependencyRead.Status.COMPLETE,
                witnesses.isEmpty()
                        ? DependencyRead.Observation.NO_VERSION_CHANGE_OBSERVED
                        : DependencyRead.Observation.SUPPORT_CHANGED,
                "VERSION_FACTS_ONLY_REEVALUATION_REQUIRED_WHEN_CHANGED",
                root,
                revisions,
                relations,
                witnesses,
                evidence,
                snapshotAt);
    }

    private static boolean recordExists(Connection c, UUID id, long deadline) throws SQLException, ReadIncomplete {
        checkTime(deadline);
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM memory.record WHERE record_id=?")) {
            p.setObject(1, id);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    private static Revision loadRevision(Connection c, UUID id, long deadline) throws SQLException, ReadIncomplete {
        checkTime(deadline);
        String sql = "SELECT v.record_id,v.revision_id,v.revision_no,r.type,r.participation_state,"
                + "r.current_revision_id,v.content,v.subject,v.scope,v.perspective,v.conditions,v.time_context,"
                + "v.uncertainty,s.predecessor_record_id,s.predecessor_revision_id,s.successor_record_id,"
                + "s.successor_revision_id,s.task_id,s.created_at "
                + "FROM memory.revision v JOIN memory.record r ON r.record_id=v.record_id "
                + "JOIN memory.revision current_v ON current_v.revision_id=r.current_revision_id "
                + "AND current_v.record_id=r.record_id "
                + "LEFT JOIN memory.record_succession s ON s.predecessor_record_id=r.record_id "
                + "WHERE v.revision_id=?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setObject(1, id);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) throw new ReadIncomplete();
                RecordSuccession succession = r.getObject(14) == null
                        ? null
                        : new RecordSuccession(
                                r.getObject(14, UUID.class),
                                r.getObject(15, UUID.class),
                                r.getObject(16, UUID.class),
                                r.getObject(17, UUID.class),
                                r.getObject(18, UUID.class),
                                r.getObject(19, OffsetDateTime.class));
                String state = r.getString(5);
                if (("SUPERSEDED".equals(state) != (succession != null))
                        || (succession != null
                                && !succession.predecessorRevisionId().equals(r.getObject(6, UUID.class))))
                    throw new ReadIncomplete();
                Revision result = new Revision(
                        r.getObject(1, UUID.class),
                        r.getObject(2, UUID.class),
                        r.getInt(3),
                        r.getString(4),
                        state,
                        r.getObject(6, UUID.class),
                        r.getString(7),
                        r.getString(8),
                        r.getString(9),
                        r.getString(10),
                        r.getString(11),
                        r.getString(12),
                        r.getString(13),
                        succession);
                if (r.next()) throw new ReadIncomplete();
                return result;
            }
        }
    }

    private static List<Relation> loadRelations(
            Connection c, UUID source, Map<UUID, Revision> loaded, Limits limits, long deadline)
            throws SQLException, ReadIncomplete {
        checkTime(deadline);
        List<Relation> result = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT target_revision_id,relation_kind "
                + "FROM memory.revision_relation WHERE revision_id=? ORDER BY relation_kind DESC,target_revision_id")) {
            p.setObject(1, source);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    if (result.size() >= limits.maxRelations()) throw new ReadIncomplete();
                    UUID targetId = r.getObject(1, UUID.class);
                    Revision target = loaded.get(targetId);
                    if (target == null) {
                        if (loaded.size() >= limits.maxRevisions()) throw new ReadIncomplete();
                        target = loadRevision(c, targetId, deadline);
                        loaded.put(targetId, target);
                    }
                    result.add(new Relation(source, targetId, r.getString(2), target));
                }
            }
        }
        return result;
    }

    private static List<Evidence> loadEvidence(Connection c, UUID id, int maxEvidence, long deadline)
            throws SQLException, ReadIncomplete {
        checkTime(deadline);
        List<Evidence> result = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement("SELECT a.anchor_id,a.unit_id,u.source_id,u.source_ref,"
                + "u.source_version,a.locator,a.frame,a.actor,a.speaking_as "
                + "FROM memory.revision_anchor ra JOIN evidence.source_anchor a ON a.anchor_id=ra.anchor_id "
                + "JOIN evidence.source_unit u ON u.unit_id=a.unit_id "
                + "WHERE ra.revision_id=? ORDER BY a.anchor_id")) {
            p.setObject(1, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    if (result.size() >= maxEvidence) throw new ReadIncomplete();
                    result.add(new Evidence(
                            id,
                            r.getObject(1, UUID.class),
                            r.getObject(2, UUID.class),
                            r.getObject(3, UUID.class),
                            r.getString(4),
                            r.getString(5),
                            r.getString(6),
                            r.getString(7),
                            r.getString(8),
                            r.getString(9)));
                }
            }
        }
        return result;
    }

    private static List<Relation> path(Map<UUID, Relation> parents, UUID root, UUID target) throws ReadIncomplete {
        ArrayDeque<Relation> path = new ArrayDeque<>();
        UUID cursor = target;
        while (!cursor.equals(root)) {
            Relation edge = parents.get(cursor);
            if (edge == null) throw new ReadIncomplete();
            path.addFirst(edge);
            cursor = edge.fromRevisionId();
        }
        return List.copyOf(path);
    }

    private static boolean supportAcyclic(Set<UUID> nodes, List<Relation> relations) {
        Map<UUID, Integer> incoming = new HashMap<>();
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        for (UUID node : nodes) incoming.put(node, 0);
        for (Relation edge : relations) {
            if (!"SUPPORT".equals(edge.kind())) continue;
            incoming.compute(edge.targetRevisionId(), (id, degree) -> degree == null ? 1 : degree + 1);
            outgoing.computeIfAbsent(edge.fromRevisionId(), ignored -> new ArrayList<>())
                    .add(edge.targetRevisionId());
        }
        ArrayDeque<UUID> zero = new ArrayDeque<>();
        incoming.forEach((id, degree) -> {
            if (degree == 0) zero.add(id);
        });
        int removed = 0;
        while (!zero.isEmpty()) {
            UUID node = zero.remove();
            removed++;
            for (UUID target : outgoing.getOrDefault(node, List.of())) {
                int degree = incoming.compute(target, (id, current) -> current - 1);
                if (degree == 0) zero.add(target);
            }
        }
        return removed == nodes.size();
    }

    private static void checkTime(long deadline) throws ReadIncomplete {
        if (System.nanoTime() >= deadline) throw new ReadIncomplete();
    }

    private static final class ReadIncomplete extends Exception {}
}
