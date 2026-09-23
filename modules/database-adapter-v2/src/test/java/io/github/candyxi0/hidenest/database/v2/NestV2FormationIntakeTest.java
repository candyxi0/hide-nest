package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.v2.adapter.JooqFormationIntakeAdapter;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvance;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceOutcome;
import io.github.candyxi0.hidenest.runtime.domain.SourceBoundary;
import io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** Real PostgreSQL gates for body-free source progress and PENDING Formation intake. */
class NestV2FormationIntakeTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-22T20:00:00Z");

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;

    private UUID sourceId;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = container("nest_v2_formation_intake", password);
        postgres.start();
        createRoles(postgres, password);
        Flyway flyway = flyway(postgres, null);
        assertEquals(4, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        dsl = DSL.using(new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password), SQLDialect.POSTGRES);
    }

    @AfterAll
    static void tearDownDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void createSource() {
        dsl.execute("DELETE FROM runtime.formation_attention_notice");
        dsl.execute("DELETE FROM runtime.formation_attempt");
        dsl.execute("DELETE FROM runtime.formation_task");
        dsl.execute("DELETE FROM runtime.source_progress");
        dsl.execute("DELETE FROM runtime.source_registration");
        sourceId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO runtime.source_registration "
                        + "(source_id,source_kind,platform,external_ref,registered_at) VALUES "
                        + "(?::uuid,'SYNTHETIC','TEST','formation-intake-' || ?::text,?::timestamptz)",
                sourceId,
                sourceId,
                T0);
    }

    @Test
    void freshV2BaselineHasCanonicalCreateSchemasAfterV003() {
        assertEquals(4, scalarInt("SELECT count(*) FROM public.flyway_schema_history WHERE success"));
        assertEquals(6, scalarInt("SELECT count(*) FROM information_schema.tables WHERE table_schema='runtime'"));
        assertTrue(bool("SELECT EXISTS (SELECT 1 FROM information_schema.schemata "
                + "WHERE schema_name IN ('memory','evidence'))"));
    }

    @Test
    void stableAdvanceCreatesOneBodyFreePendingAndBecomesReadyAfterNinetyMinutes() {
        var adapter = adapterAt(T0);
        var result = adapter.recordSourceAdvanced(advance(1, 1, 1, reread("reader-v1")));

        assertEquals(SourceAdvanceOutcome.CREATED, result.outcome());
        assertEquals(T0.plusMinutes(90), result.readyAt());
        assertEquals(1, count("runtime.source_progress"));
        assertEquals(1, count("runtime.formation_task"));
        assertTrue(adapterAt(T0.plusMinutes(89).plusSeconds(59))
                .findReadyFormationTasks(10)
                .isEmpty());
        assertEquals(
                1, adapterAt(T0.plusMinutes(90)).findReadyFormationTasks(10).size());

        assertEquals(
                0,
                scalarInt(
                        "SELECT count(*) FROM information_schema.columns "
                                + "WHERE table_schema='runtime' AND table_name IN ('source_progress','formation_task') "
                                + "AND (column_name ~ '(body|message|summary|query|candidate|prompt|embedding|vector|token)')"));
        assertEquals(0L, scalarLong("SELECT generation FROM runtime.formation_task"));
        assertTrue(dsl.fetchOne("SELECT processed_sequence IS NULL FROM runtime.source_progress")
                .get(0, Boolean.class));
    }

    @Test
    void replaysStaleNotificationsAndPollingHeartbeatsDoNotResetQuiet() {
        var initial = adapterAt(T0);
        SourceAdvance first = advance(5, 5, 5, reread("reader-v1"));
        UUID taskId = initial.recordSourceAdvanced(first).pendingTaskId();

        var later = adapterAt(T0.plusMinutes(20));
        assertEquals(
                SourceAdvanceOutcome.EXACT_REPLAY,
                later.recordSourceAdvanced(first).outcome());
        assertEquals(
                SourceAdvanceOutcome.STALE_NOOP,
                later.recordSourceAdvanced(advance(4, 4, 4, reread("reader-v1")))
                        .outcome());
        assertEquals(
                SourceAdvanceOutcome.HEARTBEAT_NOOP,
                later.recordSourceAdvanced(advance(6, 5, 5, reread("reader-v1")))
                        .outcome());

        assertEquals(
                taskId,
                dsl.fetchOne("SELECT task_id FROM runtime.formation_task").get(0, UUID.class));
        assertEquals(T0.plusMinutes(90).toInstant(), readyAt().toInstant());
        assertEquals(1, count("runtime.formation_task"));
    }

    @Test
    void unstableTailBlocksReadyAndCatchUpRestartsQuiet() {
        adapterAt(T0).recordSourceAdvanced(advance(1, 2, 1, reread("reader-v1")));
        assertTrue(adapterAt(T0.plusHours(3)).findReadyFormationTasks(10).isEmpty());

        OffsetDateTime caughtUpAt = T0.plusHours(3);
        var caughtUp = adapterAt(caughtUpAt);
        SourceAdvanceResultView view = view(caughtUp.recordSourceAdvanced(advance(2, 2, 2, reread("reader-v2"))));
        assertEquals(SourceAdvanceOutcome.MERGED, view.outcome());
        assertEquals(caughtUpAt.plusMinutes(90), view.readyAt());
        assertEquals(1, count("runtime.formation_task"));
        assertTrue(adapterAt(caughtUpAt.plusMinutes(89))
                .findReadyFormationTasks(10)
                .isEmpty());
        assertEquals(
                1,
                adapterAt(caughtUpAt.plusMinutes(90))
                        .findReadyFormationTasks(10)
                        .size());
    }

    @Test
    void genuineAdvanceMergesSamePendingAndMovesReadyAt() {
        UUID taskId = adapterAt(T0)
                .recordSourceAdvanced(advance(1, 1, 1, reread("reader-v1")))
                .pendingTaskId();
        OffsetDateTime changedAt = T0.plusMinutes(45);
        var result = adapterAt(changedAt).recordSourceAdvanced(advance(2, 2, 2, reread("reader-v2")));

        assertEquals(SourceAdvanceOutcome.MERGED, result.outcome());
        assertEquals(taskId, result.pendingTaskId());
        assertEquals(changedAt.plusMinutes(90), result.readyAt());
        assertEquals(1, count("runtime.formation_task"));
        assertEquals(2L, scalarLong("SELECT to_sequence FROM runtime.formation_task"));
    }

    @Test
    void conflictsAndOrderRegressionsAreZeroWrite() {
        var adapter = adapterAt(T0);
        SourceAdvance first = advance(5, 5, 5, reread("reader-v5"));
        adapter.recordSourceAdvanced(first);
        String before = stateFingerprint();

        SourceAdvance sameNotificationDifferentPayload = new SourceAdvance(
                sourceId,
                5,
                new SourceBoundary(6, "cursor-6", "source-v6"),
                new SourceBoundary(6, "cursor-6", "source-v6"),
                reread("reader-v6"));
        assertEquals(
                SourceAdvanceOutcome.CONFLICT,
                adapter.recordSourceAdvanced(sameNotificationDifferentPayload).outcome());
        assertEquals(before, stateFingerprint());

        SourceAdvance regressed = advance(6, 4, 4, reread("reader-v4"));
        assertEquals(
                SourceAdvanceOutcome.SOURCE_ORDER_INVALID,
                adapter.recordSourceAdvanced(regressed).outcome());
        assertEquals(before, stateFingerprint());

        SourceAdvance samePositionChangedVersion = new SourceAdvance(
                sourceId,
                6,
                new SourceBoundary(5, "cursor-5", "source-v6"),
                new SourceBoundary(5, "cursor-5", "source-v6"),
                reread("reader-v6"));
        assertEquals(
                SourceAdvanceOutcome.SOURCE_ORDER_INVALID,
                adapter.recordSourceAdvanced(samePositionChangedVersion).outcome());
        assertEquals(before, stateFingerprint());
    }

    @Test
    void readBindingChangesDoNotPretendToBeActivity() {
        adapterAt(T0).recordSourceAdvanced(advance(1, 1, 1, reread("reader-v1")));
        OffsetDateTime afterQuiet = T0.plusMinutes(100);

        var unavailable = adapterAt(afterQuiet);
        unavailable.recordSourceAdvanced(
                advance(2, 1, 1, new SourceReadBinding(SourceReadBinding.Kind.UNAVAILABLE, null, null, null)));
        assertEquals(T0.plusMinutes(90).toInstant(), readyAt().toInstant());
        assertTrue(unavailable.findReadyFormationTasks(10).isEmpty());

        var readableAgain = adapterAt(afterQuiet.plusMinutes(1));
        readableAgain.recordSourceAdvanced(advance(3, 1, 1, reread("reader-v1")));
        assertEquals(T0.plusMinutes(90).toInstant(), readyAt().toInstant());
        assertEquals(1, readableAgain.findReadyFormationTasks(10).size());

        readableAgain.recordSourceAdvanced(advance(
                4,
                1,
                1,
                new SourceReadBinding(
                        SourceReadBinding.Kind.BOUNDED_SNAPSHOT,
                        "snapshot-ref",
                        "snapshot-v1",
                        afterQuiet.plusMinutes(2))));
        assertEquals(1, readableAgain.findReadyFormationTasks(10).size());
        assertTrue(
                adapterAt(afterQuiet.plusMinutes(2)).findReadyFormationTasks(10).isEmpty());
    }

    @Test
    void concurrentAdjacentNotificationsConvergeWithoutDuplicatePending() throws Exception {
        adapterAt(T0).recordSourceAdvanced(advance(1, 1, 1, reread("reader-v1")));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceResult> second = executor.submit(() -> {
                start.await();
                return adapterAt(T0.plusMinutes(1)).recordSourceAdvanced(advance(2, 2, 2, reread("reader-v2")));
            });
            Future<io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceResult> third = executor.submit(() -> {
                start.await();
                return adapterAt(T0.plusMinutes(2)).recordSourceAdvanced(advance(3, 3, 3, reread("reader-v3")));
            });
            start.countDown();
            List<SourceAdvanceOutcome> outcomes =
                    List.of(second.get().outcome(), third.get().outcome());
            assertTrue(outcomes.stream()
                    .allMatch(outcome ->
                            outcome == SourceAdvanceOutcome.MERGED || outcome == SourceAdvanceOutcome.STALE_NOOP));
        }

        assertEquals(1, count("runtime.source_progress"));
        assertEquals(1, count("runtime.formation_task"));
        assertEquals(3L, scalarLong("SELECT discovered_sequence FROM runtime.source_progress"));
        assertEquals(3L, scalarLong("SELECT stable_sequence FROM runtime.source_progress"));
        assertEquals(3L, scalarLong("SELECT to_sequence FROM runtime.formation_task"));
    }

    @Test
    void unknownSourceAndWorkerTableAccessFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> adapterAt(T0)
                .recordSourceAdvanced(new SourceAdvance(
                        UUID.randomUUID(),
                        1,
                        new SourceBoundary(1, "cursor-1", "source-v1"),
                        new SourceBoundary(1, "cursor-1", "source-v1"),
                        reread("reader-v1"))));
        assertEquals(0, count("runtime.source_progress"));

        assertTrue(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_registration','SELECT')"));
        assertTrue(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_registration','INSERT')"));
        assertFalse(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_registration','UPDATE')"));
        assertFalse(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_registration','DELETE')"));
        assertTrue(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_progress','SELECT')"));
        assertTrue(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_progress','INSERT')"));
        assertTrue(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_progress','UPDATE')"));
        assertFalse(bool("SELECT has_table_privilege('hide_nest_api','runtime.source_progress','DELETE')"));
        for (String table :
                List.of("runtime.source_registration", "runtime.source_progress", "runtime.formation_task")) {
            assertFalse(bool("SELECT has_table_privilege('hide_nest_worker','" + table + "','SELECT')"));
            assertFalse(bool("SELECT has_table_privilege('hide_nest_worker','" + table + "','INSERT')"));
            assertFalse(bool("SELECT has_table_privilege('hide_nest_worker','" + table + "','UPDATE')"));
        }
    }

    private JooqFormationIntakeAdapter adapterAt(OffsetDateTime time) {
        return new JooqFormationIntakeAdapter(dsl, Clock.fixed(time.toInstant(), ZoneOffset.UTC));
    }

    private SourceAdvance advance(long notification, long discovered, long stable, SourceReadBinding binding) {
        SourceBoundary discoveredBoundary =
                new SourceBoundary(discovered, "cursor-" + discovered, "source-v" + discovered);
        SourceBoundary stableBoundary = new SourceBoundary(stable, "cursor-" + stable, "source-v" + stable);
        return new SourceAdvance(sourceId, notification, discoveredBoundary, stableBoundary, binding);
    }

    private static SourceReadBinding reread(String version) {
        return new SourceReadBinding(SourceReadBinding.Kind.STABLE_REREAD, "reader-ref", version, null);
    }

    private static SourceAdvanceResultView view(io.github.candyxi0.hidenest.runtime.domain.SourceAdvanceResult result) {
        return new SourceAdvanceResultView(result.outcome(), result.readyAt());
    }

    private record SourceAdvanceResultView(SourceAdvanceOutcome outcome, OffsetDateTime readyAt) {}

    private static int count(String table) {
        return scalarInt("SELECT count(*) FROM " + table);
    }

    private static int scalarInt(String sql) {
        return dsl.fetchOne(sql).get(0, Integer.class);
    }

    private static long scalarLong(String sql) {
        return dsl.fetchOne(sql).get(0, Long.class);
    }

    private static boolean bool(String sql) {
        return Boolean.TRUE.equals(dsl.fetchOne(sql).get(0, Boolean.class));
    }

    private static OffsetDateTime readyAt() {
        return dsl.fetchOne("SELECT ready_at FROM runtime.formation_task").get(0, OffsetDateTime.class);
    }

    private static String stateFingerprint() {
        return dsl.fetchOne(
                        "SELECT concat_ws('|',p.last_notification_sequence,p.discovered_sequence,p.discovered_cursor,"
                                + "p.discovered_source_version,p.stable_sequence,p.stable_cursor,p.stable_source_version,"
                                + "p.quiet_until,t.task_id,t.to_sequence,t.to_cursor,t.to_source_version,t.ready_at) "
                                + "FROM runtime.source_progress p LEFT JOIN runtime.formation_task t USING(source_id)")
                .get(0, String.class);
    }

    private static PostgreSQLContainer<?> container(String database, String password) {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database)
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    private static Flyway flyway(PostgreSQLContainer<?> container, String target) {
        var configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), USER, container.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/v2/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void createRoles(PostgreSQLContainer<?> container, String password) throws Exception {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
    }
}
