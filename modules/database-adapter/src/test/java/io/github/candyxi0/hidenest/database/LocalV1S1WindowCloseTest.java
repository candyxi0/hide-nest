package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.coordinator.*;
import io.github.candyxi0.hidenest.application.model.*;
import io.github.candyxi0.hidenest.database.adapter.*;
import io.github.candyxi0.hidenest.evidence.domain.PayloadStoreException;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1S1WindowCloseTest {

    private static final String IMG =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String U = "hide_nest_migrator";
    private static final String HH = "00".repeat(32);
    private static final Clock CLK =
            Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> pg;
    private static DSLContext dsl;
    private static LocalV1S1WindowCloseCoordinator coord;
    private static CanonicalPublishCoordinator pub;
    private static EvidenceReferencePort ep;
    private static MemoryGovernancePort mp;
    private static RuntimeTransactionPort rp;
    private static PayloadStore payloadStore;
    private static Path payloadRoot;
    private static String PW;

    /** Canary lives in test fixture, never enters prepare request. */
    private static final String CANARY = "CANARY-ZZZ-999";

    @BeforeAll
    static void setUp() throws Exception {
        PW = UUID.randomUUID().toString() + UUID.randomUUID();
        pg = new PostgreSQLContainer<>(
                DockerImageName.parse(IMG).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest").withUsername(U).withPassword(PW)
                .withStartupTimeout(Duration.ofSeconds(120));
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN");
            s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway fw = Flyway.configure().dataSource(pg.getJdbcUrl(), U, PW)
                .defaultSchema("public").locations("classpath:db/migration")
                .cleanDisabled(true).baselineOnMigrate(false)
                .outOfOrder(false).validateMigrationNaming(true).load();
        assertEquals(18, fw.migrate().migrationsExecuted);
        var rds = new DriverManagerDataSource(pg.getJdbcUrl(), U, PW);
        DataSource pds = new TransactionAwareDataSourceProxy(rds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(rds));
        DefaultConfiguration cfg = new DefaultConfiguration();
        cfg.setSQLDialect(SQLDialect.POSTGRES);
        cfg.setDataSource(pds);
        dsl = new DefaultDSLContext(cfg);
        mp = new JooqMemoryGovernanceAdapter(dsl);
        rp = new JooqRuntimeTransactionAdapter(dsl);
        ep = new JooqEvidenceReferenceAdapter(dsl);
        TransactionExecutor te = new SpringTransactionExecutor(tx);
        pub = new CanonicalPublishCoordinator(mp, rp, te, CLK);
        payloadRoot = Files.createTempDirectory("s2a-payload-test-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        coord = new LocalV1S1WindowCloseCoordinator(ep, mp, rp, te, pub, payloadStore, CLK);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) pg.stop();
        if (payloadRoot != null) {
            try (var files = Files.walk(payloadRoot)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static byte[] h(String in) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(in.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] h0() {
        return new byte[32];
    }

    // ── test data builders ──────────────────────────────────────────────

    /** Build a prepare request with only 2 evidence messages. Canary is NOT in request. */
    private LocalV1S1PrepareRequest buildPrepareRequest(
            String idempotencyKey, byte[] requestHash,
            UUID perspectiveActorId, UUID evidenceMsg1, UUID evidenceMsg2,
            UUID actorA, UUID actorB) {
        String bodyText = "对协作者的判断从误解逐渐变成理解";
        byte[] bodyHash = h(bodyText);

        // R1-01: only evidence messages (with body text for S2A payload store)
        List<LocalV1S1PrepareRequest.EvidenceMessage> messages = List.of(
                new LocalV1S1PrepareRequest.EvidenceMessage(
                        evidenceMsg1, actorA, 1L, "msg-evidence-1",
                        OffsetDateTime.now(CLK),
                        "协作者：我们需要重新评估技术方案的可行性，目前的数据支持不够充分"),
                new LocalV1S1PrepareRequest.EvidenceMessage(
                        evidenceMsg2, actorB, 2L, "msg-evidence-2",
                        OffsetDateTime.now(CLK),
                        "小林：我理解了，让我重新整理一下证据材料，确保每个结论都有充分支撑"));

        UUID anchor1 = UUID.randomUUID();
        UUID anchor2 = UUID.randomUUID();
        List<LocalV1S1PrepareRequest.AnchorInput> anchors = List.of(
                new LocalV1S1PrepareRequest.AnchorInput(anchor1, List.of(
                        new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                evidenceMsg1, 0L, 12L, 1L))),
                new LocalV1S1PrepareRequest.AnchorInput(anchor2, List.of(
                        new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                evidenceMsg2, 0L, 14L, 2L))));

        return new LocalV1S1PrepareRequest(
                idempotencyKey, requestHash, perspectiveActorId,
                "Interpretation", bodyText, bodyHash, messages, anchors);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 1: prepare → Review exists, Memory count = 0
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(1)
    void prepareCreatesReviewButNoMemory() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var req = buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB);
        var result = coord.prepare(req);

        assertNotNull(result);
        assertEquals("PREPARED", result.state());
        assertNotNull(result.sourceId());
        assertNotNull(result.reviewSessionId());
        assertNotNull(result.proposalRevisionId());
        assertEquals("Interpretation", result.memoryType());
        assertEquals(actorA, result.perspectiveActorId());

        // Review session exists and is OPEN
        var rs = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.REVIEW_SESSION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ReviewSession
                        .REVIEW_SESSION.REVIEW_SESSION_ID.eq(result.reviewSessionId()))
                .fetchOne();
        assertNotNull(rs);
        assertEquals("OPEN", rs.getState());

        // Review member exists (exactly 1)
        long memberCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.REVIEW_MEMBER,
                io.github.candyxi0.hidenest.database.generated.memory.tables.ReviewMember.REVIEW_MEMBER
                        .REVIEW_SESSION_ID.eq(result.reviewSessionId()));
        assertEquals(1L, memberCount);

        // ProposalRevision exists
        var pr = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.PROPOSAL_REVISION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ProposalRevision
                        .PROPOSAL_REVISION.PROPOSAL_REVISION_ID.eq(result.proposalRevisionId()))
                .fetchOne();
        assertNotNull(pr);
        assertEquals("PUBLISH", pr.getActionCode());
        assertEquals("Interpretation", pr.getMemoryType());

        // Source exists with deterministic externalRef
        var src = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE)
                .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.Source.SOURCE
                        .SOURCE_ID.eq(result.sourceId()))
                .fetchOne();
        assertNotNull(src);
        assertEquals("SYNTHETIC_CONVERSATION", src.getSourceKind());
        assertTrue(src.getExternalRef().startsWith("review:"),
                "Source.externalRef must use deterministic review: prefix");

        // R1-01: only 2 evidence SourceUnits (no irrelevant messages)
        long unitCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_UNIT,
                io.github.candyxi0.hidenest.database.generated.evidence.tables.SourceUnit.SOURCE_UNIT
                        .SOURCE_ID.eq(result.sourceId()));
        assertEquals(2L, unitCount, "only 2 evidence source units, no irrelevant ones");

        // ZERO memory records or revisions (global — this test runs first)
        long memCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD);
        assertEquals(0L, memCount);
        long revCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
        assertEquals(0L, revCount);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 2: canary 0 hits in ALL business persistence including source_unit
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(2)
    void canaryZeroHitsAllTables() {
        // The fixed four-message story includes two unselected chat messages.
        // They carry the canary but deliberately remain outside the prepare request.
        List<String> unselectedChat = List.of(
                "今天天气不错-" + CANARY,
                "亲亲抱抱-" + CANARY);
        assertEquals(2, unselectedChat.size());
        assertTrue(unselectedChat.stream().allMatch(text -> text.contains(CANARY)));
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();

        var confirmReq = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        coord.confirm(confirmReq);

        // R1-01: scan ALL business tables for canary
        // evidence tables
        scanTable("evidence.source", CANARY);
        scanTable("evidence.source_unit", CANARY);
        scanTable("evidence.source_anchor", CANARY);
        scanTable("evidence.source_anchor_unit", CANARY);
        scanTable("evidence.source_payload", CANARY);

        // memory tables
        scanTable("memory.memory_record", CANARY);
        scanTable("memory.memory_revision", CANARY);
        scanTable("memory.memory_relation", CANARY);
        scanTable("memory.proposal", CANARY);
        scanTable("memory.proposal_revision", CANARY);
        scanTable("memory.review_session", CANARY);
        scanTable("memory.review_member", CANARY);
        scanTable("memory.decision", CANARY);
        scanTable("memory.change_event", CANARY);
        scanTable("memory.access_policy", CANARY);
        scanTable("memory.access_policy_revision", CANARY);

        // runtime tables
        scanOutboxPayloads(CANARY);
        scanReceiptManifests(CANARY);
    }

    private void scanTable(String tableName, String canary) {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT * FROM " + tableName);
            var meta = rs.getMetaData();
            while (rs.next()) {
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    String val = rs.getString(i);
                    if (val != null) {
                        assertFalse(val.contains(canary),
                                "canary found in " + tableName + " column "
                                        + meta.getColumnName(i));
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void scanOutboxPayloads(String canary) {
        var oes = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT)
                .fetch();
        for (var oe : oes) {
            if (oe.getPayloadManifest() != null) {
                assertFalse(oe.getPayloadManifest().data().contains(canary),
                        "canary in outbox payload_manifest");
            }
        }
    }

    private void scanReceiptManifests(String canary) {
        var receipts = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.runtime.Tables.IDEMPOTENCY_RECEIPT)
                .fetch();
        for (var rc : receipts) {
            if (rc.getResponseManifest() != null) {
                assertFalse(rc.getResponseManifest().data().contains(canary),
                        "canary in receipt response_manifest");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 3: confirm → exactly 1 Memory, revisionNo=1, pointer correct
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(3)
    void confirmCreatesExactlyOneMemory() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();

        var confirmReq = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        var confirmResult = coord.confirm(confirmReq);

        assertNotNull(confirmResult);
        assertEquals(memoryId, confirmResult.memoryId());
        assertEquals(1L, confirmResult.revisionNo());
        assertNotNull(confirmResult.currentRevisionId());
        assertEquals("SUCCEEDED", confirmResult.resultCategory());
        assertEquals(2, confirmResult.evidenceCount());

        // Memory record for our memoryId
        var mr = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD
                        .MEMORY_ID.eq(memoryId))
                .fetchOne();
        assertNotNull(mr);
        assertEquals("ACTIVE", mr.getState());
        assertEquals(confirmResult.currentRevisionId(), mr.getCurrentRevisionId());

        // Revision with revisionNo=1
        var rev = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION
                        .MEMORY_ID.eq(memoryId))
                .fetchOne();
        assertNotNull(rev);
        assertEquals(1L, rev.getRevisionNo());

        // Review is COMPLETED
        var rs = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.REVIEW_SESSION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ReviewSession.REVIEW_SESSION
                        .REVIEW_SESSION_ID.eq(prepResult.reviewSessionId()))
                .fetchOne();
        assertEquals("COMPLETED", rs.getState());

        // 2 EVIDENCED_BY relations
        long relCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RELATION,
                io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRelation.MEMORY_RELATION
                        .FROM_REVISION_ID.eq(confirmResult.currentRevisionId()));
        assertEquals(2L, relCount);
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 4: reject → no Memory, ChangeEvent (memory), Outbox created
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(4)
    void rejectCreatesNoMemory() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        long memBefore = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD);
        long reviseBefore = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
        long ceBefore = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT);

        String rejectKey = UUID.randomUUID().toString();
        coord.reject(rejectKey, h(rejectKey), prepResult.reviewSessionId(), "not needed");

        // Review is CANCELLED
        var rs = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.REVIEW_SESSION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ReviewSession.REVIEW_SESSION
                        .REVIEW_SESSION_ID.eq(prepResult.reviewSessionId()))
                .fetchOne();
        assertEquals("CANCELLED", rs.getState());

        // No new memory/revision
        assertEquals(memBefore, (long) dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD));
        assertEquals(reviseBefore, (long) dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION));
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 5: prepare replay returns exact same fields (R1-05)
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(5)
    void prepareReplayExactMatch() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        byte[] hash = h(key);
        var req = buildPrepareRequest(key, hash, actorA, msg1, msg2, actorA, actorB);
        var r1 = coord.prepare(req);

        // Same key + same hash → exact same result
        var r2 = coord.prepare(req);
        assertEquals(r1.sourceId(), r2.sourceId(), "sourceId must match on replay");
        assertEquals(r1.reviewSessionId(), r2.reviewSessionId(),
                "reviewSessionId must match on replay");
        assertEquals(r1.proposalRevisionId(), r2.proposalRevisionId(),
                "proposalRevisionId must match on replay");
        assertEquals(r1.hideSelectDecisionIds(), r2.hideSelectDecisionIds(),
                "hideSelectDecisionIds must match on replay");
        assertEquals(r1.anchorIds(), r2.anchorIds(), "anchorIds must match on replay");
        assertEquals(r1.bodyText(), r2.bodyText(), "bodyText must match on replay");
        assertEquals(r1.memoryType(), r2.memoryType(), "memoryType must match on replay");
        assertEquals(r1.perspectiveActorId(), r2.perspectiveActorId(),
                "perspectiveActorId must match on replay");
        assertEquals(r1.state(), r2.state());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 6: confirm replay returns exact same fields (R1-05)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void confirmReplayExactMatch() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        byte[] hash = h(key);

        var req = new LocalV1S1ConfirmRequest(
                key, hash,
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        var r1 = coord.confirm(req);

        // Same key + same hash → exact same result
        var r2 = coord.confirm(req);
        assertEquals(r1.memoryId(), r2.memoryId());
        assertEquals(r1.currentRevisionId(), r2.currentRevisionId());
        assertEquals(r1.revisionNo(), r2.revisionNo());
        assertEquals(r1.evidenceCount(), r2.evidenceCount());
    }

    @Test
    void confirmReplayFailsClosedWhenEvidenceRelationIsMissing() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prep = coord.prepare(buildPrepareRequest(
                prepKey, h(prepKey), actorA, UUID.randomUUID(), UUID.randomUUID(),
                actorA, actorB));
        String confirmKey = UUID.randomUUID().toString();
        var request = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey), prep.proposalRevisionId(),
                prep.reviewSessionId(), UUID.randomUUID(), UUID.randomUUID(), h0());
        var confirmed = coord.confirm(request);

        var relationTable =
                io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRelation.MEMORY_RELATION;
        UUID relationId = dsl.select(relationTable.RELATION_ID)
                .from(relationTable)
                .where(relationTable.FROM_REVISION_ID.eq(confirmed.currentRevisionId()))
                .limit(1)
                .fetchOne(relationTable.RELATION_ID);
        assertNotNull(relationId);
        assertEquals(1, dsl.deleteFrom(relationTable)
                .where(relationTable.RELATION_ID.eq(relationId))
                .execute());

        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.confirm(request));
        assertEquals(CanonicalFailureCode.CANONICAL_COMMIT_FAILED, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 7: idempotency key reused with different hash → rejected
    // ════════════════════════════════════════════════════════════════════
    @Test
    void idempotencyKeyReusedRejected() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var req = buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB);
        coord.prepare(req);

        // Same key, different hash → rejected
        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.prepare(
                buildPrepareRequest(key, h("different"), actorA, msg1, msg2, actorA, actorB)));
        assertEquals(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 8: STALE (already COMPLETED) review → rejected
    // ════════════════════════════════════════════════════════════════════
    @Test
    void staleCompletedReviewRejected() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var req = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        coord.confirm(req); // first confirm succeeds

        // Second confirm on COMPLETED review → rejected
        UUID memoryId2 = UUID.randomUUID();
        UUID policyId2 = UUID.randomUUID();
        String key2 = UUID.randomUUID().toString();
        var req2 = new LocalV1S1ConfirmRequest(
                key2, h(key2),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId2, policyId2, h0());
        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.confirm(req2));
        assertEquals(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 9: non-existent review session → rejected
    // ════════════════════════════════════════════════════════════════════
    @Test
    void nonexistentReviewSessionRejected() {
        var req = new LocalV1S1ConfirmRequest(
                UUID.randomUUID().toString(), h0(),
                UUID.randomUUID(), // wrong proposal revision
                UUID.randomUUID(), // wrong review session
                UUID.randomUUID(), UUID.randomUUID(), h0());
        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.confirm(req));
        assertEquals(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 10: double reject → rejected (R1-04)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void doubleRejectRejected() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        String rejectKey = UUID.randomUUID().toString();
        coord.reject(rejectKey, h(rejectKey), prepResult.reviewSessionId(), "no");

        // Second reject on CANCELLED → rejected
        String key2 = UUID.randomUUID().toString();
        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.reject(
                key2, h(key2), prepResult.reviewSessionId(), "no again"));
        assertEquals(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 11: wrong proposal revision for review → rejected (R1-04)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void wrongProposalRevisionRejected() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        // Use a different proposal revision than the review member's
        UUID wrongPrId = UUID.randomUUID();
        var req = new LocalV1S1ConfirmRequest(
                UUID.randomUUID().toString(), h0(),
                wrongPrId, // wrong proposal revision — not the review member
                prepResult.reviewSessionId(),
                UUID.randomUUID(), UUID.randomUUID(), h0());
        var ex = assertThrows(LocalV1S1Exception.class, () -> coord.confirm(req));
        assertEquals(CanonicalFailureCode.REVIEW_MEMBER_MISMATCH, ex.failureCode());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 12: USER_CONFIRM actor equals existing perspective actor (R1-04)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void userConfirmActorMatchesPerspectiveActor() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var req = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        coord.confirm(req);

        // Find the USER_CONFIRM decision for this review
        var decisions = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.DECISION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.Decision.DECISION
                        .REVIEW_SESSION_ID.eq(prepResult.reviewSessionId()))
                .and(io.github.candyxi0.hidenest.database.generated.memory.tables.Decision.DECISION
                        .DECISION_KIND.eq("USER_CONFIRM"))
                .fetch();
        assertEquals(1, decisions.size());
        // R1-04: USER_CONFIRM actor = perspectiveActorId (not a random new UUID)
        assertEquals(actorA, decisions.get(0).getActorId(),
                "USER_CONFIRM actor must be the perspective actor, not a random one");
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 13: confirm cannot tamper with body/type/perspective/anchor (R1-02)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void confirmDerivesContentFromProposalRevision() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        String expectedBody = "对协作者的判断从误解逐渐变成理解";
        var req = buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB);
        assertEquals(expectedBody, req.bodyText());
        assertEquals("Interpretation", req.memoryType());
        assertEquals(actorA, req.perspectiveActorId());

        var prepResult = coord.prepare(req);

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        // R1-02: ConfirmRequest has no bodyText/memoryType/perspectiveActorId/evidenceAnchorIds
        var confirmReq = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        coord.confirm(confirmReq);

        // Verify the memory revision uses the ProposalRevision's content, not request input
        var rev = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION
                        .MEMORY_ID.eq(memoryId))
                .fetchOne();
        assertNotNull(rev);
        assertEquals(expectedBody, rev.getBodyText(),
                "body must come from ProposalRevision, not request");
        assertEquals("Interpretation", rev.getMemoryType());
        assertEquals(actorA, rev.getPerspectiveActorId());

        // Evidence count = 2 (derived from Source anchors, not request)
        var rels = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RELATION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRelation.MEMORY_RELATION
                        .FROM_REVISION_ID.eq(rev.getMemoryRevisionId()))
                .fetch();
        assertEquals(2, rels.size(), "evidence derived from Source anchors, not request input");
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 14: EVIDENCED_BY validation rejects bad inputs (R1-06)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void evidencedByRejectsInvalidTargets() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        // Need to test CanonicalPublishCoordinator directly with bad relations

        // Create a USER_CONFIRM decision first (simulating confirm flow artifacts)
        // We'll use raw SQL for seeding, then test CanonicalPublishCoordinator directly
        // (reusing SliceCCoordinatorTest seed pattern)

        // Test 14a: SUPPORTS type → rejected
        var relBadType = new CanonicalPublishRequest.RelationSpec(
                "SUPPORTS", UUID.randomUUID(), null, actorA);
        assertEVIDENCED_BYRejected(relBadType);

        // Test 14b: null toAnchorId → rejected
        var relNullAnchor = new CanonicalPublishRequest.RelationSpec(
                "EVIDENCED_BY", null, null, actorA);
        assertEVIDENCED_BYRejected(relNullAnchor);

        // Test 14c: non-null toRevisionId → rejected
        var relWithRev = new CanonicalPublishRequest.RelationSpec(
                "EVIDENCED_BY", UUID.randomUUID(), UUID.randomUUID(), actorA);
        assertEVIDENCED_BYRejected(relWithRev);

        // Test 14d: wrong perspective actor → rejected
        var relWrongActor = new CanonicalPublishRequest.RelationSpec(
                "EVIDENCED_BY", null, UUID.randomUUID(), UUID.randomUUID());
        assertEVIDENCED_BYRejected(relWrongActor);
    }

    private void assertEVIDENCED_BYRejected(
            CanonicalPublishRequest.RelationSpec badRel) {
        // Use the SliceC seed pattern to create a valid review + decision,
        // then call publishFirst with the bad relation
        UUID actorA = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW)) {
            c.setAutoCommit(false);
            q(c, "SET CONSTRAINTS ALL DEFERRED");
            UUID pi = UUID.randomUUID(), pr = UUID.randomUUID();
            UUID r = UUID.randomUUID(), d = UUID.randomUUID();
            UUID pd = UUID.randomUUID(), ce = UUID.randomUUID();
            q(c, "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('%s','COMPLETED','rv-%s',decode('%s','hex'),clock_timestamp(),clock_timestamp())"
                    .formatted(r, r, HH));
            q(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('%s','SYNTHETIC','a-%s',clock_timestamp())"
                    .formatted(actorA, actorA));
            q(c, "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('%s','CREATE',NULL,clock_timestamp())"
                    .formatted(pi));
            q(c, "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES ('%s','%s',1,'PUBLISH','t','Claim',NULL,NULL,clock_timestamp())"
                    .formatted(pr, pi));
            q(c, "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('%s','%s',1)"
                    .formatted(r, pr));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','USER_CONFIRM','%s','HUMAN','%s','%s','MEMORY','%s',1,'x','d-%s',clock_timestamp())"
                    .formatted(d, actorA, pr, r, memoryId, d));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'x','pd-%s',clock_timestamp())"
                    .formatted(pd, actorA, policyId, pd));
            q(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('%s','review.decisions-committed.v1','%s','REVIEW_SESSION','%s',1,'%s',clock_timestamp())"
                    .formatted(ce, actorA, r, d));
            q(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"
                    + UUID.randomUUID() + "','ob-" + d
                    + "','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','" + r
                    + "',1,'pink.event.v1','REVIEW_SYNC',1,decode('" + HH
                    + "','hex'),'{\"aggregateId\":\"" + r
                    + "\",\"aggregateRevision\":1,\"policyRevision\":1,\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""
                    + HH + "\"}','" + ce
                    + "','READY',clock_timestamp(),0,8,clock_timestamp())");
            c.commit();

            var ex = assertThrows(CanonicalPublishException.class,
                    () -> pub.publishFirst(new CanonicalPublishRequest(
                            UUID.randomUUID().toString(), h0(),
                            Set.of(d), pr, r,
                            memoryId, "Claim", actorA, "X", policyId,
                            List.of(badRel), h0())));
            assertEquals(CanonicalFailureCode.CANONICAL_COMMIT_FAILED,
                    ex.failureCode());
        } catch (CanonicalPublishException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 15: duplicate anchor in EVIDENCED_BY → rejected (R1-06)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void evidencedByDuplicateAnchorRejected() {
        UUID actorA = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW)) {
            c.setAutoCommit(false);
            q(c, "SET CONSTRAINTS ALL DEFERRED");
            UUID pi = UUID.randomUUID(), pr = UUID.randomUUID();
            UUID r = UUID.randomUUID(), d = UUID.randomUUID();
            UUID pd = UUID.randomUUID(), ce = UUID.randomUUID();
            q(c, "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('%s','COMPLETED','rv-%s',decode('%s','hex'),clock_timestamp(),clock_timestamp())"
                    .formatted(r, r, HH));
            q(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('%s','SYNTHETIC','a-%s',clock_timestamp())"
                    .formatted(actorA, actorA));
            q(c, "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('%s','CREATE',NULL,clock_timestamp())"
                    .formatted(pi));
            q(c, "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES ('%s','%s',1,'PUBLISH','t','Claim',NULL,NULL,clock_timestamp())"
                    .formatted(pr, pi));
            q(c, "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('%s','%s',1)"
                    .formatted(r, pr));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','USER_CONFIRM','%s','HUMAN','%s','%s','MEMORY','%s',1,'x','d-%s',clock_timestamp())"
                    .formatted(d, actorA, pr, r, memoryId, d));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'x','pd-%s',clock_timestamp())"
                    .formatted(pd, actorA, policyId, pd));
            q(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('%s','review.decisions-committed.v1','%s','REVIEW_SESSION','%s',1,'%s',clock_timestamp())"
                    .formatted(ce, actorA, r, d));
            q(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"
                    + UUID.randomUUID() + "','ob-" + d
                    + "','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','" + r
                    + "',1,'pink.event.v1','REVIEW_SYNC',1,decode('" + HH
                    + "','hex'),'{\"aggregateId\":\"" + r
                    + "\",\"aggregateRevision\":1,\"policyRevision\":1,\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""
                    + HH + "\"}','" + ce
                    + "','READY',clock_timestamp(),0,8,clock_timestamp())");
            c.commit();

            // Two relations with same anchor → duplicate
            var rel1 = new CanonicalPublishRequest.RelationSpec(
                    "EVIDENCED_BY", null, anchorId, actorA);
            var rel2 = new CanonicalPublishRequest.RelationSpec(
                    "EVIDENCED_BY", null, anchorId, actorA);
            var ex = assertThrows(CanonicalPublishException.class,
                    () -> pub.publishFirst(new CanonicalPublishRequest(
                            UUID.randomUUID().toString(), h0(),
                            Set.of(d), pr, r,
                            memoryId, "Claim", actorA, "X", policyId,
                            List.of(rel1, rel2), h0())));
            assertEquals(CanonicalFailureCode.CANONICAL_COMMIT_FAILED,
                    ex.failureCode());
        } catch (CanonicalPublishException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 16: end-to-end rollback (R1-07.9)
    // ════════════════════════════════════════════════════════════════════
    @Test
    void endToEndRollbackPreservesNothing() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        // Install fault-injection trigger
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("""
                    CREATE OR REPLACE FUNCTION pg_temp.force_outbox_failure()
                    RETURNS trigger AS $$
                    BEGIN
                        IF NEW.event_type = 'memory.canonical-committed.v1' THEN
                            RAISE EXCEPTION 'INJECTED_FAILURE: force rollback';
                        END IF;
                        RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            s.execute("""
                    CREATE TRIGGER injected_outbox_failure
                    BEFORE INSERT ON runtime.outbox_event
                    FOR EACH ROW EXECUTE FUNCTION pg_temp.force_outbox_failure()
                    """);

            long memBefore = dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD);
            long revBefore = dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
            long relBefore = dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RELATION);

            UUID memoryId = UUID.randomUUID();
            UUID policyId = UUID.randomUUID();
            String confirmKey = UUID.randomUUID().toString();
            var req = new LocalV1S1ConfirmRequest(
                    confirmKey, h(confirmKey),
                    prepResult.proposalRevisionId(),
                    prepResult.reviewSessionId(),
                    memoryId, policyId, h0());

            assertThrows(RuntimeException.class, () -> coord.confirm(req));

            // Everything rolled back
            assertEquals(memBefore, (long) dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD));
            assertEquals(revBefore, (long) dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION));
            assertEquals(relBefore, (long) dsl.fetchCount(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RELATION));

            // Review still OPEN
            var rs = dsl.selectFrom(
                    io.github.candyxi0.hidenest.database.generated.memory.Tables.REVIEW_SESSION)
                    .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ReviewSession.REVIEW_SESSION
                            .REVIEW_SESSION_ID.eq(prepResult.reviewSessionId()))
                    .fetchOne();
            assertEquals("OPEN", rs.getState());

            // Cleanup
            s.execute(
                    "DROP TRIGGER IF EXISTS injected_outbox_failure ON runtime.outbox_event");
            s.execute("DROP FUNCTION IF EXISTS pg_temp.force_outbox_failure()");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 17: V007 CREATE + null target succeeds
    // ════════════════════════════════════════════════════════════════════
    @Test
    void v007CreateWithNullTargetSucceeds() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        // Verify proposal has CREATE kind with null target_memory_id
        var pr = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.PROPOSAL_REVISION)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.ProposalRevision.PROPOSAL_REVISION
                        .PROPOSAL_REVISION_ID.eq(prepResult.proposalRevisionId()))
                .fetchOne();
        assertNotNull(pr);
        var proposal = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.PROPOSAL)
                .where(io.github.candyxi0.hidenest.database.generated.memory.tables.Proposal.PROPOSAL
                        .PROPOSAL_ID.eq(pr.getProposalId()))
                .fetchOne();
        assertNotNull(proposal);
        assertEquals("CREATE", proposal.getProposalKind());
        assertNull(proposal.getTargetMemoryId());

        // Confirm should succeed (V007 validates at commit)
        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var req = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        var result = coord.confirm(req);
        assertEquals(1L, result.revisionNo());
    }

    // ════════════════════════════════════════════════════════════════════
    // Test 18: concurrency serializes
    // ════════════════════════════════════════════════════════════════════
    @Test
    void concurrentConfirmSerializes() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        byte[] hash = h(key);
        var req = new LocalV1S1ConfirmRequest(
                key, hash,
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());

        var barrier = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        var ok = new AtomicInteger(0);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Runnable task = () -> {
            try {
                barrier.countDown();
                go.await();
                coord.confirm(req);
                ok.incrementAndGet();
            } catch (Exception ignored) {
            }
        };
        exec.submit(task);
        exec.submit(task);
        barrier.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(2, ok.get());
        assertEquals(1L, (long) dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD,
                io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD
                        .MEMORY_ID.eq(memoryId)));
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: prepare → SourceUnit=2, SourcePayload=2, files=2
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(20)
    void s2aPrepareCreatesTwoSourcePayloadsAndTwoFiles() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        var req = buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB);
        var result = coord.prepare(req);

        // SourceUnit count = 2
        long unitCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_UNIT,
                io.github.candyxi0.hidenest.database.generated.evidence.tables.SourceUnit.SOURCE_UNIT
                        .SOURCE_ID.eq(result.sourceId()));
        assertEquals(2L, unitCount);

        // SourcePayload count = 2
        var payloadRecords = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD)
                .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.in(msg1, msg2))
                .fetch();
        assertEquals(2, payloadRecords.size());

        // Verify SourcePayload fields
        Set<String> objectRefs = new HashSet<>();
        for (var sp : payloadRecords) {
            assertEquals("TEXT", sp.getPayloadKind());
            assertEquals("LOCAL_FILE", sp.getStoreAdapter());
            assertEquals("text/plain; charset=UTF-8", sp.getContentType());
            assertEquals("MINIMUM_EVIDENCE", sp.getRetentionClass());
            assertNotNull(sp.getObjectRef());
            assertNull(sp.getObjectVersionRef());
            assertNotNull(sp.getContentHash());
            assertEquals(32, sp.getContentHash().length);
            assertTrue(sp.getSizeBytes() > 0);
            objectRefs.add(sp.getObjectRef());
        }

        // Verify each objectRef resolves to an actual file
        for (String ref : objectRefs) {
            Path filePath = payloadRoot.resolve(ref);
            assertTrue(Files.exists(filePath), "payload file must exist: " + ref);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: read full evidence text, speaker, order via anchor→unit→payload
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(21)
    void s2aReadPayloadViaAnchorUnitPayload() {
        UUID actorA = UUID.randomUUID(); // 小林 (perspective)
        UUID actorB = UUID.randomUUID(); // 协作者
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        String msg1Body = "协作者：我们需要重新评估技术方案的可行性，目前的数据支持不够充分";
        String msg2Body = "小林：我理解了，让我重新整理一下证据材料，确保每个结论都有充分支撑";

        var req = new LocalV1S1PrepareRequest(
                key, h(key), actorA, "Interpretation",
                "对协作者的判断从误解逐渐变成理解", h("对协作者的判断从误解逐渐变成理解"),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                msg1, actorB, 1L, "msg-ev-1",
                                OffsetDateTime.now(CLK), msg1Body),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                msg2, actorA, 2L, "msg-ev-2",
                                OffsetDateTime.now(CLK), msg2Body)),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(
                                UUID.randomUUID(), List.of(
                                        new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                                msg1, 0L, (long) msg1Body.length(), 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(
                                UUID.randomUUID(), List.of(
                                        new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                                msg2, 0L, (long) msg2Body.length(), 2L)))));

        var result = coord.prepare(req);

        // Read back through the chain
        var sourceUnits = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_UNIT)
                .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourceUnit.SOURCE_UNIT
                        .SOURCE_ID.eq(result.sourceId()))
                .orderBy(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourceUnit.SOURCE_UNIT
                        .ORDINAL.asc())
                .fetch();
        assertEquals(2, sourceUnits.size());

        // Verify speaker/order
        assertEquals(actorB, sourceUnits.get(0).getActorId()); // msg1 actor = 协作者
        assertEquals(1L, sourceUnits.get(0).getOrdinal());
        assertEquals(actorA, sourceUnits.get(1).getActorId()); // msg2 actor = 小林
        assertEquals(2L, sourceUnits.get(1).getOrdinal());

        // Read payloads through source_payload → PayloadStore
        for (var unit : sourceUnits) {
            var spRecord = dsl.selectFrom(
                    io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD)
                    .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                            .SOURCE_UNIT_ID.eq(unit.getSourceUnitId()))
                    .fetchOne();
            assertNotNull(spRecord, "SourcePayload must exist for unit " + unit.getSourceUnitId());

            byte[] bodyBytes = payloadStore.get(
                    spRecord.getObjectRef(), spRecord.getContentHash(), 1024 * 1024);
            String bodyText = new String(bodyBytes, StandardCharsets.UTF_8);

            // Verify the correct body text
            if (unit.getSourceUnitId().equals(msg1)) {
                assertEquals(msg1Body, bodyText);
            } else if (unit.getSourceUnitId().equals(msg2)) {
                assertEquals(msg2Body, bodyText);
            } else {
                fail("unexpected sourceUnitId");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: canary 0 in DB source_payload and payload files
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(22)
    void s2aCanaryZeroInPayloadStore() throws Exception {
        // Unselected chat messages (never enter request)
        List<String> unselectedChat = List.of(
                "今天天气不错-" + CANARY,
                "亲亲抱抱-" + CANARY);
        assertEquals(2, unselectedChat.size());
        assertTrue(unselectedChat.stream().allMatch(text -> text.contains(CANARY)));

        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        coord.prepare(buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB));

        // Scan evidence.source_payload table for canary
        scanTable("evidence.source_payload", CANARY);

        // Scan all payload files for canary
        try (var files = Files.walk(payloadRoot)) {
            var regularFiles = files.filter(Files::isRegularFile).toList();
            for (Path f : regularFiles) {
                String content = Files.readString(f, StandardCharsets.UTF_8);
                assertFalse(content.contains(CANARY),
                        "canary must not be in payload file: " + f.getFileName());
            }
        }

        // Scan evidence.body_text canary (table name varies) — use source_payload only
        scanTable("evidence.source_payload", CANARY);
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: prepare replay preserves objectRef and hash
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(23)
    void s2aPrepareReplayPreservesObjectRefAndHash() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();
        byte[] hash = h(key);
        var req = buildPrepareRequest(key, hash, actorA, msg1, msg2, actorA, actorB);
        coord.prepare(req);

        // Capture SourcePayload rows after first prepare
        var spRows1 = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD)
                .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.in(msg1, msg2))
                .orderBy(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.asc())
                .fetch();
        assertEquals(2, spRows1.size());
        Map<UUID, String> objectRefs1 = new HashMap<>();
        Map<UUID, byte[]> hashes1 = new HashMap<>();
        for (var sp : spRows1) {
            objectRefs1.put(sp.getSourceUnitId(), sp.getObjectRef());
            hashes1.put(sp.getSourceUnitId(), sp.getContentHash());
        }

        // Replay same request
        coord.prepare(req);

        // Verify SourcePayload rows unchanged (same count, same objectRef, same hash)
        var spRows2 = dsl.selectFrom(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD)
                .where(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.in(msg1, msg2))
                .orderBy(io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.asc())
                .fetch();
        assertEquals(2, spRows2.size(), "replay must not create additional SourcePayload rows");
        for (var sp : spRows2) {
            assertEquals(objectRefs1.get(sp.getSourceUnitId()), sp.getObjectRef(),
                    "objectRef must be unchanged on replay");
            assertArrayEquals(hashes1.get(sp.getSourceUnitId()), sp.getContentHash(),
                    "contentHash must be unchanged on replay");
        }

        // Files for these objectRefs still exist
        for (var entry : objectRefs1.entrySet()) {
            Path filePath = payloadRoot.resolve(entry.getValue());
            assertTrue(Files.exists(filePath),
                    "payload file must still exist after replay: " + entry.getValue());
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: DB insert failure triggers file compensation
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(24)
    void s2aDbFailureCompensatesPayloadFiles() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        // Count files before
        long filesBefore;
        try (var files = Files.walk(payloadRoot)) {
            filesBefore = files.filter(Files::isRegularFile).count();
        }

        // Install a trigger to cause source_payload insert to fail
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("""
                    CREATE OR REPLACE FUNCTION pg_temp.force_payload_insert_failure()
                    RETURNS trigger AS $$
                    BEGIN
                        RAISE EXCEPTION 'INJECTED_FAILURE: force payload insert rollback';
                    END;
                    $$ LANGUAGE plpgsql
                    """);
            s.execute("""
                    CREATE TRIGGER injected_payload_insert_failure
                    BEFORE INSERT ON evidence.source_payload
                    FOR EACH ROW EXECUTE FUNCTION pg_temp.force_payload_insert_failure()
                    """);

            var req = buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB);
            assertThrows(Exception.class, () -> coord.prepare(req));

            // Cleanup triggers
            s.execute(
                    "DROP TRIGGER IF EXISTS injected_payload_insert_failure ON evidence.source_payload");
            s.execute("DROP FUNCTION IF EXISTS pg_temp.force_payload_insert_failure()");
        }

        // After compensation: file count should be back to before (new files deleted)
        long filesAfter;
        try (var files = Files.walk(payloadRoot)) {
            filesAfter = files.filter(Files::isRegularFile).count();
        }
        assertEquals(filesBefore, filesAfter,
                "compensation must delete newly created payload files on DB failure");

        // SourcePayload count for these units must be 0
        long spCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD,
                io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.in(msg1, msg2));
        assertEquals(0L, spCount, "no SourcePayload rows after rollback");
    }

    @Test
    @Order(25)
    void s2aLatePrepareFailureCompensatesPayloadFiles() throws Exception {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String key = UUID.randomUUID().toString();

        long filesBefore;
        try (var files = Files.walk(payloadRoot)) {
            filesBefore = files.filter(Files::isRegularFile).count();
        }

        var baseReq = buildPrepareRequest(key, h(key), actorA, msg1, msg2, actorA, actorB);
        UUID missingSourceUnitId = UUID.randomUUID();
        var badAnchors = List.of(new LocalV1S1PrepareRequest.AnchorInput(
                UUID.randomUUID(),
                List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                        missingSourceUnitId, 0L, 1L, 1L))));
        var badReq = new LocalV1S1PrepareRequest(
                baseReq.idempotencyKey(),
                baseReq.requestHash(),
                baseReq.perspectiveActorId(),
                baseReq.memoryType(),
                baseReq.bodyText(),
                baseReq.bodyHash(),
                baseReq.selectedEvidenceMessages(),
                badAnchors);

        assertThrows(Exception.class, () -> coord.prepare(badReq));

        long filesAfter;
        try (var files = Files.walk(payloadRoot)) {
            filesAfter = files.filter(Files::isRegularFile).count();
        }
        assertEquals(filesBefore, filesAfter,
                "compensation must cover failures after SourcePayload insert");

        long spCount = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.evidence.Tables.SOURCE_PAYLOAD,
                io.github.candyxi0.hidenest.database.generated.evidence.tables.SourcePayload.SOURCE_PAYLOAD
                        .SOURCE_UNIT_ID.in(msg1, msg2));
        assertEquals(0L, spCount, "no SourcePayload rows after late rollback");
    }

    // ════════════════════════════════════════════════════════════════════
    // S2A Test: confirm / reject / concurrent still pass (regression)
    // ════════════════════════════════════════════════════════════════════
    @Test
    @Order(26)
    void s2aConfirmAndRejectStillWork() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        var prepResult = coord.prepare(
                buildPrepareRequest(prepKey, h(prepKey), actorA, msg1, msg2, actorA, actorB));

        // Confirm still works
        UUID memoryId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var confirmReq = new LocalV1S1ConfirmRequest(
                confirmKey, h(confirmKey),
                prepResult.proposalRevisionId(),
                prepResult.reviewSessionId(),
                memoryId, policyId, h0());
        var confirmResult = coord.confirm(confirmReq);
        assertEquals("SUCCEEDED", confirmResult.resultCategory());
        assertEquals(2, confirmResult.evidenceCount());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static void q(Connection c, String s) throws Exception {
        try (Statement st = c.createStatement()) {
            st.execute(s);
        }
    }
}
