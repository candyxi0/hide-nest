package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3AException;
import io.github.candyxi0.hidenest.application.model.LocalV1DeletionEvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewResult;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicy;
import io.github.candyxi0.hidenest.memory.domain.AccessPolicyRevision;
import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jooq.impl.DSL;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
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

class LocalV1S3ADeletionPreviewTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH_HEX = "ab".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static TransactionExecutor transactions;
    private static Path payloadRoot;
    private static String dbPassword;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
        dbPassword = password;
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (var connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(18, Flyway.configure().dataSource(postgres.getJdbcUrl(), USER, password)
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load()
                .migrate().migrationsExecuted);
        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        MemoryGovernancePort governance = new JooqMemoryGovernanceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        transactions = executor;
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        payloadRoot = Files.createTempDirectory("s3a-payload-test-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);
        preview = new LocalV1S3ADeletionPreviewCoordinator(
                new JooqDeletionPreviewAdapter(dsl), executor, CLOCK, new JooqDeletionFenceAdapter(dsl), payloadStore);
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
    void migrationUpgradeAndRepeatAreExact() throws Exception {
        String database = "s3a_migration_" + UUID.randomUUID().toString().replace("-", "");
        String adminUrl = postgres.getJdbcUrl().replace("/hide_nest", "/postgres");
        try (var connection = DriverManager.getConnection(adminUrl, USER, dbPassword)) {
            connection.createStatement().execute("CREATE DATABASE " + database);
        }
        String databaseUrl = postgres.getJdbcUrl().replace("/hide_nest", "/" + database);
        Flyway v10 = Flyway.configure().dataSource(databaseUrl, USER, dbPassword)
                .defaultSchema("public").locations("classpath:db/migration").target("10").load();
        assertEquals(10, v10.migrate().migrationsExecuted);
        var v15 = Flyway.configure().dataSource(databaseUrl, USER, dbPassword)
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load();
        assertEquals(8, v15.migrate().migrationsExecuted);
        assertEquals(0, v15.migrate().migrationsExecuted);
    }

    @Test
    void exclusivePreviewIsImmutableAndIdempotent() {
        Fixture fixture = createMemory("exclusive", "根记忆正文 canary");
        byte[] requestHash = sha256("request-exclusive".getBytes(StandardCharsets.UTF_8));
        var request = new LocalV1S3ADeletionPreviewRequest(fixture.memoryId(), "preview-exclusive", requestHash);
        List<String> businessBefore = businessSnapshot();
        List<String> payloadBefore = payloadSnapshot();
        long before = count("memory.memory_record") + count("memory.memory_revision");
        var first = preview.preview(request);
        assertEquals(fixture.memoryId(), first.rootMemoryId());
        assertEquals(1L, first.previewRevision());
        assertEquals(List.of(fixture.memoryId()), first.deleteCandidateMemoryIds());
        assertEquals(2L, first.payloadCount());
        assertEquals(0, first.sharedMemories().size());
        assertEquals(before, count("memory.memory_record") + count("memory.memory_revision"));
        assertEquals(1, count("memory.deletion_closure"));
        assertEquals(1 + 1 + 2 + 2 + 2, count("memory.deletion_closure_member"));
        // Complete evidence body is now surfaced as a human-readable projection.
        assertTrue(first.evidence().stream().anyMatch(message -> message.bodyText().contains("EVIDENCE_BODY_CANARY")));

        var replay = preview.preview(request);
        assertResultEquals(first, replay);
        var second = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                fixture.memoryId(), "preview-exclusive-2", requestHash));
        assertEquals(2L, second.previewRevision());
        assertArrayEquals(first.manifestHash(), second.manifestHash());
        LocalV1S3AException hashConflict = assertThrows(LocalV1S3AException.class, () -> preview.preview(
                new LocalV1S3ADeletionPreviewRequest(fixture.memoryId(), "preview-exclusive", sha256("different".getBytes(StandardCharsets.UTF_8)))));
        assertEquals(LocalV1S3AException.Code.IDEMPOTENCY_CONFLICT, hashConflict.code());
        LocalV1S3AException rootConflict = assertThrows(LocalV1S3AException.class, () -> preview.preview(
                new LocalV1S3ADeletionPreviewRequest(UUID.randomUUID(), "preview-exclusive", requestHash)));
        assertEquals(LocalV1S3AException.Code.IDEMPOTENCY_CONFLICT, rootConflict.code());

        assertThrows(RuntimeException.class, () -> dsl.execute("UPDATE memory.deletion_closure SET state='PREVIEWED'"));
        assertThrows(RuntimeException.class, () -> dsl.execute("DELETE FROM memory.deletion_closure"));
        assertThrows(RuntimeException.class, () -> dsl.execute("UPDATE memory.deletion_closure_member SET disposition='DELETE_CANDIDATE'"));
        assertThrows(RuntimeException.class, () -> dsl.execute("DELETE FROM memory.deletion_closure_member"));
        assertEquals(businessBefore, businessSnapshot());
        assertEquals(payloadBefore, payloadSnapshot());
    }

    @Test
    void sharedPayloadMarksAffectedMemoryWithoutExpandingDeleteCandidates() {
        Fixture root = createMemory("shared-root", "root正文");
        Fixture other = createMemory("shared-other", "other正文");
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", other.revisionId());
        UUID decisionId = dsl.fetch("SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?", other.revisionId())
                .get(0).get(0, UUID.class);
        dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) VALUES (?,?, 'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), other.revisionId(), root.anchorOne(), decisionId);

        var result = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root.memoryId(), "preview-shared", sha256("request-shared".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of(root.memoryId()), result.deleteCandidateMemoryIds());
        assertEquals(1, result.sharedMemories().size());
        assertEquals(other.memoryId(), result.sharedMemories().get(0).memoryId());
        // Direct anchor reuse: only the first segment's message is retained by `other`.
        for (LocalV1DeletionEvidenceMessage message : result.evidence()) {
            if (message.ordinal() == 1L) {
                assertEquals(List.of(other.memoryId()), message.sharedByMemoryIds());
            } else {
                assertEquals(List.of(), message.sharedByMemoryIds());
            }
        }
    }

    @Test
    void indirectUnitSharingMapsToRetainingMemory() {
        Fixture root = createMemory("indirect-root", "root正文");
        Fixture other = createMemory("indirect-other", "other正文");
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", other.revisionId());
        UUID decisionId = dsl.fetch("SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?", other.revisionId())
                .get(0).get(0, UUID.class);
        UUID rootUnit = dsl.fetchOne("SELECT source_unit_id FROM evidence.source_anchor_unit WHERE anchor_id=?", root.anchorOne())
                .get(0, UUID.class);
        UUID sourceId = dsl.fetchOne("SELECT source_id FROM evidence.source_anchor WHERE anchor_id=?", root.anchorOne())
                .get(0, UUID.class);
        // A different (non-root) anchor reuses the same SourceUnit/Payload — indirect sharing.
        UUID indirectAnchor = UUID.randomUUID();
        dsl.execute("INSERT INTO evidence.source_anchor(anchor_id, source_id, anchor_kind, created_at) VALUES (?,?, 'MESSAGE_SEGMENT', clock_timestamp())",
                indirectAnchor, sourceId);
        dsl.execute("INSERT INTO evidence.source_anchor_unit(anchor_id, source_unit_id, from_offset, to_offset, ordinal) VALUES (?,?, 0, 1, 1)",
                indirectAnchor, rootUnit);
        dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) VALUES (?,?, 'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), other.revisionId(), indirectAnchor, decisionId);

        var result = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root.memoryId(), "preview-indirect", sha256("request-indirect".getBytes(StandardCharsets.UTF_8))));
        assertEquals(1, result.sharedMemories().size());
        assertEquals(other.memoryId(), result.sharedMemories().get(0).memoryId());
        for (LocalV1DeletionEvidenceMessage message : result.evidence()) {
            if (message.ordinal() == 1L) {
                assertEquals(List.of(other.memoryId()), message.sharedByMemoryIds());
            } else {
                assertEquals(List.of(), message.sharedByMemoryIds());
            }
        }
    }

    @Test
    void changedGraphMakesOldKeyStaleAndNewKeyCreatesNewSnapshot() {
        Fixture root = createMemory("stale-root", "stale-root-body");
        byte[] requestHash = sha256("request-stale".getBytes(StandardCharsets.UTF_8));
        var oldRequest = new LocalV1S3ADeletionPreviewRequest(root.memoryId(), "stale-old", requestHash);
        var first = preview.preview(oldRequest);
        List<String> oldClosure = closureSnapshot(first.previewId());

        Fixture other = createMemory("stale-other", "stale-other-body");
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", other.revisionId());
        UUID decisionId = dsl.fetch(
                        "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                        other.revisionId())
                .get(0).get(0, UUID.class);
        dsl.execute(
                "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                        + "VALUES (?,?, 'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), other.revisionId(), root.anchorOne(), decisionId);

        LocalV1S3AException stale = assertThrows(LocalV1S3AException.class, () -> preview.preview(oldRequest));
        assertEquals(LocalV1S3AException.Code.PREVIEW_STALE, stale.code());
        var newer = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root.memoryId(), "stale-new", requestHash));
        assertEquals(2L, newer.previewRevision());
        assertFalse(java.util.Arrays.equals(first.manifestHash(), newer.manifestHash()));
        assertEquals(oldClosure, closureSnapshot(first.previewId()));
    }

    @Test
    void historicalRevisionSharingIsAffectedPendingChoice() throws Exception {
        Fixture root = createMemory("historical-root", "historical-root-body");
        Fixture other = createMemory("historical-other", "historical-other-body");
        UUID historicalRevision = other.revisionId();
        publishSecondRevision(other, "historical-current-body");
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", historicalRevision);
        UUID decisionId = dsl.fetch(
                        "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                        historicalRevision)
                .get(0).get(0, UUID.class);
        dsl.execute(
                "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                        + "VALUES (?,?, 'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), historicalRevision, root.anchorOne(), decisionId);

        var result = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root.memoryId(), "historical-shared", sha256("historical".getBytes(StandardCharsets.UTF_8))));
        assertEquals(List.of(root.memoryId()), result.deleteCandidateMemoryIds());
        assertEquals(1, result.sharedMemories().size());
        assertEquals(other.memoryId(), result.sharedMemories().get(0).memoryId());
    }

    @Test
    void graphAttackSetIsRejectedBeforePersistence() {
        UUID unitId = UUID.randomUUID();
        UUID payloadId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        List<Supplier<DeletionPreviewGraph>> attacks = List.of(
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    MemoryRecord root = graph.rootMemory();
                    return new DeletionPreviewGraph(
                            new MemoryRecord(root.memoryId(), root.state(), UUID.randomUUID(), root.policyId(),
                                    root.currentPolicyRevisionNo(), root.createdAt(), root.updatedAt()),
                            graph.currentRevision(), graph.policy(), graph.policyRevision(), graph.allRevisions(),
                            graph.anchors(), graph.sharedAnchorIds(), graph.sharedUnitIds(), graph.sharedPayloadIds(),
                            graph.evidenceUnits(), graph.sharedMemories());
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return new DeletionPreviewGraph(graph.rootMemory(), graph.currentRevision(),
                            new AccessPolicy(graph.policy().policyId(), "MEMORY", UUID.randomUUID(), 1L, null),
                            graph.policyRevision(), graph.allRevisions(), graph.anchors(),
                            graph.sharedAnchorIds(), graph.sharedUnitIds(), graph.sharedPayloadIds(),
                            graph.evidenceUnits(), graph.sharedMemories());
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return replaceEvidence(graph, new DeletionPreviewGraph.Anchor(
                            UUID.randomUUID(), UUID.randomUUID(),
                            List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId,
                                    List.of(validPayload(payloadId, unitId))))));
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return replaceEvidence(graph, new DeletionPreviewGraph.Anchor(
                            UUID.randomUUID(), sourceId,
                            List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId, List.of()))));
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return replaceEvidence(graph, new DeletionPreviewGraph.Anchor(
                            UUID.randomUUID(), sourceId,
                            List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId,
                                    List.of(validPayload(payloadId, unitId), validPayload(UUID.randomUUID(), unitId))))));
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return replaceEvidence(graph, new DeletionPreviewGraph.Anchor(
                            UUID.randomUUID(), sourceId,
                            List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId,
                                    List.of(new DeletionPreviewGraph.Payload(payloadId, unitId, "TEXT",
                                            "text/plain; charset=UTF-8", "LOCAL_FILE", null,
                                            "MINIMUM_EVIDENCE", 1L, new byte[31]))))));
                },
                () -> {
                    DeletionPreviewGraph graph = validGraph();
                    return replaceEvidence(graph, new DeletionPreviewGraph.Anchor(
                            UUID.randomUUID(), sourceId,
                            List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId,
                                    List.of(new DeletionPreviewGraph.Payload(payloadId, unitId, "TEXT",
                                            "text/plain; charset=UTF-8", "LOCAL_FILE", null,
                                            "MINIMUM_EVIDENCE", -1L, sha256(new byte[] {1})))))));
                });

        for (Supplier<DeletionPreviewGraph> attack : attacks) {
            FakePreviewPort port = new FakePreviewPort(attack.get());
            LocalV1S3ADeletionPreviewCoordinator coordinator = new LocalV1S3ADeletionPreviewCoordinator(
                    port, immediateTransactions(), CLOCK, new TestDeletionFencePort(), new FakePayloadStore());
            assertThrows(LocalV1S3AException.class, () -> coordinator.preview(
                    new LocalV1S3ADeletionPreviewRequest(UUID.randomUUID(), "attack-" + UUID.randomUUID(),
                            new byte[32])));
            assertNull(port.inserted);
        }
        assertEquals(7, attacks.size());
    }

    @Test
    void sharedByMemoryIdsAttackSetIsRejectedBeforePersistence() {
        UUID foreignId = UUID.randomUUID();
        List<Supplier<DeletionPreviewGraph>> attacks = List.of(
                // sharedByMemoryIds references a memory absent from the shared dictionary.
                () -> graphWithSharedBy(List.of(UUID.randomUUID()), List.of()),
                // global dictionary orphan: sharedMemories not referenced by any evidence.
                () -> graphWithSharedBy(List.of(), List.of(new DeletionPreviewGraph.SharedMemory(UUID.randomUUID(), 1L, "orphan"))),
                // root memoryId in sharedByMemoryIds.
                () -> graphWithSharedBy(List.of(validGraph().rootMemory().memoryId()), List.of()),
                // duplicate sharedByMemoryIds.
                () -> graphWithSharedBy(List.of(foreignId, foreignId),
                        List.of(new DeletionPreviewGraph.SharedMemory(foreignId, 1L, "dup"))));

        for (Supplier<DeletionPreviewGraph> attack : attacks) {
            FakePreviewPort port = new FakePreviewPort(attack.get());
            LocalV1S3ADeletionPreviewCoordinator coordinator = new LocalV1S3ADeletionPreviewCoordinator(
                    port, immediateTransactions(), CLOCK, new TestDeletionFencePort(), new FakePayloadStore());
            assertThrows(LocalV1S3AException.class, () -> coordinator.preview(
                    new LocalV1S3ADeletionPreviewRequest(UUID.randomUUID(), "attack-" + UUID.randomUUID(),
                            new byte[32])));
            assertNull(port.inserted);
        }
        assertEquals(4, attacks.size());
    }

    @Test
    void normalizedNodeBoundaryUsesFormalCoordinator() {
        for (int affectedCount : List.of(995, 996)) {
            FakePreviewPort port = new FakePreviewPort(graphWithAffectedMemories(affectedCount));
            LocalV1S3ADeletionPreviewCoordinator coordinator = new LocalV1S3ADeletionPreviewCoordinator(
                    port, immediateTransactions(), CLOCK, new TestDeletionFencePort(), new FakePayloadStore());
            LocalV1S3AException exception = null;
            try {
                coordinator.preview(new LocalV1S3ADeletionPreviewRequest(
                        UUID.randomUUID(), "node-" + affectedCount, new byte[32]));
            } catch (LocalV1S3AException ex) {
                exception = ex;
            }
            if (affectedCount == 995) {
                assertNull(exception);
                assertNotNull(port.inserted);
            } else {
                assertNotNull(exception);
                assertEquals(LocalV1S3AException.Code.GRAPH_LIMIT_EXCEEDED, exception.code());
                assertNull(port.inserted);
            }
        }
    }

    @Test
    void persistenceFailureRollsBackClosureAndMembers() {
        Fixture fixture = createMemory("rollback", "rollback-body");
        int closures = count("memory.deletion_closure");
        int members = count("memory.deletion_closure_member");
        DeletionPreviewPort delegate = new JooqDeletionPreviewAdapter(dsl);
        LocalV1S3ADeletionPreviewCoordinator failing = new LocalV1S3ADeletionPreviewCoordinator(
                new FailingInsertPort(delegate), transactions, CLOCK, new JooqDeletionFenceAdapter(dsl), new FakePayloadStore());
        LocalV1S3AException exception = assertThrows(LocalV1S3AException.class, () -> failing.preview(
                new LocalV1S3ADeletionPreviewRequest(fixture.memoryId(), "rollback-key", new byte[32])));
        assertEquals(LocalV1S3AException.Code.PERSISTENCE_CONFLICT, exception.code());
        assertEquals(closures, count("memory.deletion_closure"));
        assertEquals(members, count("memory.deletion_closure_member"));
    }

    private static void assertResultEquals(LocalV1S3ADeletionPreviewResult expected,
            LocalV1S3ADeletionPreviewResult actual) {
        assertEquals(expected.previewId(), actual.previewId());
        assertEquals(expected.previewRevision(), actual.previewRevision());
        assertArrayEquals(expected.manifestHash(), actual.manifestHash());
        assertEquals(expected.expiresAt(), actual.expiresAt());
        assertEquals(expected.rootMemoryId(), actual.rootMemoryId());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.currentRevisionNo(), actual.currentRevisionNo());
        assertEquals(expected.policyRevisionNo(), actual.policyRevisionNo());
        assertEquals(expected.rootBodyPreview(), actual.rootBodyPreview());
        assertEquals(expected.deleteCandidateMemoryIds(), actual.deleteCandidateMemoryIds());
        assertEquals(expected.payloadCount(), actual.payloadCount());
        assertEquals(expected.payloadBytes(), actual.payloadBytes());
        assertEquals(expected.evidence(), actual.evidence());
        assertEquals(expected.sharedMemories(), actual.sharedMemories());
    }

    private static List<String> closureSnapshot(UUID closureId) {
        List<String> snapshot = new ArrayList<>();
        snapshot.addAll(dsl.fetch(
                        "SELECT row_to_json(t)::text FROM memory.deletion_closure t WHERE closure_id=?", closureId)
                .getValues(0, String.class));
        snapshot.addAll(dsl.fetch(
                        "SELECT row_to_json(t)::text FROM memory.deletion_closure_member t WHERE closure_id=? ORDER BY ordinal",
                        closureId)
                .getValues(0, String.class));
        return snapshot;
    }

    private static List<String> businessSnapshot() {
        List<String> snapshot = new ArrayList<>();
        List<String> tables = dsl.fetch(
                        "SELECT table_schema || '.' || table_name FROM information_schema.tables "
                                + "WHERE table_schema IN ('evidence','memory','runtime','security') "
                                + "AND table_type='BASE TABLE' AND table_name NOT IN ('deletion_closure','deletion_closure_member') "
                                + "ORDER BY table_schema, table_name")
                .getValues(0, String.class);
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

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static TransactionExecutor immediateTransactions() {
        return new TransactionExecutor() {
            @Override
            public <T> T executeInTransaction(Supplier<T> work) {
                return work.get();
            }
        };
    }

    private static DeletionPreviewGraph validGraph() {
        UUID memoryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        UUID unitId = UUID.randomUUID();
        UUID payloadId = UUID.randomUUID();
        MemoryRevision revision = new MemoryRevision(revisionId, memoryId, 1L, "Claim", actorId,
                "synthetic body", null, null, null, UUID.randomUUID(), null);
        return new DeletionPreviewGraph(
                new MemoryRecord(memoryId, "ACTIVE", revisionId, policyId, 1L, null, null), revision,
                new AccessPolicy(policyId, "MEMORY", memoryId, 1L, null),
                new AccessPolicyRevision(policyId, 1L, true, true, false, false, false, UUID.randomUUID(), null),
                List.of(revision),
                List.of(new DeletionPreviewGraph.Anchor(anchorId, sourceId,
                        List.of(new DeletionPreviewGraph.SourceUnit(unitId, sourceId,
                                List.of(validPayload(payloadId, unitId)))))),
                Set.of(), Set.of(), Set.of(),
                List.of(new DeletionPreviewGraph.EvidenceUnit(
                        anchorId, unitId, 1L, actorId, "SYNTHETIC", "a-" + actorId, "小林",
                        OffsetDateTime.now(CLOCK), "aa/bb.payload", 1L,
                        sha256(payloadId.toString().getBytes(StandardCharsets.UTF_8)), List.of())),
                List.of());
    }

    private static DeletionPreviewGraph graphWithSharedBy(
            List<UUID> sharedBy, List<DeletionPreviewGraph.SharedMemory> sharedMemories) {
        DeletionPreviewGraph base = validGraph();
        DeletionPreviewGraph.EvidenceUnit unit = base.evidenceUnits().get(0);
        DeletionPreviewGraph.EvidenceUnit updated = new DeletionPreviewGraph.EvidenceUnit(
                unit.anchorId(), unit.sourceUnitId(), unit.ordinal(), unit.actorId(), unit.actorKind(),
                unit.actorStableRef(), unit.displayLabel(), unit.occurredAt(), unit.objectRef(),
                unit.sizeBytes(), unit.contentHash(), sharedBy);
        return new DeletionPreviewGraph(base.rootMemory(), base.currentRevision(), base.policy(),
                base.policyRevision(), base.allRevisions(), base.anchors(),
                base.sharedAnchorIds(), base.sharedUnitIds(), Set.of(),
                List.of(updated), sharedMemories);
    }

    private static DeletionPreviewGraph.Payload validPayload(UUID payloadId, UUID unitId) {
        return new DeletionPreviewGraph.Payload(payloadId, unitId, "TEXT", "text/plain; charset=UTF-8",
                "LOCAL_FILE", null, "MINIMUM_EVIDENCE", 1L, sha256(payloadId.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static DeletionPreviewGraph replaceEvidence(DeletionPreviewGraph graph,
            DeletionPreviewGraph.Anchor anchor) {
        return new DeletionPreviewGraph(graph.rootMemory(), graph.currentRevision(), graph.policy(),
                graph.policyRevision(), graph.allRevisions(), List.of(anchor),
                graph.sharedAnchorIds(), graph.sharedUnitIds(), graph.sharedPayloadIds(),
                graph.evidenceUnits(), graph.sharedMemories());
    }

    private static DeletionPreviewGraph graphWithAffectedMemories(int count) {
        DeletionPreviewGraph base = validGraph();
        DeletionPreviewGraph.SourceUnit sourceUnit = base.anchors().get(0).sourceUnits().get(0);
        DeletionPreviewGraph.Payload payload = sourceUnit.payloads().get(0);
        List<UUID> memoryIds = new ArrayList<>();
        List<DeletionPreviewGraph.SharedMemory> shared = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            memoryIds.add(id);
            shared.add(new DeletionPreviewGraph.SharedMemory(id, 1L, "shared-" + i));
        }
        List<UUID> sortedMemoryIds = memoryIds.stream().sorted().toList();
        DeletionPreviewGraph.EvidenceUnit unit = base.evidenceUnits().get(0);
        DeletionPreviewGraph.EvidenceUnit retainedUnit = new DeletionPreviewGraph.EvidenceUnit(
                unit.anchorId(), unit.sourceUnitId(), unit.ordinal(), unit.actorId(), unit.actorKind(),
                unit.actorStableRef(), unit.displayLabel(), unit.occurredAt(), unit.objectRef(),
                unit.sizeBytes(), unit.contentHash(), sortedMemoryIds);
        return new DeletionPreviewGraph(base.rootMemory(), base.currentRevision(), base.policy(),
                base.policyRevision(), base.allRevisions(), base.anchors(),
                base.sharedAnchorIds(), Set.of(sourceUnit.sourceUnitId()), Set.of(payload.payloadId()),
                List.of(retainedUnit), shared);
    }

    private static void publishSecondRevision(Fixture fixture, String body) throws Exception {
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID reviewChangeId = UUID.randomUUID();
        UUID memoryChangeId = UUID.randomUUID();
        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute(("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                            + "VALUES ('%s','REVISE','%s',clock_timestamp())").formatted(proposalId, fixture.memoryId()));
            tx.execute(("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                            + "expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                            + "('%s','%s',1,'REVISE','%s',1,clock_timestamp())")
                    .formatted(proposalRevisionId, proposalId, fixture.revisionId()));
            tx.execute(("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                            + "VALUES ('%s','OPEN','r1-%s',decode('%s','hex'),clock_timestamp())")
                    .formatted(reviewId, reviewId, HASH_HEX));
            tx.execute(("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                            + "VALUES ('%s','%s',1)").formatted(reviewId, proposalRevisionId));
            tx.execute(("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                            + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',2,'synthetic','r1-decision-%s',clock_timestamp())")
                    .formatted(decisionId, fixture.actorId(), proposalRevisionId, reviewId, fixture.memoryId(), decisionId));
            insertGoverned(tx, reviewChangeId, "review.decisions-committed.v1", "REVIEW_SESSION", reviewId, 2L,
                    decisionId);
            tx.execute(("INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) "
                            + "VALUES ('%s','%s',2,'Claim','%s','%s',clock_timestamp())")
                    .formatted(revisionId, fixture.memoryId(), body, decisionId));
            insertGoverned(tx, memoryChangeId, "memory.canonical-committed.v1", "MEMORY", fixture.memoryId(), 2L,
                    decisionId);
            tx.execute(("UPDATE memory.memory_record SET current_revision_id='%s',updated_at=clock_timestamp() "
                            + "WHERE memory_id='%s' AND current_revision_id='%s'")
                    .formatted(revisionId, fixture.memoryId(), fixture.revisionId()));
        });
    }

    private static void insertGoverned(DSLContext tx, UUID changeId, String eventType, String aggregateKind,
            UUID aggregateId, long revision, UUID decisionId) {
        tx.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,decision_id,occurred_at) "
                        + "VALUES ('%s','%s','%s','%s',%d,'%s',clock_timestamp())")
                .formatted(changeId, eventType, aggregateKind, aggregateId, revision, decisionId));
        String manifest = "{\"aggregateId\":\"%s\",\"aggregateRevision\":%d,\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"%s\"}"
                .formatted(aggregateId, revision, HASH_HEX);
        tx.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) "
                        + "VALUES ('%s','r1-outbox-%s','GOVERNED','%s','%s','%s',%d,'pink.event.v1','DATABASE_TEST',0,decode('%s','hex'),'%s'::jsonb,'%s','READY',clock_timestamp(),0,8,clock_timestamp())")
                .formatted(UUID.randomUUID(), UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision,
                        HASH_HEX, manifest, changeId));
    }

    private static final class FakePreviewPort implements DeletionPreviewPort {
        private final DeletionPreviewGraph graph;
        private PreviewDraft inserted;

        private FakePreviewPort(DeletionPreviewGraph graph) {
            this.graph = graph;
        }

        @Override
        public ExistingPreview findByIdempotencyKey(String idempotencyKey) { return null; }

        @Override
        public DeletionPreviewGraph lockAndReadGraph(UUID rootMemoryId) { return graph; }

        @Override
        public long nextPreviewRevision(UUID rootMemoryId) { return 1L; }

        @Override
        public void insertPreview(PreviewDraft draft) { inserted = draft; }
    }

    private static final class FailingInsertPort implements DeletionPreviewPort {
        private final DeletionPreviewPort delegate;

        private FailingInsertPort(DeletionPreviewPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public ExistingPreview findByIdempotencyKey(String idempotencyKey) {
            return delegate.findByIdempotencyKey(idempotencyKey);
        }

        @Override
        public DeletionPreviewGraph lockAndReadGraph(UUID rootMemoryId) {
            return delegate.lockAndReadGraph(rootMemoryId);
        }

        @Override
        public long nextPreviewRevision(UUID rootMemoryId) {
            return delegate.nextPreviewRevision(rootMemoryId);
        }

        @Override
        public void insertPreview(PreviewDraft draft) {
            delegate.insertPreview(draft);
            throw new IllegalStateException("synthetic member persistence failure");
        }
    }

    private static final class TestDeletionFencePort implements DeletionFencePort {
        @Override
        public void insertFences(List<FenceDraft> drafts) {
            throw new UnsupportedOperationException("S3A preview tests do not write deletion fences");
        }

        @Override
        public boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef) {
            return false;
        }

        @Override
        public List<DeletionFence> findByClosureId(UUID closureId) {
            return List.of();
        }
    }

    private static final class FakePayloadStore implements PayloadStore {
        @Override
        public PayloadPutResult put(UUID payloadId, String contentType, byte[] bytes, byte[] expectedHash) {
            throw new UnsupportedOperationException("S3A preview unit tests do not write payload files");
        }

        @Override
        public byte[] get(String objectRef, byte[] expectedHash, long maxBytes) {
            return new byte[Math.toIntExact(maxBytes)];
        }

        @Override
        public PayloadHeadResult head(String objectRef) {
            throw new UnsupportedOperationException("S3A preview unit tests do not head payload files");
        }

        @Override
        public void delete(String objectRef, byte[] expectedHash) {
            throw new UnsupportedOperationException("S3A preview unit tests do not delete payload files");
        }
    }

    private Fixture createMemory(String marker, String body) {
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String key = "s3a-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key.getBytes(StandardCharsets.UTF_8)), actor, "Interpretation", body,
                sha256(body.getBytes(StandardCharsets.UTF_8)),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(unitOne, actor, 1L,
                        "unit-one-" + marker, OffsetDateTime.now(CLOCK), "evidence-one"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(unitTwo, actor, 2L,
                                "unit-two-" + marker, OffsetDateTime.now(CLOCK), "EVIDENCE_BODY_CANARY-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key, sha256(("confirm-" + key).getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(), prepared.reviewSessionId(), memoryId,
                UUID.randomUUID(), new byte[32]));
        return new Fixture(memoryId, confirmed.currentRevisionId(), anchorOne, actor);
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    private static int count(String table) {
        return dsl.resultQuery("SELECT count(*) FROM " + table).fetchOne().get(0, Integer.class);
    }

    private record Fixture(UUID memoryId, UUID revisionId, UUID anchorOne, UUID actorId) {}
}
