package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.v2.adapter.JdbcProjectionMaterialReader;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.EventKind;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Limits;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Reason;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Result;
import io.github.candyxi0.hidenest.memory.v2.ProjectionMaterialRead.Status;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** B1 counterexamples use synthetic committed rows in real PostgreSQL 18. */
class NestV2ProjectionMaterialReadTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String WORLD = "world:projection-material";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-24T00:00:00Z");
    private static PostgreSQLContainer<?> postgres;
    private static JdbcProjectionMaterialReader reader;
    private static UUID task;
    private static UUID sourceId;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nest_v2_material")
                .withUsername("hide_nest_migrator")
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection c = connection()) {
            c.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            c.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(
                7,
                Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .defaultSchema("public")
                        .locations("classpath:db/v2/migration")
                        .cleanDisabled(true)
                        .load()
                        .migrate()
                        .migrationsExecuted);
        reader = new JdbcProjectionMaterialReader(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void fixture() throws Exception {
        try (Connection c = connection()) {
            execute(
                    c,
                    "TRUNCATE runtime.projection_attempt,runtime.projection_delivery,runtime.projection_target,"
                            + "memory.projection_outbox,memory.revision,memory.record,runtime.formation_task,"
                            + "runtime.source_progress,runtime.source_registration,memory.world_binding CASCADE");
            UUID source = UUID.randomUUID();
            sourceId = source;
            task = UUID.randomUUID();
            execute(c, "INSERT INTO memory.world_binding(singleton,world_ref,bound_at) VALUES (1,?,?)", WORLD, NOW);
            execute(
                    c,
                    "INSERT INTO runtime.source_registration(source_id,source_kind,platform,external_ref,registered_at)"
                            + " VALUES (?,'SYNTHETIC','B1',?,?)",
                    source,
                    source.toString(),
                    NOW);
            execute(
                    c,
                    "INSERT INTO runtime.source_progress(source_id,last_notification_sequence,"
                            + "last_notification_hash,discovered_sequence,discovered_cursor,discovered_source_version,"
                            + "read_kind,read_ref,read_version,last_real_change_at,quiet_until,updated_at)"
                            + " VALUES (?,1,decode(repeat('00',32),'hex'),1,'cursor','v1',"
                            + "'STABLE_REREAD','synthetic','v1',?,?,?)",
                    source,
                    NOW,
                    NOW.plusMinutes(90),
                    NOW);
            execute(
                    c,
                    "INSERT INTO runtime.formation_task(task_id,source_id,state,to_sequence,to_cursor,"
                            + "to_source_version,read_kind,read_ref,read_version,ready_at,created_at,updated_at)"
                            + " VALUES (?,?,'PENDING',1,'cursor','v1','STABLE_REREAD','synthetic','v1',?,?,?)",
                    task,
                    source,
                    NOW,
                    NOW,
                    NOW);
        }
    }

    @Test
    void historicalEventsKeepTheirOwnBodiesPredecessorsAndEmptySuccessors() throws Exception {
        UUID record = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID successor = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID created = UUID.randomUUID();
        UUID revised = UUID.randomUUID();
        UUID superseded = UUID.randomUUID();
        Identity unrelated = create("unrelated canonical revision");
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertRecord(c, record, first);
            insertRevision(c, record, first, 1, "first");
            relation(c, first, unrelated.revision, "SUPPORT");
            insertEvent(c, created, "MEMORY_CREATED", record, first, null, null);
            c.commit();
        }
        Result before = read(created);
        assertEquals(Status.COMPLETE, before.status());
        assertEquals("first", before.material().content());
        assertNull(before.material().predecessor());
        assertTrue(before.material().relatedRevisionMaterials().isEmpty());
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertRevision(c, record, second, 2, "second");
            execute(c, "UPDATE memory.record SET current_revision_id=? WHERE record_id=?", second, record);
            insertEvent(c, revised, "MEMORY_REVISED", record, second, record, first);
            c.commit();
        }
        assertEquals("first", read(created).material().content());
        Result reviseMaterial = read(revised);
        assertEquals(EventKind.MEMORY_REVISED, reviseMaterial.material().eventKind());
        assertEquals("second", reviseMaterial.material().content());
        assertEquals(
                "first",
                reviseMaterial.material().relatedRevisionMaterials().getFirst().content());
        assertEquals(
                List.of("revision:" + unrelated.revision),
                reviseMaterial.material().relatedRevisionMaterials().getFirst().supportingRevisionRefs());
        assertEquals(1, reviseMaterial.material().relatedRevisionMaterials().size());
        assertEquals(
                "revision:" + first, reviseMaterial.material().predecessor().revisionRef());
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertRecord(c, successor, third);
            insertRevision(c, successor, third, 1, "third");
            insertEvent(c, superseded, "MEMORY_SUPERSEDED", successor, third, record, second);
            execute(c, "UPDATE memory.record SET participation_state='SUPERSEDED' WHERE record_id=?", record);
            execute(
                    c,
                    "INSERT INTO memory.record_succession(predecessor_record_id,predecessor_revision_id,"
                            + "successor_record_id,successor_revision_id,task_id,created_at) VALUES (?,?,?,?,?,?)",
                    record,
                    second,
                    successor,
                    third,
                    task,
                    NOW);
            c.commit();
        }
        Result after = read(created);
        assertEquals(before.material(), after.material());
        assertTrue(after.material().successorRefs().isEmpty());
        assertEquals(
                "second",
                read(superseded)
                        .material()
                        .relatedRevisionMaterials()
                        .getFirst()
                        .content());
        assertTrue(read(superseded)
                .material()
                .relatedRevisionMaterials()
                .getFirst()
                .supportingRevisionRefs()
                .isEmpty());
        assertEquals(
                "revision:" + second, read(superseded).material().predecessor().revisionRef());
        assertTrue(read(revised).material().successorRefs().isEmpty());
    }

    @Test
    void allRelationsAreReturnedOrNothingIsReturned() throws Exception {
        Identity source = create("owner");
        List<Identity> targets = new ArrayList<>();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            for (int i = 0; i < 101; i++) {
                Identity target = insertIdentity(c, "target-" + i);
                targets.add(target);
                if (i < 100) relation(c, source.revision, target.revision, i % 2 == 0 ? "SUPPORT" : "COUNTER");
            }
            c.commit();
        }
        Result full = read(source.event);
        assertEquals(Status.COMPLETE, full.status());
        assertEquals(50, full.material().supportingRevisionRefs().size());
        assertEquals(50, full.material().counterRevisionRefs().size());
        assertEquals(
                full.material().supportingRevisionRefs().stream().sorted().toList(),
                full.material().supportingRevisionRefs());
        try (Connection c = connection()) {
            relation(c, source.revision, targets.get(100).revision, "SUPPORT");
        }
        incomplete(source.event, Reason.RELATIONS_INVALID);
        try (Connection c = connection()) {
            execute(
                    c,
                    "DELETE FROM memory.revision_relation WHERE revision_id=? AND target_revision_id=?",
                    source.revision,
                    targets.get(100).revision);
            relation(c, source.revision, targets.getFirst().revision, "COUNTER");
        }
        incomplete(source.event, Reason.RELATIONS_INVALID);
    }

    @Test
    void uncommittedEventAndConcurrentRevisionUseCommittedSnapshot() throws Exception {
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        try (Connection writer = connection()) {
            writer.setAutoCommit(false);
            insertRecord(writer, record, revision);
            insertRevision(writer, record, revision, 1, "uncommitted");
            insertEvent(writer, event, "MEMORY_CREATED", record, revision, null, null);
            incomplete(event, Reason.EVENT_MISSING);
            writer.commit();
        }
        assertEquals("uncommitted", read(event).material().content());
        CountDownLatch opened = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        UUID revisedEvent = UUID.randomUUID();
        UUID supersededEvent = UUID.randomUUID();
        try (var pool = Executors.newSingleThreadExecutor()) {
            var future = pool.submit(() -> {
                try (Connection c = connection()) {
                    c.setAutoCommit(false);
                    UUID next = UUID.randomUUID();
                    UUID successorRecord = UUID.randomUUID();
                    UUID successorRevision = UUID.randomUUID();
                    insertRevision(c, record, next, 2, "concurrent");
                    execute(c, "UPDATE memory.record SET current_revision_id=? WHERE record_id=?", next, record);
                    insertEvent(c, revisedEvent, "MEMORY_REVISED", record, next, record, revision);
                    insertRecord(c, successorRecord, successorRevision);
                    insertRevision(c, successorRecord, successorRevision, 1, "concurrent successor");
                    insertEvent(
                            c, supersededEvent, "MEMORY_SUPERSEDED", successorRecord, successorRevision, record, next);
                    execute(c, "UPDATE memory.record SET participation_state='SUPERSEDED' WHERE record_id=?", record);
                    execute(
                            c,
                            "INSERT INTO memory.record_succession(predecessor_record_id,predecessor_revision_id,"
                                    + "successor_record_id,successor_revision_id,task_id,created_at) VALUES (?,?,?,?,?,?)",
                            record,
                            next,
                            successorRecord,
                            successorRevision,
                            task,
                            NOW);
                    opened.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timed out");
                    c.commit();
                }
                return true;
            });
            assertTrue(opened.await(5, TimeUnit.SECONDS));
            assertEquals("uncommitted", read(event).material().content());
            incomplete(revisedEvent, Reason.EVENT_MISSING);
            incomplete(supersededEvent, Reason.EVENT_MISSING);
            release.countDown();
            assertTrue(future.get(5, TimeUnit.SECONDS));
        }
        assertEquals("uncommitted", read(event).material().content());
        assertEquals(
                "uncommitted",
                read(revisedEvent)
                        .material()
                        .relatedRevisionMaterials()
                        .getFirst()
                        .content());
        assertEquals(
                "concurrent",
                read(supersededEvent)
                        .material()
                        .relatedRevisionMaterials()
                        .getFirst()
                        .content());
    }

    @Test
    void wrongWorldMissingPredecessorBadOwnerBoundsBudgetAndSqlFailureAreIncomplete() throws Exception {
        Identity base = create("safe");
        assertEquals(
                Reason.WORLD_MISMATCH,
                reader.read("world:other", base.event, Limits.defaults()).reason());
        incomplete(UUID.randomUUID(), Reason.EVENT_MISSING);
        assertEquals(
                Reason.BUDGET_EXHAUSTED,
                reader.read(WORLD, base.event, new Limits(5000, 0)).reason());
        UUID otherRecord = UUID.randomUUID();
        UUID otherRevision = UUID.randomUUID();
        UUID badEvent = UUID.randomUUID();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertRecord(c, otherRecord, otherRevision);
            insertRevision(c, otherRecord, otherRevision, 1, "different");
            execute(c, "SET LOCAL session_replication_role=replica");
            insertEvent(c, badEvent, "MEMORY_CREATED", base.record, otherRevision, null, null);
            c.commit();
        }
        incomplete(badEvent, Reason.EVENT_INCONSISTENT);
        UUID missingOldEvent = UUID.randomUUID();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            execute(c, "SET LOCAL session_replication_role=replica");
            insertEvent(
                    c, missingOldEvent, "MEMORY_REVISED", base.record, base.revision, base.record, UUID.randomUUID());
            c.commit();
        }
        incomplete(missingOldEvent, Reason.PREDECESSOR_MISSING);
        UUID wrongOldOwnerEvent = UUID.randomUUID();
        UUID successorRecord = UUID.randomUUID();
        UUID successorRevision = UUID.randomUUID();
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            insertRecord(c, successorRecord, successorRevision);
            insertRevision(c, successorRecord, successorRevision, 1, "wrong old owner");
            execute(c, "SET LOCAL session_replication_role=replica");
            insertEvent(
                    c,
                    wrongOldOwnerEvent,
                    "MEMORY_SUPERSEDED",
                    successorRecord,
                    successorRevision,
                    base.record,
                    otherRevision);
            c.commit();
        }
        incomplete(wrongOldOwnerEvent, Reason.EVENT_INCONSISTENT);
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            execute(c, "ALTER TABLE memory.revision DROP CONSTRAINT revision_content_check");
            execute(c, "UPDATE memory.revision SET content=repeat('x',16385) WHERE revision_id=?", base.revision);
            c.commit();
        }
        incomplete(base.event, Reason.MATERIAL_OUT_OF_BOUNDS);
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            execute(c, "UPDATE memory.revision SET content='safe' WHERE revision_id=?", base.revision);
            execute(
                    c,
                    "ALTER TABLE memory.revision ADD CONSTRAINT revision_content_check "
                            + "CHECK (octet_length(content) BETWEEN 1 AND 16384)");
            c.commit();
        }
        JdbcProjectionMaterialReader failing = new JdbcProjectionMaterialReader(new DriverManagerDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("synthetic connection failure", "08006");
            }
        });
        assertEquals(
                Reason.READ_FAILURE,
                failing.read(WORLD, base.event, Limits.defaults()).reason());
    }

    @Test
    void readLeavesCanonicalAndDeliveryTablesUntouchedAndExcludesEvidenceText() throws Exception {
        Identity identity = create("no-evidence-text");
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String privateText = "PRIVATE_SOURCE_ANCHOR_TEXT_NEVER_PROJECT";
        try (Connection c = connection()) {
            execute(
                    c,
                    "INSERT INTO evidence.source_unit(unit_id,source_id,source_ref,source_version)"
                            + " VALUES (?,?,?,'v1')",
                    unit,
                    sourceId,
                    "source:synthetic");
            execute(
                    c,
                    "INSERT INTO evidence.source_anchor(anchor_id,unit_id,locator,exact_text,text_hash)"
                            + " VALUES (?,?,?,?,decode(repeat('00',32),'hex'))",
                    anchor,
                    unit,
                    "line:1",
                    privateText);
            execute(
                    c,
                    "INSERT INTO memory.revision_anchor(revision_id,anchor_id,relation_kind)"
                            + " VALUES (?,?,'SUPPORT')",
                    identity.revision,
                    anchor);
        }
        long before = count("SELECT count(*) FROM memory.projection_outbox");
        long revisions = count("SELECT count(*) FROM memory.revision");
        long anchors = count("SELECT count(*) FROM evidence.source_anchor");
        long tasks = count("SELECT count(*) FROM runtime.formation_task");
        long delivery = count("SELECT count(*) FROM runtime.projection_delivery");
        long processed = count("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL");
        Result result = read(identity.event);
        assertEquals(Status.COMPLETE, result.status());
        assertFalse(result.material().toString().contains(privateText));
        assertEquals(before, count("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(revisions, count("SELECT count(*) FROM memory.revision"));
        assertEquals(anchors, count("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(tasks, count("SELECT count(*) FROM runtime.formation_task"));
        assertEquals(delivery, count("SELECT count(*) FROM runtime.projection_delivery"));
        assertEquals(
                processed, count("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
    }

    @Test
    void databaseStatementTimeoutIsDiagnosticAndHasNoMaterial() throws Exception {
        Identity identity = create("blocked read");
        try (Connection blocker = connection()) {
            blocker.setAutoCommit(false);
            execute(blocker, "LOCK TABLE memory.world_binding IN ACCESS EXCLUSIVE MODE");
            Result result = reader.read(WORLD, identity.event, new Limits(150, 8));
            assertEquals(Status.INCOMPLETE, result.status());
            assertEquals(Reason.READ_TIMEOUT, result.reason());
            assertNull(result.material());
            blocker.rollback();
        }
    }

    private static Identity create(String content) throws Exception {
        try (Connection c = connection()) {
            c.setAutoCommit(false);
            Identity identity = insertIdentity(c, content);
            c.commit();
            return identity;
        }
    }

    private static Identity insertIdentity(Connection c, String content) throws SQLException {
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        insertRecord(c, record, revision);
        insertRevision(c, record, revision, 1, content);
        insertEvent(c, event, "MEMORY_CREATED", record, revision, null, null);
        return new Identity(record, revision, event);
    }

    private static void insertRecord(Connection c, UUID record, UUID revision) throws SQLException {
        execute(
                c,
                "INSERT INTO memory.record(record_id,type,current_revision_id) VALUES (?,'CLAIM',?)",
                record,
                revision);
    }

    private static void insertRevision(Connection c, UUID record, UUID revision, int number, String content)
            throws SQLException {
        execute(
                c,
                "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                        + "perspective,formation_ref,task_id,created_at) VALUES (?,?,?,?,"
                        + "'synthetic subject','synthetic scope','synthetic perspective','synthetic',?,?)",
                revision,
                record,
                number,
                content,
                task,
                NOW);
    }

    private static void insertEvent(
            Connection c, UUID event, String kind, UUID record, UUID revision, UUID oldRecord, UUID oldRevision)
            throws SQLException {
        execute(
                c,
                "INSERT INTO memory.projection_outbox(event_id,event_kind,record_id,revision_id,"
                        + "predecessor_record_id,previous_revision_id,request_hash,created_at) "
                        + "VALUES (?,?,?,?,?,?,decode(repeat('00',32),'hex'),?)",
                event,
                kind,
                record,
                revision,
                oldRecord,
                oldRevision,
                NOW);
    }

    private static void relation(Connection c, UUID source, UUID target, String kind) throws SQLException {
        execute(
                c,
                "INSERT INTO memory.revision_relation(revision_id,target_revision_id,relation_kind)"
                        + " VALUES (?,?,?)",
                source,
                target,
                kind);
    }

    private static Result read(UUID event) {
        return reader.read(WORLD, event, Limits.defaults());
    }

    private static void incomplete(UUID event, Reason reason) {
        Result result = read(event);
        assertEquals(Status.INCOMPLETE, result.status());
        assertEquals(reason, result.reason());
        assertNull(result.material());
    }

    private static long count(String sql) throws Exception {
        try (Connection c = connection();
                var r = c.createStatement().executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) p.setObject(i + 1, values[i]);
            p.executeUpdate();
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private record Identity(UUID record, UUID revision, UUID event) {}
}
