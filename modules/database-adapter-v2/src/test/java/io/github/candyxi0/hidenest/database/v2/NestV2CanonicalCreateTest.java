package io.github.candyxi0.hidenest.database.v2;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.v2.CreatePublication;
import io.github.candyxi0.hidenest.database.v2.adapter.JdbcCreatePublicationWriter;
import io.github.candyxi0.hidenest.database.v2.adapter.JdbcRecordSuccessionReader;
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
        assertEquals(1, flyway("3").migrate().migrationsExecuted);
        db = DSL.using(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()),
                SQLDialect.POSTGRES);
        NestV2CanonicalCreateTest legacy = new NestV2CanonicalCreateTest();
        legacy.setup();
        FormationAttempt task = legacy.attempt();
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        byte[] hash = new byte[32];
        db.transaction(config -> {
            DSLContext tx = config.dsl();
            tx.execute(
                    "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                            + "perspective,formation_ref,task_id,created_at) VALUES(?::uuid,?::uuid,1,'legacy',"
                            + "'subject','world-1','observed','formation',?::uuid,?::timestamptz)",
                    revision,
                    record,
                    task.taskId(),
                    T0);
            tx.execute(
                    "INSERT INTO memory.record(record_id,type,current_revision_id) VALUES(?::uuid,'CLAIM',?::uuid)",
                    record,
                    revision);
            tx.execute(
                    "INSERT INTO memory.create_receipt(idempotency_key,request_hash,task_id,attempt_id,created_at) "
                            + "VALUES(?::uuid,?,?::uuid,?::uuid,?::timestamptz)",
                    key,
                    hash,
                    task.taskId(),
                    task.attemptId(),
                    T0);
            tx.execute(
                    "INSERT INTO memory.create_receipt_item(idempotency_key,item_index,record_id,revision_id) "
                            + "VALUES(?::uuid,0,?::uuid,?::uuid)",
                    key,
                    record,
                    revision);
            tx.execute(
                    "INSERT INTO memory.projection_outbox(event_id,event_kind,record_id,revision_id,request_hash,created_at) "
                            + "VALUES(?::uuid,'MEMORY_CREATED',?::uuid,?::uuid,?,?::timestamptz)",
                    UUID.randomUUID(),
                    record,
                    revision,
                    hash,
                    T0);
        });
        assertEquals(1, flyway("4").migrate().migrationsExecuted);
        legacy.newSource();
        FormationAttempt reviseTask = legacy.attempt();
        UUID revisedRevision = UUID.randomUUID();
        UUID reviseKey = UUID.randomUUID();
        db.transaction(config -> {
            DSLContext tx = config.dsl();
            tx.execute(
                    "INSERT INTO memory.revision(revision_id,record_id,revision_no,content,subject,scope,"
                            + "perspective,formation_ref,task_id,created_at) VALUES(?::uuid,?::uuid,2,'legacy-revised',"
                            + "'subject','world-1','observed','formation',?::uuid,?::timestamptz)",
                    revisedRevision,
                    record,
                    reviseTask.taskId(),
                    T0);
            tx.execute(
                    "UPDATE memory.record SET current_revision_id=?::uuid WHERE record_id=?::uuid",
                    revisedRevision,
                    record);
            tx.execute(
                    "INSERT INTO memory.create_receipt(idempotency_key,request_hash,task_id,attempt_id,created_at) "
                            + "VALUES(?::uuid,?,?::uuid,?::uuid,?::timestamptz)",
                    reviseKey,
                    hash,
                    reviseTask.taskId(),
                    reviseTask.attemptId(),
                    T0);
            tx.execute(
                    "INSERT INTO memory.create_receipt_item(idempotency_key,item_index,record_id,revision_id,action,expected_revision_id) "
                            + "VALUES(?::uuid,0,?::uuid,?::uuid,'REVISE',?::uuid)",
                    reviseKey,
                    record,
                    revisedRevision,
                    revision);
            tx.execute(
                    "INSERT INTO memory.projection_outbox(event_id,event_kind,record_id,revision_id,request_hash,created_at,previous_revision_id) "
                            + "VALUES(?::uuid,'MEMORY_REVISED',?::uuid,?::uuid,?,?::timestamptz,?::uuid)",
                    UUID.randomUUID(),
                    record,
                    revisedRevision,
                    hash,
                    T0,
                    revision);
        });
        assertEquals(1, flyway("5").migrate().migrationsExecuted);
        legacy.newSource();
        FormationAttempt supersedeTask = legacy.attempt();
        CreatePublication.Prepared supersede =
                legacy.prepare(List.of(supersede(MemoryType.CLAIM, record, revisedRevision, "a")));
        UUID supersedeKey = UUID.randomUUID();
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                legacy.publish(legacy.candidate(supersedeTask, supersede, supersedeKey), supersede));
        assertEquals(1, legacy.n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(2, flyway(null).migrate().migrationsExecuted);
        assertEquals(0, flyway(null).migrate().migrationsExecuted);
        flyway(null).validate();
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENT_REPLAY,
                legacy.publish(legacy.candidate(supersedeTask, supersede, supersedeKey), supersede));
        assertEquals(1, legacy.n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(
                "legacy",
                db.fetchOne("SELECT content FROM memory.revision WHERE revision_id=?::uuid", revision)
                        .get(0, String.class));
        assertEquals(
                "CREATE",
                db.fetchOne("SELECT action FROM memory.create_receipt_item WHERE idempotency_key=?::uuid", key)
                        .get(0, String.class));
        assertEquals(1, legacy.n("SELECT count(*) FROM memory.projection_outbox WHERE event_kind='MEMORY_CREATED'"));
        assertEquals(
                record,
                db.fetchOne("SELECT predecessor_record_id FROM memory.create_receipt_item " + "WHERE action='REVISE'")
                        .get(0, UUID.class));
        assertEquals(
                record,
                db.fetchOne("SELECT predecessor_record_id FROM memory.projection_outbox "
                                + "WHERE event_kind='MEMORY_REVISED'")
                        .get(0, UUID.class));
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
        assertEquals(7, n("SELECT count(*) FROM public.flyway_schema_history WHERE success"));
        assertEquals(
                0,
                n("SELECT count(*) FROM pg_constraint WHERE conrelid='memory.record_succession'::regclass "
                        + "AND conname='record_succession_task_id_key'"));
        assertEquals(
                1,
                n("SELECT count(*) FROM pg_constraint WHERE conrelid='memory.record_succession'::regclass "
                        + "AND conname='record_succession_task_id_fkey' AND contype='f'"));
        assertEquals(
                1,
                n("SELECT count(*) FROM pg_constraint WHERE conrelid='memory.record_succession'::regclass "
                        + "AND conname='record_succession_pkey' AND contype='p'"));
        assertTrue(Boolean.TRUE.equals(db.fetchOne("SELECT has_column_privilege('hide_nest_api',"
                        + "'memory.record','current_revision_id','UPDATE')")
                .get(0, Boolean.class)));
        assertFalse(Boolean.TRUE.equals(
                db.fetchOne("SELECT has_column_privilege('hide_nest_api'," + "'memory.revision','content','UPDATE')")
                        .get(0, Boolean.class)));
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
                "record_succession",
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
    void sameBatchSupportResolvesForwardAndBackwardAndReplays() throws Exception {
        FormationAttempt first = frozenAttempt();
        CreatePublication.Prepared forward = prepare(List.of(
                named(
                        "understanding",
                        MemoryType.UNDERSTANDING,
                        List.of(),
                        List.of(RevisionRef.item("event", RelationKind.SUPPORT))),
                named("event", MemoryType.EVENT, List.of(anchor("mid-105"), anchor("edge-120")), List.of())));
        UUID key = UUID.randomUUID();
        FormationSettlementCandidate submitted = candidate(first, forward, key);
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(submitted, forward));
        UUID understanding = db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item WHERE idempotency_key=?::uuid AND item_index=0",
                        key)
                .get(0, UUID.class);
        UUID event = db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item WHERE idempotency_key=?::uuid AND item_index=1",
                        key)
                .get(0, UUID.class);
        assertEquals(
                event,
                db.fetchOne(
                                "SELECT target_revision_id FROM memory.revision_relation WHERE revision_id=?::uuid",
                                understanding)
                        .get(0, UUID.class));
        assertEquals(2, n("SELECT count(*) FROM memory.revision_anchor WHERE revision_id='" + event + "'"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(120, n("SELECT processed_sequence FROM runtime.source_progress"));
        assertEquals(FormationSettlementOutcome.IDEMPOTENT_REPLAY, publish(submitted, forward));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var receipt = new JdbcCreatePublicationWriter()
                    .findReceipt(c, key, forward.hash())
                    .orElseThrow();
            assertEquals(
                    List.of(understanding, event),
                    receipt.items().stream()
                            .map(JdbcCreatePublicationWriter.PublishedItem::revisionId)
                            .toList());
        }
        CreatePublication.Prepared changed = prepare(List.of(
                named(
                        "understanding",
                        MemoryType.UNDERSTANDING,
                        List.of(),
                        List.of(RevisionRef.item("event", RelationKind.SUPPORT))),
                named("event", MemoryType.EVENT, List.of(anchor("a")), List.of())));
        assertEquals(FormationSettlementOutcome.IDEMPOTENCY_CONFLICT, publish(candidate(first, changed, key), changed));
        newSource();
        FormationAttempt second = attempt();
        CreatePublication.Prepared backward = prepare(List.of(
                named("event", MemoryType.EVENT, List.of(anchor("a")), List.of()),
                named(
                        "understanding",
                        MemoryType.UNDERSTANDING,
                        List.of(),
                        List.of(RevisionRef.item("event", RelationKind.SUPPORT)))));
        UUID secondKey = UUID.randomUUID();
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(second, backward, secondKey), backward));
        UUID secondEvent = db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item WHERE idempotency_key=?::uuid AND item_index=0",
                        secondKey)
                .get(0, UUID.class);
        assertEquals(
                secondEvent,
                db.fetchOne(
                                "SELECT target_revision_id FROM memory.revision_relation WHERE revision_id=(SELECT revision_id "
                                        + "FROM memory.create_receipt_item WHERE idempotency_key=?::uuid AND item_index=1)",
                                secondKey)
                        .get(0, UUID.class));
        assertEquals(
                0,
                n("SELECT count(*) FROM information_schema.columns WHERE table_schema='memory' "
                        + "AND table_name IN ('revision_relation','create_receipt_item','projection_outbox') "
                        + "AND column_name='item_ref'"));
    }

    @Test
    void committedRevisionOnlyHashKeepsPublishedEncoding() {
        CreateWriteSet legacy = new CreateWriteSet(
                "w",
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "s",
                "v",
                List.of(new CreateItem(
                        "CREATE",
                        MemoryType.EVENT,
                        "c",
                        "subject",
                        "scope",
                        "perspective",
                        null,
                        null,
                        null,
                        "f",
                        List.of(new AnchorRef("a", "text-a", null, null, null)),
                        List.of(new RevisionRef(
                                UUID.fromString("00000000-0000-0000-0000-000000000002"), RelationKind.SUPPORT)))));
        assertEquals(
                "731adfda1734d7797e134e62862bec6ee2db918d9262623d0b48c687951ce7cb",
                java.util.HexFormat.of().formatHex(CreatePublication.hash(legacy)));
    }

    @Test
    void sameBatchCanSupportAndCounterLocalAndCommittedRevisions() {
        FormationAttempt first = attempt();
        CreatePublication.Prepared committed = prepare(List.of(
                item(MemoryType.EVENT, List.of(anchor("a")), List.of()),
                item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        UUID priorKey = UUID.randomUUID();
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(first, committed, priorKey), committed));
        UUID oldSupport = db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item "
                                + "WHERE idempotency_key=?::uuid AND item_index=0",
                        priorKey)
                .get(0, UUID.class);
        UUID oldCounter = db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item "
                                + "WHERE idempotency_key=?::uuid AND item_index=1",
                        priorKey)
                .get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreatePublication.Prepared mixed = prepare(List.of(
                named(
                        "understanding",
                        MemoryType.UNDERSTANDING,
                        List.of(),
                        List.of(
                                RevisionRef.item("event", RelationKind.SUPPORT),
                                new RevisionRef(oldSupport, RelationKind.SUPPORT),
                                RevisionRef.item("claim", RelationKind.COUNTER),
                                new RevisionRef(oldCounter, RelationKind.COUNTER))),
                named("event", MemoryType.EVENT, List.of(anchor("b")), List.of()),
                named("claim", MemoryType.CLAIM, List.of(anchor("c")), List.of())));
        UUID key = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(second, mixed, key), mixed));
        assertEquals(
                4,
                n("SELECT count(*) FROM memory.revision_relation WHERE revision_id=(SELECT revision_id "
                        + "FROM memory.create_receipt_item WHERE idempotency_key='" + key + "' AND item_index=0)"));
        assertEquals(2, n("SELECT count(*) FROM memory.revision_relation WHERE relation_kind='COUNTER'"));
    }

    @Test
    void invalidLocalGraphsAndLateRelationFailureDoNotPublish() {
        FormationAttempt a = attempt();
        CreateItem event = named("event", MemoryType.EVENT, List.of(anchor("a")), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(
                        event,
                        named(
                                "event",
                                MemoryType.UNDERSTANDING,
                                List.of(),
                                List.of(RevisionRef.item("event", RelationKind.SUPPORT))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(
                        event,
                        named(
                                "understanding",
                                MemoryType.UNDERSTANDING,
                                List.of(),
                                List.of(RevisionRef.item("missing", RelationKind.SUPPORT))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(named(
                        "event",
                        MemoryType.EVENT,
                        List.of(anchor("a")),
                        List.of(RevisionRef.item("event", RelationKind.SUPPORT))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(
                        event,
                        named(
                                "understanding",
                                MemoryType.UNDERSTANDING,
                                List.of(),
                                List.of(
                                        RevisionRef.item("event", RelationKind.SUPPORT),
                                        RevisionRef.item("event", RelationKind.COUNTER))))));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(
                        named(
                                "understanding",
                                MemoryType.UNDERSTANDING,
                                List.of(),
                                List.of(RevisionRef.item("event", RelationKind.COUNTER))),
                        event)));
        CreatePublication.Prepared cycle = prepare(List.of(
                named(
                        "left",
                        MemoryType.EVENT,
                        List.of(anchor("a")),
                        List.of(RevisionRef.item("right", RelationKind.SUPPORT))),
                named(
                        "right",
                        MemoryType.EVENT,
                        List.of(anchor("b")),
                        List.of(RevisionRef.item("left", RelationKind.SUPPORT)))));
        assertEquals(FormationSettlementOutcome.RETRY_WAIT, publish(candidate(a, cycle, UUID.randomUUID()), cycle));
        assertZero();
        FormationAttempt retry = control(T0.plusMinutes(92))
                .claim("retry", Duration.ofMinutes(5))
                .orElseThrow();
        preparedAttempt = retry;
        CreatePublication.Prepared lateFailure = prepare(List.of(
                event,
                named(
                        "understanding",
                        MemoryType.UNDERSTANDING,
                        List.of(),
                        List.of(
                                RevisionRef.item("event", RelationKind.SUPPORT),
                                new RevisionRef(UUID.randomUUID(), RelationKind.COUNTER)))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(retry, lateFailure, UUID.randomUUID()), lateFailure));
        assertZero();
    }

    @Test
    void denseAcyclicSupportGraphHasBoundedCycleCheck() {
        FormationAttempt a = attempt();
        int layers = 32;
        List<CreateItem> items = new java.util.ArrayList<>();
        for (int layer = 0; layer < layers; layer++) {
            for (String branch : List.of("a", "b")) {
                List<RevisionRef> relations = layer == layers - 1
                        ? List.of()
                        : List.of(
                                RevisionRef.item("node-" + (layer + 1) + "-a", RelationKind.SUPPORT),
                                RevisionRef.item("node-" + (layer + 1) + "-b", RelationKind.SUPPORT));
                items.add(named("node-" + layer + "-" + branch, MemoryType.EVENT, List.of(anchor("a")), relations));
            }
        }
        CreatePublication.Prepared request = prepare(items);
        FormationSettlementCandidate submitted = candidate(a, request, UUID.randomUUID());
        int callsBeforeSettlement = resolverCalls;
        var sqlState = new java.util.concurrent.atomic.AtomicReference<String>();
        FormationSettlementOutcome outcome = CreatePublication.settle(
                control(T0.plusMinutes(91)),
                submitted,
                request,
                (connection, result, prepared) -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET LOCAL statement_timeout = '1500ms'");
                    }
                    try {
                        new JdbcCreatePublicationWriter().commit(connection, result, prepared);
                    } catch (java.sql.SQLException failure) {
                        sqlState.set(failure.getSQLState());
                        throw failure;
                    }
                },
                ignored -> {});
        assertEquals(callsBeforeSettlement, resolverCalls);
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, outcome, "SQLSTATE=" + sqlState.get());
        assertEquals(64, n("SELECT count(*) FROM memory.record"));
        assertEquals(124, n("SELECT count(*) FROM memory.revision_relation"));
        assertEquals(64, n("SELECT count(*) FROM memory.create_receipt_item"));
        assertEquals(64, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(1, n("SELECT processed_sequence FROM runtime.source_progress"));
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

    @Test
    void reviseSwitchesCurrentReplaysAndRejectsStaleCurrent() throws Exception {
        FormationAttempt first = attempt();
        CreatePublication.Prepared created = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(first, created, UUID.randomUUID()), created));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID old = db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreateItem revision = new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "30 seconds",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(),
                "revised-claim",
                new ExpectedCurrent(record, old));
        CreatePublication.Prepared revised = prepare(List.of(revision));
        UUID key = UUID.randomUUID();
        FormationSettlementCandidate request = candidate(second, revised, key);
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(request, revised));
        UUID current = db.fetchOne("SELECT current_revision_id FROM memory.record WHERE record_id=?::uuid", record)
                .get(0, UUID.class);
        assertNotEquals(old, current);
        assertEquals(2, n("SELECT count(*) FROM memory.revision WHERE record_id='" + record + "'"));
        assertEquals(1, n("SELECT revision_no FROM memory.revision WHERE revision_id='" + current + "'") - 1);
        assertEquals(
                "content",
                db.fetchOne("SELECT content FROM memory.revision WHERE revision_id=?::uuid", old)
                        .get(0, String.class));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor WHERE revision_id='" + old + "'"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor WHERE revision_id='" + current + "'"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(FormationSettlementOutcome.IDEMPOTENT_REPLAY, publish(request, revised));
        CreatePublication.Prepared changed = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "different",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(),
                "revised-claim",
                new ExpectedCurrent(record, old))));
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENCY_CONFLICT, publish(candidate(second, changed, key), changed));
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var receipt = new JdbcCreatePublicationWriter()
                    .findReceipt(c, key, revised.hash())
                    .orElseThrow();
            assertEquals(current, receipt.items().get(0).revisionId());
            assertEquals("REVISE", receipt.items().get(0).action());
            assertEquals(old, receipt.items().get(0).expectedRevisionId());
        }
        newSource();
        FormationAttempt third = attempt();
        CreatePublication.Prepared stale = prepare(List.of(revision));
        assertEquals(
                FormationSettlementOutcome.CURRENT_CONFLICT,
                publish(candidate(third, stale, UUID.randomUUID()), stale));
        assertEquals(
                "CURRENT_CONFLICT",
                db.fetchOne(
                                "SELECT failure_code FROM runtime.formation_attempt WHERE attempt_id=?::uuid",
                                third.attemptId())
                        .get(0, String.class));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
        assertEquals(2, n("SELECT count(*) FROM memory.revision"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(revision, item(MemoryType.CLAIM, List.of(anchor("a")), List.of()))));
    }

    @Test
    void understandingRevisionExplicitlyReachesOldAnchorWithoutCopyingEvidence() {
        FormationAttempt claimAttempt = attempt();
        CreatePublication.Prepared claim = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(claimAttempt, claim, UUID.randomUUID()), claim));
        UUID claimRevision =
                db.fetchOne("SELECT revision_id FROM memory.revision").get(0, UUID.class);
        newSource();
        FormationAttempt understandingAttempt = attempt();
        CreatePublication.Prepared understanding = prepare(List.of(item(
                MemoryType.UNDERSTANDING, List.of(), List.of(new RevisionRef(claimRevision, RelationKind.SUPPORT)))));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(understandingAttempt, understanding, UUID.randomUUID()), understanding));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record WHERE type='UNDERSTANDING'")
                .get(0, UUID.class);
        UUID old = db.fetchOne("SELECT current_revision_id FROM memory.record WHERE record_id=?::uuid", record)
                .get(0, UUID.class);
        newSource();
        FormationAttempt revisedAttempt = attempt();
        CreateItem item = new CreateItem(
                "REVISE",
                MemoryType.UNDERSTANDING,
                "reconsidered",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(),
                List.of(new RevisionRef(old, RelationKind.SUPPORT)),
                "understanding-r2",
                new ExpectedCurrent(record, old));
        CreatePublication.Prepared revised = prepare(List.of(item));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(revisedAttempt, revised, UUID.randomUUID()), revised));
        UUID current = db.fetchOne("SELECT current_revision_id FROM memory.record WHERE record_id=?::uuid", record)
                .get(0, UUID.class);
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor"));
        assertEquals(
                1,
                n("SELECT count(*) FROM memory.revision_relation WHERE revision_id='" + current
                        + "' AND target_revision_id='" + old + "'"));
        assertEquals(
                0,
                n("SELECT count(*) FROM memory.revision_relation WHERE revision_id='" + current
                        + "' AND target_revision_id='" + claimRevision + "'"));
    }

    @Test
    void concurrentRevisionsHaveOneWinnerAndRollbackFailedWrites() throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID old = db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt left = attempt();
        CreatePublication.Prepared leftRequest = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "left",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(),
                "left",
                new ExpectedCurrent(record, old))));
        FormationSettlementCandidate leftCandidate = candidate(left, leftRequest, UUID.randomUUID());
        UUID leftSource = sourceId;
        newSource();
        FormationAttempt right = attempt();
        CreatePublication.Prepared rightRequest = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "right",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("c")),
                List.of(),
                "right",
                new ExpectedCurrent(record, old))));
        FormationSettlementCandidate rightCandidate = candidate(right, rightRequest, UUID.randomUUID());
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var one = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        leftCandidate,
                        leftRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            var two = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        rightCandidate,
                        rightRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            start.countDown();
            assertEquals(
                    java.util.Set.of(
                            FormationSettlementOutcome.COMMITTED_WRITE, FormationSettlementOutcome.CURRENT_CONFLICT),
                    java.util.Set.of(one.get(), two.get()));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, n("SELECT count(*) FROM memory.revision"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(
                1,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id IN ('" + leftSource + "','" + sourceId
                        + "') AND processed_sequence IS NOT NULL"));
        UUID current =
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt badAttempt = attempt();
        CreatePublication.Prepared bad = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "bad",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("d")),
                List.of(new RevisionRef(UUID.randomUUID(), RelationKind.SUPPORT)),
                "bad",
                new ExpectedCurrent(record, current))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT, publish(candidate(badAttempt, bad, UUID.randomUUID()), bad));
        assertEquals(
                current,
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class));
        assertEquals(2, n("SELECT count(*) FROM memory.revision"));
        assertEquals(2, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
    }

    @Test
    void reviseRejectsWrongTypeAndMissingRecord() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID current =
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt wrongType = attempt();
        CreatePublication.Prepared typed = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.QUOTE,
                "wrong",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(),
                "wrong-type",
                new ExpectedCurrent(record, current))));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                publish(candidate(wrongType, typed, UUID.randomUUID()), typed));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
        newSource();
        FormationAttempt missing = attempt();
        CreatePublication.Prepared absent = prepare(List.of(new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "missing",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("c")),
                List.of(),
                "missing",
                new ExpectedCurrent(UUID.randomUUID(), current))));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                publish(candidate(missing, absent, UUID.randomUUID()), absent));
        db.execute("UPDATE memory.record SET participation_state='SUPERSEDED' WHERE record_id=?::uuid", record);
        newSource();
        FormationAttempt supersededAttempt = attempt();
        CreateItem activeTarget = new CreateItem(
                "REVISE",
                MemoryType.CLAIM,
                "updated",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("d")),
                List.of(),
                "target",
                new ExpectedCurrent(record, current));
        CreatePublication.Prepared superseded = prepare(List.of(activeTarget));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                publish(candidate(supersededAttempt, superseded, UUID.randomUUID()), superseded));
        db.execute("UPDATE memory.record SET participation_state='ACTIVE' WHERE record_id=?::uuid", record);
        newSource();
        FormationAttempt wrongWorld = attempt();
        CreatePublication.Prepared worldRequest = prepare(List.of(activeTarget));
        db.execute("UPDATE memory.world_binding SET world_ref='other-world'");
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(wrongWorld, worldRequest, UUID.randomUUID()), worldRequest));
        db.execute("UPDATE memory.world_binding SET world_ref='world-1'");
        newSource();
        FormationAttempt blockedSource = attempt();
        CreatePublication.Prepared gated = prepare(List.of(activeTarget));
        db.execute("UPDATE runtime.source_write_gate SET state='BLOCKED' WHERE source_id=?::uuid", sourceId);
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(blockedSource, gated, UUID.randomUUID()), gated));
        assertEquals(1, n("SELECT count(*) FROM memory.revision"));
        assertEquals(1, n("SELECT count(*) FROM memory.projection_outbox"));
    }

    @Test
    void supersedeCreatesDistinctIdentityAndDirectReadWithoutCopyingEvidence() throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID oldRecord = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID oldRevision =
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreateItem replacement = supersede(MemoryType.UNDERSTANDING, oldRecord, oldRevision, "b");
        CreatePublication.Prepared request = prepare(List.of(replacement));
        FormationSettlementCandidate submitted = candidate(second, request, UUID.randomUUID());
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(submitted, request));
        UUID successor = db.fetchOne("SELECT successor_record_id FROM memory.record_succession")
                .get(0, UUID.class);
        UUID firstRevision = db.fetchOne("SELECT successor_revision_id FROM memory.record_succession")
                .get(0, UUID.class);
        assertNotEquals(oldRecord, successor);
        assertNotEquals(oldRevision, firstRevision);
        assertEquals(
                "SUPERSEDED",
                db.fetchOne("SELECT participation_state FROM memory.record WHERE record_id=?::uuid", oldRecord)
                        .get(0, String.class));
        assertEquals(
                oldRevision,
                db.fetchOne("SELECT current_revision_id FROM memory.record WHERE record_id=?::uuid", oldRecord)
                        .get(0, UUID.class));
        assertEquals(
                "content",
                db.fetchOne("SELECT content FROM memory.revision WHERE revision_id=?::uuid", oldRevision)
                        .get(0, String.class));
        assertEquals(
                "UNDERSTANDING",
                db.fetchOne("SELECT type FROM memory.record WHERE record_id=?::uuid", successor)
                        .get(0, String.class));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor WHERE revision_id='" + oldRevision + "'"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision_anchor WHERE revision_id='" + firstRevision + "'"));
        assertEquals(2, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT revision_no FROM memory.revision WHERE revision_id='" + firstRevision + "'"));
        assertEquals(
                "MEMORY_SUPERSEDED",
                db.fetchOne("SELECT event_kind FROM memory.projection_outbox WHERE record_id=?::uuid", successor)
                        .get(0, String.class));
        var reader = new JdbcRecordSuccessionReader(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        var direct = reader.findDirectSuccessor(oldRecord).orElseThrow();
        assertEquals(oldRevision, direct.predecessorRevisionId());
        assertEquals(successor, direct.successorRecordId());
        assertTrue(reader.findDirectSuccessor(successor).isEmpty());
        assertEquals(FormationSettlementOutcome.IDEMPOTENT_REPLAY, publish(submitted, request));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.record_succession"));
        CreatePublication.Prepared changed = prepare(List.of(new CreateItem(
                "SUPERSEDE",
                MemoryType.UNDERSTANDING,
                "changed",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(),
                "successor",
                new ExpectedCurrent(oldRecord, oldRevision))));
        assertEquals(
                FormationSettlementOutcome.IDEMPOTENCY_CONFLICT,
                publish(candidate(second, changed, submitted.idempotencyKey()), changed));
        newSource();
        FormationAttempt third = attempt();
        CreatePublication.Prepared stale = prepare(List.of(supersede(MemoryType.CLAIM, oldRecord, oldRevision, "c")));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT, publish(candidate(third, stale, UUID.randomUUID()), stale));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.record_succession"));
    }

    @Test
    void supersedeFailedRelationRollsBackWholeSettlement() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID oldRecord = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID oldRevision =
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreateItem invalid = new CreateItem(
                "SUPERSEDE",
                MemoryType.CLAIM,
                "new",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("b")),
                List.of(new RevisionRef(UUID.randomUUID(), RelationKind.SUPPORT)),
                "replacement",
                new ExpectedCurrent(oldRecord, oldRevision));
        CreatePublication.Prepared request = prepare(List.of(invalid));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT, publish(candidate(second, request, UUID.randomUUID()), request));
        assertEquals(
                "ACTIVE",
                db.fetchOne("SELECT participation_state FROM memory.record WHERE record_id=?::uuid", oldRecord)
                        .get(0, String.class));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(1, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(1, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
    }

    @Test
    void sameTypeCanSupersedeAgainButOldLookupStopsAtDirectSuccessor() throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID firstRecord = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID firstRevision =
                db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt second = attempt();
        CreatePublication.Prepared next =
                prepare(List.of(supersede(MemoryType.CLAIM, firstRecord, firstRevision, "b")));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(second, next, UUID.randomUUID()), next));
        UUID middleRecord = db.fetchOne(
                        "SELECT successor_record_id FROM memory.record_succession "
                                + "WHERE predecessor_record_id=?::uuid",
                        firstRecord)
                .get(0, UUID.class);
        UUID middleRevision = db.fetchOne(
                        "SELECT current_revision_id FROM memory.record WHERE record_id=?::uuid", middleRecord)
                .get(0, UUID.class);
        newSource();
        FormationAttempt third = attempt();
        CreatePublication.Prepared last =
                prepare(List.of(supersede(MemoryType.CLAIM, middleRecord, middleRevision, "c")));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(third, last, UUID.randomUUID()), last));
        var reader = new JdbcRecordSuccessionReader(
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        assertEquals(
                middleRecord,
                reader.findDirectSuccessor(firstRecord).orElseThrow().successorRecordId());
        assertNotEquals(
                middleRecord,
                reader.findDirectSuccessor(middleRecord).orElseThrow().successorRecordId());
        assertEquals(2, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(1, n("SELECT count(*) FROM memory.record WHERE participation_state='ACTIVE'"));
        assertFalse(priv("SELECT", "memory.record_succession"));
        assertFalse(priv("INSERT", "memory.record_succession"));
    }

    @Test
    void supersedeRequiresConcreteExpectedCurrentAndEveryMixedItemRef() {
        FormationAttempt initial = attempt();
        CreateItem incomplete = new CreateItem(
                "SUPERSEDE",
                MemoryType.CLAIM,
                "new",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor("a")),
                List.of(),
                "replacement",
                null);
        assertThrows(IllegalArgumentException.class, () -> prepare(List.of(incomplete)));
        CreateItem specified = supersede(MemoryType.CLAIM, UUID.randomUUID(), UUID.randomUUID(), "a");
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(specified, item(MemoryType.CLAIM, List.of(anchor("b")), List.of()))));
        assertEquals(0, n("SELECT count(*) FROM memory.record"));
        assertNotNull(initial);
    }

    @Test
    void mixedGraphPublishesTwoSuccessionsAndReplaysAfterTargetsChange() throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared old = prepare(List.of(
                item(MemoryType.UNDERSTANDING, List.of(anchor("a")), List.of()),
                item(MemoryType.CLAIM, List.of(anchor("b")), List.of()),
                item(MemoryType.CLAIM, List.of(anchor("c")), List.of())));
        UUID oldKey = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(initial, old, oldKey), old));
        UUID[] records = new UUID[3];
        UUID[] revisions = new UUID[3];
        for (int i = 0; i < 3; i++) {
            var row = db.fetchOne(
                    "SELECT record_id,revision_id FROM memory.create_receipt_item "
                            + "WHERE idempotency_key=?::uuid AND item_index=?",
                    oldKey,
                    i);
            records[i] = row.get("record_id", UUID.class);
            revisions[i] = row.get("revision_id", UUID.class);
        }
        newSource();
        FormationAttempt task = attempt();
        CreatePublication.Prepared mixed = prepare(List.of(
                change(
                        "SUPERSEDE",
                        "successor-b",
                        MemoryType.CLAIM,
                        records[1],
                        revisions[1],
                        "b",
                        List.of(RevisionRef.item("revised-a", RelationKind.COUNTER))),
                named(
                        "event",
                        MemoryType.EVENT,
                        List.of(anchor("a")),
                        List.of(RevisionRef.item("successor-c", RelationKind.SUPPORT))),
                change(
                        "REVISE",
                        "revised-a",
                        MemoryType.UNDERSTANDING,
                        records[0],
                        revisions[0],
                        "c",
                        List.of(RevisionRef.item("event", RelationKind.SUPPORT))),
                change(
                        "SUPERSEDE",
                        "successor-c",
                        MemoryType.CLAIM,
                        records[2],
                        revisions[2],
                        "c",
                        List.of(RevisionRef.item("revised-a", RelationKind.COUNTER)))));
        UUID key = UUID.randomUUID();
        FormationSettlementCandidate submitted = candidate(task, mixed, key);
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(submitted, mixed));
        assertEquals(2, n("SELECT count(*) FROM memory.record_succession WHERE task_id='" + task.taskId() + "'"));
        assertEquals(2, n("SELECT count(*) FROM memory.record WHERE participation_state='SUPERSEDED'"));
        assertEquals(
                2,
                n("SELECT count(*) FROM memory.record_succession WHERE predecessor_record_id IN ('" + records[1] + "','"
                        + records[2] + "')"));
        assertEquals(4, n("SELECT count(*) FROM memory.create_receipt_item WHERE idempotency_key='" + key + "'"));
        assertEquals(
                4,
                n("SELECT count(*) FROM memory.projection_outbox WHERE request_hash=decode('"
                        + java.util.HexFormat.of().formatHex(mixed.hash()) + "','hex')"));
        assertEquals(
                4,
                n("SELECT count(*) FROM memory.revision_relation WHERE revision_id IN "
                        + "(SELECT revision_id FROM memory.create_receipt_item WHERE idempotency_key='" + key + "')"));
        assertEquals(
                2,
                n("SELECT count(*) FROM memory.revision_relation WHERE relation_kind='SUPPORT' "
                        + "AND revision_id IN (SELECT revision_id FROM memory.create_receipt_item "
                        + "WHERE idempotency_key='" + key + "')"));
        assertEquals(
                2,
                n("SELECT count(*) FROM memory.revision_relation WHERE relation_kind='COUNTER' "
                        + "AND revision_id IN (SELECT revision_id FROM memory.create_receipt_item "
                        + "WHERE idempotency_key='" + key + "')"));
        assertEquals(
                2,
                n("SELECT count(*) FROM memory.record_succession s JOIN memory.record r "
                        + "ON r.record_id=s.predecessor_record_id WHERE s.task_id='" + task.taskId()
                        + "' AND r.current_revision_id=s.predecessor_revision_id"));
        int facts = n("SELECT count(*) FROM memory.revision");
        assertEquals(FormationSettlementOutcome.IDEMPOTENT_REPLAY, publish(submitted, mixed));
        assertEquals(facts, n("SELECT count(*) FROM memory.revision"));
        CreatePublication.Prepared changed =
                prepare(List.of(named("different", MemoryType.EVENT, List.of(anchor("a")), List.of())));
        assertEquals(FormationSettlementOutcome.IDEMPOTENCY_CONFLICT, publish(candidate(task, changed, key), changed));
        try (Connection c =
                DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            var receipt = new JdbcCreatePublicationWriter()
                    .findReceipt(c, key, mixed.hash())
                    .orElseThrow();
            assertEquals(
                    List.of("SUPERSEDE", "CREATE", "REVISE", "SUPERSEDE"),
                    receipt.items().stream()
                            .map(JdbcCreatePublicationWriter.PublishedItem::action)
                            .toList());
        }
    }

    @Test
    void duplicateChangeTargetsAreRejectedBeforeSourceReadOrWrites() {
        attempt();
        UUID record = UUID.randomUUID();
        UUID revision = UUID.randomUUID();
        for (String second : List.of("REVISE", "SUPERSEDE")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> prepare(List.of(
                            change("REVISE", "first", MemoryType.CLAIM, record, revision, "a", List.of()),
                            change(second, "second", MemoryType.CLAIM, record, revision, "b", List.of()))));
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> prepare(List.of(
                        change("SUPERSEDE", "first", MemoryType.CLAIM, record, revision, "a", List.of()),
                        change("SUPERSEDE", "second", MemoryType.CLAIM, record, revision, "b", List.of()))));
        assertEquals(0, resolverCalls);
        assertZero();
    }

    @Test
    void staleSecondTargetRollsBackEntireMixedBatch() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared old = prepare(List.of(
                item(MemoryType.CLAIM, List.of(anchor("a")), List.of()),
                item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        UUID oldKey = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(initial, old, oldKey), old));
        UUID first = receiptRecord(oldKey, 0);
        UUID second = receiptRecord(oldKey, 1);
        UUID firstRevision = receiptRevision(oldKey, 0);
        newSource();
        FormationAttempt task = attempt();
        CreatePublication.Prepared mixed = prepare(List.of(
                named("new", MemoryType.EVENT, List.of(anchor("a")), List.of()),
                change("REVISE", "first", MemoryType.CLAIM, first, firstRevision, "b", List.of()),
                change("SUPERSEDE", "second", MemoryType.CLAIM, second, UUID.randomUUID(), "c", List.of())));
        assertEquals(
                FormationSettlementOutcome.CURRENT_CONFLICT, publish(candidate(task, mixed, UUID.randomUUID()), mixed));
        assertEquals(2, n("SELECT count(*) FROM memory.record"));
        assertEquals(2, n("SELECT count(*) FROM memory.revision"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(2, n("SELECT count(*) FROM memory.record WHERE participation_state='ACTIVE'"));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
    }

    @Test
    void lateAnchorAndRelationFailuresRollBackMixedBatch() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared old = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        UUID oldKey = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(initial, old, oldKey), old));
        newSource();
        FormationAttempt task = attempt();
        CreateItem first = named("first", MemoryType.EVENT, List.of(anchor("b")), List.of());
        CreatePublication.Prepared anchorFailure = prepare(List.of(
                first,
                change(
                        "SUPERSEDE",
                        "second",
                        MemoryType.CLAIM,
                        receiptRecord(oldKey, 0),
                        receiptRevision(oldKey, 0),
                        "g",
                        List.of())));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(task, anchorFailure, UUID.randomUUID()), anchorFailure));
        assertEquals(1, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        FormationAttempt retry = control(T0.plusMinutes(92))
                .claim("retry", Duration.ofMinutes(5))
                .orElseThrow();
        preparedAttempt = retry;
        CreatePublication.Prepared relationFailure = prepare(List.of(
                first,
                change(
                        "SUPERSEDE",
                        "second",
                        MemoryType.CLAIM,
                        receiptRecord(oldKey, 0),
                        receiptRevision(oldKey, 0),
                        "c",
                        List.of(new RevisionRef(UUID.randomUUID(), RelationKind.COUNTER)))));
        assertEquals(
                FormationSettlementOutcome.RETRY_WAIT,
                publish(candidate(retry, relationFailure, UUID.randomUUID()), relationFailure));
        assertEquals(1, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision"));
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(1, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
    }

    @Test
    void sqlFailureOnSecondItemRollsBackFirstAndSettlement() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared old = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        UUID oldKey = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(initial, old, oldKey), old));
        newSource();
        FormationAttempt task = attempt();
        CreatePublication.Prepared mixed = prepare(List.of(
                named("first", MemoryType.EVENT, List.of(anchor("b")), List.of()),
                change(
                        "SUPERSEDE",
                        "second",
                        MemoryType.CLAIM,
                        receiptRecord(oldKey, 0),
                        receiptRevision(oldKey, 0),
                        "c",
                        List.of())));
        db.execute("CREATE FUNCTION public.reject_second_revision() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN IF NEW.content='changed-second' THEN RAISE EXCEPTION 'test second failure'; "
                + "END IF; RETURN NEW; END $$");
        db.execute("CREATE TRIGGER reject_second_revision BEFORE INSERT ON memory.revision "
                + "FOR EACH ROW EXECUTE FUNCTION public.reject_second_revision()");
        try {
            assertEquals(
                    FormationSettlementOutcome.RETRY_WAIT, publish(candidate(task, mixed, UUID.randomUUID()), mixed));
        } finally {
            db.execute("DROP TRIGGER reject_second_revision ON memory.revision");
            db.execute("DROP FUNCTION public.reject_second_revision()");
        }
        assertEquals(1, n("SELECT count(*) FROM memory.record"));
        assertEquals(1, n("SELECT count(*) FROM memory.revision"));
        assertEquals(1, n("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(1, n("SELECT count(*) FROM memory.projection_outbox"));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(
                0,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id='" + sourceId
                        + "' AND processed_sequence IS NOT NULL"));
    }

    @Test
    void oppositeActionOrdersTakeStableLocksAndOnlyOneBatchWins() throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared old = prepare(List.of(
                item(MemoryType.CLAIM, List.of(anchor("a")), List.of()),
                item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        UUID oldKey = UUID.randomUUID();
        assertEquals(FormationSettlementOutcome.COMMITTED_WRITE, publish(candidate(initial, old, oldKey), old));
        UUID first = receiptRecord(oldKey, 0);
        UUID second = receiptRecord(oldKey, 1);
        UUID firstRevision = receiptRevision(oldKey, 0);
        UUID secondRevision = receiptRevision(oldKey, 1);
        newSource();
        FormationAttempt left = attempt();
        CreatePublication.Prepared leftRequest = prepare(List.of(
                change("SUPERSEDE", "left-first", MemoryType.CLAIM, first, firstRevision, "a", List.of()),
                change("SUPERSEDE", "left-second", MemoryType.CLAIM, second, secondRevision, "b", List.of())));
        FormationSettlementCandidate leftCandidate = candidate(left, leftRequest, UUID.randomUUID());
        UUID leftSource = sourceId;
        newSource();
        FormationAttempt right = attempt();
        CreatePublication.Prepared rightRequest = prepare(List.of(
                change("SUPERSEDE", "right-second", MemoryType.CLAIM, second, secondRevision, "a", List.of()),
                change("SUPERSEDE", "right-first", MemoryType.CLAIM, first, firstRevision, "b", List.of())));
        FormationSettlementCandidate rightCandidate = candidate(right, rightRequest, UUID.randomUUID());
        UUID rightSource = sourceId;
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var leftFuture = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        leftCandidate,
                        leftRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            var rightFuture = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        rightCandidate,
                        rightRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            start.countDown();
            var outcomes = List.of(
                    leftFuture.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    rightFuture.get(20, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(o -> o == FormationSettlementOutcome.COMMITTED_WRITE)
                            .count());
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(o -> o == FormationSettlementOutcome.CURRENT_CONFLICT
                                    || o == FormationSettlementOutcome.INVALID_RESULT)
                            .count());
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
        }
        assertEquals(2, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(2, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(2, n("SELECT count(*) FROM memory.projection_outbox " + "WHERE event_kind='MEMORY_SUPERSEDED'"));
        assertEquals(
                1,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id IN ('" + leftSource + "','"
                        + rightSource + "') AND processed_sequence IS NOT NULL"));
    }

    @Test
    void supersedeRejectsMissingRecordAndStaleRevisionWithoutPublishing() {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared missing =
                prepare(List.of(supersede(MemoryType.CLAIM, UUID.randomUUID(), UUID.randomUUID(), "a")));
        assertEquals(
                FormationSettlementOutcome.INVALID_RESULT,
                publish(candidate(initial, missing, UUID.randomUUID()), missing));
        assertEquals(0, n("SELECT count(*) FROM memory.create_receipt"));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        newSource();
        FormationAttempt createAttempt = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("b")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(createAttempt, create, UUID.randomUUID()), create));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt staleAttempt = attempt();
        CreatePublication.Prepared stale =
                prepare(List.of(supersede(MemoryType.CLAIM, record, UUID.randomUUID(), "c")));
        assertEquals(
                FormationSettlementOutcome.CURRENT_CONFLICT,
                publish(candidate(staleAttempt, stale, UUID.randomUUID()), stale));
        assertEquals(0, n("SELECT count(*) FROM memory.record_succession"));
        assertEquals(1, n("SELECT count(*) FROM memory.record WHERE participation_state='ACTIVE'"));
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt"));
    }

    @Test
    void twoSupersedesRacingSameCurrentPublishOnlyOneSuccessor() throws Exception {
        raceReplacement(false);
    }

    @Test
    void reviseAndSupersedeRacingSameCurrentPublishOnlyOneChange() throws Exception {
        raceReplacement(true);
    }

    private void raceReplacement(boolean competingRevision) throws Exception {
        FormationAttempt initial = attempt();
        CreatePublication.Prepared create = prepare(List.of(item(MemoryType.CLAIM, List.of(anchor("a")), List.of())));
        assertEquals(
                FormationSettlementOutcome.COMMITTED_WRITE,
                publish(candidate(initial, create, UUID.randomUUID()), create));
        UUID record = db.fetchOne("SELECT record_id FROM memory.record").get(0, UUID.class);
        UUID old = db.fetchOne("SELECT current_revision_id FROM memory.record").get(0, UUID.class);
        newSource();
        FormationAttempt left = attempt();
        CreatePublication.Prepared leftRequest = prepare(List.of(supersede(MemoryType.CLAIM, record, old, "b")));
        FormationSettlementCandidate leftCandidate = candidate(left, leftRequest, UUID.randomUUID());
        UUID leftSource = sourceId;
        newSource();
        FormationAttempt right = attempt();
        CreateItem rightItem = competingRevision
                ? new CreateItem(
                        "REVISE",
                        MemoryType.CLAIM,
                        "revised",
                        "subject",
                        "world-1",
                        "observed",
                        null,
                        null,
                        null,
                        "formation",
                        List.of(anchor("c")),
                        List.of(),
                        "revision",
                        new ExpectedCurrent(record, old))
                : supersede(MemoryType.CLAIM, record, old, "c");
        CreatePublication.Prepared rightRequest = prepare(List.of(rightItem));
        FormationSettlementCandidate rightCandidate = candidate(right, rightRequest, UUID.randomUUID());
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        leftCandidate,
                        leftRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            var second = pool.submit(() -> {
                start.await();
                return CreatePublication.settle(
                        control(T0.plusMinutes(91)),
                        rightCandidate,
                        rightRequest,
                        new JdbcCreatePublicationWriter(),
                        ignored -> {});
            });
            start.countDown();
            var outcomes = List.of(first.get(), second.get());
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(o -> o == FormationSettlementOutcome.COMMITTED_WRITE)
                            .count());
            assertEquals(
                    1,
                    outcomes.stream()
                            .filter(o -> o == FormationSettlementOutcome.CURRENT_CONFLICT
                                    || o == FormationSettlementOutcome.INVALID_RESULT)
                            .count());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, n("SELECT count(*) FROM memory.create_receipt_item WHERE action IN ('SUPERSEDE','REVISE')"));
        assertEquals(
                1,
                n("SELECT count(*) FROM memory.projection_outbox "
                        + "WHERE event_kind IN ('MEMORY_SUPERSEDED','MEMORY_REVISED')"));
        assertEquals(
                1,
                n("SELECT count(*) FROM runtime.source_progress WHERE source_id IN ('" + leftSource + "','" + sourceId
                        + "') AND processed_sequence IS NOT NULL"));
        assertEquals(
                1,
                n("SELECT count(*) FROM memory.record_succession")
                        + n("SELECT count(*) FROM memory.revision WHERE record_id='" + record + "' AND revision_no=2"));
    }

    private static CreateItem supersede(MemoryType type, UUID record, UUID revision, String anchor) {
        return new CreateItem(
                "SUPERSEDE",
                type,
                "successor",
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor(anchor)),
                List.of(),
                "successor",
                new ExpectedCurrent(record, revision));
    }

    private static CreateItem change(
            String action,
            String ref,
            MemoryType type,
            UUID record,
            UUID revision,
            String anchor,
            List<RevisionRef> relations) {
        return new CreateItem(
                action,
                type,
                "changed-" + ref,
                "subject",
                "world-1",
                "observed",
                null,
                null,
                null,
                "formation",
                List.of(anchor(anchor)),
                relations,
                ref,
                new ExpectedCurrent(record, revision));
    }

    private static UUID receiptRecord(UUID key, int index) {
        return db.fetchOne(
                        "SELECT record_id FROM memory.create_receipt_item "
                                + "WHERE idempotency_key=?::uuid AND item_index=?",
                        key,
                        index)
                .get(0, UUID.class);
    }

    private static UUID receiptRevision(UUID key, int index) {
        return db.fetchOne(
                        "SELECT revision_id FROM memory.create_receipt_item "
                                + "WHERE idempotency_key=?::uuid AND item_index=?",
                        key,
                        index)
                .get(0, UUID.class);
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
                        case "mid-105" -> 105L;
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

    private static CreateItem named(String ref, MemoryType type, List<AnchorRef> anchors, List<RevisionRef> relations) {
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
                relations,
                ref,
                null);
    }

    private static AnchorRef anchor(String locator) {
        if ("g".equals(locator)) return new AnchorRef("g", "text-g", "game-1", "actor", "role");
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
