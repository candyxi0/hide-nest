package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.database.v2.adapter.JdbcDependencyReader;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Limits;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Observation;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Result;
import io.github.candyxi0.hidenest.memory.v2.DependencyRead.Status;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class NestV2DependencyReadTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-22T20:00:00Z");
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext db;
    private static DataSource dataSource;
    private UUID sourceId;
    private UUID taskId;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nest_v2_dependency_read")
                .withUsername("hide_nest_migrator")
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            c.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            c.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        db = DSL.using(dataSource, SQLDialect.POSTGRES);
        assertEquals(
                6,
                Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                        .defaultSchema("public")
                        .locations("classpath:db/v2/migration")
                        .load()
                        .migrate()
                        .migrationsExecuted);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setup() {
        db.execute("TRUNCATE memory.projection_outbox,memory.create_receipt_item,memory.create_receipt,"
                + "memory.record_succession,memory.revision_relation,memory.revision_anchor,memory.revision,"
                + "memory.record,evidence.source_anchor,evidence.source_unit,runtime.source_write_gate,"
                + "runtime.formation_attention_notice,runtime.formation_attempt,runtime.formation_task CASCADE");
        db.execute("DELETE FROM runtime.source_progress");
        db.execute("DELETE FROM runtime.source_registration");
        db.execute("DELETE FROM memory.world_binding");
        db.execute(
                "INSERT INTO memory.world_binding(singleton,world_ref,bound_at) VALUES(1,'world-1',?::timestamptz)",
                T0);
        sourceId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        db.execute(
                "INSERT INTO runtime.source_registration(source_id,source_kind,platform,external_ref,registered_at) "
                        + "VALUES(?::uuid,'SYNTHETIC','TEST','synthetic',?::timestamptz)",
                sourceId,
                T0);
        db.execute(
                "INSERT INTO runtime.source_progress(source_id,last_notification_sequence,last_notification_hash,"
                        + "discovered_sequence,discovered_cursor,discovered_source_version,stable_sequence,stable_cursor,"
                        + "stable_source_version,read_kind,read_ref,read_version,last_real_change_at,quiet_until,updated_at) "
                        + "VALUES(?::uuid,1,decode(repeat('00',32),'hex'),1,'cursor','version',1,'cursor','version',"
                        + "'STABLE_REREAD','reader','version',?::timestamptz,?::timestamptz,?::timestamptz)",
                sourceId,
                T0,
                T0.plusMinutes(90),
                T0);
        db.execute(
                "INSERT INTO runtime.formation_task(task_id,source_id,state,to_sequence,to_cursor,to_source_version,"
                        + "read_kind,read_ref,read_version,ready_at,created_at,updated_at) VALUES(?::uuid,?::uuid,"
                        + "'PENDING',1,'cursor','version','STABLE_REREAD','reader','version',"
                        + "?::timestamptz,?::timestamptz,?::timestamptz)",
                taskId,
                sourceId,
                T0,
                T0,
                T0);
    }

    @Test
    void completeGraphReportsOnlyChangedSupportPathAndRealHistoricalEvent() {
        Id event = record("EVENT", "we enjoyed the night market");
        anchor(event, "night-market");
        Id independent = record("EVENT", "another observed event");
        anchor(independent, "independent");
        Id claim = record("CLAIM", "weekends usually favor night markets");
        anchor(claim, "old-preference");
        Id b = record("UNDERSTANDING", "conditional understanding B");
        Id a = record("UNDERSTANDING", "higher understanding A");
        edge(b.revision, event.revision, "SUPPORT");
        edge(b.revision, claim.revision, "SUPPORT");
        edge(a.revision, b.revision, "SUPPORT");
        edge(a.revision, independent.revision, "SUPPORT");
        long before = canonicalCount();
        Result fresh = read(a.record);
        assertEquals(Status.COMPLETE, fresh.status());
        assertEquals(Observation.NO_VERSION_CHANGE_OBSERVED, fresh.observation());
        assertTrue(fresh.witnesses().isEmpty());
        assertEquals(2, fresh.evidence().size());
        assertTrue(fresh.evidence().stream().noneMatch(e -> "old-preference".equals(e.locator())));
        UUID newClaim = revise(claim, "now favors quiet walks");
        Result changed = read(a.record);
        assertEquals(Status.COMPLETE, changed.status());
        assertEquals(Observation.SUPPORT_CHANGED, changed.observation());
        assertEquals(1, changed.witnesses().size());
        assertEquals(
                List.of(b.revision, claim.revision),
                changed.witnesses().getFirst().supportPath().stream()
                        .map(DependencyRead.Relation::targetRevisionId)
                        .toList());
        assertEquals(newClaim, changed.witnesses().getFirst().changedTarget().currentRevisionId());
        assertTrue(
                changed.relations().stream().anyMatch(r -> r.targetRevisionId().equals(independent.revision)));
        assertEquals(2, changed.evidence().size());
        assertEquals(before + 1, canonicalCount(), "only fixture revision changed canonical tables");
        assertEquals(newClaim, read(claim.record).root().revisionId());
    }

    @Test
    void directReviseAndCounterChangeRemainDistinct() {
        Id claim = record("CLAIM", "earlier preference");
        Id root = record("UNDERSTANDING", "root");
        edge(root.revision, claim.revision, "SUPPORT");
        revise(claim, "current preference");
        Result direct = read(root.record);
        assertEquals(1, direct.witnesses().size());
        assertEquals(1, direct.witnesses().getFirst().supportPath().size());
        Id counterOnly = record("UNDERSTANDING", "counter only");
        edge(counterOnly.revision, claim.revision, "COUNTER");
        Result counter = read(counterOnly.record);
        assertEquals(Observation.NO_VERSION_CHANGE_OBSERVED, counter.observation());
        assertEquals(1, counter.relations().size());
        assertEquals("COUNTER", counter.relations().getFirst().kind());
        assertNotEquals(claim.revision, counter.relations().getFirst().target().currentRevisionId());
    }

    @Test
    void supersededOldIdentityAndDirectSuccessorRemainTraceable() {
        Id old = record("CLAIM", "old identity");
        Id root = record("UNDERSTANDING", "depends on old identity");
        edge(root.revision, old.revision, "SUPPORT");
        Id successor = record("UNDERSTANDING", "new identity");
        db.execute("UPDATE memory.record SET participation_state='SUPERSEDED' WHERE record_id=?::uuid", old.record);
        db.execute(
                "INSERT INTO memory.record_succession(predecessor_record_id,predecessor_revision_id,"
                        + "successor_record_id,successor_revision_id,task_id,created_at) "
                        + "VALUES(?::uuid,?::uuid,?::uuid,?::uuid,?::uuid,?::timestamptz)",
                old.record,
                old.revision,
                successor.record,
                successor.revision,
                taskId,
                T0);
        Result oldRead = read(old.record);
        assertEquals(old.record, oldRead.root().recordId());
        assertEquals(old.revision, oldRead.root().revisionId());
        assertEquals(successor.record, oldRead.root().directSuccessor().successorRecordId());
        Result dependent = read(root.record);
        assertEquals(Observation.SUPPORT_CHANGED, dependent.observation());
        assertEquals(
                successor.revision,
                dependent
                        .witnesses()
                        .getFirst()
                        .changedTarget()
                        .directSuccessor()
                        .successorRevisionId());
    }

    @Test
    void noEventMeansNoHistoricalExperienceAndFailuresAreIncomplete() {
        Id claim = record("CLAIM", "old claim");
        anchor(claim, "claim-anchor");
        Id root = record("UNDERSTANDING", "root");
        edge(root.revision, claim.revision, "SUPPORT");
        assertTrue(read(root.record).evidence().isEmpty());
        assertEquals(
                Status.WORLD_MISMATCH,
                reader().read("other", root.record, Limits.defaults()).status());
        assertEquals(Status.RECORD_MISSING, read(UUID.randomUUID()).status());
        assertEquals(
                Status.INCOMPLETE,
                reader().read("world-1", root.record, new Limits(1, 10, 10, 5000))
                        .status());
        DataSource broken = new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/unavailable", "none", "none");
        assertEquals(
                Status.INCOMPLETE,
                new JdbcDependencyReader(broken)
                        .read("world-1", root.record, Limits.defaults())
                        .status());
        assertNull(reader().read("world-1", root.record, new Limits(1, 10, 10, 5000))
                .root());
    }

    @Test
    void witnessBudgetAndUnexpectedSupportCycleNeverReportFresh() {
        Id first = record("CLAIM", "first");
        Id second = record("CLAIM", "second");
        Id root = record("UNDERSTANDING", "root");
        edge(root.revision, first.revision, "SUPPORT");
        edge(root.revision, second.revision, "SUPPORT");
        revise(first, "first revised");
        revise(second, "second revised");
        Result limited = reader().read("world-1", root.record, new Limits(10, 10, 1, 5000));
        assertEquals(Status.INCOMPLETE, limited.status());
        assertNull(limited.root());
        edge(first.revision, root.revision, "SUPPORT");
        Result cyclic = read(root.record);
        assertEquals(Status.INCOMPLETE, cyclic.status());
        assertTrue(cyclic.relations().isEmpty());
    }

    @Test
    void allCanonicalAndCursorTablesStayUnchangedByRead() {
        Id event = record("EVENT", "event");
        anchor(event, "event-anchor");
        Id root = record("UNDERSTANDING", "root");
        edge(root.revision, event.revision, "SUPPORT");
        Map<String, String> before = tableDigests();
        assertEquals(Status.COMPLETE, read(root.record).status());
        assertEquals(before, tableDigests());
    }

    @Test
    void concurrentReviseCannotSpliceNewTargetIntoOldSnapshot() {
        Id target = record("CLAIM", "earlier");
        Id root = record("UNDERSTANDING", "root");
        edge(root.revision, target.revision, "SUPPORT");
        boolean[] revised = {false};
        DataSource hook =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection original = super.getConnection();
                        return (Connection) Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[] {Connection.class},
                                (proxy, method, args) -> {
                                    try {
                                        Object value = method.invoke(original, args);
                                        if ("prepareStatement".equals(method.getName())
                                                && args[0] instanceof String sql
                                                && sql.startsWith("SELECT current_revision_id FROM memory.record")) {
                                            var statement = (java.sql.PreparedStatement) value;
                                            return Proxy.newProxyInstance(
                                                    java.sql.PreparedStatement.class.getClassLoader(),
                                                    new Class<?>[] {java.sql.PreparedStatement.class},
                                                    (ignored, queryMethod, queryArgs) -> {
                                                        try {
                                                            if ("executeQuery".equals(queryMethod.getName())
                                                                    && !revised[0]) {
                                                                revise(target, "newer");
                                                                revised[0] = true;
                                                            }
                                                            return queryMethod.invoke(statement, queryArgs);
                                                        } catch (InvocationTargetException e) {
                                                            throw e.getCause();
                                                        }
                                                    });
                                        }
                                        return value;
                                    } catch (InvocationTargetException e) {
                                        throw e.getCause();
                                    }
                                });
                    }
                };
        Result snapshot = new JdbcDependencyReader(hook).read("world-1", root.record, Limits.defaults());
        assertTrue(revised[0]);
        assertEquals(Status.COMPLETE, snapshot.status());
        assertEquals(Observation.NO_VERSION_CHANGE_OBSERVED, snapshot.observation());
        assertEquals(Observation.SUPPORT_CHANGED, read(root.record).observation());
    }

    private JdbcDependencyReader reader() {
        return new JdbcDependencyReader(dataSource);
    }

    private Result read(UUID id) {
        return reader().read("world-1", id, Limits.defaults());
    }

    private Id record(String type, String content) {
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        db.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute(
                    "INSERT INTO memory.record(record_id,type,current_revision_id) VALUES(?::uuid,?,?::uuid)",
                    record,
                    type,
                    revision);
            tx.execute(
                    "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                            + "perspective,formation_ref,task_id,created_at) VALUES(?::uuid,?::uuid,1,?,'subject',"
                            + "'world-1','observed','formation',?::uuid,?::timestamptz)",
                    revision,
                    record,
                    content,
                    taskId,
                    T0);
        });
        return new Id(record, revision);
    }

    private UUID revise(Id id, String content) {
        UUID revision = UUID.randomUUID();
        db.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute(
                    "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                            + "perspective,formation_ref,task_id,created_at) VALUES(?::uuid,?::uuid,2,?,'subject',"
                            + "'world-1','observed','formation',?::uuid,?::timestamptz)",
                    revision,
                    id.record,
                    content,
                    taskId,
                    T0);
            tx.execute(
                    "UPDATE memory.record SET current_revision_id=?::uuid WHERE record_id=?::uuid",
                    revision,
                    id.record);
        });
        return revision;
    }

    private void edge(UUID source, UUID target, String kind) {
        db.execute(
                "INSERT INTO memory.revision_relation(revision_id,target_revision_id,relation_kind) "
                        + "VALUES(?::uuid,?::uuid,?)",
                source,
                target,
                kind);
    }

    private void anchor(Id id, String locator) {
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        db.execute(
                "INSERT INTO evidence.source_unit(unit_id,source_id,source_ref,source_version) "
                        + "VALUES(?::uuid,?::uuid,?,?)",
                unit,
                sourceId,
                locator,
                "version");
        db.execute(
                "INSERT INTO evidence.source_anchor(anchor_id,unit_id,locator,exact_text,text_hash) "
                        + "VALUES(?::uuid,?::uuid,?,'fixture-only',decode(repeat('00',32),'hex'))",
                anchor,
                unit,
                locator);
        db.execute(
                "INSERT INTO memory.revision_anchor(revision_id,anchor_id,relation_kind) VALUES(?::uuid,?::uuid,'SUPPORT')",
                id.revision,
                anchor);
    }

    private long canonicalCount() {
        long total = 0;
        for (String table : List.of(
                "memory.world_binding",
                "memory.record",
                "memory.revision",
                "memory.revision_relation",
                "memory.revision_anchor",
                "memory.record_succession",
                "memory.create_receipt",
                "memory.create_receipt_item",
                "memory.projection_outbox",
                "evidence.source_unit",
                "evidence.source_anchor",
                "runtime.source_progress",
                "runtime.formation_task",
                "runtime.formation_attempt"))
            total += db.fetchOne("SELECT count(*) FROM " + table).get(0, Long.class);
        return total;
    }

    private Map<String, String> tableDigests() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of(
                "memory.world_binding",
                "memory.record",
                "memory.revision",
                "memory.revision_relation",
                "memory.revision_anchor",
                "memory.record_succession",
                "memory.create_receipt",
                "memory.create_receipt_item",
                "memory.projection_outbox",
                "evidence.source_unit",
                "evidence.source_anchor",
                "runtime.source_progress",
                "runtime.formation_task",
                "runtime.formation_attempt"))
            result.put(
                    table,
                    db.fetchOne("SELECT md5(coalesce(string_agg(row_to_json(t)::text,'|' "
                                    + "ORDER BY row_to_json(t)::text),'')) FROM " + table + " t")
                            .get(0, String.class));
        return result;
    }

    private record Id(UUID record, UUID revision) {}
}
