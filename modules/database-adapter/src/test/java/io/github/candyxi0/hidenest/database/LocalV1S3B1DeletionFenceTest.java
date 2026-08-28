package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3AException;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalV1S3B1DeletionFenceTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH = "ab".repeat(32);
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static DeletionFencePort fences;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static Path payloadRoot;

    @BeforeAll
    static void setUp() throws Exception {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword())) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(22, Flyway.configure().dataSource(postgres.getJdbcUrl(), USER, postgres.getPassword())
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate().migrationsExecuted);
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        DriverManagerDataSource raw = new DriverManagerDataSource(
                postgres.getJdbcUrl(), USER, postgres.getPassword());
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        fences = new JooqDeletionFenceAdapter(dsl);
        var governance = new JooqMemoryGovernanceAdapter(dsl);
        var runtime = new JooqRuntimeTransactionAdapter(dsl);
        var evidence = new JooqEvidenceReferenceAdapter(dsl);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("UTC")));
        payloadRoot = Files.createTempDirectory("s3b1-payload-test-");
        var payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, executor, publisher, payloadStore,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("UTC")));
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (postgres != null) postgres.stop();
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    @Test
    void validFenceIsPersistentExactAndImmutable() {
        Fixture fixture = fixture("MEMORY", null);
        fences.insertFences(List.of(fixture.draft()));
        assertTrue(fences.isFenced("MEMORY", fixture.targetId(), null));
        assertFalse(fences.isFenced("MEMORY_REVISION", fixture.targetId(), null));
        assertEquals(List.of(fixture.targetId()), fences.findByClosureId(fixture.closureId()).stream()
                .map(fence -> fence.targetId()).toList());
        assertThrows(RuntimeException.class, () -> dsl.execute(
                "UPDATE memory.deletion_fence SET target_kind='SOURCE_UNIT' WHERE fence_id=?", fixture.fenceId()));
        assertThrows(RuntimeException.class, () -> dsl.execute(
                "DELETE FROM memory.deletion_fence WHERE fence_id=?", fixture.fenceId()));
    }

    @Test
    void invalidMemberDispositionAndDecisionBindingsAreRejected() {
        Fixture missingMember = fixture("MEMORY", null);
        assertFenced(new AttackCase("member must belong to the same closure", () ->
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), missingMember.closureId(), "MEMORY", UUID.randomUUID(), null,
                        missingMember.decisionId(), missingMember.createdAt())))));

        Fixture retained = fixture("SHARED_REFERENCE", null);
        assertFenced(new AttackCase("retained shared reference must not become a fence", () ->
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        retained.fenceId(), retained.closureId(), "MEMORY", retained.targetId(), null,
                        retained.decisionId(), retained.createdAt())))));

        Fixture wrongDecisionKind = fixture(
                "MEMORY", UUID.randomUUID(), null, "USER_ARCHIVE", "DELETION_CLOSURE",
                "DELETE_REQUESTED", true, 7L);
        assertFenced(new AttackCase("decision kind must be USER_DELETE_CONFIRM", () ->
                fences.insertFences(List.of(wrongDecisionKind.draft()))));

        Fixture wrongDecisionTargetKind = fixture(
                "MEMORY", UUID.randomUUID(), null, "USER_DELETE_CONFIRM", "MEMORY",
                "DELETE_REQUESTED", true, 7L);
        assertFenced(new AttackCase("decision target kind must be DELETION_CLOSURE", () ->
                fences.insertFences(List.of(wrongDecisionTargetKind.draft()))));

        Fixture wrongDecisionTargetId = fixture(
                "MEMORY", UUID.randomUUID(), null, "USER_DELETE_CONFIRM", "DELETION_CLOSURE",
                "DELETE_REQUESTED", false, 7L);
        assertFenced(new AttackCase("decision target id must equal closure id", () ->
                fences.insertFences(List.of(wrongDecisionTargetId.draft()))));

        Fixture wrongDecisionRevision = fixture(
                "MEMORY", UUID.randomUUID(), null, "USER_DELETE_CONFIRM", "DELETION_CLOSURE",
                "DELETE_REQUESTED", true, 8L);
        assertFenced(new AttackCase("decision revision must equal preview revision", () ->
                fences.insertFences(List.of(wrongDecisionRevision.draft()))));

        Fixture valid = fixture("MEMORY_REVISION", 3L);
        fences.insertFences(List.of(valid.draft()));
        assertThrows(RuntimeException.class, () -> fences.insertFences(List.of(valid.draft())));
        assertThrows(IllegalArgumentException.class, () -> fences.insertFences(List.of()));
    }

    @Test
    void batchFailureRollsBackAllRowsAndKindRevisionLookupIsExact() {
        Fixture first = fixture("SOURCE_UNIT", null);
        Fixture second = fixture("SOURCE_PAYLOAD", null);
        assertThrows(RuntimeException.class, () -> dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);
            DeletionFencePort adapter = new JooqDeletionFenceAdapter(tx);
            adapter.insertFences(List.of(
                    first.draft(), new DeletionFencePort.FenceDraft(
                            UUID.randomUUID(), second.closureId(), "SOURCE_PAYLOAD", second.targetId(), 9L,
                            second.decisionId(), second.createdAt())));
        }));
        assertFalse(fences.isFenced("SOURCE_UNIT", first.targetId(), null));
    }

    @Test
    void fencedRootIsRejectedBeforeS3APreviewPersistence() {
        Fixture fixture = fixture("MEMORY", null);
        fences.insertFences(List.of(fixture.draft()));
        int closuresBefore = dsl.fetchCount(DSL.table(DSL.name("memory", "deletion_closure")));
        DeletionPreviewPort port = new DeletionPreviewPort() {
            @Override public ExistingPreview findByIdempotencyKey(String key) { throw new AssertionError("must not read"); }
            @Override public io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph lockAndReadGraph(UUID id) {
                throw new AssertionError("must not read");
            }
            @Override public long nextPreviewRevision(UUID id) { throw new AssertionError("must not write"); }
            @Override public void insertPreview(PreviewDraft draft) { throw new AssertionError("must not write"); }
        };
        LocalV1S3ADeletionPreviewCoordinator coordinator = new LocalV1S3ADeletionPreviewCoordinator(
                port, immediateTransactions(), Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneId.of("UTC")), fences,
                new LocalPayloadStore(payloadRoot));
        LocalV1S3AException error = assertThrows(LocalV1S3AException.class, () -> coordinator.preview(
                new LocalV1S3ADeletionPreviewRequest(fixture.targetId(), "fenced-root", new byte[32])));
        assertEquals(LocalV1S3AException.Code.DELETION_FENCED, error.code());
        assertEquals(closuresBefore, dsl.fetchCount(DSL.table(DSL.name("memory", "deletion_closure"))));
    }

    @Test
    void realPostgresqlSixCategoryResurrectionMatrixRejectsAllAndPreservesRowsAndPayloadFiles() {
        MatrixFixture root = createRealMemory("matrix-root", "matrix-root-body");
        MatrixFixture exact = createRealMemory("matrix-exact", "matrix-exact-body");
        MatrixFixture legal = createRealMemory("matrix-legal", "matrix-legal-body");
        UUID relationFromOwnerId = UUID.randomUUID();
        UUID relationFromExactId = UUID.randomUUID();
        UUID relationDecisionId = dsl.fetch(
                "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                root.revisionId()).get(0).get(0, UUID.class);
        dsl.execute(
                "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_revision_id,created_by_decision_id,created_at) "
                        + "VALUES (?,?, 'INTERPRETS',?,?,clock_timestamp())",
                relationFromOwnerId, root.revisionId(), legal.revisionId(), relationDecisionId);
        dsl.execute(
                "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_revision_id,created_by_decision_id,created_at) "
                        + "VALUES (?,?, 'INTERPRETS',?,?,clock_timestamp())",
                relationFromExactId, exact.revisionId(), legal.revisionId(), relationDecisionId);

        fence("MEMORY", root.memoryId(), null);
        fence("MEMORY_REVISION", exact.revisionId(), exact.revisionNo());
        fence("SOURCE_ANCHOR", root.anchorId(), null);
        fence("SOURCE_UNIT", root.unitId(), null);
        fence("SOURCE_PAYLOAD", root.payloadId(), null);
        assertTrue(fences.isFenced("MEMORY", root.memoryId(), null));
        assertTrue(fences.isFenced("MEMORY_REVISION", exact.revisionId(), exact.revisionNo()));
        assertTrue(fences.isFenced("SOURCE_ANCHOR", root.anchorId(), null));
        assertTrue(fences.isFenced("SOURCE_UNIT", root.unitId(), null));
        assertTrue(fences.isFenced("SOURCE_PAYLOAD", root.payloadId(), null));
        assertEquals(1, dsl.fetch("SELECT count(*) FROM memory.memory_record WHERE memory_id=?",
                root.memoryId()).get(0).get(0, Integer.class));
        assertTrue(dsl.fetch("SELECT memory.deletion_fence_exists('MEMORY', ?, NULL)", root.memoryId())
                .get(0).get(0, Boolean.class));
        assertEquals("O", dsl.fetch(
                "SELECT tgenabled FROM pg_trigger WHERE tgrelid='memory.memory_record'::regclass "
                        + "AND tgname='memory_record_deletion_fence_guard'")
                .get(0).get(0, String.class));
        assertEquals("origin", dsl.fetch("SELECT current_setting('session_replication_role')")
                .get(0).get(0, String.class));
        assertTrue(dsl.fetch(
                "SELECT pg_get_triggerdef(oid) FROM pg_trigger WHERE tgrelid='memory.memory_record'::regclass "
                        + "AND tgname='memory_record_deletion_fence_guard'")
                .get(0).get(0, String.class).contains("BEFORE INSERT OR UPDATE"));
        assertTrue(dsl.fetch(
                "SELECT pg_get_functiondef(t.tgfoid) FROM pg_trigger t "
                        + "WHERE t.tgrelid='memory.memory_record'::regclass "
                        + "AND t.tgname='memory_record_deletion_fence_guard'")
                .get(0).get(0, String.class).contains("OLD.memory_id"));
        try (Connection independent = DriverManager.getConnection(
                postgres.getJdbcUrl(), USER, postgres.getPassword());
                var statement = independent.prepareStatement(
                        "SELECT memory.deletion_fence_exists('MEMORY', ?, NULL)")) {
            statement.setObject(1, root.memoryId());
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1), "fence must be visible from an independent connection");
            }
        } catch (SQLException exception) {
            throw new AssertionError(exception);
        }
        UUID legalUnitId = UUID.randomUUID();
        UUID legalPayloadId = UUID.randomUUID();
        UUID legalAnchorId = UUID.randomUUID();
        dsl.execute("INSERT INTO evidence.source_unit(source_unit_id,source_id,external_unit_ref,source_version,ordinal,actor_id,occurred_at,created_at) "
                        + "VALUES (?,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
                legalUnitId, root.sourceId(), "legal-unit-" + legalUnitId, "v1", 99L, root.actorId());
        dsl.execute("INSERT INTO evidence.source_payload(payload_id,source_unit_id,payload_kind,store_adapter,object_ref,content_type,size_bytes,content_hash,policy_id,current_policy_revision_no,retention_class,created_at) "
                        + "SELECT ?,?,'TEXT',store_adapter,object_ref,content_type,size_bytes,content_hash,policy_id,current_policy_revision_no,retention_class,clock_timestamp() "
                        + "FROM evidence.source_payload WHERE payload_id=?",
                legalPayloadId, legalUnitId, root.payloadId());
        dsl.execute("INSERT INTO evidence.source_anchor(anchor_id,source_id,anchor_kind,created_at) VALUES (?,?, 'MESSAGE_SEGMENT', clock_timestamp())",
                legalAnchorId, legal.sourceId());
        UUID newLegalUnitId = UUID.randomUUID();
        dsl.execute("INSERT INTO evidence.source_unit(source_unit_id,source_id,external_unit_ref,source_version,ordinal,actor_id,occurred_at,created_at) "
                        + "VALUES (?,?,?,?,?,?,clock_timestamp(),clock_timestamp())",
                newLegalUnitId, legal.sourceId(), "new-legal-unit-" + newLegalUnitId, "v1", 100L, legal.actorId());
        assertEquals(1, dsl.fetch("SELECT count(*) FROM evidence.source_unit WHERE source_unit_id=?",
                newLegalUnitId).get(0).get(0, Integer.class));

        List<String> businessBefore = businessSnapshot();
        List<String> payloadBefore = payloadSnapshot();
        List<AttackCase> attacks = List.of(
                new AttackCase("memory.memory_record same memory_id UPDATE", () -> {
                    int affected = executeAttack(
                            "UPDATE memory.memory_record SET state='ARCHIVED' WHERE memory_id=?", root.memoryId());
                    if (affected != 1) {
                        throw new AssertionError("expected one candidate row before fence rejection, affected=" + affected);
                    }
                }),
                new AttackCase("memory.memory_record rebuild attack", () -> executeAttack(
                        "INSERT INTO memory.memory_record(memory_id,state,current_revision_id,policy_id,current_policy_revision_no,created_at,updated_at) "
                                + "SELECT memory_id,state,current_revision_id,policy_id,current_policy_revision_no,clock_timestamp(),clock_timestamp() "
                                + "FROM memory.memory_record WHERE memory_id=?",
                        root.memoryId())),
                new AttackCase("memory.memory_revision owner Memory fence", () -> executeAttack(
                        "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) "
                                + "SELECT ?,memory_id,99,memory_type,'owner-fenced',created_by_decision_id,clock_timestamp() "
                                + "FROM memory.memory_revision WHERE memory_revision_id=?",
                        UUID.randomUUID(), root.revisionId())),
                new AttackCase("memory.memory_revision exact revision fence", () -> executeAttack(
                        "UPDATE memory.memory_revision SET body_text=body_text || '-attack' WHERE memory_revision_id=?",
                        exact.revisionId())),
                new AttackCase("memory.memory_relation from revision owner Memory", () -> executeAttack(
                        "UPDATE memory.memory_relation SET relation_type='SUPPORTS', to_revision_id=?, to_anchor_id=NULL WHERE relation_id=?",
                        legal.revisionId(), relationFromOwnerId)),
                new AttackCase("memory.memory_relation from revision exact", () -> executeAttack(
                        "UPDATE memory.memory_relation SET relation_type='SUPPORTS', to_revision_id=?, to_anchor_id=NULL WHERE relation_id=?",
                        legal.revisionId(), relationFromExactId)),
                new AttackCase("memory.memory_relation to revision exact", () -> executeAttack(
                        "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_revision_id,created_by_decision_id,created_at) "
                                + "VALUES (?,?,'SUPPORTS',?,?,clock_timestamp())",
                        UUID.randomUUID(), legal.revisionId(), exact.revisionId(), relationDecisionId)),
                new AttackCase("memory.memory_relation to anchor", () -> executeAttack(
                        "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                                + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                        UUID.randomUUID(), legal.revisionId(), root.anchorId(), relationDecisionId)),
                new AttackCase("evidence.source_anchor same anchor", () -> executeAttack(
                        "UPDATE evidence.source_anchor SET anchor_kind='MESSAGE_SEGMENT' WHERE anchor_id=?",
                        root.anchorId())),
                new AttackCase("evidence.source_unit same source_unit", () -> executeAttack(
                        "UPDATE evidence.source_unit SET external_unit_ref=external_unit_ref || '-attack' WHERE source_unit_id=?",
                        root.unitId())),
                new AttackCase("evidence.source_payload same payload", () -> executeAttack(
                        "UPDATE evidence.source_payload SET object_ref=object_ref || '-attack' WHERE payload_id=?",
                        root.payloadId())),
                new AttackCase("evidence.source_payload fenced SourceUnit", () -> executeAttack(
                        "UPDATE evidence.source_payload SET source_unit_id=? WHERE payload_id=?",
                        root.unitId(), legalPayloadId)));

        int rejected = 0;
        for (AttackCase attack : attacks) {
            assertFenced(attack);
            rejected++;
            assertEquals(businessBefore, businessSnapshot(), attack.name());
            assertEquals(payloadBefore, payloadSnapshot(), attack.name());
        }
        assertEquals(12, rejected);
    }

    private static Fixture fixture(String kind, Long revisionRef) {
        return fixture(kind, UUID.randomUUID(), revisionRef, "USER_DELETE_CONFIRM", "DELETION_CLOSURE");
    }

    private static Fixture fixture(String kind, Long revisionRef, String decisionKind) {
        return fixture(kind, UUID.randomUUID(), revisionRef, decisionKind, "DELETION_CLOSURE");
    }

    private static Fixture fixture(String kind, Long revisionRef, String decisionKind, String decisionTargetKind) {
        return fixture(kind, UUID.randomUUID(), revisionRef, decisionKind, decisionTargetKind);
    }

    private static Fixture fixture(
            String kind, UUID targetId, Long revisionRef, String decisionKind, String decisionTargetKind) {
        String disposition = "MEMORY".equals(kind) || "MEMORY_REVISION".equals(kind)
                ? "DELETE_REQUESTED"
                : "SHARED_REFERENCE".equals(kind) ? "RETAIN_SHARED" : "DELETE_CANDIDATE";
        return fixture(kind, targetId, revisionRef, decisionKind, decisionTargetKind,
                disposition, true, 7L);
    }

    private static Fixture fixture(
            String kind, UUID targetId, Long revisionRef, String decisionKind, String decisionTargetKind,
            String disposition, boolean correctDecisionTargetId, Long decisionRevisionRef) {
        UUID actorId = UUID.randomUUID();
        UUID closureId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID fenceId = UUID.randomUUID();
        dsl.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES (?, 'SYNTHETIC', ?, clock_timestamp())",
                actorId, "fence-" + actorId);
        dsl.execute("INSERT INTO memory.deletion_closure(closure_id,root_memory_id,preview_revision,root_current_revision_id,root_revision_no,root_policy_id,root_policy_revision_no,request_idempotency_key,request_hash,manifest_hash,state,created_at,expires_at) VALUES (?, ?, 7, ?, 1, ?, 1, ?, decode(?, 'hex'), decode(?, 'hex'), 'PREVIEWED', clock_timestamp(), clock_timestamp()+interval '1 hour')",
                closureId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "fence-" + closureId, HASH, HASH);
        if ("SOURCE_PAYLOAD".equals(kind)) {
            dsl.execute("INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,target_revision_ref,disposition,size_bytes,content_hash) VALUES (?,1,?,?,?,? ,1,decode(?, 'hex'))",
                    closureId, kind, targetId, revisionRef, disposition, HASH);
        } else {
            dsl.execute("INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,target_revision_ref,disposition) VALUES (?,1,?,?,?,?)",
                    closureId, kind, targetId, revisionRef, disposition);
        }
        dsl.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES (?, ?, ?, 'USER', ?, ?, ?, 'synthetic', ?, clock_timestamp())",
                decisionId, decisionKind, actorId, decisionTargetKind,
                correctDecisionTargetId ? closureId : UUID.randomUUID(), decisionRevisionRef,
                "decision-" + decisionId);
        return new Fixture(fenceId, closureId, targetId, decisionId, revisionRef,
                OffsetDateTime.now(), kind);
    }

    private static MatrixFixture createRealMemory(String marker, String body) {
        UUID actorId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        String key = "s3b1-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key.getBytes(StandardCharsets.UTF_8)), actorId, "Interpretation", body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        unitId, actorId, 1L, "unit-" + marker, OffsetDateTime.now(), "evidence-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(anchorId, List.of(
                        new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitId, 0L, 1L, 1L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key, sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(), prepared.reviewSessionId(), memoryId,
                UUID.randomUUID(), new byte[32]));
        UUID payloadId = dsl.fetch(
                "SELECT payload_id FROM evidence.source_payload WHERE source_unit_id=?",
                unitId).get(0).get(0, UUID.class);
        assertEquals(1, dsl.fetch("SELECT count(*) FROM memory.memory_record WHERE memory_id=?",
                confirmed.memoryId()).get(0).get(0, Integer.class));
        return new MatrixFixture(confirmed.memoryId(), confirmed.currentRevisionId(), confirmed.revisionNo(),
                prepared.sourceId(), unitId, payloadId, anchorId, actorId);
    }

    private static void fence(String kind, UUID targetId, Long revisionRef) {
        Fixture fixture = fixture(kind, targetId, revisionRef, "USER_DELETE_CONFIRM", "DELETION_CLOSURE");
        fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                fixture.fenceId(), fixture.closureId(), kind, targetId, revisionRef,
                fixture.decisionId(), fixture.createdAt())));
    }

    private static void assertFenced(AttackCase attack) {
        RuntimeException exception = assertThrows(
                RuntimeException.class, () -> attack.run().run(), attack.name());
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertEquals("23514", sql.getSQLState(), attack.name());
                assertTrue(sql.getMessage().contains("HDM012_DELETION_FENCED"), attack.name());
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected SQLException cause for " + attack.name(), exception);
    }

    private static int executeAttack(String sql, Object... parameters) {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), USER, postgres.getPassword());
                var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static List<String> businessSnapshot() {
        List<String> snapshot = new ArrayList<>();
        List<String> tables = List.of(
                "memory.memory_record",
                "memory.memory_revision",
                "memory.memory_relation",
                "evidence.source_anchor",
                "evidence.source_unit",
                "evidence.source_payload");
        for (String table : tables) {
            List<String> rows = dsl.fetch("SELECT row_to_json(t)::text FROM " + table + " t")
                    .getValues(0, String.class);
            rows.stream().sorted().forEach(row -> snapshot.add(table + "|" + row));
        }
        return snapshot;
    }

    private static List<String> payloadSnapshot() {
        if (payloadRoot == null || !Files.exists(payloadRoot)) return List.of();
        try (Stream<Path> paths = Files.walk(payloadRoot)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return payloadRoot.relativize(path).toString().replace('\\', '/')
                                    + "|" + Files.size(path) + "|" + hex(sha256(Files.readAllBytes(path)));
                        } catch (Exception ex) {
                            throw new AssertionError(ex);
                        }
                    })
                    .sorted()
                    .toList();
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static TransactionExecutor immediateTransactions() {
        return new TransactionExecutor() {
            @Override public <T> T executeInTransaction(Supplier<T> work) { return work.get(); }
        };
    }

    private record Fixture(UUID fenceId, UUID closureId, UUID targetId, UUID decisionId,
            Long revisionRef, OffsetDateTime createdAt, String kind) {
        DeletionFencePort.FenceDraft draft() {
            return new DeletionFencePort.FenceDraft(fenceId, closureId, kind, targetId, revisionRef, decisionId, createdAt);
        }
    }

    private record MatrixFixture(
            UUID memoryId,
            UUID revisionId,
            Long revisionNo,
            UUID sourceId,
            UUID unitId,
            UUID payloadId,
            UUID anchorId,
            UUID actorId) {}

    private record AttackCase(String name, Runnable run) {}
}
