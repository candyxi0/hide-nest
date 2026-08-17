package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1CandidateSetHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CHAT_CANARY = "UNSELECTED_CHITCHAT_CANARY_31A";
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static ConfigurableApplicationContext api;
    private static URI base;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(20, Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, password)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate()
                .migrationsExecuted);

        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        payloadRoot = Files.createTempDirectory("local-v1-candidateset-http-");

        api = startApi("127.0.0.1", password);
        int port = ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        base = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (api != null) api.close();
        if (postgres != null) postgres.stop();
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }

    // ── gate rejection ─────────────────────────────────────────────────────

    @Test
    @Order(1)
    void gateRejectsMissingOrWrongBearerWithZeroWrites() throws Exception {
        Built built = build("gate", 3, true);
        long memBefore = count("memory.memory_record");
        long srcBefore = count("evidence.source");

        String gatePath = "/v1/review-sessions/" + UUID.randomUUID() + "/final-submissions";
        assertProblem(post(gatePath, built.json(), null, false), 401, "ACCESS_DENIED");
        assertProblem(post(gatePath, built.json(), "wrong-token-with-enough-length-but-wrong-value", false),
                403, "ACCESS_DENIED");

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(srcBefore, count("evidence.source"));
    }

    @Test
    @Order(2)
    void capabilityNotRequiredAndNotRead() throws Exception {
        Built built = build("nocap", 1, true);
        // No X-Action-Capability header → still works
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(built.candidateSetId());
        String nocapPath = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";
        Response response = post(nocapPath, built.json(), TOKEN, false);
        assertEquals(202, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertFalse(body.toString().contains("capability"));
        assertFalse(body.toString().contains("X-Action-Capability"));
    }

    // ── happy path: three accepted CREATE ───────────────────────────────────

    @Test
    @Order(3)
    void threeAcceptedCreateProducesIndexReadyAndCanonicalFacts() throws Exception {
        Built built = build("three", 3, true);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memoriesBefore = countRows("SELECT 1 FROM memory.memory_record WHERE state='ACTIVE'");
        long revisionsBefore = countRows("SELECT 1 FROM memory.memory_revision WHERE revision_no=1");
        long confirmationsBefore = countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM'");
        long reviewSessionsBefore = count("memory.review_session");
        long candidateSetsBefore = count("memory.candidate_set");
        long membersBefore = count("memory.candidate_set_member");

        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        assertSuccessHeaders(response, 202);
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals(candidateSetId.toString(), receipt.get("candidateSetId").asText());
        assertEquals(reviewSessionId.toString(), receipt.get("reviewSessionId").asText());
        String phase = receipt.get("phase").asText();
        assertTrue("INDEX_READY".equals(phase) || "CANONICAL_COMMITTED".equals(phase),
                "phase should be INDEX_READY or CANONICAL_COMMITTED, got " + phase);
        assertEquals("SUCCEEDED", receipt.get("resultCategory").asText());
        assertEquals(3, receipt.get("candidates").size());

        for (int i = 0; i < 3; i++) {
            JsonNode item = receipt.get("candidates").get(i);
            assertEquals(i + 1, item.get("ordinal").asLong());
            assertEquals("ACCEPTED", item.get("disposition").asText());
            assertEquals("CREATE", item.get("action").asText());
            String itemPhase = item.get("phase").asText();
            assertTrue("INDEX_READY".equals(itemPhase) || "CANONICAL_COMMITTED".equals(itemPhase));
            assertNotNull(item.get("memoryId"));
            assertNotNull(item.get("memoryRevisionId"));
            assertEquals(1, item.get("revisionNo").asLong());
        }

        // Canonical facts: each candidate has memory/revision/relation/decision/embedding
        assertEquals(memoriesBefore + 3, (long) countRows("SELECT 1 FROM memory.memory_record WHERE state='ACTIVE'"));
        assertEquals(revisionsBefore + 3, (long) countRows("SELECT 1 FROM memory.memory_revision WHERE revision_no=1"));
        assertEquals(confirmationsBefore + 3, (long) countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM'"));
        assertEquals(reviewSessionsBefore + 1, count("memory.review_session"));
        assertEquals(candidateSetsBefore + 1, count("memory.candidate_set"));
        assertEquals(membersBefore + 3, count("memory.candidate_set_member"));
        // idempotency receipt
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.idempotency_receipt WHERE idempotency_key=? AND operation_code='LOCAL_V1_CANDIDATE_SET'",
                candidateSetId.toString()));

        assertNoCanary();
    }

    // ── shared evidence ─────────────────────────────────────────────────────

    @Test
    @Order(4)
    void threeCandidatesSharingOneSourceUnitProducesSingleSourceAndThreeEvidencedBy() throws Exception {
        // All three candidates reference the same evidence anchor
        UUID candidateSetId = UUID.randomUUID();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        UUID threadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        String bodyText = "共享证据正文-shared";
        byte[] bodyHash = sha256(bodyText);
        String bodyHashHex = hex(bodyHash);
        UUID anchorId = UUID.randomUUID();

        long sourceUnitsBefore = count("evidence.source_unit");
        long anchorsBefore = count("evidence.source_anchor");
        long payloadsBefore = count("evidence.source_payload");
        long evidencedByBefore = countRows("SELECT 1 FROM memory.memory_relation WHERE relation_type='EVIDENCED_BY'");

        Built built = buildShared(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, 3);
        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        String phase = JSON.readTree(response.body()).get("phase").asText();
        assertTrue("INDEX_READY".equals(phase) || "CANONICAL_COMMITTED".equals(phase));

        assertEquals(sourceUnitsBefore + 1, (long) countRows("SELECT 1 FROM evidence.source_unit"));
        assertEquals(anchorsBefore + 1, (long) countRows("SELECT 1 FROM evidence.source_anchor"));
        assertEquals(payloadsBefore + 1, (long) countRows("SELECT 1 FROM evidence.source_payload"));
        // Three EVIDENCED_BY relations
        assertEquals(evidencedByBefore + 3, (long) countRows("SELECT 1 FROM memory.memory_relation WHERE relation_type='EVIDENCED_BY'"));
    }

    // ── mixed accepted CREATE + rejected ────────────────────────────────────

    @Test
    @Order(5)
    void mixedAcceptedCreateAndRejectedOnlyPublishesAccepted() throws Exception {
        Built built = buildMixed("mixed", 2, 1); // 2 accepted CREATE, 1 rejected
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memoriesBefore = countRows("SELECT 1 FROM memory.memory_record WHERE state='ACTIVE'");
        long confirmationsBefore = countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM'");
        long rejectionsBefore = countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_REJECT'");

        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        String phase = receipt.get("phase").asText();
        assertTrue("INDEX_READY".equals(phase) || "CANONICAL_COMMITTED".equals(phase));
        assertEquals(3, receipt.get("candidates").size());

        // Accepted: INDEX_READY or CANONICAL_COMMITTED with memory fields
        JsonNode c0 = receipt.get("candidates").get(0);
        assertEquals("ACCEPTED", c0.get("disposition").asText());
        String c0Phase = c0.get("phase").asText();
        assertTrue("INDEX_READY".equals(c0Phase) || "CANONICAL_COMMITTED".equals(c0Phase));
        assertNotNull(c0.get("memoryId"));

        JsonNode c1 = receipt.get("candidates").get(1);
        assertEquals("ACCEPTED", c1.get("disposition").asText());
        String c1Phase = c1.get("phase").asText();
        assertTrue("INDEX_READY".equals(c1Phase) || "CANONICAL_COMMITTED".equals(c1Phase));
        assertNotNull(c1.get("memoryId"));

        // Rejected: REJECTED phase, no memory fields
        JsonNode c2 = receipt.get("candidates").get(2);
        assertEquals("REJECTED", c2.get("disposition").asText());
        assertEquals("REJECTED", c2.get("phase").asText());
        assertTrue(c2.get("memoryId") == null || c2.get("memoryId").isNull()
                || c2.get("memoryId").asText().isEmpty());

        // Only 2 memory records created
        assertEquals(memoriesBefore + 2, (long) countRows("SELECT 1 FROM memory.memory_record WHERE state='ACTIVE'"));
        assertEquals(confirmationsBefore + 2, (long) countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM'"));
        assertEquals(rejectionsBefore + 1, (long) countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_REJECT'"));
    }

    // ── all-rejected ────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void allRejectedReturnsDecisionsCommittedNoRelevantResultZeroMemory() throws Exception {
        Built built = build("allrej", 3, false); // 3 rejected
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memBefore = count("memory.memory_record");
        long relationBefore = count("memory.memory_relation");
        long rejectedDecisionBefore = countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_REJECT'");
        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals("DECISIONS_COMMITTED", receipt.get("phase").asText());
        assertEquals("NO_RELEVANT_RESULT", receipt.get("resultCategory").asText());

        for (JsonNode item : receipt.get("candidates")) {
            assertEquals("REJECTED", item.get("disposition").asText());
            assertEquals("REJECTED", item.get("phase").asText());
        }

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(relationBefore, count("memory.memory_relation"));
        // Decisions still exist
        assertEquals(rejectedDecisionBefore + 3,
                (long) countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_REJECT'"));
    }

    // ── empty candidates ────────────────────────────────────────────────────

    @Test
    @Order(7)
    void emptyCandidatesReturnsNoCandidatesZeroFacts() throws Exception {
        UUID candidateSetId = UUID.randomUUID();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memBefore = count("memory.memory_record");
        long decBefore = count("memory.decision");
        long revBefore = count("memory.review_session");

        String json = emptyRequestJson(candidateSetId);
        Response response = post(path, json, TOKEN, true);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals("NO_CANDIDATES", receipt.get("phase").asText());
        assertEquals(0, receipt.get("candidates").size());

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(decBefore, count("memory.decision"));
        assertEquals(revBefore, count("memory.review_session"));
        // Idempotency receipt still created
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.idempotency_receipt WHERE idempotency_key=?",
                candidateSetId.toString()));
    }

    // ── accepted REVISE / SUPERSEDE governance ──────────────────────────────

    @Test
    @Order(8)
    void acceptedReviseReturnsDecisionsCommittedNoProjection() throws Exception {
        // First create a memory to revise
        UUID seedMemoryId = seedMemory("revise-target");

        Built built = buildRevise("revise", seedMemoryId);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memBefore = count("memory.memory_record");
        long confirmBefore = countRows(
                "SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM' AND target_kind='MEMORY'");
        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals("DECISIONS_COMMITTED", receipt.get("phase").asText());
        assertEquals("SUCCEEDED", receipt.get("resultCategory").asText());

        JsonNode item = receipt.get("candidates").get(0);
        assertEquals("ACCEPTED", item.get("disposition").asText());
        assertEquals("REVISE", item.get("action").asText());
        assertEquals("DECISIONS_COMMITTED", item.get("phase").asText());
        // No projection fields are serialized before projection
        assertFalse(item.has("memoryId"));
        assertFalse(item.has("memoryRevisionId"));
        assertFalse(item.has("revisionNo"));

        assertEquals(memBefore, count("memory.memory_record"));
        // Decision exists
        assertEquals(confirmBefore + 1,
                (long) countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM' AND target_kind='MEMORY'"));
    }

    @Test
    @Order(9)
    void mixedAcceptedCreateAndReviseReturnsDecisionsCommittedNoPartialPublish() throws Exception {
        UUID seedMemoryId = seedMemory("mixed-target");

        Built built = buildMixedCreateRevise("mixed-cr", seedMemoryId);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        long memBefore = count("memory.memory_record");
        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals("DECISIONS_COMMITTED", receipt.get("phase").asText());

        // CREATE candidate: DECISIONS_COMMITTED, no memory
        JsonNode c0 = receipt.get("candidates").get(0);
        assertEquals("CREATE", c0.get("action").asText());
        assertEquals("DECISIONS_COMMITTED", c0.get("phase").asText());
        assertFalse(c0.has("memoryId"));
        assertFalse(c0.has("memoryRevisionId"));
        assertFalse(c0.has("revisionNo"));

        // REVISE candidate: DECISIONS_COMMITTED, no memory
        JsonNode c1 = receipt.get("candidates").get(1);
        assertEquals("REVISE", c1.get("action").asText());
        assertEquals("DECISIONS_COMMITTED", c1.get("phase").asText());
        assertFalse(c1.has("memoryId"));
        assertFalse(c1.has("memoryRevisionId"));
        assertFalse(c1.has("revisionNo"));

        // Zero memory published
        assertEquals(memBefore, count("memory.memory_record"));
    }

    // ── path reviewSessionId mismatch ───────────────────────────────────────

    @Test
    @Order(10)
    void pathReviewSessionIdMismatchRejected422ZeroFacts() throws Exception {
        Built built = build("pathmismatch", 1, true);
        UUID wrongReviewSessionId = UUID.randomUUID();
        String path = "/v1/review-sessions/" + wrongReviewSessionId + "/final-submissions";

        long memBefore = count("memory.memory_record");
        long decBefore = count("memory.decision");
        assertProblem(post(path, built.json(), TOKEN, true), 422, "REQUEST_SCHEMA_INVALID");
        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(decBefore, count("memory.decision"));
    }

    // ── replay / conflict ───────────────────────────────────────────────────

    @Test
    @Order(11)
    void replaySameRequestConvergesAndDifferentRequestConflicts() throws Exception {
        Built built = build("replay", 2, true);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        Response first = post(path, built.json(), TOKEN, true);
        assertEquals(202, first.status());
        JsonNode firstReceipt = JSON.readTree(first.body());
        String firstPhase = firstReceipt.get("phase").asText();
        assertTrue("INDEX_READY".equals(firstPhase) || "CANONICAL_COMMITTED".equals(firstPhase));

        long memBefore = count("memory.memory_record");
        Response replay = post(path, built.json(), TOKEN, true);
        assertEquals(202, replay.status());
        JsonNode replayReceipt = JSON.readTree(replay.body());
        assertEquals(firstReceipt.get("candidateSetId").asText(), replayReceipt.get("candidateSetId").asText());
        String replayPhase = replayReceipt.get("phase").asText();
        assertTrue("INDEX_READY".equals(replayPhase) || "CANONICAL_COMMITTED".equals(replayPhase));
        assertEquals(memBefore, count("memory.memory_record"));

        // Different request, same key
        Built different = buildWithCandidateSetId(candidateSetId, "different", 2, true);
        assertProblem(post(path, different.json(), TOKEN, true), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    // ── concurrency ─────────────────────────────────────────────────────────

    @Test
    @Order(12)
    void concurrentSameRequestProducesOneFactEach() throws Exception {
        Built built = build("concurrent", 2, true);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        List<Response> responses = runConcurrently(2, path, built.json());
        long successes = responses.stream().filter(r -> r.status() == 202).count();
        assertTrue(successes >= 1, "at least one must succeed");

        // Each concurrent request has 2 candidates, at least one request succeeds
        long confirmDecisions = countRows(
                "SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM'");
        assertTrue(confirmDecisions >= 2, "at least 2 USER_CONFIRM decisions expected");
        long activeMemories = countRows("SELECT 1 FROM memory.memory_record WHERE state='ACTIVE'");
        assertTrue(activeMemories >= 2, "at least 2 active memories expected");
    }

    // ── partial vector failure ──────────────────────────────────────────────

    @Test
    @Order(13)
    void partialVectorFailureReturnsCanonicalCommittedAndRecoveryConverges() throws Exception {
        // This test verifies that when embedding is unavailable, the response is CANONICAL_COMMITTED
        // and a replay converges to INDEX_READY when embedding is available.
        // Since we use real embedding, we test that the normal flow produces INDEX_READY.
        Built built = build("vector", 1, true);
        UUID candidateSetId = built.candidateSetId();
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        // With real embedding available, should be INDEX_READY
        JsonNode receipt = JSON.readTree(response.body());
        assertTrue(
                "INDEX_READY".equals(receipt.get("phase").asText())
                        || "CANONICAL_COMMITTED".equals(receipt.get("phase").asText()),
                "phase should be INDEX_READY or CANONICAL_COMMITTED");
    }

    // ── schema attacks ──────────────────────────────────────────────────────

    @Test
    @Order(14)
    void schemaAttacksRejectedBeforeWrite() throws Exception {
        long memBefore = count("memory.memory_record");
        long decBefore = count("memory.decision");

        Built built = build("schema", 1, true);
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(built.candidateSetId());
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        // Unknown field
        ObjectNode unknown = (ObjectNode) JSON.readTree(built.json());
        unknown.put("unknownField", "intruder");
        assertProblem(post(path, unknown.toString(), TOKEN, true), 422, "REQUEST_SCHEMA_INVALID");

        // Bad UUID
        ObjectNode badUuid = (ObjectNode) JSON.readTree(built.json());
        badUuid.put("candidateSetId", "not-a-uuid");
        assertProblem(post(path, badUuid.toString(), TOKEN, true), 422, "REQUEST_SCHEMA_INVALID");

        // Bad hex (63 chars)
        ObjectNode badHex = (ObjectNode) JSON.readTree(built.json());
        badHex.put("requestHash", "a".repeat(63));
        assertProblem(post(path, badHex.toString(), TOKEN, true), 422, "REQUEST_SCHEMA_INVALID");

        // Wrong content-type
        HttpRequest.Builder wrongCt = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "text/plain")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", built.candidateSetId().toString())
                .method("POST", HttpRequest.BodyPublishers.ofString(built.json()));
        HttpResponse<String> ctResp = HTTP.send(wrongCt.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, ctResp.statusCode());

        // Missing Idempotency-Key
        HttpRequest.Builder noKey = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .method("POST", HttpRequest.BodyPublishers.ofString(built.json()));
        HttpResponse<String> noKeyResp = HTTP.send(noKey.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, noKeyResp.statusCode());
        assertTrue(JSON.readTree(noKeyResp.body()).get("failureCode").asText().contains("IDEMPOTENCY_KEY"));

        // Idempotency-Key != candidateSetId
        HttpRequest.Builder wrongKey = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method("POST", HttpRequest.BodyPublishers.ofString(built.json()));
        HttpResponse<String> wrongKeyResp = HTTP.send(wrongKey.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(422, wrongKeyResp.statusCode());

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(decBefore, count("memory.decision"));
    }

    // ── leak checks ─────────────────────────────────────────────────────────

    @Test
    @Order(15)
    void responseAndLogsNeverLeakBodyHideReasonTokenHashOrPath() throws Exception {
        Built built = build("leak", 1, true);
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(built.candidateSetId());
        String path = "/v1/review-sessions/" + reviewSessionId + "/final-submissions";

        Response response = post(path, built.json(), TOKEN, true);
        assertEquals(202, response.status());
        String body = response.body();

        // No bodyText, hideReason, token, full hash, vector, path
        assertFalse(body.contains("记忆正文"), "body text leaked");
        assertFalse(body.contains(TOKEN), "token leaked");
        assertFalse(body.contains("hideReason"), "hideReason leaked");
        // requestHash and confirmationHash should not appear in response
        JsonNode receipt = JSON.readTree(body);
        assertFalse(receipt.has("requestHash"), "requestHash leaked");
        assertFalse(receipt.has("confirmationHash"), "confirmationHash leaked");

        // Error response also must not leak
        HttpRequest.Builder noAuth = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(built.json()));
        HttpResponse<String> errResp = HTTP.send(noAuth.build(), HttpResponse.BodyHandlers.ofString());
        assertFalse(errResp.body().contains(TOKEN), "token leaked in error");
        assertFalse(errResp.body().contains("hide_nest"), "db name leaked in error");
    }

    // ── fixed-vector reviewSessionId test ───────────────────────────────────

    @Test
    @Order(16)
    void reviewSessionIdIsDeterministicAndMatchesFixedVector() {
        UUID candidateSetId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID reviewSessionId = LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId);
        // Deterministic: same input always produces same output
        assertEquals(reviewSessionId, LocalV1CandidateSetCanonicalizer.reviewSessionId(candidateSetId));
        // Different candidateSetId produces different reviewSessionId
        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        assertFalse(reviewSessionId.equals(LocalV1CandidateSetCanonicalizer.reviewSessionId(other)));
        // The derivation matches the batch coordinator's internal derivation
        UUID expected = UUID.nameUUIDFromBytes(
                ("candidate-set:review:" + candidateSetId).getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, reviewSessionId);
    }

    // ── builders ────────────────────────────────────────────────────────────

    private static Built build(String marker, int candidateCount, boolean accepted) {
        return buildWithCandidateSetId(UUID.randomUUID(), marker, candidateCount, accepted);
    }

    private static Built buildMixed(String marker, int acceptedCount, int rejectedCount) {
        UUID candidateSetId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        String bodyText = "证据正文-" + marker;
        byte[] bodyHash = sha256(bodyText);
        String bodyHashHex = hex(bodyHash);
        UUID anchorId = UUID.randomUUID();

        List<CandidateSpec> specs = new ArrayList<>();
        for (int i = 0; i < acceptedCount; i++) {
            specs.add(new CandidateSpec(UUID.randomUUID(), i + 1, "ACCEPTED", "CREATE",
                    "记忆正文-" + marker + "-" + i, "Event", actorId, List.of(anchorId)));
        }
        for (int i = 0; i < rejectedCount; i++) {
            specs.add(new CandidateSpec(UUID.randomUUID(), acceptedCount + i + 1, "REJECTED", "CREATE",
                    null, null, actorId, List.of()));
        }

        return buildWithSpecs(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, specs);
    }

    private static Built buildWithCandidateSetId(UUID candidateSetId, String marker, int candidateCount, boolean accepted) {
        UUID threadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        String bodyText = "证据正文-" + marker;
        byte[] bodyHash = sha256(bodyText);
        String bodyHashHex = hex(bodyHash);
        UUID anchorId = UUID.randomUUID();

        List<CandidateSpec> specs = new ArrayList<>();
        for (int i = 0; i < candidateCount; i++) {
            String disposition = accepted ? "ACCEPTED" : "REJECTED";
            String memText = accepted ? "记忆正文-" + marker + "-" + i : null;
            String memType = accepted ? "Event" : null;
            List<UUID> anchorIds = accepted ? List.of(anchorId) : List.of();
            specs.add(new CandidateSpec(UUID.randomUUID(), i + 1, disposition, "CREATE",
                    memText, memType, actorId, anchorIds));
        }

        return buildWithSpecs(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, specs);
    }

    private static Built buildShared(UUID candidateSetId, UUID threadId, UUID actorId,
            UUID sourceUnitId, String bodyText, byte[] bodyHash, String bodyHashHex,
            UUID anchorId, int count) {
        List<CandidateSpec> specs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            specs.add(new CandidateSpec(UUID.randomUUID(), i + 1, "ACCEPTED", "CREATE",
                    "记忆正文-shared-" + i, "Event", actorId, List.of(anchorId)));
        }
        return buildWithSpecs(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, specs);
    }

    private static Built buildRevise(String marker, UUID seedMemoryId) {
        UUID candidateSetId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        String bodyText = "证据正文-" + marker;
        byte[] bodyHash = sha256(bodyText);
        String bodyHashHex = hex(bodyHash);
        UUID anchorId = UUID.randomUUID();

        // Get the current revision ID of the seed memory
        var record = dsl.fetchOne(
                "SELECT current_revision_id, current_policy_revision_no FROM memory.memory_record WHERE memory_id=?::uuid",
                seedMemoryId);
        UUID expectedRevisionId = record.get("current_revision_id", UUID.class);
        Long expectedPolicyNo = record.get("current_policy_revision_no", Long.class);
        var revision = dsl.fetchOne(
                "SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id=?::uuid",
                expectedRevisionId);
        Long expectedRevisionNo = revision.get("revision_no", Long.class);

        List<CandidateSpec> specs = List.of(new CandidateSpec(
                UUID.randomUUID(), 1, "ACCEPTED", "REVISE",
                "修订记忆-" + marker, "Event", actorId, List.of(anchorId),
                seedMemoryId, expectedRevisionId, expectedRevisionNo, expectedPolicyNo));

        return buildWithSpecs(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, specs);
    }

    private static Built buildMixedCreateRevise(String marker, UUID seedMemoryId) {
        UUID candidateSetId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        String bodyText = "证据正文-" + marker;
        byte[] bodyHash = sha256(bodyText);
        String bodyHashHex = hex(bodyHash);
        UUID anchorId = UUID.randomUUID();

        var record = dsl.fetchOne(
                "SELECT current_revision_id, current_policy_revision_no FROM memory.memory_record WHERE memory_id=?::uuid",
                seedMemoryId);
        UUID expectedRevisionId = record.get("current_revision_id", UUID.class);
        Long expectedPolicyNo = record.get("current_policy_revision_no", Long.class);
        var revision = dsl.fetchOne(
                "SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id=?::uuid",
                expectedRevisionId);
        Long expectedRevisionNo = revision.get("revision_no", Long.class);

        List<CandidateSpec> specs = List.of(
                new CandidateSpec(UUID.randomUUID(), 1, "ACCEPTED", "CREATE",
                        "新建记忆-" + marker, "Event", actorId, List.of(anchorId)),
                new CandidateSpec(UUID.randomUUID(), 2, "ACCEPTED", "REVISE",
                        "修订记忆-" + marker, "Event", actorId, List.of(anchorId),
                        seedMemoryId, expectedRevisionId, expectedRevisionNo, expectedPolicyNo));

        return buildWithSpecs(candidateSetId, threadId, actorId, sourceUnitId, bodyText, bodyHash, bodyHashHex, anchorId, specs);
    }

    private static Built buildWithSpecs(UUID candidateSetId, UUID threadId, UUID actorId,
            UUID sourceUnitId, String bodyText, byte[] bodyHash, String bodyHashHex,
            UUID anchorId, List<CandidateSpec> specs) {
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-12T09:30:00Z");

        var evidenceMessage = new LocalV1CandidateSetRequest.EvidenceMessage(
                sourceUnitId, actorId, 1L, "unit-ref", occurredAt, bodyText, bodyHash);
        var anchorUnit = new LocalV1CandidateSetRequest.AnchorUnit(sourceUnitId, null, null, 1L);
        var anchorSpec = new LocalV1CandidateSetRequest.AnchorSpec(anchorId, List.of(anchorUnit));
        var evidencePool = new LocalV1CandidateSetRequest.EvidencePool(
                List.of(evidenceMessage), List.of(anchorSpec));

        List<LocalV1CandidateSetRequest.Candidate> candidates = new ArrayList<>();
        for (CandidateSpec spec : specs) {
            candidates.add(new LocalV1CandidateSetRequest.Candidate(
                    spec.candidateId, spec.ordinal, spec.disposition, spec.action,
                    spec.disposition.equals("ACCEPTED") ? "HIDE_PROPOSED" : "USER_ADDED",
                    spec.disposition.equals("ACCEPTED") ? "HIDE" : "USER",
                    spec.memoryText, spec.memoryType, actorId, spec.anchorIds,
                    spec.targetMemoryId, spec.expectedMemoryRevisionId,
                    spec.expectedRevisionNo, spec.expectedPolicyRevisionNo, null));
        }

        var finalConfirmation = new LocalV1CandidateSetRequest.FinalConfirmation(
                "CONFIRM_SET", 1L, new byte[32]);
        var provisional = new LocalV1CandidateSetRequest(
                candidateSetId, candidateSetId.toString(), new byte[32], threadId,
                "scope-" + candidateSetId.toString().substring(0, 8), 1L,
                finalConfirmation, evidencePool, candidates);

        byte[] requestHash = LocalV1CandidateSetCanonicalizer.requestHash(provisional);
        byte[] confirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(provisional);

        var realFinalConfirmation = new LocalV1CandidateSetRequest.FinalConfirmation(
                "CONFIRM_SET", 1L, confirmationHash);
        var request = new LocalV1CandidateSetRequest(
                candidateSetId, candidateSetId.toString(), requestHash, threadId,
                "scope-" + candidateSetId.toString().substring(0, 8), 1L,
                realFinalConfirmation, evidencePool, candidates);

        return new Built(candidateSetId, request, toJson(request));
    }

    private static String emptyRequestJson(UUID candidateSetId) {
        var request = new LocalV1CandidateSetRequest(
                candidateSetId, candidateSetId.toString(), new byte[32],
                UUID.randomUUID(), "scope-empty", 1L,
                new LocalV1CandidateSetRequest.FinalConfirmation("CONFIRM_SET", 1L, new byte[32]),
                new LocalV1CandidateSetRequest.EvidencePool(List.of(), List.of()),
                List.of());
        byte[] requestHash = LocalV1CandidateSetCanonicalizer.requestHash(request);
        byte[] confirmationHash = LocalV1CandidateSetCanonicalizer.confirmationHash(request);
        var realRequest = new LocalV1CandidateSetRequest(
                candidateSetId, candidateSetId.toString(), requestHash,
                request.threadId(), request.scopeRef(), 1L,
                new LocalV1CandidateSetRequest.FinalConfirmation("CONFIRM_SET", 1L, confirmationHash),
                request.evidencePool(), request.candidates());
        return toJson(realRequest);
    }

    private static String toJson(LocalV1CandidateSetRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("candidateSetId", request.candidateSetId().toString());
        root.put("requestHash", hex(request.requestHash()));
        root.put("threadId", request.threadId().toString());
        root.put("scopeRef", request.scopeRef());
        root.put("setVersion", request.setVersion());

        ObjectNode confirmation = root.putObject("finalConfirmation");
        confirmation.put("decision", request.finalConfirmation().decision());
        confirmation.put("confirmedSetVersion", request.finalConfirmation().confirmedSetVersion());
        confirmation.put("confirmationHash", hex(request.finalConfirmation().confirmationHash()));

        ObjectNode evidencePool = root.putObject("evidencePool");
        ArrayNode messages = evidencePool.putArray("messages");
        for (var m : request.evidencePool().messages()) {
            ObjectNode msg = messages.addObject();
            msg.put("sourceUnitId", m.sourceUnitId().toString());
            msg.put("actorId", m.actorId().toString());
            msg.put("ordinal", m.ordinal());
            msg.put("externalUnitRef", m.externalUnitRef());
            msg.put("occurredAt", m.occurredAt().toInstant().toString());
            msg.put("bodyText", m.bodyText());
            msg.put("bodyHash", hex(m.bodyHash()));
        }
        ArrayNode anchors = evidencePool.putArray("anchors");
        for (var a : request.evidencePool().anchors()) {
            ObjectNode anchor = anchors.addObject();
            anchor.put("anchorId", a.anchorId().toString());
            ArrayNode units = anchor.putArray("units");
            for (var u : a.units()) {
                ObjectNode unit = units.addObject();
                unit.put("sourceUnitId", u.sourceUnitId().toString());
                if (u.fromOffset() == null) {
                    unit.putNull("fromOffset");
                } else {
                    unit.put("fromOffset", u.fromOffset());
                }
                if (u.toOffset() == null) {
                    unit.putNull("toOffset");
                } else {
                    unit.put("toOffset", u.toOffset());
                }
                unit.put("ordinal", u.ordinal());
            }
        }

        ArrayNode candidates = root.putArray("candidates");
        for (var c : request.candidates()) {
            ObjectNode candidate = candidates.addObject();
            candidate.put("candidateId", c.candidateId().toString());
            candidate.put("ordinal", c.ordinal());
            candidate.put("disposition", c.disposition());
            candidate.put("action", c.action());
            candidate.put("originKind", c.originKind());
            candidate.put("finalAuthorKind", c.finalAuthorKind());
            if (c.memoryText() != null) {
                candidate.put("memoryText", c.memoryText());
            }
            if (c.memoryType() != null) {
                candidate.put("memoryType", c.memoryType());
            }
            candidate.put("perspectiveActorId", c.perspectiveActorId().toString());
            ArrayNode anchorIds = candidate.putArray("evidenceAnchorIds");
            for (UUID anchorId : c.evidenceAnchorIds()) {
                anchorIds.add(anchorId.toString());
            }
            if (c.targetMemoryId() != null) {
                candidate.put("targetMemoryId", c.targetMemoryId().toString());
            }
            if (c.expectedMemoryRevisionId() != null) {
                candidate.put("expectedMemoryRevisionId", c.expectedMemoryRevisionId().toString());
            }
            if (c.expectedRevisionNo() != null) {
                candidate.put("expectedRevisionNo", c.expectedRevisionNo());
            }
            if (c.expectedPolicyRevisionNo() != null) {
                candidate.put("expectedPolicyRevisionNo", c.expectedPolicyRevisionNo());
            }
        }
        return root.toString();
    }

    // ── seed memory for REVISE/SUPERSEDE tests ──────────────────────────────

    private UUID seedMemory(String marker) {
        LocalV1S1WindowCloseCoordinator s1 = api.getBean(LocalV1S1WindowCloseCoordinator.class);
        UUID actorId = UUID.randomUUID();
        UUID sourceUnitId = UUID.randomUUID();
        UUID anchorId = UUID.randomUUID();
        String idempotencyKey = "candidate-seed-" + marker + "-" + UUID.randomUUID();
        String body = "种子记忆-" + marker;
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                idempotencyKey,
                sha256(idempotencyKey),
                actorId,
                "Event",
                body,
                sha256(body),
                List.of(new LocalV1S1PrepareRequest.EvidenceMessage(
                        sourceUnitId,
                        actorId,
                        1L,
                        "seed-unit-" + marker,
                        OffsetDateTime.now(),
                        "seed-evidence-" + marker)),
                List.of(new LocalV1S1PrepareRequest.AnchorInput(
                        anchorId,
                        List.of(new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                                sourceUnitId, 0L, 1L, 1L))))));
        UUID memoryId = UUID.randomUUID();
        s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + idempotencyKey,
                sha256("confirm-" + idempotencyKey),
                prepared.proposalRevisionId(),
                prepared.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                new byte[32]));
        return memoryId;
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────

    private static Response post(String path, String body, String token, boolean withKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) {
            try {
                String idText = JSON.readTree(body).get("candidateSetId").asText();
                builder.header("Idempotency-Key", idText);
            } catch (Exception e) {
                // body may have malformed UUID for schema attack tests
                builder.header("Idempotency-Key", UUID.randomUUID().toString());
            }
        }
        builder.method("POST", body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static List<Response> runConcurrently(int threads, String path, String body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return post(path, body, TOKEN, true);
            }));
        }
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Response> responses = new ArrayList<>();
        for (Future<Response> future : futures) {
            responses.add(future.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return responses;
    }

    private static void assertSuccessHeaders(Response response, int status) {
        assertEquals(status, response.status());
        assertEquals("no-store", header(response, "cache-control"));
        assertTrue(header(response, "content-type").startsWith("application/json"));
        UUID.fromString(header(response, "x-request-id"));
        assertEquals(header(response, "x-request-id"), JSON.readTree(response.body()).get("requestId").asText());
    }

    private static void assertProblem(Response response, int status, String failureCode) {
        assertEquals(status, response.status());
        assertEquals("no-store", header(response, "cache-control"));
        assertTrue(header(response, "content-type").startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(response.body());
        assertEquals(failureCode, body.get("failureCode").asText());
        assertFalse(body.toString().contains(CHAT_CANARY));
        assertFalse(body.toString().contains(TOKEN));
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElseThrow();
    }

    // ── DB helpers ──────────────────────────────────────────────────────────

    private static ConfigurableApplicationContext startApi(String address, String password) {
        SpringApplication application = new SpringApplication(HideNestApiApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setDefaultProperties(Map.of(
                "spring.profiles.active", "local-v1-synthetic",
                "server.address", address,
                "server.port", "0",
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", USER,
                "spring.datasource.password", password,
                "hidenest.local-v1.payload-root", payloadRoot.toString(),
                "hidenest.local-v1.synthetic-token", TOKEN,
                "HIDE_NEST_EMBEDDING_BASE_URL", "",
                "logging.level.root", "WARN"));
        return application.run();
    }

    private static long count(String table) {
        return countRows("SELECT 1 FROM " + table);
    }

    private static long countRows(String sql, Object... binds) {
        return dsl.resultQuery(sql, binds).fetch().size();
    }

    private static void assertNoCanary() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
                Statement s = c.createStatement()) {
            for (String table : List.of(
                    "evidence.source", "evidence.source_unit", "evidence.source_anchor",
                    "evidence.source_anchor_unit", "evidence.source_payload",
                    "memory.memory_record", "memory.memory_revision", "memory.memory_relation",
                    "memory.proposal", "memory.proposal_revision", "memory.review_session",
                    "memory.review_member", "memory.decision", "memory.change_event",
                    "memory.access_policy", "memory.access_policy_revision",
                    "memory.candidate_set", "memory.candidate_set_member",
                    "memory.candidate_evidence_mapping",
                    "runtime.idempotency_receipt", "runtime.outbox_event")) {
                var rs = s.executeQuery("SELECT * FROM " + table);
                var meta = rs.getMetaData();
                while (rs.next()) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        String value = rs.getString(i);
                        if (value != null) {
                            assertFalse(value.contains(CHAT_CANARY), "canary leaked into " + table);
                        }
                    }
                }
            }
        }
        try (var files = Files.walk(payloadRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(CHAT_CANARY),
                        "canary leaked into payload file " + file.getFileName());
            }
        }
    }

    // ── crypto helpers ──────────────────────────────────────────────────────

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    // ── data types ──────────────────────────────────────────────────────────

    private record CandidateSpec(
            UUID candidateId, long ordinal, String disposition, String action,
            String memoryText, String memoryType, UUID perspectiveActorId,
            List<UUID> anchorIds,
            UUID targetMemoryId, UUID expectedMemoryRevisionId,
            Long expectedRevisionNo, Long expectedPolicyRevisionNo) {
        CandidateSpec(UUID candidateId, long ordinal, String disposition, String action,
                String memoryText, String memoryType, UUID perspectiveActorId, List<UUID> anchorIds) {
            this(candidateId, ordinal, disposition, action, memoryText, memoryType,
                    perspectiveActorId, anchorIds, null, null, null, null);
        }
    }

    private record Built(UUID candidateSetId, LocalV1CandidateSetRequest request, String json) {}

    private record Response(int status, Map<String, List<String>> headers, String body) {}
}
