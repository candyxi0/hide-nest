package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3ADeletionPreviewCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BDeletionConfirmCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S3B2BException;
import io.github.candyxi0.hidenest.application.model.CanonicalFailureCode;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3ADeletionPreviewRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S3B2BDeletionConfirmResult;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionPreviewAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.DeletionConfirmationPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalV1S3B2BDeletionConfirmationCoordinatorTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH_HEX = "ab".repeat(32);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S3ADeletionPreviewCoordinator preview;
    private static LocalV1S3B2BDeletionConfirmCoordinator confirm;
    private static TransactionExecutor transactions;
    private static Path payloadRoot;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
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
        assertEquals(13, Flyway.configure().dataSource(postgres.getJdbcUrl(), USER, password)
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
        payloadRoot = Files.createTempDirectory("s3b2b-payload-test-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);
        DeletionPreviewPort previewAdapter = new JooqDeletionPreviewAdapter(dsl);
        DeletionFencePort fenceAdapter = new JooqDeletionFenceAdapter(dsl);
        preview = new LocalV1S3ADeletionPreviewCoordinator(previewAdapter, executor, CLOCK, fenceAdapter);
        confirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), governance, fenceAdapter, previewAdapter, executor, CLOCK);
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

    // -- 1. legal confirm ----------------------------------------------------

    @Test
    @DisplayName("1. legal confirm: 1 decision, all non-affected fenced exactly once, affected 0 fences, closure CONFIRMED")
    void legalConfirm() {
        Fixture fixture = createMemoryAndPreview("legal", "legal-root-body", false);
        var result = confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "confirm-legal"));
        assertEquals(fixture.previewId(), result.closureId());
        assertEquals(fixture.previewRevision(), result.previewRevision());
        assertArrayEquals(fixture.manifestHash(), result.manifestHash());
        assertNotNull(result.decisionId());
        assertEquals("CONFIRMED", result.state());
        assertTrue(result.fenceCount() > 0);
        assertEquals(0, result.unfencedAffectedCount());

        assertEquals("CONFIRMED", closureState(fixture.previewId()));
        assertEquals(1L, count("SELECT count(*) FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?",
                fixture.previewId()));
        // Each non-AFFECTED member has exactly 1 fence
        List<Long> fencePerMember = dsl.resultQuery(
                "SELECT count(*) FROM memory.deletion_fence WHERE closure_id=? GROUP BY target_kind,target_id,COALESCE(target_revision_ref,0)",
                fixture.previewId()).fetch().getValues(0, Long.class);
        for (long count : fencePerMember) {
            assertEquals(1L, count);
        }
        // AFFECTED members have 0 fences
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence f "
                + "JOIN memory.deletion_closure_member m ON m.closure_id=f.closure_id "
                + "AND m.member_kind=f.target_kind AND m.target_id=f.target_id "
                + "AND m.target_revision_ref IS NOT DISTINCT FROM f.target_revision_ref "
                + "WHERE f.closure_id=? AND m.disposition='AFFECTED_PENDING_CHOICE'",
                fixture.previewId()));
    }

    // -- 2. exact replay same key same values ---------------------------------

    @Test
    @DisplayName("2. same-key same-value replay: result all fields EXACT, database count does not grow")
    void exactReplay() {
        Fixture fixture = createMemoryAndPreview("replay", "replay-root-body", false);
        var request = new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "confirm-replay");
        var first = confirm.confirm(request);
        long decisionCount = count("SELECT count(*) FROM memory.decision");
        long fenceCount = count("SELECT count(*) FROM memory.deletion_fence");

        var second = confirm.confirm(request);
        assertEquals(first.closureId(), second.closureId());
        assertEquals(first.previewRevision(), second.previewRevision());
        assertArrayEquals(first.manifestHash(), second.manifestHash());
        assertEquals(first.decisionId(), second.decisionId());
        assertEquals(first.confirmedAt(), second.confirmedAt());
        assertEquals(first.state(), second.state());
        assertEquals(first.fenceCount(), second.fenceCount());
        assertEquals(first.unfencedAffectedCount(), second.unfencedAffectedCount());
        assertEquals(decisionCount, count("SELECT count(*) FROM memory.decision"));
        assertEquals(fenceCount, count("SELECT count(*) FROM memory.deletion_fence"));
    }

    // -- 3. same key different values -----------------------------------------

    @Test
    @DisplayName("3. same-key different closure/preview/manifest/actor: all rejected")
    void sameKeyDifferentValues() {
        Fixture fixture = createMemoryAndPreview("same-key-diff", "root-body", false);
        // Create second preview BEFORE confirming first (confirm creates fences blocking new previews)
        var preview2 = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                fixture.memoryId(), "same-key-diff-prev2", sha256("same-key-diff-prev2")));

        confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "same-key-diff-key"));

        // Different closure
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        UUID.randomUUID(), fixture.previewRevision(), fixture.manifestHash(),
                        fixture.actorId(), "same-key-diff-key")));

        // Different preview revision (use preview2's revision on fixture's previewId)
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), preview2.previewRevision(), fixture.manifestHash(),
                        fixture.actorId(), "same-key-diff-key")));

        // Different manifest
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), fixture.previewRevision(), sha256("different-manifest"),
                        fixture.actorId(), "same-key-diff-key")));

        // Different actor
        UUID otherActor = UUID.randomUUID();
        dsl.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES (?,'SYNTHETIC',?,clock_timestamp())",
                otherActor, "other-" + otherActor);
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                        otherActor, "same-key-diff-key")));
    }

    // -- 4. different keys concurrent same closure: exactly one wins ----------

    @Test
    @DisplayName("4. different keys concurrent same closure: exactly one success, one decision")
    void differentKeysConcurrent() throws Exception {
        Fixture fixture = createMemoryAndPreview("dk-concur", "root-dk", false);
        var executor = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        var results = new ArrayList<Object>();

        Supplier<Void> task = () -> {
            try {
                latch.await();
                var result = confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                        fixture.actorId(), "dk-concur-" + UUID.randomUUID()));
                synchronized (results) { results.add(result); }
            } catch (Exception e) {
                synchronized (results) { results.add(e); }
            }
            return null;
        };

        var f1 = executor.submit(() -> task.get());
        var f2 = executor.submit(() -> task.get());
        latch.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        long successCount = results.stream().filter(r -> r instanceof LocalV1S3B2BDeletionConfirmResult).count();
        assertEquals(1L, successCount);
        assertEquals(1L, count("SELECT count(*) FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?",
                fixture.previewId()));
        assertEquals("CONFIRMED", closureState(fixture.previewId()));
    }

    // -- 5. same key concurrent same value: both get same decision/confirmedAt -

    @Test
    @DisplayName("5. same-key concurrent same-value: both calls get same decisionId/confirmedAt, one fact in DB")
    void sameKeyConcurrentSameValue() throws Exception {
        Fixture fixture = createMemoryAndPreview("sk-concur", "root-sk", false);
        var request = new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "sk-concur-key");
        var executor = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        var results = new ArrayList<LocalV1S3B2BDeletionConfirmResult>();

        Supplier<LocalV1S3B2BDeletionConfirmResult> task = () -> {
            try {
                latch.await();
                return confirm.confirm(request);
            } catch (Exception e) {
                return null;
            }
        };

        var f1 = executor.submit(() -> task.get());
        var f2 = executor.submit(() -> task.get());
        latch.countDown();
        var r1 = f1.get(30, TimeUnit.SECONDS);
        var r2 = f2.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertNotNull(r1);
        assertNotNull(r2);
        assertEquals(r1.decisionId(), r2.decisionId());
        assertEquals(r1.confirmedAt(), r2.confirmedAt());
        assertEquals(r1.fenceCount(), r2.fenceCount());
        assertEquals(1L, count("SELECT count(*) FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?",
                fixture.previewId()));
    }

    // -- 6. stale scenarios ---------------------------------------------------

    @Test
    @DisplayName("6. preview revision, manifest, memory revision, policy revision, and expired each stale")
    void staleScenarios() {
        Fixture fixture = createMemoryAndPreview("stale-all", "stale-root", false);

        // wrong preview revision → CLOSURE_MISMATCH
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), 999L, fixture.manifestHash(),
                        fixture.actorId(), "stale-revision")));

        // wrong manifest → CLOSURE_MISMATCH
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), fixture.previewRevision(), sha256("wrong-manifest"),
                        fixture.actorId(), "stale-manifest")));

        // Expired: re-create coordinator with future clock so expires_at < now
        Clock futureClock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneId.of("UTC"));
        var expiredConfirm = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), new JooqMemoryGovernanceAdapter(dsl),
                new JooqDeletionFenceAdapter(dsl), new JooqDeletionPreviewAdapter(dsl),
                transactions, futureClock);
        Fixture expiredFixture = createMemoryAndPreview("stale-expired", "expired-root", false);
        assertThrowsB2B(CanonicalFailureCode.DELETION_PREVIEW_STALE, () ->
                expiredConfirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        expiredFixture.previewId(), expiredFixture.previewRevision(), expiredFixture.manifestHash(),
                        expiredFixture.actorId(), "stale-expired-key")));

        // memory revision change makes stale (canonical re-derivation fails) → PREVIEW_STALE
        Fixture changedFixture = createMemoryAndPreview("stale-mem-changed", "mem-root", false);
        publishSecondRevision(changedFixture, "new-body-text");
        assertThrowsB2B(CanonicalFailureCode.DELETION_PREVIEW_STALE, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        changedFixture.previewId(), changedFixture.previewRevision(), changedFixture.manifestHash(),
                        changedFixture.actorId(), "stale-mem-key")));

        // access_policy pointer changes while memory_record still points at the previewed policy revision
        Fixture policyChanged = createMemoryAndPreview("stale-policy-changed", "policy-root", false);
        publishSecondPolicyRevision(policyChanged);
        assertThrowsB2B(CanonicalFailureCode.DELETION_PREVIEW_STALE, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        policyChanged.previewId(), policyChanged.previewRevision(), policyChanged.manifestHash(),
                        policyChanged.actorId(), "stale-policy-key")));
    }

    // -- 7. member set tampered or graph changed ------------------------------

    @Test
    @DisplayName("7. canonical graph member added/removed: stale, zero confirmation facts")
    void memberSetChanges() {
        // Adding an affected memory changes the canonical member set → PREVIEW_STALE
        Fixture root2 = createMemoryAndPreview("member-add-affected", "add-root", false);
        Fixture other = createMemory("member-other", "other-body", root2.anchorOne());
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", other.revisionId());
        UUID decisionId = dsl.fetch("SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                other.revisionId()).get(0).get(0, UUID.class);
        dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                UUID.randomUUID(), other.revisionId(), root2.anchorOne(), decisionId);
        assertThrowsB2B(CanonicalFailureCode.DELETION_PREVIEW_STALE, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        root2.previewId(), root2.previewRevision(), root2.manifestHash(),
                        root2.actorId(), "member-add")));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?",
                root2.previewId()));

        // Removing affected memory: create link first, then preview, then delete link → STALE
        Fixture root3mem = createMemory("member-remove-root", "remove-root-body");
        Fixture linked = createMemory("member-remove-linked", "linked-body", root3mem.anchorOne());
        // root3mem now has linked as affected. Create preview.
        byte[] root3ReqHash = sha256("preview-member-remove");
        var root3preview = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                root3mem.memoryId(), "preview-member-remove", root3ReqHash));
        // Now remove the link → graph changes
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", linked.revisionId());
        assertThrowsB2B(CanonicalFailureCode.DELETION_PREVIEW_STALE, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        root3preview.previewId(), root3preview.previewRevision(), root3preview.manifestHash(),
                        root3mem.actorId(), "member-remove")));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?",
                root3preview.previewId()));
    }

    // -- 8. actor does not exist ----------------------------------------------

    @Test
    @DisplayName("8. actor does not exist: rejected, coordinator does not create ActorRef")
    void actorNotFound() {
        Fixture fixture = createMemoryAndPreview("no-actor", "actor-root", false);
        long actorCount = count("SELECT count(*) FROM memory.actor_ref");
        assertThrowsB2B(CanonicalFailureCode.DELETION_CLOSURE_MISMATCH, () ->
                confirm.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                        fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                        UUID.randomUUID(), "no-actor-key")));
        assertEquals(actorCount, count("SELECT count(*) FROM memory.actor_ref"));
    }

    // -- 9. failure injection: full rollback at 3 points ----------------------

    @Test
    @DisplayName("9. injected failures after decision, partial fences, and closure CAS: all rolled back")
    void injectedFailureRollback() {
        Fixture fixture = createMemoryAndPreview("inject", "inject-root", false);

        // Inject after decision insertion: wrap governance port to throw after insertDecision
        var failAfterDecision = new FailingMemoryGovernancePort(
                new JooqMemoryGovernanceAdapter(dsl), true);
        var c1 = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), failAfterDecision,
                new JooqDeletionFenceAdapter(dsl), new JooqDeletionPreviewAdapter(dsl),
                transactions, CLOCK);
        assertThrows(InjectedFailure.class, () -> c1.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "inject-decision")));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE idempotency_key='inject-decision'"));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.previewId()));
        assertEquals("PREVIEWED", closureState(fixture.previewId()));

        // Inject after partial fences
        var failAfterFences = new FailingDeletionFencePort(
                new JooqDeletionFenceAdapter(dsl), true);
        var c2 = new LocalV1S3B2BDeletionConfirmCoordinator(
                new JooqDeletionConfirmationAdapter(dsl), new JooqMemoryGovernanceAdapter(dsl),
                failAfterFences, new JooqDeletionPreviewAdapter(dsl),
                transactions, CLOCK);
        assertThrows(InjectedFailure.class, () -> c2.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "inject-fences")));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE idempotency_key='inject-fences'"));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.previewId()));
        assertEquals("PREVIEWED", closureState(fixture.previewId()));

        // Inject after closure CAS success
        var failAfterConfirm = new FailingDeletionConfirmationPort(
                new JooqDeletionConfirmationAdapter(dsl), true);
        var c3 = new LocalV1S3B2BDeletionConfirmCoordinator(
                failAfterConfirm, new JooqMemoryGovernanceAdapter(dsl),
                new JooqDeletionFenceAdapter(dsl), new JooqDeletionPreviewAdapter(dsl),
                transactions, CLOCK);
        assertThrows(InjectedFailure.class, () -> c3.confirm(new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), fixture.manifestHash(),
                fixture.actorId(), "inject-confirm")));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE idempotency_key='inject-confirm'"));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.previewId()));
        assertEquals("PREVIEWED", closureState(fixture.previewId()));
    }

    // -- 10. defensive copy ---------------------------------------------------

    @Test
    @DisplayName("10. defensive copy: request/result byte[] mutations do not leak")
    void defensiveCopy() {
        Fixture fixture = createMemoryAndPreview("defcopy", "defcopy-root", false);
        byte[] manifest = fixture.manifestHash();
        byte[] originalManifest = manifest.clone();
        var request = new LocalV1S3B2BDeletionConfirmRequest(
                fixture.previewId(), fixture.previewRevision(), manifest,
                fixture.actorId(), "defcopy-key");
        manifest[0] = (byte) 0xFF;
        assertArrayEquals(originalManifest, request.manifestHash());

        var result = confirm.confirm(request);
        byte[] resultHash = result.manifestHash();
        resultHash[0] = (byte) 0xFF;
        assertArrayEquals(originalManifest, result.manifestHash());

        // Verify result toString does not leak raw hash content as hex
        String resultStr = result.toString();
        assertFalse(resultStr.contains("EVIDENCE_BODY_CANARY"));
        // Confirm no payload body leaks
        assertFalse(resultStr.contains("unit-"));
    }

    // -- helpers --------------------------------------------------------------

    private Fixture createMemoryAndPreview(String marker, String body, boolean withAffected) {
        Fixture mem = createMemory(marker, body);
        byte[] requestHash = sha256(("preview-" + marker).getBytes(StandardCharsets.UTF_8));
        var result = preview.preview(new LocalV1S3ADeletionPreviewRequest(
                mem.memoryId(), "preview-" + marker, requestHash));
        return new Fixture(mem.memoryId(), mem.revisionId(), mem.anchorOne(), mem.actorId(),
                result.previewId(), result.previewRevision(), result.manifestHash());
    }

    private Fixture createMemory(String marker, String body) {
        return createMemory(marker, body, null);
    }

    private Fixture createMemory(String marker, String body, UUID linkToAnchor) {
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String key = "s3b2b-" + marker + "-" + UUID.randomUUID();
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

        if (linkToAnchor != null) {
            dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", confirmed.currentRevisionId());
            UUID decisionId = dsl.fetch(
                    "SELECT created_by_decision_id FROM memory.memory_revision WHERE memory_revision_id=?",
                    confirmed.currentRevisionId()).get(0).get(0, UUID.class);
            dsl.execute("INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_anchor_id,created_by_decision_id,created_at) "
                    + "VALUES (?,?,'EVIDENCED_BY',?,?,clock_timestamp())",
                    UUID.randomUUID(), confirmed.currentRevisionId(), linkToAnchor, decisionId);
        }
        return new Fixture(memoryId, confirmed.currentRevisionId(), anchorOne, actor);
    }

    private void publishSecondRevision(Fixture fixture, String body) {
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID reviewChangeId = UUID.randomUUID();
        UUID memoryChangeId = UUID.randomUUID();
        dsl.transaction(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            tx.execute(("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                    + "VALUES ('%s','REVISE','%s',clock_timestamp())").formatted(proposalId, fixture.memoryId()));
            tx.execute(("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                    + "expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                    + "('%s','%s',1,'REVISE','%s',1,clock_timestamp())")
                    .formatted(proposalRevisionId, proposalId, fixture.revisionId()));
            tx.execute(("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                    + "VALUES ('%s','OPEN','r2-%s',decode('%s','hex'),clock_timestamp())")
                    .formatted(reviewId, reviewId, HASH_HEX));
            tx.execute(("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                    + "VALUES ('%s','%s',1)").formatted(reviewId, proposalRevisionId));
            tx.execute(("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                    + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',2,'synthetic','r2-decision-%s',clock_timestamp())")
                    .formatted(decisionId, fixture.actorId(), proposalRevisionId, reviewId, fixture.memoryId(), decisionId));
            insertGoverned(tx, reviewChangeId, "review.decisions-committed.v1", "REVIEW_SESSION", reviewId, 2L, decisionId);
            tx.execute(("INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) "
                    + "VALUES ('%s','%s',2,'Claim','%s','%s',clock_timestamp())")
                    .formatted(revisionId, fixture.memoryId(), body, decisionId));
            insertGoverned(tx, memoryChangeId, "memory.canonical-committed.v1", "MEMORY", fixture.memoryId(), 2L, decisionId);
            tx.execute(("UPDATE memory.memory_record SET current_revision_id='%s',updated_at=clock_timestamp() "
                    + "WHERE memory_id='%s' AND current_revision_id='%s'")
                    .formatted(revisionId, fixture.memoryId(), fixture.revisionId()));
        });
    }

    private void publishSecondPolicyRevision(Fixture fixture) {
        UUID policyId = dsl.fetchOne("SELECT policy_id FROM memory.memory_record WHERE memory_id=?", fixture.memoryId())
                .get(0, UUID.class);
        UUID decisionId = UUID.randomUUID();
        UUID changeId = UUID.randomUUID();
        dsl.transaction(configuration -> {
            DSLContext tx = org.jooq.impl.DSL.using(configuration);
            tx.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                            + "VALUES (?,'HIDE_SELECT',?,'USER','ACCESS_POLICY',?,2,'synthetic',?,clock_timestamp())",
                    decisionId, fixture.actorId(), policyId, "policy-2-" + decisionId);
            tx.execute("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,maintenance_allowed,export_allowed,external_provider_allowed,isolated,created_by_decision_id,created_at) "
                            + "VALUES (?,2,true,true,false,false,false,?,clock_timestamp())",
                    policyId, decisionId);
            insertGoverned(tx, changeId, "memory.policy-changed.v1", "ACCESS_POLICY", policyId, 2L, decisionId);
            assertEquals(1, tx.execute("UPDATE memory.access_policy SET current_revision_no=2 WHERE policy_id=? AND current_revision_no=1",
                    policyId));
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
                + "VALUES ('%s','r2-outbox-%s','GOVERNED','%s','%s','%s',%d,'pink.event.v1','DATABASE_TEST',0,decode('%s','hex'),'%s'::jsonb,'%s','READY',clock_timestamp(),0,8,clock_timestamp())")
                .formatted(UUID.randomUUID(), UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision,
                        HASH_HEX, manifest, changeId));
    }

    private static byte[] sha256(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    private static byte[] sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private static String closureState(UUID closureId) {
        return dsl.fetchOne("SELECT state FROM memory.deletion_closure WHERE closure_id=?", closureId)
                .get(0, String.class);
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static void assertThrowsB2B(CanonicalFailureCode code, Runnable operation) {
        LocalV1S3B2BException ex = assertThrows(LocalV1S3B2BException.class, operation::run);
        assertEquals(code, ex.failureCode());
    }

    private record Fixture(
            UUID memoryId, UUID revisionId, UUID anchorOne, UUID actorId,
            UUID previewId, long previewRevision, byte[] manifestHash) {
        Fixture(UUID memoryId, UUID revisionId, UUID anchorOne, UUID actorId) {
            this(memoryId, revisionId, anchorOne, actorId, null, 0, null);
        }
    }

    // -- test-only failure injection seams ------------------------------------

    private static final class InjectedFailure extends RuntimeException {}

    private static final class FailingMemoryGovernancePort implements MemoryGovernancePort {
        private final MemoryGovernancePort delegate;
        private final boolean failAfterInsert;

        FailingMemoryGovernancePort(MemoryGovernancePort delegate, boolean failAfterInsert) {
            this.delegate = delegate;
            this.failAfterInsert = failAfterInsert;
        }

        @Override
        public void insertDecision(io.github.candyxi0.hidenest.memory.domain.Decision decision) {
            delegate.insertDecision(decision);
            if (failAfterInsert) throw new InjectedFailure();
        }

        @Override
        public io.github.candyxi0.hidenest.memory.domain.Decision findDecisionByIdempotencyKey(String key) {
            return delegate.findDecisionByIdempotencyKey(key);
        }

        // remaining methods delegate
        @Override public void insertProposal(io.github.candyxi0.hidenest.memory.domain.Proposal p) { delegate.insertProposal(p); }
        @Override public void insertProposalRevision(io.github.candyxi0.hidenest.memory.domain.ProposalRevision r) { delegate.insertProposalRevision(r); }
        @Override public void insertReviewSession(io.github.candyxi0.hidenest.memory.domain.ReviewSession s) { delegate.insertReviewSession(s); }
        @Override public io.github.candyxi0.hidenest.memory.domain.ReviewSession lockReviewSessionForWrite(UUID id) { return delegate.lockReviewSessionForWrite(id); }
        @Override public boolean transitionReviewSessionState(UUID id, String e, String n, OffsetDateTime t) { return delegate.transitionReviewSessionState(id, e, n, t); }
        @Override public void insertReviewMember(io.github.candyxi0.hidenest.memory.domain.ReviewMember m) { delegate.insertReviewMember(m); }
        @Override public List<io.github.candyxi0.hidenest.memory.domain.ReviewMember> findReviewMembersBySessionId(UUID id) { return delegate.findReviewMembersBySessionId(id); }
        @Override public List<io.github.candyxi0.hidenest.memory.domain.Decision> lockAndVerifyDecisions(java.util.Set<UUID> d, UUID r, UUID p, String t, UUID tid, Long tr) { return delegate.lockAndVerifyDecisions(d, r, p, t, tid, tr); }
        @Override public io.github.candyxi0.hidenest.memory.domain.ProposalRevision findProposalRevisionById(UUID id) { return delegate.findProposalRevisionById(id); }
        @Override public io.github.candyxi0.hidenest.memory.domain.ReviewSession findReviewSessionById(UUID id) { return delegate.findReviewSessionById(id); }
        @Override public io.github.candyxi0.hidenest.memory.domain.MemoryRecord lockMemoryRecordForWrite(UUID id) { return delegate.lockMemoryRecordForWrite(id); }
        @Override public void insertMemoryRecord(io.github.candyxi0.hidenest.memory.domain.MemoryRecord r) { delegate.insertMemoryRecord(r); }
        @Override public void insertMemoryRevision(io.github.candyxi0.hidenest.memory.domain.MemoryRevision r) { delegate.insertMemoryRevision(r); }
        @Override public void insertMemoryRelations(List<io.github.candyxi0.hidenest.memory.domain.MemoryRelation> r) { delegate.insertMemoryRelations(r); }
        @Override public List<io.github.candyxi0.hidenest.memory.domain.MemoryRelation> findMemoryRelationsByFromRevisionId(UUID id) { return delegate.findMemoryRelationsByFromRevisionId(id); }
        @Override public io.github.candyxi0.hidenest.memory.domain.AccessPolicy lockAccessPolicyForWrite(UUID id) { return delegate.lockAccessPolicyForWrite(id); }
        @Override public void insertAccessPolicy(io.github.candyxi0.hidenest.memory.domain.AccessPolicy p) { delegate.insertAccessPolicy(p); }
        @Override public void insertAccessPolicyRevision(io.github.candyxi0.hidenest.memory.domain.AccessPolicyRevision r) { delegate.insertAccessPolicyRevision(r); }
        @Override public boolean updateMemoryRecordPointerCAS(UUID m, UUID e, UUID n, UUID p, Long epr, Long npr) { return delegate.updateMemoryRecordPointerCAS(m, e, n, p, epr, npr); }
        @Override public void insertChangeEvent(io.github.candyxi0.hidenest.memory.domain.ChangeEvent e) { delegate.insertChangeEvent(e); }
        @Override public io.github.candyxi0.hidenest.memory.domain.MemoryRevision lockMemoryRevisionForWrite(UUID id) { return delegate.lockMemoryRevisionForWrite(id); }
        @Override public void insertActorRef(io.github.candyxi0.hidenest.memory.domain.ActorRef a) { delegate.insertActorRef(a); }
        @Override public io.github.candyxi0.hidenest.memory.domain.ActorRef findActorRefById(UUID id) { return delegate.findActorRefById(id); }
        @Override public io.github.candyxi0.hidenest.memory.domain.ActorRef findActorRefByKindAndStableRef(String k, String r) { return delegate.findActorRefByKindAndStableRef(k, r); }
    }

    private static final class FailingDeletionFencePort implements DeletionFencePort {
        private final DeletionFencePort delegate;
        private final boolean failAfterInsert;

        FailingDeletionFencePort(DeletionFencePort delegate, boolean failAfterInsert) {
            this.delegate = delegate;
            this.failAfterInsert = failAfterInsert;
        }

        @Override
        public void insertFences(List<FenceDraft> drafts) {
            if (failAfterInsert) {
                delegate.insertFences(drafts.subList(0, 1));
                throw new InjectedFailure();
            }
            delegate.insertFences(drafts);
        }

        @Override
        public boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef) {
            return delegate.isFenced(targetKind, targetId, targetRevisionRef);
        }

        @Override
        public List<io.github.candyxi0.hidenest.memory.domain.DeletionFence> findByClosureId(UUID closureId) {
            return delegate.findByClosureId(closureId);
        }
    }

    private static final class FailingDeletionConfirmationPort implements DeletionConfirmationPort {
        private final DeletionConfirmationPort delegate;
        private final boolean failAfterConfirm;

        FailingDeletionConfirmationPort(DeletionConfirmationPort delegate, boolean failAfterConfirm) {
            this.delegate = delegate;
            this.failAfterConfirm = failAfterConfirm;
        }

        @Override
        public ConfirmationSnapshot lockConfirmationSnapshot(UUID closureId) {
            return delegate.lockConfirmationSnapshot(closureId);
        }

        @Override
        public boolean confirmClosure(UUID closureId, long expectedPreviewRevision, byte[] expectedManifestHash,
                UUID expectedRootCurrentRevisionId, long expectedRootRevisionNo, UUID expectedRootPolicyId,
                long expectedRootPolicyRevisionNo, UUID decisionId, OffsetDateTime confirmedAt) {
            boolean result = delegate.confirmClosure(closureId, expectedPreviewRevision, expectedManifestHash,
                    expectedRootCurrentRevisionId, expectedRootRevisionNo, expectedRootPolicyId,
                    expectedRootPolicyRevisionNo, decisionId, confirmedAt);
            if (failAfterConfirm && result) throw new InjectedFailure();
            return result;
        }
    }
}
