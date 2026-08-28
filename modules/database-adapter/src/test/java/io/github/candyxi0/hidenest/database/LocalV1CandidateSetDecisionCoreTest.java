package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetBatchCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorSpec;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.Candidate;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest.FinalConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetResult;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareResult;
import io.github.candyxi0.hidenest.database.adapter.JooqCandidateSetGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.port.CandidateSetGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1CandidateSetDecisionCoreTest {

    private static final String IMG =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String U = "hide_nest_migrator";
    private static final Clock CLK = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> pg;
    private static DSLContext dsl;
    private static String PW;
    private static LocalV1CandidateSetBatchCoordinator coord;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static Path payloadRoot;
    private static PayloadStore payloadStore;
    private static MemoryGovernancePort mp;
    private static RuntimeTransactionPort rp;

    @BeforeAll
    static void setUp() throws Exception {
        PW = UUID.randomUUID().toString() + UUID.randomUUID();
        pg = new PostgreSQLContainer<>(DockerImageName.parse(IMG).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(U)
                .withPassword(PW)
                .withStartupTimeout(Duration.ofSeconds(120));
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN");
            s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway fw = Flyway.configure()
                .dataSource(pg.getJdbcUrl(), U, PW)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .load();
        assertEquals(22, fw.migrate().migrationsExecuted);

        var rds = new DriverManagerDataSource(pg.getJdbcUrl(), U, PW);
        DataSourceTransactionManager txm = new DataSourceTransactionManager(rds);
        TransactionTemplate tx = new TransactionTemplate(txm);
        DefaultConfiguration cfg = new DefaultConfiguration();
        cfg.setSQLDialect(SQLDialect.POSTGRES);
        cfg.setDataSource(new TransactionAwareDataSourceProxy(rds));
        dsl = new DefaultDSLContext(cfg);

        mp = new JooqMemoryGovernanceAdapter(dsl);
        rp = new JooqRuntimeTransactionAdapter(dsl);
        var ep = new JooqEvidenceReferenceAdapter(dsl);
        CandidateSetGovernancePort csp = new JooqCandidateSetGovernanceAdapter(dsl);
        TransactionExecutor te = new SpringTransactionExecutor(tx);
        payloadRoot = Files.createTempDirectory("candidate-set-payload-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        var pub = new CanonicalPublishCoordinator(mp, rp, te, CLK);
        s1 = new LocalV1S1WindowCloseCoordinator(ep, mp, rp, te, pub, payloadStore, CLK);
        coord = new LocalV1CandidateSetBatchCoordinator(ep, mp, rp, csp, te, payloadStore, CLK);
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

    // ── helpers ──────────────────────────────────────────────────────────

    private static byte[] sha(String in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static byte[] h0() {
        return new byte[32];
    }

    private long count(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long filesCount() throws Exception {
        try (var files = Files.walk(payloadRoot)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    private Candidate createCandidate(
            UUID candidateId,
            long ordinal,
            String disposition,
            String action,
            String origin,
            String author,
            String text,
            String type,
            UUID perspective,
            List<UUID> anchors) {
        return new Candidate(
                candidateId,
                ordinal,
                disposition,
                action,
                origin,
                author,
                text,
                type,
                perspective,
                anchors,
                null,
                null,
                null,
                null,
                "reason-" + candidateId);
    }

    private Candidate reviseCandidate(
            UUID candidateId,
            long ordinal,
            String disposition,
            String action,
            String origin,
            String author,
            String text,
            String type,
            UUID perspective,
            List<UUID> anchors,
            UUID target,
            UUID expectedRevId,
            long expectedRevNo,
            long expectedPolicyNo) {
        return new Candidate(
                candidateId,
                ordinal,
                disposition,
                action,
                origin,
                author,
                text,
                type,
                perspective,
                anchors,
                target,
                expectedRevId,
                expectedRevNo,
                expectedPolicyNo,
                "reason-" + candidateId);
    }

    private LocalV1CandidateSetRequest seal(
            UUID setId,
            String key,
            UUID threadId,
            List<EvidenceMessage> messages,
            List<AnchorSpec> anchors,
            List<Candidate> candidates) {
        LocalV1CandidateSetRequest draft = new LocalV1CandidateSetRequest(
                setId,
                key,
                new byte[32],
                threadId,
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors),
                candidates);
        byte[] requestHash = LocalV1CandidateSetCanonicalizer.requestHash(draft);
        byte[] confirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(draft);
        return new LocalV1CandidateSetRequest(
                setId,
                key,
                requestHash,
                threadId,
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, confirmationHash),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors),
                candidates);
    }

    private EvidenceMessage message(UUID unitId, UUID actorId, long ordinal, String text) {
        return message(unitId, actorId, "XIAOLIN", ordinal, text);
    }

    private EvidenceMessage message(UUID unitId, UUID actorId, String role, long ordinal, String text) {
        return new EvidenceMessage(
                unitId, actorId, role, ordinal, "msg-" + unitId, OffsetDateTime.now(CLK), text, sha(text));
    }

    private AnchorSpec fullAnchor(UUID anchorId, UUID unitId, String text) {
        return new AnchorSpec(anchorId, List.of(new AnchorUnit(unitId, 0L, (long) text.length(), 1L)));
    }

    // ── 1. three accepted candidates ──────────────────────────────────────

    @Test
    @Order(1)
    void threeAcceptedCandidatesNoMemory() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "小林喜欢粉色，下周准备购买家庭服务器，预算不超过3000元";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "小林喜欢粉色",
                        "Claim",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        2,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "下周准备购买家庭服务器",
                        "Event",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        3,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "购买预算不超过 3000 元",
                        "Claim",
                        actor,
                        List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                threadId,
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);

        LocalV1CandidateSetResult result = coord.submit(request);
        assertEquals("CANONICAL_COMMITTED", result.status());
        assertEquals(3, result.outcomes().size());

        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
        assertEquals(
                1,
                count("SELECT count(DISTINCT review_session_id) FROM memory.candidate_set WHERE candidate_set_id='"
                        + setId + "'"));
        assertEquals(
                3, count("SELECT count(*) FROM memory.candidate_set_member WHERE candidate_set_id='" + setId + "'"));
        assertEquals(
                3,
                count(
                        "SELECT count(*) FROM memory.decision d JOIN memory.candidate_set cs ON cs.review_session_id=d.review_session_id WHERE cs.candidate_set_id='"
                                + setId + "' AND d.decision_kind='USER_CONFIRM'"));
        assertEquals(
                3,
                count(
                        "SELECT count(*) FROM memory.proposal_revision pr JOIN memory.review_member rm ON rm.proposal_revision_id=pr.proposal_revision_id JOIN memory.candidate_set cs ON cs.review_session_id=rm.review_session_id WHERE cs.candidate_set_id='"
                                + setId + "'"));
        // No memory / revision / embedding
        assertEquals(0, count("SELECT count(*) FROM memory.memory_record"));
        assertEquals(0, count("SELECT count(*) FROM memory.memory_revision"));
        assertEquals(0, count("SELECT count(*) FROM memory.memory_revision_embedding"));
        assertEquals(
                "COMPLETED",
                scalar(
                        "SELECT state FROM memory.review_session WHERE review_session_id=(SELECT review_session_id FROM memory.candidate_set WHERE candidate_set_id='"
                                + setId + "')"));
    }

    // ── 2. shared evidence dedup ──────────────────────────────────────────

    @Test
    @Order(2)
    void sharedEvidenceDeduplicated() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "下周准备购买家庭服务器，预算不超过3000元";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "下周准备购买家庭服务器",
                        "Event",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        2,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "购买预算不超过 3000 元",
                        "Claim",
                        actor,
                        List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                threadId,
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        coord.submit(request);

        // one Source, one SourceUnit, one SourcePayload, one SourceAnchor for the shared message
        UUID sourceId = uuid("SELECT source_id FROM evidence.source WHERE external_ref='candidate-set:" + setId + "'");
        assertEquals(1, count("SELECT count(*) FROM evidence.source_unit WHERE source_id='" + sourceId + "'"));
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM evidence.source_payload WHERE source_unit_id IN (SELECT source_unit_id FROM evidence.source_unit WHERE source_id='"
                                + sourceId + "')"));
        assertEquals(1, count("SELECT count(*) FROM evidence.source_anchor WHERE source_id='" + sourceId + "'"));
        // 3 candidate→anchor mappings (2 candidates × 1 anchor = 2 here; verify against accepted count)
        assertEquals(
                2,
                count("SELECT count(*) FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + setId + "'"));
    }

    // ── 3. mixed disposition / origin / author ────────────────────────────

    @Test
    @Order(3)
    void mixedDispositionOriginAuthor() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "一段共享证据";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "hide 原文",
                        "Claim",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        2,
                        "REJECTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "被拒绝",
                        null,
                        actor,
                        List.of()),
                createCandidate(
                        UUID.randomUUID(),
                        3,
                        "ACCEPTED",
                        "CREATE",
                        "USER_EDITED",
                        "USER",
                        "小林改写",
                        "Claim",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        4,
                        "ACCEPTED",
                        "CREATE",
                        "USER_ADDED",
                        "USER",
                        "小林新增",
                        "Claim",
                        actor,
                        List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                threadId,
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        coord.submit(request);

        assertEquals(
                "ACCEPTED",
                scalar("SELECT disposition FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=1"));
        assertEquals(
                "REJECTED",
                scalar("SELECT disposition FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=2"));
        assertEquals(
                "USER_EDITED",
                scalar("SELECT origin_kind FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=3"));
        assertEquals(
                "USER",
                scalar("SELECT final_author_kind FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=3"));
        assertEquals(
                "USER_ADDED",
                scalar("SELECT origin_kind FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=4"));
        assertEquals(
                "USER",
                scalar("SELECT final_author_kind FROM memory.candidate_set_member WHERE candidate_set_id='" + setId
                        + "' AND ordinal=4"));
        // one USER_REJECT, three USER_CONFIRM
        assertEquals(
                1,
                count(
                        "SELECT count(*) FROM memory.decision d JOIN memory.candidate_set cs ON cs.review_session_id=d.review_session_id WHERE cs.candidate_set_id='"
                                + setId + "' AND d.decision_kind='USER_REJECT'"));
        assertEquals(
                3,
                count(
                        "SELECT count(*) FROM memory.decision d JOIN memory.candidate_set cs ON cs.review_session_id=d.review_session_id WHERE cs.candidate_set_id='"
                                + setId + "' AND d.decision_kind='USER_CONFIRM'"));
    }

    // ── 4. rejected-only evidence not persisted ───────────────────────────

    @Test
    @Order(4)
    void rejectedOnlyEvidenceNotPersisted() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID acceptedUnit = UUID.randomUUID();
        UUID rejectedUnit = UUID.randomUUID();
        UUID acceptedAnchor = UUID.randomUUID();
        UUID rejectedAnchor = UUID.randomUUID();
        String acceptedBody = "accepted evidence";
        String rejectedBody = "rejected-only evidence body";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "被接受的记忆",
                        "Claim",
                        actor,
                        List.of(acceptedAnchor)),
                createCandidate(
                        UUID.randomUUID(),
                        2,
                        "REJECTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "被拒绝的记忆",
                        null,
                        actor,
                        List.of()));
        var request = seal(
                setId,
                "key-" + setId,
                threadId,
                List.of(message(acceptedUnit, actor, 1, acceptedBody), message(rejectedUnit, actor, 2, rejectedBody)),
                List.of(
                        fullAnchor(acceptedAnchor, acceptedUnit, acceptedBody),
                        fullAnchor(rejectedAnchor, rejectedUnit, rejectedBody)),
                candidates);
        coord.submit(request);

        // rejected unit never persisted
        assertEquals(0, count("SELECT count(*) FROM evidence.source_unit WHERE source_unit_id='" + rejectedUnit + "'"));
        assertEquals(
                0, count("SELECT count(*) FROM evidence.source_payload WHERE source_unit_id='" + rejectedUnit + "'"));
        assertEquals(0, count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id='" + rejectedAnchor + "'"));
        // accepted unit persisted
        assertEquals(1, count("SELECT count(*) FROM evidence.source_unit WHERE source_unit_id='" + acceptedUnit + "'"));
        // rejected does not create memory/embedding
        assertEquals(0, count("SELECT count(*) FROM memory.memory_revision_embedding"));
    }

    // ── 5. empty set ──────────────────────────────────────────────────────

    @Test
    @Order(5)
    void emptySetNoCandidates() throws Exception {
        long rsBefore = count("SELECT count(*) FROM memory.review_session");
        long decBefore = count("SELECT count(*) FROM memory.decision");
        long srcBefore = count("SELECT count(*) FROM evidence.source");
        long unitBefore = count("SELECT count(*) FROM evidence.source_unit");
        long payloadBefore = count("SELECT count(*) FROM evidence.source_payload");
        long anchorBefore = count("SELECT count(*) FROM evidence.source_anchor");

        UUID setId = UUID.randomUUID();
        var request = seal(setId, "key-" + setId, UUID.randomUUID(), List.of(), List.of(), List.of());
        LocalV1CandidateSetResult result = coord.submit(request);
        assertEquals("NO_CANDIDATES", result.status());

        assertEquals(rsBefore, count("SELECT count(*) FROM memory.review_session"));
        assertEquals(decBefore, count("SELECT count(*) FROM memory.decision"));
        assertEquals(srcBefore, count("SELECT count(*) FROM evidence.source"));
        assertEquals(unitBefore, count("SELECT count(*) FROM evidence.source_unit"));
        assertEquals(payloadBefore, count("SELECT count(*) FROM evidence.source_payload"));
        assertEquals(anchorBefore, count("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
    }

    // ── 6. 1 and 8 boundary pass; 9 rejected ──────────────────────────────

    @Test
    @Order(6)
    void boundaryCounts() throws Exception {
        // 1 candidate
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "single";
        var one = seal(
                UUID.randomUUID(),
                "key-one",
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "single",
                        "Claim",
                        actor,
                        List.of(anchor))));
        assertEquals("CANONICAL_COMMITTED", coord.submit(one).status());

        // 8 candidates
        UUID actor2 = UUID.randomUUID();
        UUID unit2 = UUID.randomUUID();
        UUID anchor2 = UUID.randomUUID();
        String body2 = "eight";
        List<Candidate> eight = new ArrayList<>();
        for (long i = 1; i <= 8; i++) {
            eight.add(createCandidate(
                    UUID.randomUUID(),
                    i,
                    "ACCEPTED",
                    "CREATE",
                    "HIDE_PROPOSED",
                    "HIDE",
                    "candidate-" + i,
                    "Claim",
                    actor2,
                    List.of(anchor2)));
        }
        var eightReq = seal(
                UUID.randomUUID(),
                "key-eight",
                UUID.randomUUID(),
                List.of(message(unit2, actor2, 1, body2)),
                List.of(fullAnchor(anchor2, unit2, body2)),
                eight);
        assertEquals("CANONICAL_COMMITTED", coord.submit(eightReq).status());

        // 9 candidates → rejected before any write
        long csBefore = count("SELECT count(*) FROM memory.candidate_set");
        List<Candidate> nine = new ArrayList<>();
        for (long i = 1; i <= 9; i++) {
            nine.add(createCandidate(
                    UUID.randomUUID(),
                    i,
                    "ACCEPTED",
                    "CREATE",
                    "HIDE_PROPOSED",
                    "HIDE",
                    "candidate-" + i,
                    "Claim",
                    actor2,
                    List.of(anchor2)));
        }
        var nineDraft = new LocalV1CandidateSetRequest(
                UUID.randomUUID(),
                "key-nine",
                new byte[32],
                UUID.randomUUID(),
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit2, actor2, 1, body2)), List.of(fullAnchor(anchor2, unit2, body2))),
                nine);
        var nineReq = new LocalV1CandidateSetRequest(
                nineDraft.candidateSetId(),
                nineDraft.idempotencyKey(),
                LocalV1CandidateSetCanonicalizer.requestHash(nineDraft),
                nineDraft.threadId(),
                nineDraft.scopeRef(),
                1,
                new FinalConfirmation("CONFIRM_SET", 1, LocalV1CandidateSetCanonicalizer.confirmationHash(nineDraft)),
                nineDraft.evidencePool(),
                nine);
        assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(nineReq));
        assertEquals(csBefore, count("SELECT count(*) FROM memory.candidate_set"));
    }

    // ── 7. structural rejections ──────────────────────────────────────────

    @Test
    @Order(7)
    void structuralRejections() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "evidence";

        // duplicate candidateId
        UUID dup = UUID.randomUUID();
        assertSchemaRejected(
                List.of(
                        createCandidate(
                                dup,
                                1,
                                "ACCEPTED",
                                "CREATE",
                                "HIDE_PROPOSED",
                                "HIDE",
                                "a",
                                "Claim",
                                actor,
                                List.of(anchor)),
                        createCandidate(
                                dup,
                                2,
                                "ACCEPTED",
                                "CREATE",
                                "HIDE_PROPOSED",
                                "HIDE",
                                "b",
                                "Claim",
                                actor,
                                List.of(anchor))),
                actor,
                unit,
                anchor,
                body);

        // ordinal gap (1 then 3)
        assertSchemaRejected(
                List.of(
                        createCandidate(
                                UUID.randomUUID(),
                                1,
                                "ACCEPTED",
                                "CREATE",
                                "HIDE_PROPOSED",
                                "HIDE",
                                "a",
                                "Claim",
                                actor,
                                List.of(anchor)),
                        createCandidate(
                                UUID.randomUUID(),
                                3,
                                "ACCEPTED",
                                "CREATE",
                                "HIDE_PROPOSED",
                                "HIDE",
                                "b",
                                "Claim",
                                actor,
                                List.of(anchor))),
                actor,
                unit,
                anchor,
                body);

        // accepted with no evidence
        assertSchemaRejected(
                List.of(createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "a",
                        "Claim",
                        actor,
                        List.of())),
                actor,
                unit,
                anchor,
                body);

        // rejected with evidence
        assertSchemaRejected(
                List.of(createCandidate(
                        UUID.randomUUID(),
                        1,
                        "REJECTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "a",
                        null,
                        actor,
                        List.of(anchor))),
                actor,
                unit,
                anchor,
                body);

        // confirmationHash tampered
        UUID setId = UUID.randomUUID();
        var draft = new LocalV1CandidateSetRequest(
                setId,
                "key-tamper",
                new byte[32],
                UUID.randomUUID(),
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)), List.of(fullAnchor(anchor, unit, body))),
                List.of(createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "a",
                        "Claim",
                        actor,
                        List.of(anchor))));
        byte[] wrongConfirmation = new byte[32];
        Arrays.fill(wrongConfirmation, (byte) 1);
        var tampered = new LocalV1CandidateSetRequest(
                setId,
                "key-tamper",
                LocalV1CandidateSetCanonicalizer.requestHash(draft),
                draft.threadId(),
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, wrongConfirmation),
                draft.evidencePool(),
                draft.candidates());
        assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(tampered));

        // old setVersion (confirmedSetVersion mismatch)
        var oldVersion = new LocalV1CandidateSetRequest(
                setId,
                "key-tamper",
                new byte[32],
                draft.threadId(),
                "scope",
                2,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                draft.evidencePool(),
                draft.candidates());
        assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(oldVersion));
    }

    private void assertSchemaRejected(List<Candidate> candidates, UUID actor, UUID unit, UUID anchor, String body) {
        var req = seal(
                UUID.randomUUID(),
                "key-schema",
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(req));
    }

    // ── 8. action binding rejections ──────────────────────────────────────

    @Test
    @Order(8)
    void actionBindingRejections() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "evidence";

        // CREATE with non-null target
        var createWithTarget = new Candidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "CREATE",
                "HIDE_PROPOSED",
                "HIDE",
                "a",
                "Claim",
                actor,
                List.of(anchor),
                UUID.randomUUID(),
                null,
                null,
                null,
                "r");
        assertSchemaRejected(List.of(createWithTarget), actor, unit, anchor, body);

        // REVISE missing target
        var reviseNoTarget = new Candidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "REVISE",
                "HIDE_PROPOSED",
                "HIDE",
                "a",
                "Claim",
                actor,
                List.of(anchor),
                null,
                null,
                null,
                null,
                "r");
        assertSchemaRejected(List.of(reviseNoTarget), actor, unit, anchor, body);

        // Seed an ACTIVE memory (revision 1, policy 1) for stale-binding tests
        var seeded = seedActiveMemory();

        // REVISE with wrong current revision → EXPECTED_REVISION_STALE
        var wrongRev = reviseCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "REVISE",
                "HIDE_PROPOSED",
                "HIDE",
                "revised",
                "Claim",
                actor,
                List.of(anchor),
                seeded.memoryId(),
                UUID.randomUUID(),
                1L,
                1L);
        assertCode(
                LocalV1CandidateSetException.Code.EXPECTED_REVISION_STALE,
                seal(
                        UUID.randomUUID(),
                        "key-rev",
                        UUID.randomUUID(),
                        List.of(message(unit, actor, 1, body)),
                        List.of(fullAnchor(anchor, unit, body)),
                        List.of(wrongRev)));

        // REVISE with wrong policy → POLICY_REVISION_STALE
        var wrongPolicy = reviseCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "REVISE",
                "HIDE_PROPOSED",
                "HIDE",
                "revised",
                "Claim",
                actor,
                List.of(anchor),
                seeded.memoryId(),
                seeded.currentRevisionId(),
                1L,
                99L);
        assertCode(
                LocalV1CandidateSetException.Code.POLICY_REVISION_STALE,
                seal(
                        UUID.randomUUID(),
                        "key-pol",
                        UUID.randomUUID(),
                        List.of(message(unit, actor, 1, body)),
                        List.of(fullAnchor(anchor, unit, body)),
                        List.of(wrongPolicy)));
    }

    private record SeededMemory(UUID memoryId, UUID currentRevisionId) {}

    private SeededMemory seedActiveMemory() {
        UUID actor = UUID.randomUUID();
        UUID msg1 = UUID.randomUUID();
        UUID msg2 = UUID.randomUUID();
        String prepKey = UUID.randomUUID().toString();
        String bodyText = "seed memory body";
        var prepare = new LocalV1S1PrepareRequest(
                prepKey,
                sha(prepKey),
                actor,
                "Claim",
                bodyText,
                sha(bodyText),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                msg1, actor, 1L, "m1", OffsetDateTime.now(CLK), "证据一"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                msg2, actor, 2L, "m2", OffsetDateTime.now(CLK), "证据二")),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(
                                UUID.randomUUID(),
                                List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(msg1, 0L, 3L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(
                                UUID.randomUUID(),
                                List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(msg2, 0L, 3L, 2L)))));
        LocalV1S1PrepareResult prep = s1.prepare(prepare);
        UUID memoryId = UUID.randomUUID();
        String confirmKey = UUID.randomUUID().toString();
        var confirm = s1.confirm(new LocalV1S1ConfirmRequest(
                confirmKey,
                sha(confirmKey),
                prep.proposalRevisionId(),
                prep.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                h0()));
        return new SeededMemory(memoryId, confirm.currentRevisionId());
    }

    private void assertCode(LocalV1CandidateSetException.Code expected, LocalV1CandidateSetRequest request) {
        LocalV1CandidateSetException ex = assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(expected, ex.code());
    }

    // ── 9. fault injection: zero half-commit ──────────────────────────────

    @Test
    @Order(9)
    void faultInjectionZeroHalfCommit() throws Exception {
        // injection 1: before first decision
        injectBeforeFirstDecision();
        // injection 2: after a member's change event (on governed outbox)
        injectOnGovernedOutbox();
        // injection 3: before review session completion
        injectBeforeSessionCompletion();
    }

    private void injectBeforeFirstDecision() throws Exception {
        runInjectedTrigger(
                "BEFORE INSERT ON memory.decision FOR EACH ROW WHEN (NEW.idempotency_key LIKE 'cs-verdict-%')");
    }

    private void injectOnGovernedOutbox() throws Exception {
        runInjectedTrigger(
                "BEFORE INSERT ON runtime.outbox_event FOR EACH ROW WHEN (NEW.event_type = 'review.decisions-committed.v1')");
    }

    private void injectBeforeSessionCompletion() throws Exception {
        runInjectedTrigger("BEFORE UPDATE ON memory.review_session FOR EACH ROW");
    }

    private void runInjectedTrigger(String triggerDefinition) throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "injected evidence";
        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(),
                        1,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "one",
                        "Claim",
                        actor,
                        List.of(anchor)),
                createCandidate(
                        UUID.randomUUID(),
                        2,
                        "ACCEPTED",
                        "CREATE",
                        "HIDE_PROPOSED",
                        "HIDE",
                        "two",
                        "Claim",
                        actor,
                        List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);

        long csBefore = count("SELECT count(*) FROM memory.candidate_set");
        long filesBefore = filesCount();

        String trigger = "cs_fault_inject";
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE OR REPLACE FUNCTION pg_temp.cs_fault() RETURNS trigger AS $$ BEGIN RAISE EXCEPTION 'INJECTED_FAILURE'; END $$ LANGUAGE plpgsql");
            s.execute("CREATE TRIGGER " + trigger + " " + triggerDefinition + " EXECUTE FUNCTION pg_temp.cs_fault()");

            assertThrows(Exception.class, () -> coord.submit(request));

            s.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + tableOf(triggerDefinition));
            s.execute("DROP FUNCTION IF EXISTS pg_temp.cs_fault()");
        }

        assertEquals(csBefore, count("SELECT count(*) FROM memory.candidate_set"));
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
        assertEquals(
                0,
                count(
                        "SELECT count(*) FROM memory.decision d JOIN memory.candidate_set cs ON cs.review_session_id=d.review_session_id WHERE cs.candidate_set_id='"
                                + setId + "'"));
        assertEquals(filesBefore, filesCount(), "no orphan payload files");
    }

    private String tableOf(String triggerDefinition) {
        if (triggerDefinition.contains("memory.decision")) return "memory.decision";
        if (triggerDefinition.contains("runtime.outbox_event")) return "runtime.outbox_event";
        return "memory.review_session";
    }

    // ── 10. replay + conflict ─────────────────────────────────────────────

    @Test
    @Order(10)
    void replayExactAndConflictRejected() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "replay evidence";
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "CREATE",
                "HIDE_PROPOSED",
                "HIDE",
                "one",
                "Claim",
                actor,
                List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);

        LocalV1CandidateSetResult r1 = coord.submit(request);
        // same key + same value → exact replay
        LocalV1CandidateSetResult r2 = coord.submit(request);
        assertEquals(r1.candidateSetId(), r2.candidateSetId());
        assertEquals(r1.status(), r2.status());
        assertEquals(r1.outcomes().size(), r2.outcomes().size());
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));

        // same key + different value → conflict
        var different = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, "different body")),
                List.of(fullAnchor(anchor, unit, "different body")),
                candidates);
        LocalV1CandidateSetException ex =
                assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(different));
        assertEquals(LocalV1CandidateSetException.Code.IDEMPOTENCY_KEY_REUSED, ex.code());
    }

    // ── 11. concurrency ───────────────────────────────────────────────────

    @Test
    @Order(11)
    void concurrentSameValueSingleFactGroup() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "concurrent evidence";
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "CREATE",
                "HIDE_PROPOSED",
                "HIDE",
                "one",
                "Claim",
                actor,
                List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);

        CountDownLatch barrier = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger(0);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        Runnable task = () -> {
            try {
                barrier.countDown();
                go.await();
                coord.submit(request);
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
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
    }

    // ── 12. replay fails closed on tampered facts ─────────────────────────

    @Test
    @Order(12)
    void replayFailsClosedOnTamperedMember() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "tamper evidence";
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "CREATE",
                "HIDE_PROPOSED",
                "HIDE",
                "one",
                "Claim",
                actor,
                List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        coord.submit(request);

        // The CandidateSet facts are immutable (UPDATE/DELETE denied by triggers). To exercise the
        // replay fail-closed path, suspend the immutability trigger, delete the evidence mapping
        // fact, then restore the trigger. Replay must detect the missing fact and fail closed.
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER candidate_evidence_mapping_immutable ON memory.candidate_evidence_mapping");
            s.execute("DELETE FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + setId + "'");
            s.execute("CREATE TRIGGER candidate_evidence_mapping_immutable "
                    + "BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping "
                    + "FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
        }
        LocalV1CandidateSetException ex = assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED, ex.code());
    }

    // ── 13. canary 0 in outbox / change_event / receipt ───────────────────

    @Test
    @Order(13)
    void canaryZeroInOutboxChangeEventReceipt() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String canary = "CANARY-SECRET-BODY-999";
        String body = "evidence with " + canary;
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(),
                1,
                "ACCEPTED",
                "CREATE",
                "HIDE_PROPOSED",
                "HIDE",
                "memory " + canary,
                "Claim",
                actor,
                List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        coord.submit(request);

        // canary must not appear in outbox payload_manifest, change_event detail_manifest, or receipt manifest
        assertEquals(
                0,
                count("SELECT count(*) FROM runtime.outbox_event WHERE payload_manifest::text LIKE '%" + canary
                        + "%'"));
        assertEquals(
                0,
                count("SELECT count(*) FROM memory.change_event WHERE detail_manifest::text LIKE '%" + canary + "%'"));
        assertEquals(
                0,
                count("SELECT count(*) FROM runtime.idempotency_receipt WHERE response_manifest::text LIKE '%" + canary
                        + "%'"));
        // canary must not appear in candidate_set tables either (memoryText is NOT stored there; only structural facts)
        assertEquals(
                0, count("SELECT count(*) FROM memory.candidate_set_member WHERE disposition LIKE '%" + canary + "%'"));
    }

    // ── 14. V019→V020 upgrade path (1 migration, repeat 0) ────────────────

    @Test
    @Order(14)
    void v019ToV020Upgrade() throws Exception {
        try (PostgreSQLContainer<?> upgrade = new PostgreSQLContainer<>(
                        DockerImageName.parse(IMG).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest_upgrade")
                .withUsername(U)
                .withPassword(PW)
                .withStartupTimeout(Duration.ofSeconds(120))) {
            upgrade.start();
            try (Connection c = DriverManager.getConnection(upgrade.getJdbcUrl(), U, PW);
                    Statement s = c.createStatement()) {
                s.execute("CREATE ROLE hide_nest_api NOLOGIN");
                s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
            }
            Flyway v19 = Flyway.configure()
                    .dataSource(upgrade.getJdbcUrl(), U, PW)
                    .defaultSchema("public")
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .target("19")
                    .load();
            assertEquals(19, v19.migrate().migrationsExecuted);
            Flyway v20 = Flyway.configure()
                    .dataSource(upgrade.getJdbcUrl(), U, PW)
                    .defaultSchema("public")
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .target("20")
                    .load();
            assertEquals(1, v20.migrate().migrationsExecuted);
            assertEquals(0, v20.migrate().migrationsExecuted);
        }
    }

    // ── 15. R1-01: commit-time deferred failure compensates payload ───────

    @Test
    @Order(15)
    void commitTimeDeferredFailureCompensatesPayloads() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "commit deferred failure evidence";
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);

        long csBefore = count("SELECT count(*) FROM memory.candidate_set");
        long srcBefore = count("SELECT count(*) FROM evidence.source");
        long filesBefore = filesCount();

        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("CREATE OR REPLACE FUNCTION cs_r1_commit_fail() RETURNS trigger AS $$ "
                    + "BEGIN RAISE EXCEPTION 'INJECTED_COMMIT_FAILURE' USING ERRCODE = '23514'; END $$ LANGUAGE plpgsql");
            s.execute("CREATE CONSTRAINT TRIGGER cs_r1_commit_fail_guard AFTER INSERT ON memory.candidate_set "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION cs_r1_commit_fail()");
            try {
                assertThrows(Exception.class, () -> coord.submit(request));
            } finally {
                s.execute("DROP TRIGGER IF EXISTS cs_r1_commit_fail_guard ON memory.candidate_set");
                s.execute("DROP FUNCTION IF EXISTS cs_r1_commit_fail()");
            }
        }

        assertEquals(csBefore, count("SELECT count(*) FROM memory.candidate_set"));
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
        assertEquals(0, count("SELECT count(*) FROM memory.decision WHERE idempotency_key LIKE 'cs-verdict-" + setId + "-%'"));
        assertEquals(srcBefore, count("SELECT count(*) FROM evidence.source"));
        assertEquals(0, count("SELECT count(*) FROM runtime.outbox_event WHERE idempotency_key LIKE 'cs-ob-" + setId + "-%'"));
        assertEquals(0, count("SELECT count(*) FROM runtime.idempotency_receipt WHERE idempotency_key='key-" + setId + "'"));
        assertEquals(filesBefore, filesCount(), "commit-time failure must compensate payload files");
    }

    // ── 16. R1-02: legal REVISE / SUPERSEDE positives + matrix ────────────

    @Test
    @Order(16)
    void legalReviseSupersedePositives() throws Exception {
        SeededMemory m1 = seedActiveMemory();
        SeededMemory m2 = seedActiveMemory();

        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "legal revise supersede evidence";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "create candidate", "Claim", actor, List.of(anchor)),
                reviseCandidate(
                        UUID.randomUUID(), 2, "ACCEPTED", "REVISE", "HIDE_PROPOSED", "HIDE",
                        "revise candidate", "Claim", actor, List.of(anchor),
                        m1.memoryId(), m1.currentRevisionId(), 1L, 1L),
                reviseCandidate(
                        UUID.randomUUID(), 3, "ACCEPTED", "SUPERSEDE", "HIDE_PROPOSED", "HIDE",
                        "supersede candidate", "Claim", actor, List.of(anchor),
                        m2.memoryId(), m2.currentRevisionId(), 1L, 1L));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                candidates);
        assertEquals("CANONICAL_COMMITTED", coord.submit(request).status());

        String join = " FROM memory.candidate_set_member m "
                + "JOIN memory.proposal_revision pr ON pr.proposal_revision_id = m.proposal_revision_id "
                + "JOIN memory.proposal p ON p.proposal_id = pr.proposal_id "
                + "JOIN memory.decision d ON d.decision_id = m.decision_id "
                + "WHERE m.candidate_set_id = '" + setId + "' ";

        // CREATE: Proposal target null, Decision target = future_memory_id, revision 1
        assertEquals(1, count("SELECT count(*)" + join + "AND m.ordinal = 1 "
                + "AND p.target_memory_id IS NULL AND d.target_kind = 'MEMORY' "
                + "AND d.target_id = m.future_memory_id AND d.target_revision_ref = 1"));
        // REVISE: Proposal target = existing, Decision target = existing, revision expected+1=2, future = target
        assertEquals(1, count("SELECT count(*)" + join + "AND m.ordinal = 2 "
                + "AND p.target_memory_id = '" + m1.memoryId() + "' AND d.target_kind = 'MEMORY' "
                + "AND d.target_id = m.target_memory_id AND m.future_memory_id = m.target_memory_id "
                + "AND d.target_revision_ref = 2"));
        // SUPERSEDE: Proposal target = old, Decision target = future_memory_id (distinct), revision 1
        assertEquals(1, count("SELECT count(*)" + join + "AND m.ordinal = 3 "
                + "AND p.target_memory_id = '" + m2.memoryId() + "' AND d.target_kind = 'MEMORY' "
                + "AND d.target_id = m.future_memory_id AND d.target_revision_ref = 1 "
                + "AND m.future_memory_id <> m.target_memory_id"));
    }

    // ── 17. R1-02: rejected REVISE / SUPERSEDE target Proposal ─────────────

    @Test
    @Order(17)
    void rejectedReviseSupersedeTargetProposal() throws Exception {
        SeededMemory m1 = seedActiveMemory();
        SeededMemory m2 = seedActiveMemory();

        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        List<Candidate> candidates = List.of(
                reviseCandidate(
                        UUID.randomUUID(), 1, "REJECTED", "REVISE", "HIDE_PROPOSED", "HIDE",
                        null, null, actor, List.of(),
                        m1.memoryId(), m1.currentRevisionId(), 1L, 1L),
                reviseCandidate(
                        UUID.randomUUID(), 2, "REJECTED", "SUPERSEDE", "HIDE_PROPOSED", "HIDE",
                        null, null, actor, List.of(),
                        m2.memoryId(), m2.currentRevisionId(), 1L, 1L));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(UUID.randomUUID(), actor, "XIAOLIN", 1, "rejected perspective identity")),
                List.of(),
                candidates);
        assertEquals("CANONICAL_COMMITTED", coord.submit(request).status());

        String join = " FROM memory.candidate_set_member m "
                + "JOIN memory.proposal_revision pr ON pr.proposal_revision_id = m.proposal_revision_id "
                + "JOIN memory.proposal p ON p.proposal_id = pr.proposal_id "
                + "JOIN memory.decision d ON d.decision_id = m.decision_id "
                + "WHERE m.candidate_set_id = '" + setId + "' ";
        // rejected: USER_REJECT targets Proposal (proposal_id) at revisionNo=1
        assertEquals(2, count("SELECT count(*)" + join
                + "AND d.decision_kind = 'USER_REJECT' AND d.target_kind = 'PROPOSAL' "
                + "AND d.target_id = p.proposal_id AND d.target_revision_ref = 1"));
    }

    // ── 18. R1-04: all-rejected writes no evidence ────────────────────────

    @Test
    @Order(18)
    void allRejectedWritesNoEvidence() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        String body = "rejected perspective identity";

        List<Candidate> candidates = List.of(
                createCandidate(UUID.randomUUID(), 1, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE", null, null, actor, List.of()),
                createCandidate(UUID.randomUUID(), 2, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE", null, null, actor, List.of()));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, "XIAOLIN", 1, body)),
                List.of(),
                candidates);

        long srcBefore = count("SELECT count(*) FROM evidence.source");
        long unitBefore = count("SELECT count(*) FROM evidence.source_unit");
        long payloadBefore = count("SELECT count(*) FROM evidence.source_payload");
        long anchorBefore = count("SELECT count(*) FROM evidence.source_anchor");
        long policyBefore = count("SELECT count(*) FROM memory.access_policy");
        long filesBefore = filesCount();

        assertEquals("CANONICAL_COMMITTED", coord.submit(request).status());

        // governance facts still written
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
        assertEquals(2, count("SELECT count(*) FROM memory.candidate_set_member WHERE candidate_set_id='" + setId + "'"));
        assertEquals(2, count("SELECT count(*) FROM memory.decision d JOIN memory.candidate_set cs ON cs.review_session_id=d.review_session_id WHERE cs.candidate_set_id='" + setId + "' AND d.decision_kind='USER_REJECT'"));
        // no evidence side effects
        assertEquals(srcBefore, count("SELECT count(*) FROM evidence.source"));
        assertEquals(unitBefore, count("SELECT count(*) FROM evidence.source_unit"));
        assertEquals(payloadBefore, count("SELECT count(*) FROM evidence.source_payload"));
        assertEquals(anchorBefore, count("SELECT count(*) FROM evidence.source_anchor"));
        assertEquals(policyBefore, count("SELECT count(*) FROM memory.access_policy"));
        assertEquals(filesBefore, filesCount());
    }

    // ── 19. R1-04: rejected-only evidence actor not persisted ─────────────

    @Test
    @Order(19)
    void rejectedOnlyActorNotPersisted() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID acceptedActor = UUID.randomUUID();
        UUID rejectedPerspective = UUID.randomUUID();
        UUID rejectedOnlyActor = UUID.randomUUID();
        UUID acceptedUnit = UUID.randomUUID();
        UUID rejectedUnit = UUID.randomUUID();
        UUID rejectedPerspectiveUnit = UUID.randomUUID();
        UUID acceptedAnchor = UUID.randomUUID();
        UUID rejectedAnchor = UUID.randomUUID();
        String acceptedBody = "accepted evidence";
        String rejectedBody = "rejected-only evidence";

        List<Candidate> candidates = List.of(
                createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "accepted", "Claim", acceptedActor, List.of(acceptedAnchor)),
                createCandidate(
                        UUID.randomUUID(), 2, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        null, null, rejectedPerspective, List.of()));
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(
                        message(acceptedUnit, acceptedActor, 1, acceptedBody),
                        message(rejectedUnit, rejectedOnlyActor, 2, rejectedBody),
                        message(rejectedPerspectiveUnit, rejectedPerspective, 3, "rejected perspective")),
                List.of(
                        fullAnchor(acceptedAnchor, acceptedUnit, acceptedBody),
                        fullAnchor(rejectedAnchor, rejectedUnit, rejectedBody)),
                candidates);
        coord.submit(request);

        // accepted evidence actor + perspective actors present; rejected-only actor absent
        assertEquals(1, count("SELECT count(*) FROM memory.actor_ref WHERE actor_id='" + acceptedActor + "'"));
        assertEquals(1, count("SELECT count(*) FROM memory.actor_ref WHERE actor_id='" + rejectedPerspective + "'"));
        assertEquals(0, count("SELECT count(*) FROM memory.actor_ref WHERE actor_id='" + rejectedOnlyActor + "'"));
        // rejected-only unit/anchors never persisted
        assertEquals(0, count("SELECT count(*) FROM evidence.source_unit WHERE source_unit_id='" + rejectedUnit + "'"));
        assertEquals(0, count("SELECT count(*) FROM evidence.source_anchor WHERE anchor_id='" + rejectedAnchor + "'"));
    }

    // ── 20. R1-05: null/empty hash distinct ───────────────────────────────

    @Test
    @Order(20)
    void nullEmptyHashDistinct() {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "null empty hash evidence";
        List<EvidenceMessage> messages = List.of(message(unit, actor, 1, body));
        List<AnchorSpec> anchors = List.of(fullAnchor(anchor, unit, body));
        List<Candidate> candidates = List.of(createCandidate(
                UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)));

        var nullScope = new LocalV1CandidateSetRequest(
                setId, "key-null", new byte[32], threadId, null, 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors), candidates);
        var emptyScope = new LocalV1CandidateSetRequest(
                setId, "key-empty", new byte[32], threadId, "", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(messages, anchors), candidates);
        byte[] nullHash = LocalV1CandidateSetCanonicalizer.requestHash(nullScope);
        byte[] emptyHash = LocalV1CandidateSetCanonicalizer.requestHash(emptyScope);
        assertFalse(Arrays.equals(nullHash, emptyHash), "null scopeRef and empty scopeRef must hash differently");
    }

    // ── 21. R1-05: defensive copies prevent hash change ───────────────────

    @Test
    @Order(21)
    void defensiveCopyPreventsHashChange() {
        UUID setId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        byte[] requestHashInput = new byte[32];
        Arrays.fill(requestHashInput, (byte) 0x0a);
        byte[] bodyHashInput = sha("body");
        List<UUID> anchorIds = new ArrayList<>(List.of(anchor));

        var request = new LocalV1CandidateSetRequest(
                setId,
                "key",
                requestHashInput,
                threadId,
                "scope",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(new EvidenceMessage(unit, actor, "XIAOLIN", 1, "msg", OffsetDateTime.now(CLK), "body", bodyHashInput)),
                        List.of(new AnchorSpec(anchor, List.of(new AnchorUnit(unit, 0L, 4L, 1L))))),
                List.of(new Candidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "one", "Claim", actor, anchorIds, null, null, null, null, "reason")));

        byte[] before = LocalV1CandidateSetCanonicalizer.confirmationHash(request);
        byte[] originalRequestHash = request.requestHash();
        // mutate the original arrays/lists AFTER construction
        Arrays.fill(requestHashInput, (byte) 0x7f);
        Arrays.fill(bodyHashInput, (byte) 0x7f);
        anchorIds.clear();
        byte[] returnedHash = request.requestHash();
        Arrays.fill(returnedHash, (byte) 0x7f);
        byte[] after = LocalV1CandidateSetCanonicalizer.confirmationHash(request);

        assertArrayEquals(before, after, "hash must be unaffected by input mutation");
        assertArrayEquals(originalRequestHash, request.requestHash(), "accessor must return a fresh clone");
    }

    // ── 22. R1-05: input closure attacks ──────────────────────────────────

    @Test
    @Order(22)
    void inputClosureAttacks() {
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "input closure evidence";
        byte[] goodHash = sha(body);
        byte[] badHash = new byte[32];
        Arrays.fill(badHash, (byte) 1);

        // duplicate message id
        assertRequestRejected(new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body), message(unit, actor, 2, body)),
                        List.of(fullAnchor(anchor, unit, body))),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)))));

        // duplicate anchor id
        assertRequestRejected(new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)),
                        List.of(fullAnchor(anchor, unit, body), fullAnchor(anchor, unit, body))),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)))));

        // duplicate evidenceAnchorId within a candidate
        assertRequestRejected(new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)), List.of(fullAnchor(anchor, unit, body))),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor, anchor)))));

        // bodyHash mismatch
        assertRequestRejected(new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(new EvidenceMessage(unit, actor, "XIAOLIN", 1, "msg", OffsetDateTime.now(CLK), body, badHash)),
                        List.of(fullAnchor(anchor, unit, body))),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)))));

        // anchor references non-existent message
        assertRequestRejected(new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)),
                        List.of(fullAnchor(anchor, UUID.randomUUID(), body))),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor)))));
    }

    private void assertRequestRejected(LocalV1CandidateSetRequest request) {
        assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
    }

    // ── 23. R1-06: heterogeneous concurrency ──────────────────────────────

    @Test
    @Order(23)
    void heterogeneousConcurrentOneSuccessOneConflict() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String key = "key-" + setId;

        var req1 = seal(
                setId, key, UUID.randomUUID(),
                List.of(message(unit, actor, 1, "concurrent one")),
                List.of(fullAnchor(anchor, unit, "concurrent one")),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor))));
        var req2 = seal(
                setId, key, UUID.randomUUID(),
                List.of(message(unit, actor, 1, "concurrent two")),
                List.of(fullAnchor(anchor, unit, "concurrent two")),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "two", "Claim", actor, List.of(anchor))));

        CountDownLatch barrier = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger conflict = new AtomicInteger(0);
        AtomicInteger other = new AtomicInteger(0);
        ExecutorService exec = Executors.newFixedThreadPool(2);
        exec.submit(() -> runConcurrent(() -> coord.submit(req1), success, conflict, other, barrier, go));
        exec.submit(() -> runConcurrent(() -> coord.submit(req2), success, conflict, other, barrier, go));
        barrier.await();
        go.countDown();
        exec.shutdown();
        assertTrue(exec.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals(1, success.get(), "exactly one success");
        assertEquals(1, conflict.get(), "exactly one idempotency conflict");
        assertEquals(0, other.get(), "no other failures");
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
    }

    private void runConcurrent(
            Runnable runnable,
            AtomicInteger success,
            AtomicInteger conflict,
            AtomicInteger other,
            CountDownLatch barrier,
            CountDownLatch go) {
        try {
            barrier.countDown();
            go.await();
            runnable.run();
            success.incrementAndGet();
        } catch (LocalV1CandidateSetException e) {
            if (e.code() == LocalV1CandidateSetException.Code.IDEMPOTENCY_KEY_REUSED) {
                conflict.incrementAndGet();
            } else {
                other.incrementAndGet();
            }
        } catch (Exception e) {
            other.incrementAndGet();
        }
    }

    // ── 24. R1-06: four-way replay corruption ─────────────────────────────

    @Test
    @Order(24)
    void replayCorruptionFourWays() throws Exception {
        corruptMemberReplayFails();
        corruptDecisionReplayFails();
        corruptMappingReplayFails();
        corruptReceiptReplayFails();
    }

    private LocalV1CandidateSetRequest submitSingleAccepted() {
        UUID setId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "corruption evidence";
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(createCandidate(UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor))));
        coord.submit(request);
        return request;
    }

    private void corruptMemberReplayFails() throws Exception {
        var request = submitSingleAccepted();
        UUID setId = request.candidateSetId();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER candidate_evidence_mapping_immutable ON memory.candidate_evidence_mapping");
            s.execute("DROP TRIGGER candidate_set_member_immutable ON memory.candidate_set_member");
            s.execute("DELETE FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + setId + "'");
            s.execute("DELETE FROM memory.candidate_set_member WHERE candidate_set_id='" + setId + "'");
            s.execute("CREATE TRIGGER candidate_set_member_immutable BEFORE UPDATE OR DELETE ON memory.candidate_set_member FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            s.execute("CREATE TRIGGER candidate_evidence_mapping_immutable BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
        }
        LocalV1CandidateSetException ex = assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED, ex.code());
    }

    private void corruptDecisionReplayFails() throws Exception {
        var request = submitSingleAccepted();
        UUID setId = request.candidateSetId();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER candidate_evidence_mapping_immutable ON memory.candidate_evidence_mapping");
            s.execute("DROP TRIGGER candidate_set_member_immutable ON memory.candidate_set_member");
            s.execute("DROP TRIGGER change_event_immutable ON memory.change_event");
            s.execute("DROP TRIGGER decision_immutable ON memory.decision");
            s.execute("DELETE FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + setId + "'");
            s.execute("DELETE FROM memory.candidate_set_member WHERE candidate_set_id='" + setId + "'");
            s.execute("DELETE FROM runtime.outbox_event WHERE idempotency_key LIKE 'cs-ob-" + setId + "-%'");
            s.execute("DELETE FROM memory.change_event WHERE decision_id IN (SELECT decision_id FROM memory.decision WHERE idempotency_key LIKE 'cs-verdict-" + setId + "-%')");
            s.execute("DELETE FROM memory.decision WHERE idempotency_key LIKE 'cs-verdict-" + setId + "-%'");
            s.execute("CREATE TRIGGER decision_immutable BEFORE UPDATE OR DELETE ON memory.decision FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            s.execute("CREATE TRIGGER change_event_immutable BEFORE UPDATE OR DELETE ON memory.change_event FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            s.execute("CREATE TRIGGER candidate_set_member_immutable BEFORE UPDATE OR DELETE ON memory.candidate_set_member FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
            s.execute("CREATE TRIGGER candidate_evidence_mapping_immutable BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
        }
        LocalV1CandidateSetException ex = assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED, ex.code());
    }

    private void corruptMappingReplayFails() throws Exception {
        var request = submitSingleAccepted();
        UUID setId = request.candidateSetId();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER candidate_evidence_mapping_immutable ON memory.candidate_evidence_mapping");
            s.execute("DELETE FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + setId + "'");
            s.execute("CREATE TRIGGER candidate_evidence_mapping_immutable BEFORE UPDATE OR DELETE ON memory.candidate_evidence_mapping FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
        }
        LocalV1CandidateSetException ex = assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(LocalV1CandidateSetException.Code.CANONICAL_COMMIT_FAILED, ex.code());
    }

    private void corruptReceiptReplayFails() throws Exception {
        var request = submitSingleAccepted();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER idempotency_receipt_immutable ON runtime.idempotency_receipt");
            s.execute("DELETE FROM runtime.idempotency_receipt WHERE idempotency_key='" + request.idempotencyKey() + "'");
            s.execute("CREATE TRIGGER idempotency_receipt_immutable BEFORE UPDATE OR DELETE ON runtime.idempotency_receipt FOR EACH ROW EXECUTE FUNCTION memory.reject_immutable_mutation()");
        }
        assertThrows(Exception.class, () -> coord.submit(request));
    }

    // ── 25. R1-03/R1-06: direct-SQL closure attacks ───────────────────────

    @Test
    @Order(25)
    void directSqlClosureAttacks() throws Exception {
        rootOnlyRejected();
        reviewReuseRejected();
        memberDecisionMismatchRejected();
        actionBindingMismatchRejected();
    }

    private void insertReviewOutbox(Statement s, UUID decisionId, UUID reviewId, UUID actorId, String hh)
            throws SQLException {
        UUID ceId = UUID.randomUUID();
        s.execute("INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('"
                + ceId + "','review.decisions-committed.v1','" + actorId + "','REVIEW_SESSION','" + reviewId + "',1,'"
                + decisionId + "',clock_timestamp())");
        s.execute("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"
                + UUID.randomUUID() + "','ob-" + decisionId
                + "','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','" + reviewId
                + "',1,'pink.event.v1','REVIEW_SYNC',1,decode('" + hh
                + "','hex'),'{\"aggregateId\":\"" + reviewId
                + "\",\"aggregateRevision\":1,\"policyRevision\":1,\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""
                + hh + "\"}','" + ceId + "','READY',clock_timestamp(),0,8,clock_timestamp())");
    }

    private void rootOnlyRejected() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        String hh = "00".repeat(32);
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('"
                    + reviewId + "','COMPLETED','rk-" + reviewId + "',decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.candidate_set(candidate_set_id,review_session_id,thread_id,set_version,confirmation_hash,request_hash,created_at,confirmed_at) VALUES ('"
                    + setId + "','" + reviewId + "','" + UUID.randomUUID() + "',1,decode('" + hh + "','hex'),decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            SQLException ex = assertThrows(SQLException.class, c::commit);
            assertEquals("23514", ex.getSQLState());
            assertTrue(ex.getMessage().contains("HDM020_CANDIDATE_SET_SIZE_INVALID"));
            c.rollback();
        }
    }

    private void reviewReuseRejected() throws Exception {
        UUID reviewId = UUID.randomUUID();
        UUID setId1 = UUID.randomUUID();
        UUID setId2 = UUID.randomUUID();
        String hh = "00".repeat(32);
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('"
                    + reviewId + "','COMPLETED','rk-" + reviewId + "',decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.candidate_set(candidate_set_id,review_session_id,thread_id,set_version,confirmation_hash,request_hash,created_at,confirmed_at) VALUES ('"
                    + setId1 + "','" + reviewId + "','" + UUID.randomUUID() + "',1,decode('" + hh + "','hex'),decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            SQLException ex = assertThrows(SQLException.class, () -> s.execute(
                    "INSERT INTO memory.candidate_set(candidate_set_id,review_session_id,thread_id,set_version,confirmation_hash,request_hash,created_at,confirmed_at) VALUES ('"
                            + setId2 + "','" + reviewId + "','" + UUID.randomUUID()
                            + "',1,decode('" + hh + "','hex'),decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())"));
            assertEquals("23505", ex.getSQLState());
            c.rollback();
        }
    }

    private void memberDecisionMismatchRejected() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String hh = "00".repeat(32);
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('"
                    + reviewId + "','COMPLETED','rk-" + reviewId + "',decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('"
                    + actorId + "','SYNTHETIC','a-" + actorId + "',clock_timestamp())");
            s.execute("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('"
                    + proposalId + "','CREATE',NULL,clock_timestamp())");
            s.execute("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,perspective_actor_id,created_at) VALUES ('"
                    + revisionId + "','" + proposalId + "',1,'PUBLISH','t','Claim','" + actorId + "',clock_timestamp())");
            s.execute("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('"
                    + reviewId + "','" + revisionId + "',1)");
            // decision kind USER_REJECT but member disposition ACCEPTED → mismatch
            s.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('"
                    + decisionId + "','USER_REJECT','" + actorId + "','HUMAN','" + revisionId + "','" + reviewId
                    + "','PROPOSAL','" + proposalId + "',1,'x','dv-" + decisionId + "',clock_timestamp())");
            insertReviewOutbox(s, decisionId, reviewId, actorId, hh);
            s.execute("INSERT INTO memory.candidate_set(candidate_set_id,review_session_id,thread_id,set_version,confirmation_hash,request_hash,created_at,confirmed_at) VALUES ('"
                    + setId + "','" + reviewId + "','" + UUID.randomUUID() + "',1,decode('" + hh + "','hex'),decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.candidate_set_member(candidate_set_id,candidate_id,ordinal,proposal_revision_id,decision_id,disposition,action,origin_kind,final_author_kind,future_memory_id) VALUES ('"
                    + setId + "','" + candidateId + "',1,'" + revisionId + "','" + decisionId
                    + "','ACCEPTED','CREATE','HIDE_PROPOSED','HIDE','" + UUID.randomUUID() + "')");
            SQLException ex = assertThrows(SQLException.class, c::commit);
            assertEquals("23514", ex.getSQLState());
            assertTrue(ex.getMessage().contains("HDM020_MEMBER_DECISION_BINDING_MISMATCH"));
            c.rollback();
        }
    }

    private void actionBindingMismatchRejected() throws Exception {
        UUID setId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID candidateId = UUID.randomUUID();
        String hh = "00".repeat(32);
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('"
                    + reviewId + "','COMPLETED','rk-" + reviewId + "',decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('"
                    + actorId + "','SYNTHETIC','a-" + actorId + "',clock_timestamp())");
            // proposal_kind = REVISE but member action = CREATE → mismatch
            s.execute("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('"
                    + proposalId + "','REVISE',NULL,clock_timestamp())");
            s.execute("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,perspective_actor_id,created_at) VALUES ('"
                    + revisionId + "','" + proposalId + "',1,'PUBLISH','t','Claim','" + actorId + "',clock_timestamp())");
            s.execute("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('"
                    + reviewId + "','" + revisionId + "',1)");
            s.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('"
                    + decisionId + "','USER_CONFIRM','" + actorId + "','HUMAN','" + revisionId + "','" + reviewId
                    + "','MEMORY','" + UUID.randomUUID() + "',1,'x','dv-" + decisionId + "',clock_timestamp())");
            insertReviewOutbox(s, decisionId, reviewId, actorId, hh);
            s.execute("INSERT INTO memory.candidate_set(candidate_set_id,review_session_id,thread_id,set_version,confirmation_hash,request_hash,created_at,confirmed_at) VALUES ('"
                    + setId + "','" + reviewId + "','" + UUID.randomUUID() + "',1,decode('" + hh + "','hex'),decode('" + hh + "','hex'),clock_timestamp(),clock_timestamp())");
            s.execute("INSERT INTO memory.candidate_set_member(candidate_set_id,candidate_id,ordinal,proposal_revision_id,decision_id,disposition,action,origin_kind,final_author_kind,future_memory_id) VALUES ('"
                    + setId + "','" + candidateId + "',1,'" + revisionId + "','" + decisionId
                    + "','ACCEPTED','CREATE','HIDE_PROPOSED','HIDE','" + UUID.randomUUID() + "')");
            SQLException ex = assertThrows(SQLException.class, c::commit);
            assertEquals("23514", ex.getSQLState());
            assertTrue(ex.getMessage().contains("HDM020_MEMBER_ACTION_BINDING_MISMATCH"));
            c.rollback();
        }
    }

    // ── 26. R2-01: empty/malformed anchor attacks ─────────────────────────

    @Test
    @Order(26)
    void anchorStructureAttacks() {
        UUID unit = UUID.randomUUID();
        UUID unit2 = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String body = "anchor attack body";
        // zero-unit / null-units anchor
        assertAnchorRejected(unit, actor, body, List.of());
        assertAnchorRejected(unit, actor, body, null);
        // unit ordinal duplicate / gap / descending — two DISTINCT sourceUnitIds (single-factor)
        assertOrdinalRejected(unit, unit2, actor, body, List.of(new AnchorUnit(unit, 0L, 2L, 1L), new AnchorUnit(unit2, 0L, 2L, 1L)));
        assertOrdinalRejected(unit, unit2, actor, body, List.of(new AnchorUnit(unit, 0L, 2L, 1L), new AnchorUnit(unit2, 0L, 2L, 3L)));
        assertOrdinalRejected(unit, unit2, actor, body, List.of(new AnchorUnit(unit, 0L, 2L, 2L), new AnchorUnit(unit2, 0L, 2L, 1L)));
        // duplicate sourceUnitId within one anchor (independent attack)
        assertAnchorRejected(unit, actor, body, List.of(new AnchorUnit(unit, 0L, 2L, 1L), new AnchorUnit(unit, 0L, 2L, 2L)));
        // null unit
        assertAnchorRejected(unit, actor, body, Arrays.asList(new AnchorUnit(unit, 0L, 2L, 1L), null));
        // offset single-null / negative / from==to / to>bodyLen
        assertAnchorRejected(unit, actor, body, List.of(new AnchorUnit(unit, 0L, null, 1L)));
        assertAnchorRejected(unit, actor, body, List.of(new AnchorUnit(unit, -1L, 2L, 1L)));
        assertAnchorRejected(unit, actor, body, List.of(new AnchorUnit(unit, 2L, 2L, 1L)));
        assertAnchorRejected(unit, actor, body, List.of(new AnchorUnit(unit, 0L, 999L, 1L)));
    }

    private void assertAnchorRejected(UUID unit, UUID actor, String body, List<AnchorUnit> units) {
        UUID anchor = UUID.randomUUID();
        var request = new LocalV1CandidateSetRequest(
                UUID.randomUUID(),
                "k",
                new byte[32],
                UUID.randomUUID(),
                "s",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)),
                        List.of(new AnchorSpec(anchor, units))),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor))));
        assertRequestRejected(request);
    }

    private void assertOrdinalRejected(UUID unit1, UUID unit2, UUID actor, String body, List<AnchorUnit> units) {
        UUID anchor = UUID.randomUUID();
        var request = new LocalV1CandidateSetRequest(
                UUID.randomUUID(),
                "k",
                new byte[32],
                UUID.randomUUID(),
                "s",
                1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit1, actor, 1, body), message(unit2, actor, 2, body)),
                        List.of(new AnchorSpec(anchor, units))),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor))));
        assertRequestRejected(request);
    }

    // ── 27. R2-02: accepted perspective actor binding ─────────────────────

    @Test
    @Order(27)
    void acceptedPerspectiveActorBinding() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID unitA = UUID.randomUUID();
        UUID unitB = UUID.randomUUID();
        UUID anchorA = UUID.randomUUID();
        UUID anchorB = UUID.randomUUID();
        String body = "actor binding body";

        // perspective actor only in the pool (message B), not in this candidate's anchor A
        var poolOnly = new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unitA, actorA, 1, body), message(unitB, actorB, 2, body)),
                        List.of(fullAnchor(anchorA, unitA, body), fullAnchor(anchorB, unitB, body))),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actorB, List.of(anchorA))));
        assertRequestRejected(poolOnly);

        // perspective actor only in ANOTHER candidate's evidence
        var otherCandidate = new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unitA, actorA, 1, body), message(unitB, actorB, 2, body)),
                        List.of(fullAnchor(anchorA, unitA, body), fullAnchor(anchorB, unitB, body))),
                List.of(
                        createCandidate(
                                UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actorA, List.of(anchorA)),
                        createCandidate(
                                UUID.randomUUID(), 2, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "two", "Claim", actorA, List.of(anchorB))));
        assertRequestRejected(otherCandidate);

        // positive: perspective actor appears in this candidate's own evidence
        var positive = new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unitA, actorA, 1, body)),
                        List.of(fullAnchor(anchorA, unitA, body))),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actorA, List.of(anchorA))));
        byte[] requestHash = LocalV1CandidateSetCanonicalizer.requestHash(positive);
        byte[] confirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(positive);
        var positiveSealed = new LocalV1CandidateSetRequest(
                positive.candidateSetId(), positive.idempotencyKey(), requestHash, positive.threadId(),
                positive.scopeRef(), positive.setVersion(), new FinalConfirmation("CONFIRM_SET", 1, confirmationHash),
                positive.evidencePool(), positive.candidates());
        assertEquals("CANONICAL_COMMITTED", coord.submit(positiveSealed).status());
    }

    // ── 28. R2-03: mapping ordinal contiguity ─────────────────────────────

    @Test
    @Order(28)
    void mappingOrdinalContiguity() throws Exception {
        // legal multi-mapping 1,2,3 via coordinator (candidate references 3 anchors)
        UUID actor = UUID.randomUUID();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        UUID u3 = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        UUID a3 = UUID.randomUUID();
        String body = "mapping ordinal evidence";
        var multi = seal(
                UUID.randomUUID(),
                "key-multi",
                UUID.randomUUID(),
                List.of(message(u1, actor, 1, body), message(u2, actor, 2, body), message(u3, actor, 3, body)),
                List.of(fullAnchor(a1, u1, body), fullAnchor(a2, u2, body), fullAnchor(a3, u3, body)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(a1, a2, a3))));
        assertEquals("CANONICAL_COMMITTED", coord.submit(multi).status());
        UUID multiSetId = multi.candidateSetId();
        assertEquals(3, count("SELECT count(*) FROM memory.candidate_evidence_mapping WHERE candidate_set_id='" + multiSetId + "'"));

        // direct SQL gap attack: valid accepted set (1 anchor), then insert a mapping with ordinal 3
        UUID setId = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        UUID actor2 = UUID.randomUUID();
        String body2 = "mapping gap evidence";
        var single = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor2, 1, body2)),
                List.of(fullAnchor(anchor, unit, body2)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor2, List.of(anchor))));
        coord.submit(single);
        UUID candidateId = uuid("SELECT candidate_id FROM memory.candidate_set_member WHERE candidate_set_id='" + setId + "' AND ordinal=1");
        UUID sourceId = uuid("SELECT source_id FROM evidence.source WHERE external_ref='candidate-set:" + setId + "'");
        UUID anchor2 = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("INSERT INTO evidence.source_anchor(anchor_id,source_id,anchor_kind,created_at) VALUES ('"
                    + anchor2 + "','" + sourceId + "','MESSAGE_SEGMENT',clock_timestamp())");
            s.execute("INSERT INTO memory.candidate_evidence_mapping(candidate_set_id,candidate_id,ordinal,anchor_id) VALUES ('"
                    + setId + "','" + candidateId + "',3,'" + anchor2 + "')");
            SQLException ex = assertThrows(SQLException.class, c::commit);
            assertEquals("23514", ex.getSQLState());
            assertTrue(ex.getMessage().contains("HDM020_MAPPING_ORDINAL_NOT_CONTIGUOUS"));
            c.rollback();
        }
    }

    // ── 29. R2A: AnchorSpec defensive-copy mutation judge ─────────────────

    @Test
    @Order(29)
    void anchorSpecDefensiveCopyMutationJudge() {
        UUID unit = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        String body = "mutation judge body";

        // 1. mutating the original units list after construction must not change the request hash
        List<AnchorUnit> mutableUnits = new ArrayList<>(List.of(new AnchorUnit(unit, 0L, 4L, 1L)));
        AnchorSpec anchor = new AnchorSpec(UUID.randomUUID(), mutableUnits);
        var request = new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)), List.of(anchor)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(anchor.anchorId()))));
        byte[] before = LocalV1CandidateSetCanonicalizer.requestHash(request);
        mutableUnits.clear();
        byte[] after = LocalV1CandidateSetCanonicalizer.requestHash(request);
        assertArrayEquals(before, after, "mutating the original units list must not change the request hash");
        assertEquals(1, anchor.units().size(), "anchor units must be a defensive copy");

        // 2. units() must be unmodifiable
        assertThrows(UnsupportedOperationException.class, () -> anchor.units().clear());
        assertThrows(UnsupportedOperationException.class, () -> anchor.units().add(new AnchorUnit(unit, 0L, 4L, 1L)));

        // 3. null unit still maps to REQUEST_SCHEMA_INVALID (not a construction NPE)
        AnchorSpec nullUnitAnchor =
                new AnchorSpec(UUID.randomUUID(), Arrays.asList(new AnchorUnit(unit, 0L, 4L, 1L), null));
        var nullUnitRequest = new LocalV1CandidateSetRequest(
                UUID.randomUUID(), "k", new byte[32], UUID.randomUUID(), "s", 1,
                new FinalConfirmation("CONFIRM_SET", 1, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(
                        List.of(message(unit, actor, 1, body)), List.of(nullUnitAnchor)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE", "one", "Claim", actor, List.of(nullUnitAnchor.anchorId()))));
        assertRequestRejected(nullUnitRequest);
    }

    // ── 30. same thread actor identity is reusable across candidate sets ───

    @Test
    @Order(30)
    void sameActorIdentityAcrossCandidateSetsIsReusedExactly() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        UUID firstSet = UUID.randomUUID();
        UUID firstUnit = UUID.randomUUID();
        UUID firstAnchor = UUID.randomUUID();
        String firstBody = "same-thread first candidate set";
        var first = seal(
                firstSet,
                "key-" + firstSet,
                threadId,
                List.of(message(firstUnit, actor, 1, firstBody)),
                List.of(fullAnchor(firstAnchor, firstUnit, firstBody)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "first memory", "Claim", actor, List.of(firstAnchor))));

        UUID secondSet = UUID.randomUUID();
        UUID secondUnit = UUID.randomUUID();
        UUID secondAnchor = UUID.randomUUID();
        String secondBody = "same-thread second candidate set";
        var second = seal(
                secondSet,
                "key-" + secondSet,
                threadId,
                List.of(message(secondUnit, actor, 2, secondBody)),
                List.of(fullAnchor(secondAnchor, secondUnit, secondBody)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "second memory", "Claim", actor, List.of(secondAnchor))));

        assertEquals("CANONICAL_COMMITTED", coord.submit(first).status());
        assertEquals("CANONICAL_COMMITTED", coord.submit(second).status());
        assertEquals(1, count("SELECT count(*) FROM memory.actor_ref WHERE actor_id='" + actor + "'"));
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + firstSet + "'"));
        assertEquals(1, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + secondSet + "'"));
    }

    // ── 31. actor id collision with different identity fails closed ────────

    @Test
    @Order(31)
    void actorIdentityCollisionIsRejectedWithoutBatchFacts() throws Exception {
        UUID actor = UUID.randomUUID();
        mp.insertActorRef(new ActorRef(
                actor, "SYNTHETIC", "foreign-" + actor, "小林", OffsetDateTime.now(CLK)));

        UUID setId = UUID.randomUUID();
        UUID unit = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body = "actor collision evidence";
        var request = seal(
                setId,
                "key-" + setId,
                UUID.randomUUID(),
                List.of(message(unit, actor, 1, body)),
                List.of(fullAnchor(anchor, unit, body)),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "collision memory", "Claim", actor, List.of(anchor))));
        long filesBefore = filesCount();

        LocalV1CandidateSetException failure =
                assertThrows(LocalV1CandidateSetException.class, () -> coord.submit(request));
        assertEquals(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID, failure.code());
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + setId + "'"));
        assertEquals(filesBefore, filesCount());
        assertEquals(
                "foreign-" + actor,
                scalar("SELECT stable_ref FROM memory.actor_ref WHERE actor_id='" + actor + "'"));
    }

    // ── 32. speaker role is independent from candidate perspective ──────

    @Test
    @Order(32)
    void speakerRoleDeterminesLabelsAcrossBothPerspectives() throws Exception {
        UUID xiaolinActor = UUID.randomUUID();
        UUID hideActor = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();

        UUID firstSet = UUID.randomUUID();
        UUID firstXiaolinUnit = UUID.randomUUID();
        UUID firstHideUnit = UUID.randomUUID();
        UUID firstAnchor = UUID.randomUUID();
        String xiaolinBody = "小林证据";
        String hideBody = "hide证据";
        AnchorSpec firstAnchorSpec = new AnchorSpec(
                firstAnchor,
                List.of(
                        new AnchorUnit(firstXiaolinUnit, 0L, (long) xiaolinBody.length(), 1L),
                        new AnchorUnit(firstHideUnit, 0L, (long) hideBody.length(), 2L)));
        var hidePerspective = seal(
                firstSet,
                "key-" + firstSet,
                threadId,
                List.of(
                        message(firstXiaolinUnit, xiaolinActor, "XIAOLIN", 1, xiaolinBody),
                        message(firstHideUnit, hideActor, "HIDE", 2, hideBody)),
                List.of(firstAnchorSpec),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "hide perspective", "Claim", hideActor, List.of(firstAnchor))));

        UUID secondSet = UUID.randomUUID();
        UUID secondXiaolinUnit = UUID.randomUUID();
        UUID secondHideUnit = UUID.randomUUID();
        UUID secondAnchor = UUID.randomUUID();
        AnchorSpec secondAnchorSpec = new AnchorSpec(
                secondAnchor,
                List.of(
                        new AnchorUnit(secondXiaolinUnit, 0L, (long) xiaolinBody.length(), 1L),
                        new AnchorUnit(secondHideUnit, 0L, (long) hideBody.length(), 2L)));
        var xiaolinPerspective = seal(
                secondSet,
                "key-" + secondSet,
                threadId,
                List.of(
                        message(secondXiaolinUnit, xiaolinActor, "XIAOLIN", 3, xiaolinBody),
                        message(secondHideUnit, hideActor, "HIDE", 4, hideBody)),
                List.of(secondAnchorSpec),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "xiaolin perspective", "Claim", xiaolinActor, List.of(secondAnchor))));

        assertEquals("CANONICAL_COMMITTED", coord.submit(hidePerspective).status());
        assertEquals("CANONICAL_COMMITTED", coord.submit(xiaolinPerspective).status());
        assertEquals("小林", scalar(
                "SELECT display_label FROM memory.actor_ref WHERE actor_id='" + xiaolinActor + "'"));
        assertEquals("hide", scalar(
                "SELECT display_label FROM memory.actor_ref WHERE actor_id='" + hideActor + "'"));
    }

    // ── 33. conflicting role and missing perspective role fail closed ────

    @Test
    @Order(33)
    void conflictingRoleAndUnboundPerspectiveAreRejectedBeforeFacts() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID unit1 = UUID.randomUUID();
        UUID unit2 = UUID.randomUUID();
        UUID anchor = UUID.randomUUID();
        String body1 = "role one";
        String body2 = "role two";
        AnchorSpec anchorSpec = new AnchorSpec(
                anchor,
                List.of(
                        new AnchorUnit(unit1, 0L, (long) body1.length(), 1L),
                        new AnchorUnit(unit2, 0L, (long) body2.length(), 2L)));

        UUID conflictingSet = UUID.randomUUID();
        var conflicting = seal(
                conflictingSet,
                "key-" + conflictingSet,
                UUID.randomUUID(),
                List.of(
                        message(unit1, actor, "XIAOLIN", 1, body1),
                        message(unit2, actor, "HIDE", 2, body2)),
                List.of(anchorSpec),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "ACCEPTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        "conflicting role", "Claim", actor, List.of(anchor))));
        assertRequestRejected(conflicting);
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + conflictingSet + "'"));

        UUID unboundSet = UUID.randomUUID();
        UUID unboundPerspective = UUID.randomUUID();
        var unbound = seal(
                unboundSet,
                "key-" + unboundSet,
                UUID.randomUUID(),
                List.of(message(UUID.randomUUID(), actor, "XIAOLIN", 1, body1)),
                List.of(),
                List.of(createCandidate(
                        UUID.randomUUID(), 1, "REJECTED", "CREATE", "HIDE_PROPOSED", "HIDE",
                        null, null, unboundPerspective, List.of())));
        assertRequestRejected(unbound);
        assertEquals(0, count("SELECT count(*) FROM memory.candidate_set WHERE candidate_set_id='" + unboundSet + "'"));
    }

    // ── raw helpers ───────────────────────────────────────────────────────

    private String scalar(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private UUID uuid(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }
}
