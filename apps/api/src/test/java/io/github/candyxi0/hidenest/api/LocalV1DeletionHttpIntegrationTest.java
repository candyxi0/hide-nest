package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.HideSelection;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.UserConfirmation;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1DeletionHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CAPABILITY = "SyntheticCap-9xQ2zW8vB5nM3kR7dF1hJ4cT6yU0lP9w";
    private static final String CHAT_CANARY = "DELETE_BODY_CANARY_33A";
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
        assertEquals(21, Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), USER, password)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate()
                .migrationsExecuted);

        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        var configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        payloadRoot = Files.createTempDirectory("local-v1-deletion-http-");

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
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    // ── 1. gate: bearer/capability, closeout + read still pass ──────────────

    @Test
    @Order(1)
    void gateEnforcesSecretsWithoutBreakingExistingPaths() throws Exception {
        Built built = buildCloseout("gate");
        String previewBody = previewBody(UUID.randomUUID(), 1, 1);

        // preview requires bearer
        assertProblem(post("/v1/deletion-previews", previewBody, null, null, UUID.randomUUID().toString()),
                401, "ACCESS_DENIED");
        assertProblem(post("/v1/deletion-previews", previewBody, "wrong-token-with-enough-length-but-wrong", null,
                UUID.randomUUID().toString()), 403, "ACCESS_DENIED");

        // confirm requires bearer + capability
        assertProblem(post("/v1/deletion-previews/" + UUID.randomUUID() + "/confirm", previewBody, null, null,
                UUID.randomUUID().toString()), 401, "ACCESS_DENIED");
        assertProblem(post("/v1/deletion-previews/" + UUID.randomUUID() + "/confirm", previewBody, TOKEN, null,
                UUID.randomUUID().toString()), 403, "CAPABILITY_REQUIRED");
        assertProblem(post("/v1/deletion-previews/" + UUID.randomUUID() + "/confirm", previewBody, TOKEN,
                "wrong-capability-with-enough-length", UUID.randomUUID().toString()), 403, "CAPABILITY_REQUIRED");

        // run query requires bearer only
        assertProblem(get("/v1/deletion-runs/" + UUID.randomUUID(), null), 401, "ACCESS_DENIED");
        assertEquals(200, get("/v1/memories", TOKEN).status());

        // closeout still passes through the gates
        Response closeout = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY, built.submission().submissionId().toString());
        assertEquals(202, closeout.status());
    }

    // ── 2. preview happy path: named members, order, zero body/objectRef ────

    @Test
    @Order(2)
    void previewReturnsNamedOrderedClosureMembersWithoutBody() throws Exception {
        Fixture fixture = createMemory("preview-happy");
        long[] facts = readFacts(fixture.memoryId());
        String idempotencyKey = UUID.randomUUID().toString();
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);

        Response response = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, idempotencyKey);
        assertEquals(200, response.status());
        assertSuccessHeaders(response, 200);
        JsonNode body = JSON.readTree(response.body());
        assertEquals("SUCCEEDED", body.get("resultCategory").asText());
        assertTrue(body.get("manifestHash").asText().matches("^[0-9a-f]{64}$"));
        assertFalse(body.has("rootBodyPreview"));
        assertFalse(body.toString().contains(CHAT_CANARY));
        assertFalse(body.toString().contains(payloadRoot.toString()));
        assertFalse(body.toString().contains("objectRef"));

        JsonNode members = body.get("closureMembers");
        assertTrue(members.isArray());
        assertEquals(8, members.size());
        long previousOrdinal = 0;
        for (JsonNode member : members) {
            long ordinal = member.get("ordinal").asLong();
            assertTrue(ordinal > previousOrdinal, "members must be ordered by ordinal ascending");
            previousOrdinal = ordinal;
            assertTrue(member.has("memberKind"));
            assertTrue(member.has("targetId"));
            assertTrue(member.has("disposition"));
            assertFalse(member.toString().contains("bodyText"));
            assertFalse(member.toString().contains("objectRef"));
        }
        // SOURCE_PAYLOAD members carry sizeBytes + contentHash (64-hex); others omit them.
        JsonNode payloadMember = findMember(members, "SOURCE_PAYLOAD");
        assertNotNull(payloadMember);
        assertTrue(payloadMember.get("sizeBytes").asLong() >= 0);
        assertTrue(payloadMember.get("contentHash").asText().matches("^[0-9a-f]{64}$"));

        UUID previewId = UUID.fromString(body.get("previewId").asText());
        assertEquals(1L, countRows("SELECT 1 FROM memory.deletion_closure WHERE closure_id=?::uuid", previewId));
    }

    // ── 3. preview replay + same-key different-value conflict ────────────────

    @Test
    @Order(3)
    void previewReplayAndConflict() throws Exception {
        Fixture a = createMemory("preview-replay-a");
        Fixture b = createMemory("preview-replay-b");
        long[] factsA = readFacts(a.memoryId());
        long[] factsB = readFacts(b.memoryId());
        String key = UUID.randomUUID().toString();
        String hashA = LocalV1DeletionCanonicalizer.requestHashHex(a.memoryId(), factsA[0], factsA[1]);
        String hashB = LocalV1DeletionCanonicalizer.requestHashHex(b.memoryId(), factsB[0], factsB[1]);

        String body = previewBody(a.memoryId(), factsA[0], factsA[1], hashA);
        Response first = post("/v1/deletion-previews", body, TOKEN, null, key);
        assertEquals(200, first.status());
        JsonNode firstJson = JSON.readTree(first.body());

        Response replay = post("/v1/deletion-previews", body, TOKEN, null, key);
        assertEquals(200, replay.status());
        JsonNode replayJson = JSON.readTree(replay.body());
        assertEquals(firstJson.get("previewId").asText(), replayJson.get("previewId").asText());
        assertEquals(firstJson.get("previewRevision").asText(), replayJson.get("previewRevision").asText());
        assertEquals(firstJson.get("manifestHash").asText(), replayJson.get("manifestHash").asText());

        // same key, different target → 409, no second preview fact
        long closuresBeforeConflict = countRows("SELECT 1 FROM memory.deletion_closure");
        String different = previewBody(b.memoryId(), factsB[0], factsB[1], hashB);
        assertProblem(post("/v1/deletion-previews", different, TOKEN, null, key), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(closuresBeforeConflict, countRows("SELECT 1 FROM memory.deletion_closure"));
    }

    // ── 4. preview stale: wrong expected revision/policy → no preview fact ──

    @Test
    @Order(4)
    void previewStaleRejectsWithoutCreatingPreview() throws Exception {
        Fixture fixture = createMemory("preview-stale");
        long[] facts = readFacts(fixture.memoryId());
        long closuresBefore = countRows("SELECT 1 FROM memory.deletion_closure");

        String wrongRevision = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0] + 1, facts[1]);
        assertProblem(post("/v1/deletion-previews",
                        previewBody(fixture.memoryId(), facts[0] + 1, facts[1], wrongRevision), TOKEN, null,
                        UUID.randomUUID().toString()),
                409, "DELETION_PREVIEW_STALE");

        String wrongPolicy = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1] + 1);
        assertProblem(post("/v1/deletion-previews",
                        previewBody(fixture.memoryId(), facts[0], facts[1] + 1, wrongPolicy), TOKEN, null,
                        UUID.randomUUID().toString()),
                409, "DELETION_PREVIEW_STALE");

        assertEquals(closuresBefore, countRows("SELECT 1 FROM memory.deletion_closure"));
    }

    // ── 5. legal confirm chain: DB erase + file erase + run COMPLETED ────────

    @Test
    @Order(5)
    void confirmChainErasesDatabaseAndFiles() throws Exception {
        Fixture fixture = createMemory("confirm-chain");
        long[] facts = readFacts(fixture.memoryId());
        String previewKey = UUID.randomUUID().toString();
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);

        Response preview = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, previewKey);
        assertEquals(200, preview.status());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();

        String confirmKey = UUID.randomUUID().toString();
        Response confirm = post("/v1/deletion-previews/" + previewId + "/confirm",
                confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash),
                TOKEN, CAPABILITY, confirmKey);
        assertEquals(202, confirm.status());
        JsonNode confirmJson = JSON.readTree(confirm.body());
        UUID runId = UUID.fromString(confirmJson.get("runId").asText());
        assertEquals("CANONICAL_COMMITTED", confirmJson.get("phase").asText());
        assertTrue(confirmJson.get("statusUrl").asText().endsWith("/v1/deletion-runs/" + runId));

        // database erasure
        assertEquals(0L, countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", fixture.memoryId()));
        assertEquals(0L, countRows("SELECT 1 FROM memory.memory_revision WHERE memory_id=?::uuid", fixture.memoryId()));
        // run COMPLETED + no failure
        assertEquals("COMPLETED", scalar("SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId));
        assertNull(scalarOrNull("SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId));

        // run status
        Response status = get("/v1/deletion-runs/" + runId, TOKEN);
        assertEquals(200, status.status());
        JsonNode statusJson = JSON.readTree(status.body());
        assertEquals("CANONICAL_COMMITTED", statusJson.get("phase").asText());
        assertFalse(statusJson.get("retryable").asBoolean());

        // post-delete S2B: list excludes, detail/evidence unreadable, payload NOT_FOUND, audit preserved
        assertEquals(200, get("/v1/memories?query=" + CHAT_CANARY, TOKEN).status());
        assertProblem(get("/v1/memories/" + fixture.memoryId(), TOKEN), 404, "RETRIEVAL_NO_MATCH");
        assertProblem(get("/v1/memories/" + fixture.memoryId() + "/evidence", TOKEN), 404, "RETRIEVAL_NO_MATCH");
        assertTrue(countRows("SELECT 1 FROM memory.deletion_closure WHERE closure_id=?::uuid", previewId) >= 1);
        assertTrue(countRows("SELECT 1 FROM memory.deletion_fence WHERE closure_id=?::uuid", previewId) >= 1);
    }

    // ── 6. confirm binding attack: any mismatch → 409, zero erasure ──────────

    @Test
    @Order(6)
    void confirmBindingAttackRejectsWithZeroErasure() throws Exception {
        Fixture fixture = createMemory("confirm-attack");
        long[] facts = readFacts(fixture.memoryId());
        String previewKey = UUID.randomUUID().toString();
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, previewKey);
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();
        long memoryBefore = countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", fixture.memoryId());

        // URL id != body previewId
        assertProblem(post("/v1/deletion-previews/" + UUID.randomUUID() + "/confirm",
                        confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash),
                        TOKEN, CAPABILITY, UUID.randomUUID().toString()),
                409, "DELETION_CLOSURE_MISMATCH");

        // different targetId (with a matching request manifest for that target)
        UUID otherTarget = UUID.randomUUID();
        String otherRequestHash = LocalV1DeletionCanonicalizer.requestHashHex(otherTarget, facts[0], facts[1]);
        assertProblem(post("/v1/deletion-previews/" + previewId + "/confirm",
                        confirmBody(otherTarget, facts[0], facts[1], otherRequestHash, previewId, previewRevision, manifestHash),
                        TOKEN, CAPABILITY, UUID.randomUUID().toString()),
                409, "DELETION_CLOSURE_MISMATCH");

        // different expectedRevision (with a matching request manifest for that revision)
        String revisedRequestHash = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0] + 1, facts[1]);
        assertProblem(post("/v1/deletion-previews/" + previewId + "/confirm",
                        confirmBody(fixture.memoryId(), facts[0] + 1, facts[1], revisedRequestHash, previewId, previewRevision, manifestHash),
                        TOKEN, CAPABILITY, UUID.randomUUID().toString()),
                409, "DELETION_CLOSURE_MISMATCH");

        // different requestManifestHash (does not match the canonical request hash) → schema
        String wrongRequestHash = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0] + 1, facts[1]);
        assertProblem(post("/v1/deletion-previews/" + previewId + "/confirm",
                        confirmBody(fixture.memoryId(), facts[0], facts[1], wrongRequestHash, previewId, previewRevision, manifestHash),
                        TOKEN, CAPABILITY, UUID.randomUUID().toString()),
                422, "REQUEST_SCHEMA_INVALID");

        // different manifestHash
        String wrongManifest = "ab".repeat(32);
        assertProblem(post("/v1/deletion-previews/" + previewId + "/confirm",
                        confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, wrongManifest),
                        TOKEN, CAPABILITY, UUID.randomUUID().toString()),
                409, "DELETION_CLOSURE_MISMATCH");

        assertEquals(memoryBefore, countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", fixture.memoryId()));
        assertEquals("PREVIEWED", scalar("SELECT state FROM memory.deletion_closure WHERE closure_id=?::uuid", previewId));
    }

    // ── 7. same-key replay + concurrency: single fact ───────────────────────

    @Test
    @Order(7)
    void confirmReplayAndConcurrencyConverge() throws Exception {
        Fixture fixture = createMemory("confirm-replay");
        long[] facts = readFacts(fixture.memoryId());
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();
        String confirmBody = confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash);

        String key = UUID.randomUUID().toString();
        Response first = post("/v1/deletion-previews/" + previewId + "/confirm", confirmBody, TOKEN, CAPABILITY, key);
        assertEquals(202, first.status());
        UUID firstRunId = UUID.fromString(JSON.readTree(first.body()).get("runId").asText());

        Response replay = post("/v1/deletion-previews/" + previewId + "/confirm", confirmBody, TOKEN, CAPABILITY, key);
        assertEquals(202, replay.status());
        assertEquals(firstRunId.toString(), JSON.readTree(replay.body()).get("runId").asText());

        assertEquals(1L, countRows("SELECT 1 FROM runtime.deletion_run WHERE closure_id=?::uuid", previewId));
        assertEquals(1L, countRows("SELECT 1 FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?::uuid", previewId));

        // concurrent same-key same-value
        Fixture fixture2 = createMemory("confirm-conc");
        long[] facts2 = readFacts(fixture2.memoryId());
        String requestHash2 = LocalV1DeletionCanonicalizer.requestHashHex(fixture2.memoryId(), facts2[0], facts2[1]);
        Response preview2 = post("/v1/deletion-previews",
                previewBody(fixture2.memoryId(), facts2[0], facts2[1], requestHash2), TOKEN, null, UUID.randomUUID().toString());
        JsonNode previewJson2 = JSON.readTree(preview2.body());
        UUID previewId2 = UUID.fromString(previewJson2.get("previewId").asText());
        long previewRevision2 = previewJson2.get("previewRevision").asLong();
        String manifestHash2 = previewJson2.get("manifestHash").asText();
        String confirmBody2 = confirmBody(fixture2.memoryId(), facts2[0], facts2[1], requestHash2, previewId2, previewRevision2, manifestHash2);
        String key2 = UUID.randomUUID().toString();

        List<Response> responses = runConcurrently(2, "/v1/deletion-previews/" + previewId2 + "/confirm", confirmBody2, key2);
        assertEquals(List.of(202, 202), responses.stream().map(Response::status).sorted().toList());
        assertEquals(JSON.readTree(responses.get(0).body()).get("runId").asText(),
                JSON.readTree(responses.get(1).body()).get("runId").asText());
        assertEquals(1L, countRows("SELECT 1 FROM runtime.deletion_run WHERE closure_id=?::uuid", previewId2));
    }

    // ── 8. file fault recovery: FILE_PENDING + retryable, replay recovers ────

    @Test
    @Order(8)
    void fileFaultLeavesRecoverableRunAndReplayRecovers() throws Exception {
        List<Path> beforeFiles = snapshotPayloadFiles();
        Fixture fixture = createMemory("confirm-fault");
        long[] facts = readFacts(fixture.memoryId());
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();
        String confirmBody = confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash);

        List<Path> newFiles = new ArrayList<>(snapshotPayloadFiles());
        newFiles.removeAll(beforeFiles);
        assertFalse(newFiles.isEmpty(), "expected new payload files for this memory");
        Files.write(newFiles.get(0), "CORRUPTED_PAYLOAD_HEADER_BREAKS_PARSING".getBytes(StandardCharsets.UTF_8));

        String key = UUID.randomUUID().toString();
        Response confirm = post("/v1/deletion-previews/" + previewId + "/confirm", confirmBody, TOKEN, CAPABILITY, key);
        assertEquals(202, confirm.status());
        UUID runId = UUID.fromString(JSON.readTree(confirm.body()).get("runId").asText());

        assertEquals("FILE_PENDING", scalar("SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId));
        assertEquals("DELETION_EXECUTION_FAILED",
                scalar("SELECT last_failure_code FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId));
        Response status = get("/v1/deletion-runs/" + runId, TOKEN);
        JsonNode statusJson = JSON.readTree(status.body());
        assertEquals("DECISIONS_COMMITTED", statusJson.get("phase").asText());
        assertTrue(statusJson.get("retryable").asBoolean());
        assertEquals("DELETION_EXECUTION_FAILED", statusJson.get("failureCode").asText());

        // recover: remove the corrupted file, replay the same confirm resumes the same run
        deletePayloadFiles();
        Response replay = post("/v1/deletion-previews/" + previewId + "/confirm", confirmBody, TOKEN, CAPABILITY, key);
        assertEquals(202, replay.status());
        assertEquals(runId.toString(), JSON.readTree(replay.body()).get("runId").asText());
        assertEquals("COMPLETED", scalar("SELECT state FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId));
        assertEquals(1L, countRows("SELECT 1 FROM runtime.deletion_run WHERE closure_id=?::uuid", previewId));
    }

    // ── 9. leak scan: no body/path/token/capability/secret ───────────────────

    @Test
    @Order(9)
    void zeroLeakageInResponsesAndLogs() throws Exception {
        Fixture fixture = createMemory("leak");
        long[] facts = readFacts(fixture.memoryId());
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(fixture.memoryId(), facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(fixture.memoryId(), facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();

        Response confirm = post("/v1/deletion-previews/" + previewId + "/confirm",
                confirmBody(fixture.memoryId(), facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash),
                TOKEN, CAPABILITY, UUID.randomUUID().toString());
        assertEquals(202, confirm.status());
        UUID runId = UUID.fromString(JSON.readTree(confirm.body()).get("runId").asText());
        Response status = get("/v1/deletion-runs/" + runId, TOKEN);

        for (String responseBody : List.of(preview.body(), confirm.body(), status.body())) {
            assertFalse(responseBody.contains(CHAT_CANARY), "body canary leaked");
            assertFalse(responseBody.contains(TOKEN), "token leaked");
            assertFalse(responseBody.contains(CAPABILITY), "capability leaked");
            assertFalse(responseBody.contains(payloadRoot.toString()), "payload root leaked");
            assertFalse(responseBody.toLowerCase().contains("select "), "SQL leaked");
            assertFalse(responseBody.toLowerCase().contains("stacktrace"), "stacktrace leaked");
        }

        // DB body erasure after a full deletion (this memory's revisions are gone)
        assertEquals(0L, countRows("SELECT 1 FROM memory.memory_revision WHERE memory_id=?::uuid", fixture.memoryId()));
    }

    // ── 10. closeout-built memory: formal entry → delete end-to-end ──────────

    @Test
    @Order(10)
    void closeoutBuiltMemoryDeletionEndToEnd() throws Exception {
        Built built = buildCloseout("closeout-delete");
        UUID submissionId = built.submission().submissionId();

        // 1. Real closeout: 202 + CANONICAL_COMMITTED, memory is S2B-readable.
        Response closeout = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY, submissionId.toString());
        assertEquals(202, closeout.status());
        assertEquals("CANONICAL_COMMITTED", JSON.readTree(closeout.body()).get("phase").asText());
        UUID memoryId = deterministicId("memory:", submissionId);
        assertEquals(200, get("/v1/memories/" + memoryId, TOKEN).status());

        // capture_scope / closeout_run audit rows exist; capture_scope_unit > 0.
        UUID scopeId = dsl.fetchOne("SELECT scope_id FROM runtime.closeout_run WHERE submission_id=?::uuid", submissionId)
                .get(0, UUID.class);
        long unitsBefore = countRows("SELECT 1 FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId);
        assertTrue(unitsBefore > 0, "closeout must leave capture_scope_unit rows");
        String scopeRowBefore = dsl.fetchOne(
                        "SELECT row_to_json(t)::text FROM runtime.capture_scope t WHERE scope_id=?::uuid", scopeId)
                .get(0, String.class);
        String closeoutRunRowBefore = dsl.fetchOne(
                        "SELECT row_to_json(t)::text FROM runtime.closeout_run t WHERE submission_id=?::uuid", submissionId)
                .get(0, String.class);
        List<String> otherUnitsBefore = dsl.fetch(
                        "SELECT scope_id::text || '|' || source_unit_id::text FROM runtime.capture_scope_unit "
                                + "WHERE scope_id <> ?::uuid ORDER BY 1",
                        scopeId)
                .getValues(0, String.class);

        // 2. Preview the same memory.
        long[] facts = readFacts(memoryId);
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(memoryId, facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(memoryId, facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        assertEquals(200, preview.status());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();

        // 3. Confirm → same real run.
        Response confirm = post("/v1/deletion-previews/" + previewId + "/confirm",
                confirmBody(memoryId, facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash),
                TOKEN, CAPABILITY, UUID.randomUUID().toString());
        assertEquals(202, confirm.status());
        UUID runId = UUID.fromString(JSON.readTree(confirm.body()).get("runId").asText());

        // 4. Run status CANONICAL_COMMITTED.
        Response status = get("/v1/deletion-runs/" + runId, TOKEN);
        assertEquals(200, status.status());
        assertEquals("CANONICAL_COMMITTED", JSON.readTree(status.body()).get("phase").asText());

        // 5. Memory + revisions gone.
        assertEquals(0L, countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", memoryId));
        assertEquals(0L, countRows("SELECT 1 FROM memory.memory_revision WHERE memory_id=?::uuid", memoryId));

        // 6. capture_scope + closeout_run preserved with unchanged fields.
        assertEquals(scopeRowBefore, dsl.fetchOne(
                        "SELECT row_to_json(t)::text FROM runtime.capture_scope t WHERE scope_id=?::uuid", scopeId)
                .get(0, String.class));
        assertEquals(closeoutRunRowBefore, dsl.fetchOne(
                        "SELECT row_to_json(t)::text FROM runtime.closeout_run t WHERE submission_id=?::uuid", submissionId)
                .get(0, String.class));

        // 7. This scope's capture_scope_unit now 0; other scopes unchanged.
        assertEquals(0L, countRows("SELECT 1 FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId));
        List<String> otherUnitsAfter = dsl.fetch(
                        "SELECT scope_id::text || '|' || source_unit_id::text FROM runtime.capture_scope_unit "
                                + "WHERE scope_id <> ?::uuid ORDER BY 1",
                        scopeId)
                .getValues(0, String.class);
        assertEquals(otherUnitsBefore, otherUnitsAfter);

        // 8. Payload files NOT_FOUND.
        var tasks = dsl.fetch("SELECT object_ref FROM runtime.deletion_payload_task WHERE deletion_run_id=?::uuid", runId);
        assertFalse(tasks.isEmpty(), "deletion run must have payload tasks");
        for (var row : tasks) {
            Path file = payloadRoot.resolve(row.get(0, String.class));
            assertFalse(Files.exists(file), "payload file must be deleted: " + row.get(0, String.class));
        }

        // 9. S2B list excludes target; detail/evidence unreadable.
        assertProblem(get("/v1/memories/" + memoryId, TOKEN), 404, "RETRIEVAL_NO_MATCH");
        assertProblem(get("/v1/memories/" + memoryId + "/evidence", TOKEN), 404, "RETRIEVAL_NO_MATCH");

        // 10. Deletion closure/fence/decision/run audit preserved.
        assertTrue(countRows("SELECT 1 FROM memory.deletion_closure WHERE closure_id=?::uuid", previewId) >= 1);
        assertTrue(countRows("SELECT 1 FROM memory.deletion_fence WHERE closure_id=?::uuid", previewId) >= 1);
        assertTrue(countRows(
                        "SELECT 1 FROM memory.decision WHERE decision_kind='USER_DELETE_CONFIRM' AND target_id=?::uuid",
                        previewId)
                >= 1);
        assertTrue(countRows("SELECT 1 FROM runtime.deletion_run WHERE deletion_run_id=?::uuid", runId) >= 1);
    }

    // ── 11. shared-evidence fixture: A/B share, no orphans, delete A → B → C retained ──

    @Test
    @Order(11)
    void sharedEvidenceFixtureDeleteFlow() throws Exception {
        long anchorsBefore = countRows("SELECT 1 FROM evidence.source_anchor");
        long unitsBefore = countRows("SELECT 1 FROM evidence.source_unit");
        long payloadsBefore = countRows("SELECT 1 FROM evidence.source_payload");

        Response seed = post("/v1/deletion-fixtures", null, TOKEN, CAPABILITY, null);
        assertEquals(200, seed.status());
        JsonNode seedJson = JSON.readTree(seed.body());
        UUID memoryA = UUID.fromString(seedJson.get("memoryA").asText());
        UUID memoryB = UUID.fromString(seedJson.get("memoryB").asText());
        UUID memoryC = UUID.fromString(seedJson.get("memoryC").asText());
        assertEquals("SHARED_FIXTURE_NOT_PROOF_OF_MULTI_CANDIDATE_CLOSEOUT", seedJson.get("sharedFixtureBoundary").asText());

        // No B-exclusive orphans: net new evidence is A(2) + C(1), not A(2)+B(1)+C(1).
        assertEquals(anchorsBefore + 3, countRows("SELECT 1 FROM evidence.source_anchor"));
        assertEquals(unitsBefore + 3, countRows("SELECT 1 FROM evidence.source_unit"));
        assertEquals(payloadsBefore + 3, countRows("SELECT 1 FROM evidence.source_payload"));

        // A and B share exactly one anchor/unit/payload.
        String sharedAnchor = scalar("SELECT to_anchor_id::text FROM memory.memory_relation WHERE relation_type='EVIDENCED_BY' GROUP BY to_anchor_id HAVING count(*) > 1");
        assertNotNull(sharedAnchor);
        String sharedUnit = scalar("SELECT source_unit_id::text FROM evidence.source_anchor_unit WHERE anchor_id=?::uuid", UUID.fromString(sharedAnchor));
        String sharedPayload = scalar("SELECT payload_id::text FROM evidence.source_payload WHERE source_unit_id=?::uuid", UUID.fromString(sharedUnit));
        String sharedObjectRef = scalar("SELECT object_ref FROM evidence.source_payload WHERE payload_id=?::uuid", UUID.fromString(sharedPayload));

        // Preview A: the shared segment carries sharedByMemoryIds=[B]; the exclusive segment is empty.
        long[] factsA = readFacts(memoryA);
        String requestHashA = LocalV1DeletionCanonicalizer.requestHashHex(memoryA, factsA[0], factsA[1]);
        Response previewA = post("/v1/deletion-previews",
                previewBody(memoryA, factsA[0], factsA[1], requestHashA), TOKEN, null, UUID.randomUUID().toString());
        assertEquals(200, previewA.status());
        JsonNode previewABody = JSON.readTree(previewA.body());
        JsonNode previewSharedMemories = previewABody.get("sharedMemories");
        assertEquals(1, previewSharedMemories.size());
        assertEquals(memoryB.toString(), previewSharedMemories.get(0).get("memoryId").asText());
        JsonNode previewEvidenceA = previewABody.get("evidence");
        assertEquals(2, previewEvidenceA.size());
        for (JsonNode item : previewEvidenceA) {
            if (item.get("ordinal").asLong() == 1L) {
                assertEquals(1, item.get("sharedByMemoryIds").size());
                assertEquals(memoryB.toString(), item.get("sharedByMemoryIds").get(0).asText());
            } else {
                assertEquals(0, item.get("sharedByMemoryIds").size());
            }
        }

        // Delete A: B + shared payload retained.
        deleteMemory(memoryA);
        assertProblem(get("/v1/memories/" + memoryA, TOKEN), 404, "RETRIEVAL_NO_MATCH");
        assertEquals(200, get("/v1/memories/" + memoryB, TOKEN).status());
        assertEquals(1L, countRows("SELECT 1 FROM evidence.source_payload WHERE payload_id=?::uuid", UUID.fromString(sharedPayload)));
        assertTrue(Files.exists(payloadRoot.resolve(sharedObjectRef)), "shared payload file retained after deleting A");

        // Delete B: shared anchor/unit/payload metadata + file finally cleared.
        deleteMemory(memoryB);
        assertEquals(0L, countRows("SELECT 1 FROM evidence.source_anchor WHERE anchor_id=?::uuid", UUID.fromString(sharedAnchor)));
        assertEquals(0L, countRows("SELECT 1 FROM evidence.source_unit WHERE source_unit_id=?::uuid", UUID.fromString(sharedUnit)));
        assertEquals(0L, countRows("SELECT 1 FROM evidence.source_payload WHERE payload_id=?::uuid", UUID.fromString(sharedPayload)));
        assertFalse(Files.exists(payloadRoot.resolve(sharedObjectRef)), "shared payload file cleared after deleting B");

        // C untouched.
        assertEquals(200, get("/v1/memories/" + memoryC, TOKEN).status());
    }

    // ── 12. closeout evidence + deletion preview return formal display names ──

    @Test
    @Order(12)
    void closeoutEvidenceAndPreviewReturnDisplayNames() throws Exception {
        Built built = buildCloseout("display-names");
        UUID submissionId = built.submission().submissionId();
        Response closeout = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY, submissionId.toString());
        assertEquals(202, closeout.status());
        UUID memoryId = deterministicId("memory:", submissionId);

        Response evidence = get("/v1/memories/" + memoryId + "/evidence", TOKEN);
        assertEquals(200, evidence.status());
        JsonNode evidenceItems = JSON.readTree(evidence.body()).get("evidenceItems");
        assertEquals(2, evidenceItems.size());
        assertEquals("hide", evidenceItems.get(0).get("displayLabel").asText());
        assertEquals("小林", evidenceItems.get(1).get("displayLabel").asText());

        long[] facts = readFacts(memoryId);
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(memoryId, facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(memoryId, facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        assertEquals(200, preview.status());
        JsonNode previewEvidence = JSON.readTree(preview.body()).get("evidence");
        assertEquals(2, previewEvidence.size());
        assertEquals("hide", previewEvidence.get(0).get("displayLabel").asText());
        assertEquals("小林", previewEvidence.get(1).get("displayLabel").asText());

        // Deletion preview must carry the same real anchor ids and ordering as read/evidence.
        for (int i = 0; i < 2; i++) {
            assertEquals(evidenceItems.get(i).get("anchorId").asText(),
                    previewEvidence.get(i).get("anchorId").asText());
            assertEquals(evidenceItems.get(i).get("ordinal").asLong(),
                    previewEvidence.get(i).get("ordinal").asLong());
        }
    }

    private static void deleteMemory(UUID memoryId) throws Exception {
        long[] facts = readFacts(memoryId);
        String requestHashHex = LocalV1DeletionCanonicalizer.requestHashHex(memoryId, facts[0], facts[1]);
        Response preview = post("/v1/deletion-previews",
                previewBody(memoryId, facts[0], facts[1], requestHashHex), TOKEN, null, UUID.randomUUID().toString());
        assertEquals(200, preview.status());
        JsonNode previewJson = JSON.readTree(preview.body());
        UUID previewId = UUID.fromString(previewJson.get("previewId").asText());
        long previewRevision = previewJson.get("previewRevision").asLong();
        String manifestHash = previewJson.get("manifestHash").asText();
        Response confirm = post("/v1/deletion-previews/" + previewId + "/confirm",
                confirmBody(memoryId, facts[0], facts[1], requestHashHex, previewId, previewRevision, manifestHash),
                TOKEN, CAPABILITY, UUID.randomUUID().toString());
        assertEquals(202, confirm.status());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

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
                "hidenest.local-v1.synthetic-capability", CAPABILITY,
                "logging.level.root", "WARN"));
        return application.run();
    }

    private static Fixture createMemory(String marker) {
        LocalV1S1WindowCloseCoordinator s1 = api.getBean(LocalV1S1WindowCloseCoordinator.class);
        UUID actor = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String body = "合成删除记忆-" + marker + "-" + CHAT_CANARY;
        String key = "del-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key,
                sha256Bytes(key),
                actor,
                "Interpretation",
                body,
                sha256Bytes(body),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitOne, actor, 1L, "unit-one-" + marker, OffsetDateTime.now(), "evidence-one"),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitTwo, actor, 2L, "unit-two-" + marker, OffsetDateTime.now(), "evidence-two")),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        s1.confirm(new LocalV1S1ConfirmRequest(
                "confirm-" + key,
                sha256Bytes("confirm-" + key),
                prepared.proposalRevisionId(),
                prepared.reviewSessionId(),
                memoryId,
                UUID.randomUUID(),
                new byte[32]));
        return new Fixture(memoryId);
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private static long[] readFacts(UUID memoryId) {
        var row = dsl.fetchOne(
                "SELECT mr.revision_no, rec.current_policy_revision_no FROM memory.memory_record rec "
                        + "JOIN memory.memory_revision mr ON mr.memory_revision_id = rec.current_revision_id "
                        + "WHERE rec.memory_id=?::uuid",
                memoryId);
        return new long[] {row.get(0, Long.class), row.get(1, Long.class)};
    }

    private static String previewBody(UUID targetId, long revision, long policyRevision) {
        return previewBody(targetId, revision, policyRevision,
                LocalV1DeletionCanonicalizer.requestHashHex(targetId, revision, policyRevision));
    }

    private static String previewBody(UUID targetId, long revision, long policyRevision, String requestHashHex) {
        ObjectNode root = JSON.createObjectNode();
        root.put("targetId", targetId.toString());
        root.put("expectedRevision", revision);
        root.put("expectedPolicyRevision", policyRevision);
        root.put("requestManifestHash", requestHashHex);
        return root.toString();
    }

    private static String confirmBody(
            UUID targetId,
            long revision,
            long policyRevision,
            String requestHashHex,
            UUID previewId,
            long previewRevision,
            String manifestHash) {
        ObjectNode root = JSON.createObjectNode();
        root.put("targetId", targetId.toString());
        root.put("expectedRevision", revision);
        root.put("expectedPolicyRevision", policyRevision);
        root.put("requestManifestHash", requestHashHex);
        root.put("previewId", previewId.toString());
        root.put("previewRevision", previewRevision);
        root.put("manifestHash", manifestHash);
        return root.toString();
    }

    private static JsonNode findMember(JsonNode members, String memberKind) {
        for (JsonNode member : members) {
            if (memberKind.equals(member.get("memberKind").asText())) {
                return member;
            }
        }
        return null;
    }

    private static List<Path> snapshotPayloadFiles() throws Exception {
        try (var paths = Files.walk(payloadRoot)) {
            return paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".payload"))
                    .map(Path::toAbsolutePath)
                    .toList();
        }
    }

    private static void deletePayloadFiles() throws Exception {
        try (var paths = Files.walk(payloadRoot)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    private static List<Response> runConcurrently(int threads, String path, String body, String idempotencyKey)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return post(path, body, TOKEN, CAPABILITY, idempotencyKey);
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

    private static Response post(String path, String body, String token, String capability, String idempotencyKey)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (capability != null) builder.header("X-Action-Capability", capability);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        builder.method("POST", body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static Response get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.GET();
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
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
        assertFalse(body.toString().contains(CAPABILITY));
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElseThrow();
    }

    private static long countRows(String sql, Object... binds) {
        return dsl.resultQuery(sql, binds).fetch().size();
    }

    private static String scalar(String sql, Object... binds) {
        return dsl.fetchOne(sql, binds).get(0, String.class);
    }

    private static String scalarOrNull(String sql, Object... binds) {
        var row = dsl.fetchOne(sql, binds);
        return row == null ? null : row.get(0, String.class);
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
    }

    // ── closeout builder (mirrors LocalV1CloseoutWriteHttpIntegrationTest) ──

    private static Built buildCloseout(String marker) {
        UUID submissionId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyText = "合成删除记忆-" + marker + "-" + CHAT_CANARY;
        String bodyHash = sha256Hex(bodyText);

        UUID msg1Unit = deterministicId("msg1:", submissionId);
        UUID msg2Unit = deterministicId("msg2:", submissionId);
        UUID actor1 = deterministicId("a1:", submissionId);
        UUID actor2 = deterministicId("a2:", submissionId);
        UUID perspectiveActor = actor2;
        UUID anchor1 = deterministicId("anchor1:", submissionId);
        String msg1 = "只保存必要证据";
        String msg2 = "已确认本版";

        EvidenceMessage m1 = new EvidenceMessage(msg1Unit, actor1, 1L, "unit-" + marker + "-1",
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), msg1, sha256Hex(msg1));
        EvidenceMessage m2 = new EvidenceMessage(msg2Unit, actor2, 2L, "unit-" + marker + "-2",
                OffsetDateTime.parse("2026-08-12T09:30:01Z"), msg2, sha256Hex(msg2));
        List<EvidenceMessage> messages = List.of(m1, m2);
        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, "", messages));

        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(perspectiveActor, "INTERPRETATION", bodyText, bodyHash);
        UserConfirmation placeholder = new UserConfirmation("CONFIRM", "", confirmationUnit);
        List<SourceAnchor> anchors = List.of(
                new SourceAnchor(anchor1, List.of(
                        new AnchorUnit(msg1Unit, 0L, (long) msg1.codePointCount(0, msg1.length()), 1L),
                        new AnchorUnit(msg2Unit, 0L, (long) msg2.codePointCount(0, msg2.length()), 2L))));

        LocalV1CloseoutSubmission provisional = new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection, placeholder, anchors, manifest, "");
        String reviewManifestHash = LocalV1CloseoutCanonicalizer.reviewManifestHash(provisional);
        String proof = LocalV1CloseoutCanonicalizer.confirmationProof(
                threadId, confirmationUnit, reviewManifestHash, submissionId);

        LocalV1CloseoutSubmission submission = new LocalV1CloseoutSubmission(
                submissionId, threadId, hideSelection,
                new UserConfirmation("CONFIRM", reviewManifestHash, confirmationUnit),
                anchors, manifest, proof);
        return new Built(submission, toCloseoutJson(submission));
    }

    private static String toCloseoutJson(LocalV1CloseoutSubmission s) {
        ObjectNode root = JSON.createObjectNode();
        root.put("submissionId", s.submissionId().toString());
        root.put("threadId", s.threadId().toString());
        root.put("confirmationProof", s.confirmationProof());

        ObjectNode hs = root.putObject("hideSelection");
        hs.put("perspectiveActorId", s.hideSelection().perspectiveActorId().toString());
        hs.put("memoryType", s.hideSelection().memoryType());
        hs.put("bodyText", s.hideSelection().bodyText());
        hs.put("bodyHash", s.hideSelection().bodyHash());

        ObjectNode uc = root.putObject("userConfirmation");
        uc.put("decision", s.userConfirmation().decision());
        uc.put("reviewManifestHash", s.userConfirmation().reviewManifestHash());
        uc.put("confirmationSourceUnitId", s.userConfirmation().confirmationSourceUnitId().toString());

        ArrayNode anchors = root.putArray("sourceAnchors");
        for (SourceAnchor a : s.sourceAnchors()) {
            ObjectNode an = anchors.addObject();
            an.put("anchorId", a.anchorId().toString());
            ArrayNode units = an.putArray("units");
            for (AnchorUnit u : a.units()) {
                ObjectNode un = units.addObject();
                un.put("sourceUnitId", u.sourceUnitId().toString());
                un.put("fromOffset", u.fromOffset());
                un.put("toOffset", u.toOffset());
                un.put("ordinal", u.ordinal());
            }
        }

        ObjectNode manifest = root.putObject("threadReaderManifest");
        manifest.put("schemaVersion", s.threadReaderManifest().schemaVersion());
        manifest.put("fromOrdinal", s.threadReaderManifest().fromOrdinal());
        manifest.put("toOrdinal", s.threadReaderManifest().toOrdinal());
        manifest.put("continuous", s.threadReaderManifest().continuous());
        manifest.put("manifestHash", s.threadReaderManifest().manifestHash());
        ArrayNode msgs = manifest.putArray("selectedEvidenceMessages");
        for (EvidenceMessage m : s.threadReaderManifest().selectedEvidenceMessages()) {
            ObjectNode mn = msgs.addObject();
            mn.put("sourceUnitId", m.sourceUnitId().toString());
            mn.put("actorId", m.actorId().toString());
            mn.put("ordinal", m.ordinal());
            mn.put("externalUnitRef", m.externalUnitRef());
            mn.put("occurredAt", m.occurredAt().toInstant().toString());
            mn.put("bodyText", m.bodyText());
            mn.put("bodyHash", m.bodyHash());
        }
        return root.toString();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : digest) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }

    private record Built(LocalV1CloseoutSubmission submission, String json) {}

    private record Fixture(UUID memoryId) {}

    private record Response(int status, Map<String, List<String>> headers, String body) {}
}
