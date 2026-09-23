package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.v2.adapter.JooqFormationControlAdapter;
import io.github.candyxi0.hidenest.database.v2.adapter.JooqFormationIntakeAdapter;
import io.github.candyxi0.hidenest.runtime.domain.FormationAttempt;
import io.github.candyxi0.hidenest.runtime.domain.FormationResultKind;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementCandidate;
import io.github.candyxi0.hidenest.runtime.domain.FormationSettlementOutcome;
import io.github.candyxi0.hidenest.runtime.domain.SourceAdvance;
import io.github.candyxi0.hidenest.runtime.domain.SourceBoundary;
import io.github.candyxi0.hidenest.runtime.domain.SourceReadBinding;
import io.github.candyxi0.hidenest.runtime.port.CanonicalCommitPort;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

/** Real PostgreSQL counterexamples for predecessor, lease and one-slot settlement. */
class NestV2FormationControlTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-22T20:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private UUID sourceId;

    @BeforeAll
    static void database() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nest_v2_formation_control")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway first = flyway("1");
        assertEquals(1, first.migrate().migrationsExecuted);
        Flyway all = flyway(null);
        assertEquals(3, all.migrate().migrationsExecuted);
        assertEquals(0, all.migrate().migrationsExecuted);
        all.validate();
        dsl = DSL.using(new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password), SQLDialect.POSTGRES);
    }

    @AfterAll
    static void close() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void source() {
        dsl.execute(
                "TRUNCATE runtime.formation_attention_notice,runtime.formation_attempt,runtime.formation_task CASCADE");
        dsl.execute("DELETE FROM runtime.source_progress");
        dsl.execute("DELETE FROM runtime.source_registration");
        sourceId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO runtime.source_registration(source_id,source_kind,platform,external_ref,registered_at) "
                        + "VALUES(?::uuid,'SYNTHETIC','TEST',? ,?::timestamptz)",
                sourceId,
                sourceId.toString(),
                T0);
    }

    @Test
    void migrationAndPermissionsAreBodyFree() {
        assertEquals(4, number("SELECT count(*) FROM public.flyway_schema_history WHERE success"));
        assertEquals(6, number("SELECT count(*) FROM information_schema.tables WHERE table_schema='runtime'"));
        assertEquals(
                1,
                number("SELECT count(*) FROM information_schema.columns WHERE table_schema='runtime' "
                        + "AND table_name='formation_task' AND column_name='predecessor_task_id'"));
        assertEquals(
                0,
                number(
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema='runtime' "
                                + "AND table_name IN ('formation_task','formation_attempt','formation_attention_notice') "
                                + "AND column_name ~ '(body|message|summary|query|prompt|embedding|vector|candidate|write_set)'"));
        for (String table : List.of("formation_task", "formation_attempt", "formation_attention_notice")) {
            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertFalse(bool(
                        "SELECT has_table_privilege('hide_nest_worker','runtime." + table + "','" + privilege + "')"));
            }
        }
    }

    @Test
    void claimRenewTakeoverAndLateOldResult() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("owner-a", LEASE).orElseThrow();
        assertTrue(control(T0.plusMinutes(91), 3).renew(first.attemptId(), "owner-a", LEASE));
        assertFalse(control(T0.plusMinutes(91), 3).renew(first.attemptId(), "wrong", LEASE));
        assertTrue(control(T0.plusMinutes(92), 3).claim("owner-b", LEASE).isEmpty());
        FormationAttempt second =
                control(T0.plusMinutes(97), 3).claim("owner-b", LEASE).orElseThrow();
        assertNotEquals(first.attemptId(), second.attemptId());
        assertEquals(first.taskId(), second.taskId());
        assertEquals(2, second.generation());
        assertEquals(first.toInclusive(), second.toInclusive());

        var control = control(T0.plusMinutes(98), 3);
        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control.settle(
                        candidate(first, FormationResultKind.NO_LONG_TERM_CHANGE, 1, true),
                        (connection, result) -> true,
                        noWrite(),
                        ignored -> {}));
        assertEquals(
                FormationSettlementOutcome.SLOT_ALREADY_SETTLED,
                control.settle(
                        candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 1, true),
                        (connection, result) -> true,
                        noWrite(),
                        ignored -> {}));
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='WON'"));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertTrue(control(T0.plusMinutes(110), 3).claim("late-takeover", LEASE).isEmpty());
        assertFalse(control(T0.plusMinutes(110), 3).renew(second.attemptId(), "owner-b", LEASE));
    }

    @Test
    void concurrentClaimCreatesOnlyOneAttempt() throws Exception {
        pendingA();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = pool.submit(() -> {
                start.await();
                return control(T0.plusMinutes(90), 3).claim("first", LEASE).isPresent();
            });
            Future<Boolean> two = pool.submit(() -> {
                start.await();
                return control(T0.plusMinutes(90), 3).claim("second", LEASE).isPresent();
            });
            start.countDown();
            assertEquals(1, (one.get() ? 1 : 0) + (two.get() ? 1 : 0));
        }
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attempt"));
        assertEquals(1, number("SELECT generation FROM runtime.formation_task"));
    }

    @Test
    void lateFailureAtBudgetDoesNotStopLeasedSiblingOrUnlockSuccessor() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 2).claim("first", LEASE).orElseThrow();
        intake(T0.plusMinutes(91)).recordSourceAdvanced(advance(2, 2));
        FormationAttempt second = control(T0.plusMinutes(96), 2)
                .claim("second", Duration.ofHours(3))
                .orElseThrow();

        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                control(T0.plusMinutes(182), 2)
                        .settle(
                                candidate(first, FormationResultKind.NO_LONG_TERM_CHANGE, 1, false),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {}));
        assertEquals("FAILED", attemptState(first));
        assertEquals("RUNNING", attemptState(second));
        assertEquals("ATTEMPTING", taskState(first));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attention_notice"));
        assertTrue(control(T0.plusMinutes(182), 2).attentionNotices(10).isEmpty());
        assertTrue(control(T0.plusMinutes(182), 2).renew(second.attemptId(), "second", Duration.ofHours(3)));
        assertTrue(control(T0.plusMinutes(182), 2).claim("successor", LEASE).isEmpty());
        assertEquals(0, number("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));

        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control(T0.plusMinutes(183), 2)
                        .settle(
                                candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {}));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(
                2,
                control(T0.plusMinutes(184), 2)
                        .claim("successor", LEASE)
                        .orElseThrow()
                        .toInclusive()
                        .sequence());
    }

    @Test
    void lateFailureBelowBudgetKeepsSiblingRenewableAndDoesNotLaunchThirdAttempt() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("first", LEASE).orElseThrow();
        FormationAttempt second = control(T0.plusMinutes(96), 3)
                .claim("second", Duration.ofHours(3))
                .orElseThrow();

        control(T0.plusMinutes(97), 3).fail(first.attemptId(), "SOURCE_INCOMPLETE");
        assertEquals("FAILED", attemptState(first));
        assertEquals("ATTEMPTING", taskState(first));
        assertTrue(control(T0.plusMinutes(98), 3).renew(second.attemptId(), "second", LEASE));
        assertTrue(control(T0.plusMinutes(99), 3).claim("third", LEASE).isEmpty());
        assertEquals(2, number("SELECT count(*) FROM runtime.formation_attempt"));
        assertTrue(control(T0.plusMinutes(99), 3).attentionNotices(10).isEmpty());

        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                control(T0.plusMinutes(100), 3).fail(second.attemptId(), "SOURCE_INCOMPLETE"));
        assertEquals("RETRY_WAIT", taskState(first));
        FormationAttempt third =
                control(T0.plusMinutes(101), 3).claim("third", LEASE).orElseThrow();
        assertEquals(3, third.generation());
        assertEquals(
                FormationSettlementOutcome.ATTENTION_REQUIRED,
                control(T0.plusMinutes(102), 3).fail(third.attemptId(), "SOURCE_INCOMPLETE"));
        assertEquals(1, control(T0.plusMinutes(102), 3).attentionNotices(10).size());
    }

    @Test
    void oldFailureRacingSiblingSettlementCannotCreateContradictoryNotice() throws Exception {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 2).claim("first", LEASE).orElseThrow();
        FormationAttempt second = control(T0.plusMinutes(96), 2)
                .claim("second", Duration.ofHours(3))
                .orElseThrow();
        CountDownLatch checking = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch failureStarted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<FormationSettlementOutcome> settlement = pool.submit(() -> control(T0.plusMinutes(97), 2)
                    .settle(
                            candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true),
                            (connection, result) -> {
                                checking.countDown();
                                try {
                                    return release.await(10, TimeUnit.SECONDS);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new SQLException("interrupted", interrupted);
                                }
                            },
                            noWrite(),
                            ignored -> {}));
            assertTrue(checking.await(10, TimeUnit.SECONDS));
            Future<FormationSettlementOutcome> failure = pool.submit(() -> {
                failureStarted.countDown();
                return control(T0.plusMinutes(97), 2).fail(first.attemptId(), "SOURCE_INCOMPLETE");
            });
            assertTrue(failureStarted.await(10, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(FormationSettlementOutcome.COMMITTED_NO_CHANGE, settlement.get());
            assertEquals(FormationSettlementOutcome.SLOT_ALREADY_SETTLED, failure.get());
        }
        assertEquals("COMMITTED_NO_CHANGE", taskState(first));
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='WON'"));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attention_notice"));
        assertTrue(control(T0.plusMinutes(98), 2).attentionNotices(10).isEmpty());
    }

    @Test
    void exhaustedLeaseMayRaiseAttentionButLateValidResultStillSettles() {
        pendingA();
        control(T0.plusMinutes(90), 2).claim("first", LEASE).orElseThrow();
        FormationAttempt second =
                control(T0.plusMinutes(96), 2).claim("second", LEASE).orElseThrow();
        assertTrue(control(T0.plusMinutes(102), 2).claim("none", LEASE).isEmpty());
        assertEquals("ATTENTION_REQUIRED", taskState(second));
        assertEquals(1, control(T0.plusMinutes(102), 2).attentionNotices(10).size());

        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control(T0.plusMinutes(103), 2)
                        .settle(
                                candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {}));
        assertEquals("COMMITTED_NO_CHANGE", taskState(second));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertTrue(control(T0.plusMinutes(103), 2).attentionNotices(10).isEmpty());
    }

    @Test
    void predecessorWaitsThroughRetryAndAttention() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 2).claim("owner-a", LEASE).orElseThrow();
        intake(T0.plusMinutes(91)).recordSourceAdvanced(advance(2, 2));
        UUID successor = dsl.fetchOne("SELECT task_id FROM runtime.formation_task WHERE state='PENDING'")
                .get(0, UUID.class);
        intake(T0.plusMinutes(93)).recordSourceAdvanced(advance(3, 3));
        assertEquals(
                successor,
                dsl.fetchOne("SELECT task_id FROM runtime.formation_task WHERE state='PENDING'")
                        .get(0, UUID.class));
        assertEquals(
                first.taskId(),
                dsl.fetchOne("SELECT predecessor_task_id FROM runtime.formation_task WHERE task_id=?::uuid", successor)
                        .get(0, UUID.class));
        assertEquals(1, number("SELECT from_sequence FROM runtime.formation_task WHERE task_id='" + successor + "'"));
        assertEquals(3, number("SELECT to_sequence FROM runtime.formation_task WHERE task_id='" + successor + "'"));
        assertEquals(
                1, number("SELECT to_sequence FROM runtime.formation_task WHERE task_id='" + first.taskId() + "'"));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                control(T0.plusMinutes(92), 2).fail(first.attemptId(), "SOURCE_INCOMPLETE"));
        assertTrue(intake(T0.plusMinutes(181)).findReadyFormationTasks(10).isEmpty());
        assertTrue(control(T0.plusMinutes(200), 2).claim("other", LEASE).isPresent());
        // The second claim is still A; B must remain blocked during ATTEMPTING.
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attempt WHERE task_id='" + successor + "'"));
        assertEquals(
                FormationSettlementOutcome.ATTENTION_REQUIRED,
                control(T0.plusMinutes(201), 2)
                        .fail(
                                dsl.fetchOne("SELECT attempt_id FROM runtime.formation_attempt WHERE generation=2")
                                        .get(0, UUID.class),
                                "SOURCE_INCOMPLETE"));
        assertTrue(control(T0.plusMinutes(400), 2).claim("other", LEASE).isEmpty());
        assertEquals(1, control(T0.plusMinutes(400), 2).attentionNotices(10).size());
        assertEquals(
                FormationSettlementOutcome.ATTEMPT_NOT_ACCEPTED,
                control(T0.plusMinutes(401), 2).fail(first.attemptId(), "SOURCE_INCOMPLETE"));
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attention_notice"));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attempt WHERE task_id='" + successor + "'"));
        assertEquals(0, number("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
    }

    @Test
    void noChangeUnlocksSuccessor() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("owner", LEASE).orElseThrow();
        intake(T0.plusMinutes(91)).recordSourceAdvanced(advance(2, 2));
        assertTrue(control(T0.plusMinutes(182), 3).claim("other", LEASE).isPresent());
        assertEquals(
                0,
                number("SELECT count(*) FROM runtime.formation_attempt a JOIN runtime.formation_task t "
                        + "ON a.task_id=t.task_id WHERE t.from_sequence=1"));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control(T0.plusMinutes(183), 3)
                        .settle(
                                candidate(first, FormationResultKind.NO_LONG_TERM_CHANGE, 1, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {}));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(
                2,
                control(T0.plusMinutes(183), 3)
                        .claim("owner-b", LEASE)
                        .orElseThrow()
                        .toInclusive()
                        .sequence());
    }

    @Test
    void writeFailureRollsBackThenRetryCanCommitAtomically() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("owner", LEASE).orElseThrow();
        intake(T0.plusMinutes(91)).recordSourceAdvanced(advance(2, 2));
        dsl.execute("CREATE TABLE IF NOT EXISTS public.canonical_probe(value integer)");
        dsl.execute("TRUNCATE public.canonical_probe");
        CanonicalCommitPort failing = (connection, result) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("INSERT INTO canonical_probe(value) VALUES (1)");
            }
            throw new SQLException("synthetic canonical failure");
        };
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                control(T0.plusMinutes(92), 3)
                        .settle(
                                candidate(first, FormationResultKind.WRITE_SET, 1, true),
                                (connection, result) -> true,
                                failing,
                                ignored -> {}));
        assertEquals(0, number("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
        assertEquals(0, number("SELECT count(*) FROM public.canonical_probe"));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_task WHERE state LIKE 'COMMITTED_%'"));
        FormationAttempt retry =
                control(T0.plusMinutes(183), 3).claim("retry", LEASE).orElseThrow();
        assertEquals(first.taskId(), retry.taskId());
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                control(T0.plusMinutes(184), 3)
                        .settle(
                                candidate(retry, FormationResultKind.WRITE_SET, 2, true),
                                (connection, result) -> true,
                                (connection, result) -> {
                                    try (var statement = connection.createStatement()) {
                                        statement.execute("INSERT INTO public.canonical_probe(value) VALUES (2)");
                                    }
                                },
                                ignored -> {}));
        assertEquals(1, number("SELECT count(*) FROM public.canonical_probe"));
        assertEquals(1, number("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(
                2,
                control(T0.plusMinutes(184), 3)
                        .claim("successor", LEASE)
                        .orElseThrow()
                        .toInclusive()
                        .sequence());
    }

    @Test
    void invalidCannotClaimSlotAndReplayIsIdempotent() {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("first", LEASE).orElseThrow();
        FormationAttempt second =
                control(T0.plusMinutes(96), 3).claim("second", LEASE).orElseThrow();
        var invalid = candidate(first, FormationResultKind.NO_LONG_TERM_CHANGE, 1, false);
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                control(T0.plusMinutes(97), 3).settle(invalid, (connection, result) -> true, noWrite(), ignored -> {}));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='WON'"));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                control(T0.plusMinutes(97), 3)
                        .settle(
                                candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true),
                                (connection, result) -> false,
                                noWrite(),
                                ignored -> {}));
        assertEquals(0, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='WON'"));
        FormationAttempt third =
                control(T0.plusMinutes(103), 3).claim("third", LEASE).orElseThrow();
        var valid = candidate(third, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true);
        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control(T0.plusMinutes(104), 3).settle(valid, (connection, result) -> true, noWrite(), ignored -> {}));
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENT_REPLAY,
                control(T0.plusMinutes(104), 3).settle(valid, (connection, result) -> true, noWrite(), ignored -> {}));
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENCY_CONFLICT,
                control(T0.plusMinutes(104), 3)
                        .settle(
                                candidate(third, FormationResultKind.NO_LONG_TERM_CHANGE, 3, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {}));
    }

    @Test
    void concurrentValidResultsHaveOneWinnerAndStopFailureIsHarmless() throws Exception {
        pendingA();
        FormationAttempt first =
                control(T0.plusMinutes(90), 3).claim("first", LEASE).orElseThrow();
        FormationAttempt second =
                control(T0.plusMinutes(96), 3).claim("second", LEASE).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger stopRequests = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<FormationSettlementOutcome> one = pool.submit(() -> {
                start.await();
                return control(T0.plusMinutes(97), 3)
                        .settle(
                                candidate(first, FormationResultKind.NO_LONG_TERM_CHANGE, 1, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {
                                    stopRequests.incrementAndGet();
                                    throw new IllegalStateException("remote stop unavailable");
                                });
            });
            Future<FormationSettlementOutcome> two = pool.submit(() -> {
                start.await();
                return control(T0.plusMinutes(97), 3)
                        .settle(
                                candidate(second, FormationResultKind.NO_LONG_TERM_CHANGE, 2, true),
                                (connection, result) -> true,
                                noWrite(),
                                ignored -> {
                                    stopRequests.incrementAndGet();
                                    throw new IllegalStateException("remote stop unavailable");
                                });
            });
            start.countDown();
            List<FormationSettlementOutcome> outcomes = List.of(one.get(), two.get());
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(v -> v == FormationSettlementOutcome.COMMITTED_NO_CHANGE)
                            .count());
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(v -> v == FormationSettlementOutcome.SLOT_ALREADY_SETTLED)
                            .count());
        }
        assertEquals(1, stopRequests.get());
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='WON'"));
        assertEquals(1, number("SELECT count(*) FROM runtime.formation_attempt WHERE state='STOP_REQUESTED'"));
    }

    private void pendingA() {
        intake(T0).recordSourceAdvanced(advance(1, 1));
    }

    private String taskState(FormationAttempt attempt) {
        return dsl.fetchOne("SELECT state FROM runtime.formation_task WHERE task_id=?::uuid", attempt.taskId())
                .get(0, String.class);
    }

    private String attemptState(FormationAttempt attempt) {
        return dsl.fetchOne("SELECT state FROM runtime.formation_attempt WHERE attempt_id=?::uuid", attempt.attemptId())
                .get(0, String.class);
    }

    private JooqFormationIntakeAdapter intake(OffsetDateTime time) {
        return new JooqFormationIntakeAdapter(dsl, Clock.fixed(time.toInstant(), ZoneOffset.UTC));
    }

    private JooqFormationControlAdapter control(OffsetDateTime time, int budget) {
        return new JooqFormationControlAdapter(
                dsl, Clock.fixed(time.toInstant(), ZoneOffset.UTC), budget, Duration.ZERO);
    }

    private SourceAdvance advance(long notification, long sequence) {
        var boundary = new SourceBoundary(sequence, "cursor-" + sequence, "source-v" + sequence);
        return new SourceAdvance(
                sourceId,
                notification,
                boundary,
                boundary,
                new SourceReadBinding(SourceReadBinding.Kind.STABLE_REREAD, "reader-ref", "reader-v" + sequence, null));
    }

    private static FormationSettlementCandidate candidate(
            FormationAttempt attempt, FormationResultKind kind, int hashByte, boolean complete) {
        byte[] hash = new byte[32];
        hash[0] = (byte) hashByte;
        return new FormationSettlementCandidate(
                attempt.taskId(),
                attempt.attemptId(),
                attempt.sourceId(),
                attempt.fromExclusive(),
                attempt.toInclusive(),
                attempt.readBinding(),
                1,
                UUID.nameUUIDFromBytes(hash),
                kind,
                hash,
                complete);
    }

    private static CanonicalCommitPort noWrite() {
        return (connection, result) -> {};
    }

    private static int number(String sql) {
        return dsl.fetchOne(sql).get(0, Integer.class);
    }

    private static boolean bool(String sql) {
        return Boolean.TRUE.equals(dsl.fetchOne(sql).get(0, Boolean.class));
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, postgres.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/v2/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }
}
