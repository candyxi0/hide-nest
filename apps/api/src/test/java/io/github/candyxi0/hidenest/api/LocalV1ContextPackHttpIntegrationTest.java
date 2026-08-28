package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.HideSelection;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.UserConfirmation;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
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

/**
 * HTTP-level assembly proof for {@code POST /v1/context-packs}: bearer gate, exact cosine ranking,
 * idempotent replay and pre-embedding request rejection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1ContextPackHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CAPABILITY = "SyntheticCap-9xQ2zW8vB5nM3kR7dF1hJ4cT6yU0lP9w";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String EMBEDDING_BASE_URL_PROPERTY = "HIDE_NEST_EMBEDDING_BASE_URL";
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static ConfigurableApplicationContext api;
    private static URI base;
    private static HttpServer embeddingServer;
    private static final AtomicInteger embeddingCalls = new AtomicInteger();

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
        assertEquals(22, Flyway.configure()
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
        payloadRoot = Files.createTempDirectory("local-v1-context-pack-http-");

        embeddingServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        embeddingServer.createContext("/embed", exchange -> {
            embeddingCalls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode root = JSON.readTree(body);
            String text = root.path("texts").get(0).asText();
            // 颜色B -> basis(1); empty-search marker -> basis(2); policy tests -> basis(3); else basis(0).
            // No memory is ever projected to basis(2), so a query on it yields NO_RELEVANT_RESULT.
            // The policy marker isolates those tests onto their own basis so they cannot displace the
            // basis(0)/basis(1) memories that the original ranking/replay tests assert on.
            String vector;
            if (text.contains("颜色B")) {
                vector = basisLiteral(1);
            } else if (text.contains("仅空检索")) {
                vector = basisLiteral(2);
            } else if (text.contains("政策")) {
                vector = basisLiteral(3);
            } else {
                vector = basisLiteral(0);
            }
            String response = "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                    + ",\"vectors\":[" + vector + "]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        embeddingServer.setExecutor(null);
        embeddingServer.start();

        String baseUrl = "http://127.0.0.1:" + embeddingServer.getAddress().getPort();
        System.setProperty(EMBEDDING_BASE_URL_PROPERTY, baseUrl);
        api = startApi(password);
        int port = ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        base = URI.create("http://127.0.0.1:" + port);
    }

    @AfterAll
    static void tearDown() throws Exception {
        System.clearProperty(EMBEDDING_BASE_URL_PROPERTY);
        if (embeddingServer != null) embeddingServer.stop(0);
        if (api != null) api.close();
        if (postgres != null) postgres.stop();
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    @Order(1)
    void createReturnsAFirstWithHigherScore() throws Exception {
        UUID memoryA = submit("颜色A");
        UUID memoryB = submit("颜色B");

        Response response = post("/v1/context-packs", contextPackBody("她喜欢什么颜色？"), "cp-http-1");
        assertEquals(200, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("SUCCEEDED", body.get("resultCategory").asText());
        JsonNode memories = body.get("memories");
        assertTrue(memories.size() >= 1);
        double firstScore = memories.get(0).get("score").asDouble();
        assertEquals(1.0, firstScore, 1e-6);
        for (JsonNode m : memories) {
            assertTrue(firstScore >= m.get("score").asDouble(), "memories must be in descending score order");
        }
        boolean deliveredA = false;
        for (JsonNode m : memories) {
            if (memoryA.toString().equals(m.get("memoryId").asText())) {
                deliveredA = true;
                break;
            }
        }
        assertTrue(deliveredA, "A must be delivered");
        assertEquals(memories.size(), body.get("policyRevisionSet").size());
    }

    @Test
    void replayReturnsIdenticalResponse() throws Exception {
        submit("颜色A");
        String requestBody = contextPackBody("她喜欢什么颜色？");

        Response first = post("/v1/context-packs", requestBody, "cp-http-replay");
        Response replay = post("/v1/context-packs", requestBody, "cp-http-replay");

        assertEquals(200, first.status());
        assertEquals(200, replay.status());
        JsonNode a = JSON.readTree(first.body());
        JsonNode b = JSON.readTree(replay.body());
        assertEquals(a.get("requestId").asText(), b.get("requestId").asText());
        assertEquals(a.get("deliveryId").asText(), b.get("deliveryId").asText());
        assertEquals(a.get("issuedAt").asText(), b.get("issuedAt").asText());
        assertEquals(a.get("expiresAt").asText(), b.get("expiresAt").asText());
        assertEquals(a.get("memories"), b.get("memories"));
    }

    @Test
    void differentValueConflicts() throws Exception {
        submit("颜色A");
        String key = "cp-http-conflict";
        assertEquals(200, post("/v1/context-packs", contextPackBody("她喜欢什么颜色？"), key).status());
        Response conflict = post("/v1/context-packs", contextPackBody("另一个完全不同的查询"), key);
        assertEquals(409, conflict.status());
    }

    @Test
    void validationRejectsBeforeEmbedding() throws Exception {
        assertEquals(400, post("/v1/context-packs", contextPackBody("查询"), null).status());

        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"not-a-uuid\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\"}", "cp-http-invalid-uuid").status());

        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"unknownField\":true}", "cp-http-unknown").status());

        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"   \"}", "cp-http-blank").status());
    }

    @Test
    void deepSearchIsNotImplemented() throws Exception {
        Response response = post("/v1/context-packs/deep-search", contextPackBody("查询"), "cp-http-deep");
        assertTrue(response.status() == 403 || response.status() == 404, "deep-search must not be implemented");
    }

    // ── Task43A Phase 3: policy fields over the wire ──────────────────────

    @Test
    void oldFourFieldRequestUsesDefaultMaxResultsThree() throws Exception {
        submit("政策DM1");
        submit("政策DM2");
        submit("政策DM3");
        submit("政策DM4");
        Response response = post("/v1/context-packs", contextPackBody("政策检索目标"), "cp-http-default-max");
        assertEquals(200, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("SUCCEEDED", body.get("resultCategory").asText());
        // default maxResults=3 caps the response regardless of how many matching memories exist
        assertEquals(3, body.get("memories").size());
    }

    @Test
    void explicitMaxResultsDeliversExactlyThatMany() throws Exception {
        submit("政策EM1");
        submit("政策EM2");
        submit("政策EM3");
        submit("政策EM4");
        Response response = post(
                "/v1/context-packs",
                contextPackBodyWithPolicy("政策检索目标", 2, null),
                "cp-http-explicit-max");
        assertEquals(200, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("SUCCEEDED", body.get("resultCategory").asText());
        assertEquals(2, body.get("memories").size());
    }

    @Test
    void explicitMinScoreAndBothFieldsAccepted() throws Exception {
        submit("政策SM1");
        submit("政策SM2");
        String onlyMin = "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                + "\",\"purpose\":\"RECALL\",\"query\":\"政策检索目标\",\"minScore\":0.4}";
        assertEquals(200, post("/v1/context-packs", onlyMin, "cp-http-only-min").status());

        String both = "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                + "\",\"purpose\":\"RECALL\",\"query\":\"政策检索目标\",\"maxResults\":1,\"minScore\":0.4}";
        Response response = post("/v1/context-packs", both, "cp-http-both");
        assertEquals(200, response.status());
        assertEquals(1, JSON.readTree(response.body()).get("memories").size());
    }

    @Test
    void emptyResultIsHttp200NoRelevantResult() throws Exception {
        // query maps to basis(2) which no memory is projected to -> every candidate is below minScore
        Response response = post(
                "/v1/context-packs", contextPackBody("仅空检索目标"), "cp-http-empty");
        assertEquals(200, response.status());
        JsonNode body = JSON.readTree(response.body());
        assertEquals("NO_RELEVANT_RESULT", body.get("resultCategory").asText());
        assertEquals(0, body.get("memories").size());
        assertEquals(0, body.get("policyRevisionSet").size());
    }

    @Test
    void explicitNullPolicyFieldsAreRejected422() throws Exception {
        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"maxResults\":null}",
                "cp-http-null-max").status());
        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"minScore\":null}",
                "cp-http-null-min").status());
    }

    @Test
    void wrongTypeAndRangePolicyFieldsAreRejected422() throws Exception {
        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"maxResults\":\"three\"}",
                "cp-http-type-max").status());
        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"minScore\":1.5}",
                "cp-http-range-min").status());
        assertEquals(422, post("/v1/context-packs",
                "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                        + "\",\"purpose\":\"p\",\"query\":\"查询\",\"maxResults\":6}",
                "cp-http-range-max").status());
    }

    @Test
    void sameKeyWithDifferentPolicyValuesConflicts409() throws Exception {
        submit("政策CF1");
        String key = "cp-http-policy-conflict";
        String firstBody = contextPackBodyWithPolicy("政策检索目标", 3, null);
        assertEquals(200, post("/v1/context-packs", firstBody, key).status());
        String different = contextPackBodyWithPolicy("政策检索目标", 2, null);
        assertEquals(409, post("/v1/context-packs", different, key).status());
    }

    // ── Task50A Bubble HTTP vertical ─────────────────────────────────────

    @Test
    void bubbleResolveUsesBearerNoStoreAndExactMinimalProjection() throws Exception {
        submit("泡泡HTTP投影");
        String turn = "bubble-http-projection-" + UUID.randomUUID();
        Response response = post("/v1/bubbles/resolve", bubbleBody("room-http", turn, "相关语义查询"), null);

        assertEquals(200, response.status());
        assertEquals("no-store", response.cacheControl());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(java.util.Set.of("status", "items"), propertySet(body));
        assertEquals("BUBBLE_READY", body.get("status").asText());
        assertEquals(1, body.get("items").size());
        assertEquals(
                java.util.Set.of("bodyText", "memoryType", "evidenceAgeDays"),
                propertySet(body.get("items").get(0)));

        assertEquals(
                401,
                postWithoutBearer("/v1/bubbles/resolve", bubbleBody("r", "t", "q"))
                        .status());
    }

    @Test
    void bubbleNoMatchReplayConflictAndQueryNonPersistenceAreExact() throws Exception {
        String marker = "泡泡查询不持久化-" + UUID.randomUUID();
        String turn = "bubble-http-replay-" + UUID.randomUUID();
        String request = bubbleBody("room-replay", turn, "仅空检索目标" + marker);
        int before = embeddingCalls.get();
        Response first = post("/v1/bubbles/resolve", request, null);
        int afterFirst = embeddingCalls.get();
        Response replay = post("/v1/bubbles/resolve", request, null);

        assertEquals(200, first.status());
        assertEquals("NO_MATCH", JSON.readTree(first.body()).get("status").asText());
        assertEquals(JSON.readTree(first.body()), JSON.readTree(replay.body()));
        assertEquals(before + 1, afterFirst);
        assertEquals(afterFirst, embeddingCalls.get());
        assertEquals(
                409,
                post("/v1/bubbles/resolve", bubbleBody("room-replay", turn, "仅空检索目标-异值"), null)
                        .status());
        assertEquals(afterFirst, embeddingCalls.get());
        assertEquals(
                0,
                dsl.fetchOne(
                                "SELECT count(*) FROM ("
                                        + "SELECT row_to_json(r)::text AS value FROM runtime.bubble_turn_receipt r "
                                        + "UNION ALL SELECT row_to_json(i)::text FROM runtime.bubble_delivery_item i "
                                        + "UNION ALL SELECT row_to_json(l)::text FROM runtime.bubble_room_revision_ledger l"
                                        + ") facts WHERE value LIKE ?",
                                "%" + marker + "%")
                        .get(0, Integer.class));
    }

    @Test
    void bubbleSchemaSpaceAndPolicyFieldsFailBeforeEmbedding() throws Exception {
        int before = embeddingCalls.get();
        for (String forbidden : List.of("minScore", "maxResults", "bubbleEnabled", "bootstrapCount")) {
            String body = bubbleBody("room-schema", "turn-" + forbidden, "query");
            body = body.substring(0, body.length() - 1) + ",\"" + forbidden + "\":1}";
            assertEquals(422, post("/v1/bubbles/resolve", body, null).status());
        }
        assertEquals(
                403,
                post(
                                "/v1/bubbles/resolve",
                                "{\"spaceKey\":\"other-space\",\"roomKey\":\"r\",\"turnKey\":\"t\",\"queryText\":\"q\"}",
                                null)
                        .status());
        assertEquals(before, embeddingCalls.get());
    }

    @Test
    void bubbleWrongSpaceRejectsBeforeAnyFactProbe() throws Exception {
        String room = "room-wrong-space-" + UUID.randomUUID();
        String turn = "turn-wrong-space-" + UUID.randomUUID();
        assertEquals(
                200,
                post("/v1/bubbles/resolve", bubbleBody(room, turn, "仅空检索目标"), null)
                        .status());
        int embeddingBefore = embeddingCalls.get();
        int receiptsBefore = dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt")
                .get(0, Integer.class);

        Response rejected = post(
                "/v1/bubbles/resolve",
                "{\"spaceKey\":\"other-space\",\"roomKey\":\"" + room + "\",\"turnKey\":\"" + turn
                        + "\",\"queryText\":\"仅空检索目标\"}",
                null);

        assertEquals(403, rejected.status());
        assertTrue(rejected.body().contains("ACCESS_DENIED"), "unexpected body: " + rejected.body());
        assertEquals(embeddingBefore, embeddingCalls.get());
        assertEquals(
                receiptsBefore,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt").get(0, Integer.class));
        assertEquals(
                1,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key=?", turn)
                        .get(0, Integer.class));
        assertEquals(
                0,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_delivery_item WHERE turn_key=?", turn)
                        .get(0, Integer.class));
        assertEquals(
                0,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_room_revision_ledger WHERE turn_key=?", turn)
                        .get(0, Integer.class));
    }

    @Test
    void bubbleRoomPurgeIsExactAndIdempotent() throws Exception {
        String roomPurge = "room-purge-" + UUID.randomUUID();
        String roomKeep = "room-keep-" + UUID.randomUUID();
        assertEquals(
                200,
                post("/v1/bubbles/resolve", bubbleBody(roomPurge, "turn-purge-http", "仅空检索目标"), null)
                        .status());
        assertEquals(
                200,
                post("/v1/bubbles/resolve", bubbleBody(roomKeep, "turn-keep-http", "仅空检索目标"), null)
                        .status());
        String purge = "{\"spaceKey\":\"local-v1-synthetic-space\",\"roomKey\":\"" + roomPurge + "\"}";
        assertEquals(200, post("/v1/bubbles/rooms/purge", purge, null).status());
        assertEquals(200, post("/v1/bubbles/rooms/purge", purge, null).status());
        assertEquals(
                0,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE room_key = ?", roomPurge)
                        .get(0, Integer.class));
        assertEquals(
                1,
                dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE room_key = ?", roomKeep)
                        .get(0, Integer.class));
    }

    @Test
    void bubbleConcurrentSameTurnIsOneFactAndSameRoomRevisionIsUnique() throws Exception {
        submit("泡泡并发候选A");
        submit("泡泡并发候选B");
        try (var executor = Executors.newFixedThreadPool(2)) {
            String sameTurn = "turn-same-" + UUID.randomUUID();
            String sameRequest = bubbleBody("room-same-turn", sameTurn, "并发查询");
            CompletableFuture<Response> first =
                    CompletableFuture.supplyAsync(() -> uncheckedPost("/v1/bubbles/resolve", sameRequest), executor);
            CompletableFuture<Response> second =
                    CompletableFuture.supplyAsync(() -> uncheckedPost("/v1/bubbles/resolve", sameRequest), executor);
            Response firstResponse = first.join();
            Response secondResponse = second.join();
            assertEquals(200, firstResponse.status());
            assertEquals(JSON.readTree(firstResponse.body()), JSON.readTree(secondResponse.body()));
            assertEquals(
                    1,
                    dsl.fetchOne("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key=?", sameTurn)
                            .get(0, Integer.class));

            String room = "room-concurrent-" + UUID.randomUUID();
            String requestA = bubbleBody(room, "turn-a-" + UUID.randomUUID(), "并发查询");
            String requestB = bubbleBody(room, "turn-b-" + UUID.randomUUID(), "并发查询");
            CompletableFuture<Response> roomA =
                    CompletableFuture.supplyAsync(() -> uncheckedPost("/v1/bubbles/resolve", requestA), executor);
            CompletableFuture<Response> roomB =
                    CompletableFuture.supplyAsync(() -> uncheckedPost("/v1/bubbles/resolve", requestB), executor);
            assertEquals(200, roomA.join().status());
            assertEquals(200, roomB.join().status());
            var counts = dsl.fetchOne(
                    "SELECT count(*), count(DISTINCT memory_revision_id) "
                            + "FROM runtime.bubble_room_revision_ledger WHERE room_key=?",
                    room);
            assertEquals(counts.get(0, Integer.class), counts.get(1, Integer.class));
            assertTrue(counts.get(0, Integer.class) >= 1);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ConfigurableApplicationContext startApi(String password) {
        SpringApplication application = new SpringApplication(HideNestApiApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.setDefaultProperties(Map.of(
                "spring.profiles.active", "local-v1-synthetic",
                "server.address", "127.0.0.1",
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

    private UUID submit(String marker) throws Exception {
        Built built = build(marker);
        UUID submissionId = built.submission().submissionId();
        Response response = post(
                "/v1/closeout-submissions", built.json(), submissionId.toString(), CAPABILITY);
        assertEquals(202, response.status());
        return deterministicId("memory:", submissionId);
    }

    private static Built build(String marker) {
        UUID submissionId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyText = "合成记忆正文-" + marker;
        String bodyHash = sha256Hex(bodyText);

        UUID msg1Unit = deterministicId("msg1:", submissionId);
        UUID msg2Unit = deterministicId("msg2:", submissionId);
        UUID actor1 = deterministicId("a1:", submissionId);
        UUID actor2 = deterministicId("a2:", submissionId);
        UUID anchor1 = deterministicId("anchor1:", submissionId);
        String msg1 = "协作者：只保存必要证据";
        String msg2 = "小林：已确认本版";

        EvidenceMessage m1 = new EvidenceMessage(
                msg1Unit, actor1, 1L, "unit-1",
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), msg1, sha256Hex(msg1));
        EvidenceMessage m2 = new EvidenceMessage(
                msg2Unit, actor2, 2L, "unit-2",
                OffsetDateTime.parse("2026-08-12T09:30:01Z"), msg2, sha256Hex(msg2));
        List<EvidenceMessage> messages = List.of(m1, m2);
        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, "", messages));
        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(actor2, "INTERPRETATION", bodyText, bodyHash);
        UserConfirmation placeholder = new UserConfirmation("CONFIRM", "", confirmationUnit);
        List<SourceAnchor> anchors = List.of(new SourceAnchor(anchor1, List.of(
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
        return new Built(submission, toJson(submission));
    }

    private static String contextPackBody(String query) {
        return "{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\"" + UUID.randomUUID()
                + "\",\"purpose\":\"RECALL\",\"query\":\"" + query + "\"}";
    }

    private static String contextPackBodyWithPolicy(String query, Integer maxResults, Double minScore) {
        StringBuilder sb = new StringBuilder("{\"threadId\":\"" + UUID.randomUUID() + "\",\"turnId\":\""
                + UUID.randomUUID() + "\",\"purpose\":\"RECALL\",\"query\":\"" + query + "\"");
        if (maxResults != null) {
            sb.append(",\"maxResults\":").append(maxResults);
        }
        if (minScore != null) {
            sb.append(",\"minScore\":").append(minScore);
        }
        sb.append("}");
        return sb.toString();
    }

    private static String bubbleBody(String roomKey, String turnKey, String queryText) throws Exception {
        ObjectNode body = JSON.createObjectNode();
        body.put("spaceKey", "local-v1-synthetic-space");
        body.put("roomKey", roomKey);
        body.put("turnKey", turnKey);
        body.put("queryText", queryText);
        return JSON.writeValueAsString(body);
    }

    private static java.util.Set<String> propertySet(JsonNode node) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

    private static Response post(String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Cache-Control").orElse(""));
    }

    private static Response postWithoutBearer(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Cache-Control").orElse(""));
    }

    private static Response uncheckedPost(String path, String body) {
        try {
            return post(path, body, null);
        } catch (Exception exception) {
            throw new java.util.concurrent.CompletionException(exception);
        }
    }

    private static Response post(String path, String body, String idempotencyKey, String capability)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + TOKEN)
                .header("X-Action-Capability", capability)
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(
                response.statusCode(),
                response.body(),
                response.headers().firstValue("Cache-Control").orElse(""));
    }

    private static String toJson(LocalV1CloseoutSubmission s) {
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
                if (u.fromOffset() == null) {
                    un.putNull("fromOffset");
                } else {
                    un.put("fromOffset", u.fromOffset());
                }
                if (u.toOffset() == null) {
                    un.putNull("toOffset");
                } else {
                    un.put("toOffset", u.toOffset());
                }
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

    private static String basisLiteral(int index) {
        StringBuilder sb = new StringBuilder(DIMENSION * 12);
        sb.append('[');
        for (int i = 0; i < DIMENSION; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == index ? 1.0 : 0.0);
        }
        sb.append(']');
        return sb.toString();
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
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

    private record Response(int status, String body, String cacheControl) {}
}
