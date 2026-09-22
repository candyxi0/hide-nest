package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.v2.CreatePublication;
import io.github.candyxi0.hidenest.database.v2.adapter.JdbcCreatePublicationWriter;
import io.github.candyxi0.hidenest.database.v2.adapter.JooqFormationControlAdapter;
import io.github.candyxi0.hidenest.database.v2.adapter.JooqFormationIntakeAdapter;
import io.github.candyxi0.hidenest.evidence.v2.SourceResolver;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet;
import io.github.candyxi0.hidenest.memory.v2.CreateWriteSet.*;
import io.github.candyxi0.hidenest.runtime.domain.*;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class NestV2CanonicalCreateTest {
    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-22T20:00:00Z");
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext db;
    private UUID sourceId;
    private int resolverCalls;
    private FormationAttempt preparedAttempt;

    @BeforeAll
    static void start() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("nest_v2_create")
                .withUsername("hide_nest_migrator")
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            c.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            c.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(2, flyway("2").migrate().migrationsExecuted);
        assertEquals(1, flyway(null).migrate().migrationsExecuted);
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        flyway(null).validate();
        db = DSL.using(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                SQLDialect.POSTGRES);
    }

    @AfterAll
    static void stop() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void setup() {
        db.execute("TRUNCATE memory.projection_outbox,memory.create_receipt_item,memory.create_receipt,"
                + "memory.revision_relation,memory.revision_anchor,memory.revision,memory.record,"
                + "evidence.source_anchor,evidence.source_unit,runtime.source_write_gate,"
                + "runtime.formation_attention_notice,runtime.formation_attempt,runtime.formation_task "
                + "CASCADE");
        db.execute("DELETE FROM runtime.source_progress");
        db.execute("DELETE FROM runtime.source_registration");
        db.execute("DELETE FROM memory.world_binding");
        db.execute(
                "INSERT INTO memory.world_binding(singleton,world_ref,bound_at) VALUES(1,'world-1',?::timestamptz)",
                T0);
        sourceId = UUID.randomUUID();
        resolverCalls = 0;
        db.execute(
                "INSERT INTO runtime.source_registration(source_id,source_kind,platform,external_ref,registered_at) "
                        + "VALUES(?::uuid,'SYNTHETIC','TEST','source-1',?::timestamptz)",
                sourceId,
                T0);
        db.execute(
                "INSERT INTO runtime.source_write_gate(source_id,source_version,state,checked_at) "
                        + "VALUES(?::uuid,'source-v1','ALLOWED',?::timestamptz)",
                sourceId,
                T0);
    }

    @Test
    void migrationPermissionsAndNoV1Tables() {
        assertEquals(3, n("SELECT count(*) FROM public.flyway_schema_history WHERE success"));
        assertEquals(
                0,
                n("SELECT count(*) FROM information_schema.tables WHERE table_schema='public' "
                        + "AND table_name<>'flyway_schema_history'"));
        assertEquals(
                0,
                n("SELECT count(*) FROM information_schema.columns WHERE table_schema IN ('runtime','memory') "
                        + "AND table_name IN ('formation_task','formation_attempt','create_receipt','projection_outbox') "
                        + "AND column_name ~ '(body|content|write_set|draft|payload)'"));
        for (String table : List.of(
                "world_binding",
                "record",
                "revision",
                "revision_anchor",
                "revision_relation",
                "create_receipt",
                "create_receipt_item",
                "projection_outbox")) {
            assertFalse(priv("SELECT", "memory." + table));
            assertFalse(priv("INSERT", "memory." + table));
        }
        for (String table : List.of("source_unit", "source_anchor")) assertFalse(priv("SELECT", "evidence." + table));
        assertFalse(priv("SELECT", "runtime.source_write_gate"));
        try (Connection worker =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            worker.createStatement().execute("SET ROLE hide_nest_worker");
            assertThrows(java.sql.SQLException.class, () -> worker.createStatement()
                    .executeQuery("SELECT * FROM memory.record"));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void oneAndTwoCreateReplayAndNoChange() {
        FormationAttempt first = attempt();
        CreatePublication.Prepared request = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        FormationSettlementCandidate candidate = candidate(first, request, UUID.randomUUID());
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate, request));
        assertEquals(1, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor"));
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(1, n("SELECT processed_sequence FROM runtime.source_progress"));
        UUID revision = db.fetchOne("SELECT revision_id FROM memory.create_receipt_item")
                .get(0, UUID.class);
        assertEquals(FormationSettlementOutcome.IDEMPOTENT_REPLAY, publish(candidate, request));
        assertEquals(
                revision,
                db.fetchOne("SELECT revision_id FROM memory.create_receipt_item")
                        .get(0, UUID.class));
        assertEquals(1, n("SELECT count(*) FROM memory.revision"));
        try (Connection connection =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var receipt = new JdbcCreatePublicationWriter()
                    .findReceipt(connection, candidate.idempotencyKey(), request.hash())
                    .orElseThrow();
            assertEquals(revision, receipt.items().get(0).revisionId());
            assertEquals(candidate.taskId(), receipt.taskId());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        CreatePublication.Prepared changed = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENCY_CONFLICT,
                publish(candidate(first, changed, candidate.idempotencyKey()), changed));
        // A new source and task can publish a two-item set; shared anchors remain one Evidence object.
        setup();
        FormationAttempt second = attempt();
        CreatePublication.Prepared two = prepare(List.of(
                item(MemoryType.EVENT, List.of(anchor("a"), anchor("b")), List.of()),
                item(MemoryType.QUOTE, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(second, two, UUID.randomUUID()), two));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        assertEquals(2, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(3, n("SELECT count(*) FROM memory.revision_anchor"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
    }

    @Test
    void secondItemFailureRollsBackEverythingIncludingProcessed() {
        FormationAttempt a = attempt();
        CreatePublication.Prepared request = prepare(List.of(
                item(MemoryType.CLAIM, List.of(anchor("a")), List.of()),
                item(MemoryType.EVENT, List.of(anchor("b")), List.of())));
        db.execute("UPDATE runtime.source_write_gate SET state='BLOCKED'");
        assertEquals(FormationSettlementOutcome.RETRY_WAIT, publish(candidate(a, request, UUID.randomUUID()), request));
        assertZero();
        db.execute("UPDATE runtime.source_write_gate SET state='ALLOWED'");
        FormationAttempt retry = control(T0.plusMinutes(92))
                .claim("retry", Duration.ofMinutes(5))
                .orElseThrow();
        preparedAttempt = retry;
        // Deliberately fail after the first item's canonical writes, inside the shared transaction.
        var writer = new JdbcCreatePublicationWriter();
        var bad = prepare(List.of(
                item(MemoryType.CLAIM, List.of(anchor("a")), List.of()),
                item(
                        MemoryType.EVENT,
                        List.of(anchor("b")),
                        List.of(new RevisionRef(UUID.randomUUID(), RelationKind.SUPPORT)))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                CreatePublication.settle(
                        control(T0.plusMinutes(93)),
                        candidate(retry, bad, UUID.randomUUID()),
                        bad,
                        writer,
                        ignored -> {}));
        assertZero();
    }

    @Test
    void validationAndGovernanceRejectWithoutWrites() {
        FormationAttempt validationAttempt = attempt();
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(item(
                        MemoryType.CLAIM, List.of(new AnchorRef("a", "fabricated", null, null, null)), List.of()))));
        CreateWriteSet command = new CreateWriteSet(
                "world-1",
                sourceId,
                "source-1",
                "source-v1",
                List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () -> CreatePublication.prepare(
                        command,
                        bindingCandidate(validationAttempt),
                        (request, locator) -> new SourceResolver.VerifiedAnchor(
                                request.sourceId(),
                                request.sourceRef(),
                                "stale",
                                request.readBinding(),
                                locator,
                                "text-a",
                                1L,
                                1L,
                                null,
                                null,
                                null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CreatePublication.prepare(
                        command,
                        bindingCandidate(validationAttempt),
                        (request, locator) -> new SourceResolver.VerifiedAnchor(
                                request.sourceId(),
                                request.sourceRef(),
                                request.sourceVersion(),
                                request.readBinding(),
                                "wrong-locator",
                                "text-a",
                                1L,
                                1L,
                                null,
                                null,
                                null)));
        assertThrows(
                IllegalArgumentException.class,
                () -> CreatePublication.prepare(
                        command, bindingCandidate(validationAttempt), (request, locator) -> null));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(item(MemoryType.UNDERSTANDING, List.of(), List.of()))));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(new CreateItem(
                        "REVISE",
                        MemoryType.CLAIM,
                        "claim",
                        "subject",
                        "world-1",
                        "observed",
                        null,
                        null,
                        null,
                        "formation",
                        List.of(anchor("a")),
                        List.of()))));
        FormationAttempt a = validationAttempt;
        CreatePublication.Prepared request = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        byte[] wrong = new byte[32];
        FormationSettlementCandidate mismatch = new FormationSettlementCandidate(
                a.taskId(),
                a.attemptId(),
                a.sourceId(),
                a.fromExclusive(),
                a.toInclusive(),
                a.readBinding(),
                1,
                UUID.randomUUID(),
                FormationResultKind.WRITE_SET,
                wrong,
                true);
        assertEquals(FormationSettlementOutcome.INVALID_RESULT, publish(mismatch, request));
        assertZero();
        db.execute("UPDATE memory.world_binding SET world_ref='elsewhere'");
        FormationAttempt retry = control(T0.plusMinutes(92))
                .claim("retry", Duration.ofMinutes(5))
                .orElseThrow();
        preparedAttempt = retry;
        request = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT, publish(candidate(retry, request, UUID.randomUUID()), request));
        assertZero();
    }

    @Test
    void understandingSupportAndFrameAreBound() {
        FormationAttempt a = attempt();
        CreatePublication.Prepared event = prepare(List.of(item(MemoryType.EVENT, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(a, event, UUID.randomUUID()), event));
        UUID support = db.fetchOne("SELECT revision_id FROM memory.revision").get(0, UUID.class);
        newSource();
        FormationAttempt b = attempt();
        CreatePublication.Prepared understanding = prepare(List.of(
                item(MemoryType.UNDERSTANDING, List.of(), List.of(new RevisionRef(support, RelationKind.SUPPORT)))));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(b, understanding, UUID.randomUUID()), understanding));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_relation"));
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        newSource();
        FormationAttempt absent = attempt();
        CreatePublication.Prepared missing = prepare(List.of(item(
                MemoryType.UNDERSTANDING,
                List.of(),
                List.of(new RevisionRef(UUID.randomUUID(), RelationKind.SUPPORT)))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT, publish(candidate(absent, missing, UUID.randomUUID()), missing));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        newSource();
        FormationAttempt framedAttempt = attempt();
        CreatePublication.Prepared framed = prepare(List.of(new CreateItem(
                "CREATE",
                MemoryType.QUOTE,
                "quote",
                "subject",
                "world-1",
                "role",
                null,
                null,
                null,
                "formation",
                List.of(new AnchorRef("g", "text-g", "game-1", "actor", "role")),
                List.of())));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(framedAttempt, framed, UUID.randomUUID()), framed));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        newSource();
        FormationAttempt gameAttempt = attempt();
        CreatePublication.Prepared game = prepare(List.of(new CreateItem(
                "CREATE",
                MemoryType.QUOTE,
                "quote",
                "subject",
                "game-1",
                "role",
                null,
                null,
                null,
                "formation",
                List.of(new AnchorRef("g", "text-g", "game-1", "actor", "role")),
                List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(gameAttempt, game, UUID.randomUUID()), game));
        assertEquals(
                "game-1",
                db.fetchOne("SELECT frame FROM evidence.source_anchor WHERE locator='g'")
                        .get(0, String.class));
        assertEquals(
                "role",
                db.fetchOne("SELECT speaking_as FROM evidence.source_anchor WHERE locator='g'")
                        .get(0, String.class));
    }

    @Test
    void existingSupportCycleCannotBecomeAnUnderstandingPath() {
        FormationAttempt first = attempt();
        CreatePublication.Prepared event = prepare(List.of(item(MemoryType.EVENT, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(first, event, UUID.randomUUID()), event));
        UUID eventRevision =
                db.fetchOne("SELECT revision_id FROM memory.revision").get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreatePublication.Prepared claim = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(second, claim, UUID.randomUUID()), claim));
        UUID claimRevision = db.fetchOne(
                        "SELECT revision_id FROM memory.revision WHERE revision_id<>?::uuid", eventRevision)
                .get(0, UUID.class);
        db.execute(
                "INSERT INTO memory.revision_relation(revision_id,target_revision_id,relation_kind) "
                        + "VALUES(?::uuid,?::uuid,'SUPPORT'),(?::uuid,?::uuid,'SUPPORT')",
                eventRevision,
                claimRevision,
                claimRevision,
                eventRevision);
        newSource();
        FormationAttempt third = attempt();
        CreatePublication.Prepared understanding = prepare(List.of(item(
                MemoryType.UNDERSTANDING, List.of(), List.of(new RevisionRef(eventRevision, RelationKind.SUPPORT)))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(third, understanding, UUID.randomUUID()), understanding));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
    }

    @Test
    void noLongTermChangeUsesCheckpointWithoutCanonicalWrites() {
        FormationAttempt a = attempt();
        byte[] hash = new byte[32];
        hash[0] = 7;
        FormationSettlementCandidate noChange = new FormationSettlementCandidate(
                a.taskId(),
                a.attemptId(),
                a.sourceId(),
                a.fromExclusive(),
                a.toInclusive(),
                a.readBinding(),
                1,
                UUID.randomUUID(),
                FormationResultKind.NO_LONG_TERM_CHANGE,
                hash,
                true);
        assertEquals(
                FormationSettlementOutcome.COMMITTED_NO_CHANGE,
                control(T0.plusMinutes(91))
                        .settle(
                                noChange,
                                (connection, candidate) -> true,
                                (connection, candidate) -> fail("canonical writer called for NO_LONG_TERM_CHANGE"),
                                ignored -> {}));
        assertEquals(1, n("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(0, n("SELECT count(*) FROM memory.record"));
        assertEquals(0, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(0, n("SELECT count(*) FROM memory.projection_outbox"));
    }

    @Test
    void missingBlockedAndStaleSourceGateFailClosed() {
        for (String mutation : List.of(
                "DELETE FROM runtime.source_write_gate",
                "UPDATE runtime.source_write_gate SET state='BLOCKED'",
                "UPDATE runtime.source_write_gate SET source_version='stale'")) {
            setup();
            FormationAttempt a = attempt();
            CreatePublication.Prepared request =
                    prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
            db.execute(mutation);
            assertEquals(
                    FormationSettlementOutcome.RETRY_WAIT, publish(candidate(a, request, UUID.randomUUID()), request));
            assertZero();
        }
    }

    @Test
    void frozenToInclusiveAllowsHistoryAndEdgeButRejectsFutureOrUncomparableRanges() {
        for (String locator : List.of("hist-50", "edge-120")) {
            setup();
            FormationAttempt attempt = frozenAttempt();
            CreatePublication.Prepared request = prepare(List.of(item(
                    MemoryType.CLAIM,
                    List.of(new AnchorRef(locator, "text-" + locator, null, null, null)),
                    List.of())));
            assertEquals(
                    FormationSettlementOutcome.COMMITTED_WRITE,
                    publish(candidate(attempt, request, UUID.randomUUID()), request));
            assertEquals(120, n("SELECT processed_sequence FROM runtime.source_progress"));
            assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        }
        for (String locator : List.of("future-121", "cross-119-121", "unknown", "reverse")) {
            setup();
            frozenAttempt();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> prepare(List.of(item(
                            MemoryType.CLAIM,
                            List.of(new AnchorRef(locator, "text-" + locator, null, null, null)),
                            List.of()))));
            assertEquals(100, n("SELECT processed_sequence FROM runtime.source_progress"));
            assertNoCanonicalWrites();
        }
    }

    @Test
    void preparedRequestCannotBeReboundToAnotherTaskBoundaryOrReadBinding() {
        setup();
        FormationAttempt attempt = frozenAttempt();
        CreatePublication.Prepared request = prepare(List.of(item(
                MemoryType.CLAIM, List.of(new AnchorRef("hist-50", "text-hist-50", null, null, null)), List.of())));
        FormationSettlementCandidate otherTask = new FormationSettlementCandidate(
                UUID.randomUUID(),
                UUID.randomUUID(),
                attempt.sourceId(),
                attempt.fromExclusive(),
                attempt.toInclusive(),
                attempt.readBinding(),
                1,
                UUID.randomUUID(),
                FormationResultKind.WRITE_SET,
                request.hash(),
                true);
        assertEquals(FormationSettlementOutcome.INVALID_RESULT, publish(otherTask, request));
        FormationSettlementCandidate otherBoundary = new FormationSettlementCandidate(
                attempt.taskId(),
                attempt.attemptId(),
                attempt.sourceId(),
                attempt.fromExclusive(),
                new SourceBoundary(119, "cursor-119", "source-v1"),
                attempt.readBinding(),
                1,
                UUID.randomUUID(),
                FormationResultKind.WRITE_SET,
                request.hash(),
                true);
        assertEquals(FormationSettlementOutcome.INVALID_RESULT, publish(otherBoundary, request));
        SourceReadBinding alternate =
                new SourceReadBinding(SourceReadBinding.Kind.STABLE_REREAD, "other-reader", "reader-v120", null);
        FormationSettlementCandidate otherBinding = new FormationSettlementCandidate(
                attempt.taskId(),
                attempt.attemptId(),
                attempt.sourceId(),
                attempt.fromExclusive(),
                attempt.toInclusive(),
                alternate,
                1,
                UUID.randomUUID(),
                FormationResultKind.WRITE_SET,
                request.hash(),
                true);
        assertEquals(FormationSettlementOutcome.INVALID_RESULT, publish(otherBinding, request));
        assertNoCanonicalWrites();
    }

    @Test
    void resolverBindingMismatchFailsClosedBeforeAnyWrite() {
        setup();
        FormationAttempt attempt = frozenAttempt();
        SourceResolver badResolver = (request, locator) -> new SourceResolver.VerifiedAnchor(
                UUID.randomUUID(),
                request.sourceRef(),
                request.sourceVersion(),
                request.readBinding(),
                locator,
                "text-" + locator,
                50L,
                50L,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> prepareWithResolver(
                        List.of(item(
                                MemoryType.CLAIM,
                                List.of(new AnchorRef("hist-50", "text-hist-50", null, null, null)),
                                List.of())),
                        badResolver));
        SourceResolver wrongBinding = (request, locator) -> new SourceResolver.VerifiedAnchor(
                request.sourceId(),
                request.sourceRef(),
                request.sourceVersion(),
                new SourceResolver.ReadBinding(
                        "STABLE_REREAD", "other-reader", request.readBinding().version(), null),
                locator,
                "text-" + locator,
                50L,
                50L,
                null,
                null,
                null);
        assertThrows(
                IllegalArgumentException.class,
                () -> prepareWithResolver(
                        List.of(item(
                                MemoryType.CLAIM,
                                List.of(new AnchorRef("hist-50", "text-hist-50", null, null, null)),
                                List.of())),
                        wrongBinding));
        assertEquals(100, n("SELECT processed_sequence FROM runtime.source_progress"));
        assertNoCanonicalWrites();
        assertNotNull(attempt);
    }

    private FormationAttempt attempt() {
        var boundary = new SourceBoundary(1, "cursor-1", "source-v1");
        intake().recordSourceAdvanced(new SourceAdvance(
                sourceId,
                1,
                boundary,
                boundary,
                new SourceReadBinding(SourceReadBinding.Kind.STABLE_REREAD, "reader-ref", "reader-v1", null)));
        preparedAttempt = control(T0.plusMinutes(90))
                .claim("worker", Duration.ofMinutes(5))
                .orElseThrow();
        return preparedAttempt;
    }

    private FormationAttempt frozenAttempt() {
        SourceBoundary initial = new SourceBoundary(100, "cursor-100", "source-v1");
        db.execute(
                "INSERT INTO runtime.source_progress(source_id,last_notification_sequence,last_notification_hash,"
                        + "discovered_sequence,discovered_cursor,discovered_source_version,stable_sequence,stable_cursor,"
                        + "stable_source_version,processed_sequence,processed_cursor,processed_source_version,read_kind,"
                        + "read_ref,read_version,last_real_change_at,quiet_until,updated_at) VALUES(?::uuid,1,decode(repeat('00',32),'hex'),"
                        + "100,'cursor-100','source-v1',100,'cursor-100','source-v1',100,'cursor-100','source-v1',"
                        + "'STABLE_REREAD','reader-ref','reader-v100',?::timestamptz,?::timestamptz,?::timestamptz)",
                sourceId,
                T0,
                T0.plusMinutes(90),
                T0);
        SourceBoundary frozen = new SourceBoundary(120, "cursor-120", "source-v1");
        intake().recordSourceAdvanced(new SourceAdvance(
                sourceId,
                2,
                frozen,
                frozen,
                new SourceReadBinding(SourceReadBinding.Kind.STABLE_REREAD, "reader-ref", "reader-v120", null)));
        preparedAttempt = control(T0.plusMinutes(90))
                .claim("worker", Duration.ofMinutes(5))
                .orElseThrow();
        return preparedAttempt;
    }

    private void newSource() {
        sourceId = UUID.randomUUID();
        db.execute(
                "INSERT INTO runtime.source_registration(source_id,source_kind,platform,external_ref,registered_at) "
                        + "VALUES(?::uuid,'SYNTHETIC',?,'source-1',?::timestamptz)",
                sourceId,
                "TEST-" + sourceId,
                T0);
        db.execute(
                "INSERT INTO runtime.source_write_gate(source_id,source_version,state,checked_at) "
                        + "VALUES(?::uuid,'source-v1','ALLOWED',?::timestamptz)",
                sourceId,
                T0);
    }

    private CreatePublication.Prepared prepare(List<CreateItem> items) {
        if (preparedAttempt == null) throw new IllegalStateException("attempt must be claimed before prepare");
        SourceResolver resolver = (request, locator) -> {
            resolverCalls++;
            long position =
                    switch (locator) {
                        case "hist-50" -> 50L;
                        case "edge-120" -> 120L;
                        case "future-121" -> 121L;
                        case "cross-119-121" -> 121L;
                        case "unknown" -> 0L;
                        case "reverse" -> 9L;
                        default -> 1L;
                    };
            Long from = position == 0 ? null : position;
            Long to = position == 0 ? null : position;
            if ("reverse".equals(locator)) from = 10L;
            return new SourceResolver.VerifiedAnchor(
                    request.sourceId(),
                    request.sourceRef(),
                    request.sourceVersion(),
                    request.readBinding(),
                    locator,
                    "text-" + locator,
                    from,
                    to,
                    locator.equals("g") ? "game-1" : null,
                    locator.equals("g") ? "actor" : null,
                    locator.equals("g") ? "role" : null);
        };
        return prepareWithResolver(items, resolver);
    }

    private CreatePublication.Prepared prepareWithResolver(List<CreateItem> items, SourceResolver resolver) {
        if (preparedAttempt == null) throw new IllegalStateException("attempt must be claimed before prepare");
        return CreatePublication.prepare(
                new CreateWriteSet("world-1", sourceId, "source-1", "source-v1", items),
                bindingCandidate(preparedAttempt),
                resolver);
    }

    private static FormationSettlementCandidate bindingCandidate(FormationAttempt attempt) {
        return new FormationSettlementCandidate(
                attempt.taskId(),
                attempt.attemptId(),
                attempt.sourceId(),
                attempt.fromExclusive(),
                attempt.toInclusive(),
                attempt.readBinding(),
                1,
                UUID.randomUUID(),
                FormationResultKind.WRITE_SET,
                new byte[32],
                true);
    }

    private static CreateItem item(MemoryType type, List<AnchorRef> anchors, List<RevisionRef> relations) {
        return new CreateItem(
                "CREATE",
                type,
                "content",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                anchors,
                relations);
    }

    private static AnchorRef anchor(String locator) {
        return new AnchorRef(locator, "text-" + locator, null, null, null);
    }

    private FormationSettlementCandidate candidate(FormationAttempt a, CreatePublication.Prepared request, UUID key) {
        return new FormationSettlementCandidate(
                a.taskId(),
                a.attemptId(),
                a.sourceId(),
                a.fromExclusive(),
                a.toInclusive(),
                a.readBinding(),
                1,
                key,
                FormationResultKind.WRITE_SET,
                request.hash(),
                true);
    }

    private FormationSettlementOutcome publish(FormationSettlementCandidate c, CreatePublication.Prepared request) {
        int before = resolverCalls;
        FormationSettlementOutcome result = CreatePublication.settle(
                control(T0.plusMinutes(91)), c, request, new JdbcCreatePublicationWriter(), ignored -> {});
        assertEquals(before, resolverCalls, "source reread occurred inside settlement");
        return result;
    }

    private void assertZero() {
        assertNoCanonicalWrites();
        assertEquals(0, n("SELECT count(*) FROM runtime.source_progress WHERE processed_sequence IS NOT NULL"));
        assertEquals(0, n("SELECT count(*) FROM runtime.formation_task WHERE state='COMMITTED_WRITE'"));
    }

    private void assertNoCanonicalWrites() {
        for (String table : List.of(
                "memory.record",
                "memory.revision",
                "memory.create_receipt",
                "memory.projection_outbox",
                "evidence.source_unit",
                "evidence.source_anchor")) assertEquals(0, n("SELECT count(*) FROM " + table), table);
    }

    private JooqFormationIntakeAdapter intake() {
        return new JooqFormationIntakeAdapter(db, Clock.fixed(T0.toInstant(), ZoneOffset.UTC));
    }

    private JooqFormationControlAdapter control(OffsetDateTime time) {
        return new JooqFormationControlAdapter(db, Clock.fixed(time.toInstant(), ZoneOffset.UTC), 3, Duration.ZERO);
    }

    private static int n(String sql) {
        return db.fetchOne(sql).get(0, Integer.class);
    }

    private static boolean priv(String p, String table) {
        return Boolean.TRUE.equals(
                db.fetchOne("SELECT has_table_privilege('hide_nest_worker','" + table + "','" + p + "')")
                        .get(0, Boolean.class));
    }

    private static Flyway flyway(String target) {
        var c = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/v2/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) c.target(target);
        return c.load();
    }
}
