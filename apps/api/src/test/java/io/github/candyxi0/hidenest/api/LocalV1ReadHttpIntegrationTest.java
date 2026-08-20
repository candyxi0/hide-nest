package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1ReadHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CHAT_CANARY = "UNSELECTED_CHITCHAT_CANARY_30B";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T09:30:00Z"), ZoneId.of("UTC"));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static LocalPayloadStore payloadStore;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static ConfigurableApplicationContext api;
    private static URI base;
    private static Fixture multi;
    private static Fixture single;

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
        var governance = new JooqMemoryGovernanceAdapter(dsl);
        var runtime = new JooqRuntimeTransactionAdapter(dsl);
        var evidence = new JooqEvidenceReferenceAdapter(dsl);
        var executor = new SpringTransactionExecutor(tx);
        payloadRoot = Files.createTempDirectory("local-v1-read-http-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);

        String unicodeBody = "   \n" + "忆".repeat(39) + "😀尾\n第二行不会进入标题，摘要仍来自同一正文" + "界".repeat(90);
        multi = createMemory(
                "multi", unicodeBody,
                List.of(
                        new Message("hide", "hide 说：只保存必要证据。", 1L),
                        new Message("xiaolin", "小林说：不要保存闲聊。", 2L),
                        new Message("hide", "hide 说：按消息轮次展示。", 3L)));
        single = createMemory(
                "single", "单条记忆正文", List.of(new Message("xiaolin", "单条原文证据。", 1L)));
        dsl.execute("UPDATE memory.memory_record SET updated_at=CAST(? AS timestamptz) WHERE memory_id=?",
                "2026-08-12T09:30:02Z", multi.memoryId());
        dsl.execute("UPDATE memory.memory_record SET updated_at=CAST(? AS timestamptz) WHERE memory_id=?",
                "2026-08-12T09:30:01Z", single.memoryId());

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
                        // Best-effort cleanup of synthetic temporary evidence.
                    }
                });
            }
        }
    }

    @Test
    @Order(1)
    void syntheticGateRejectsMissingAndWrongTokenAndRejectsOtherV1Routes() throws Exception {
        Response missing = get("/v1/memories", null);
        assertProblem(missing, 401, "ACCESS_DENIED");
        Response wrong = get("/v1/memories", "wrong-token-with-enough-characters-but-not-the-right-value");
        assertProblem(wrong, 403, "ACCESS_DENIED");
        Response write = request("POST", "/v1/memories", TOKEN);
        assertProblem(write, 403, "ACCESS_DENIED");
        Response trajectory = get("/v1/memories/" + multi.memoryId() + "/trajectory", TOKEN);
        assertProblem(trajectory, 403, "ACCESS_DENIED");
    }

    @Test
    @Order(2)
    void realHttpListsDetailsAndReadsExactOrderedEvidence() throws Exception {
        Response list = get("/v1/memories?limit=30", TOKEN);
        assertSuccessHeaders(list);
        JsonNode listJson = JSON.readTree(list.body());
        assertEquals(2, listJson.get("items").size());
        JsonNode first = listJson.get("items").get(0);
        assertEquals(multi.memoryId().toString(), first.get("memoryId").asText());
        assertEquals(multi.revisionId().toString(), first.get("currentRevisionId").asText());
        assertEquals(40, first.get("title").asText().codePointCount(0, first.get("title").asText().length()));
        assertTrue(first.get("title").asText().endsWith("😀"));
        assertEquals(120, first.get("summary").asText().codePointCount(0, first.get("summary").asText().length()));
        assertEquals("AVAILABLE", first.get("sourceAvailability").asText());
        assertFalse(first.get("isolated").asBoolean(true));

        Response detail = get("/v1/memories/" + multi.memoryId(), TOKEN);
        assertSuccessHeaders(detail);
        JsonNode detailJson = JSON.readTree(detail.body());
        assertEquals(multi.body(), detailJson.at("/memory/bodyText").asText());
        assertEquals(multi.revisionId().toString(), detailJson.at("/memory/currentRevisionId").asText());
        assertEquals(3, detailJson.at("/memory/evidenceCount").asInt());

        Response evidence = get(
                "/v1/memories/" + multi.memoryId() + "/evidence?revisionId=" + multi.revisionId(), TOKEN);
        assertSuccessHeaders(evidence);
        JsonNode evidenceJson = JSON.readTree(evidence.body());
        assertEquals(3, evidenceJson.get("evidenceItems").size());
        assertEquals(List.of("hide 说：只保存必要证据。", "小林说：不要保存闲聊。", "hide 说：按消息轮次展示。"),
                texts(evidenceJson.get("evidenceItems")));
        assertEquals(List.of(1, 2, 3), ordinals(evidenceJson.get("evidenceItems")));
        assertTrue(evidenceJson.at("/evidenceItems/0/actorStableRef").asText().startsWith("hide:"));
        assertTrue(evidenceJson.at("/evidenceItems/1/actorStableRef").asText().startsWith("xiaolin:"));
        assertNoLeak(list.body() + detail.body() + evidence.body());
    }

    @Test
    @Order(3)
    void queryStatePagingCursorAndUnsupportedFiltersAreReal() throws Exception {
        Response search = get("/v1/memories?query=%E5%8D%95%E6%9D%A1&state=ACTIVE", TOKEN);
        assertEquals(200, search.status());
        assertEquals(1, JSON.readTree(search.body()).get("items").size());

        Response pageOne = get("/v1/memories?limit=1", TOKEN);
        JsonNode pageOneJson = JSON.readTree(pageOne.body());
        assertEquals(1, pageOneJson.get("items").size());
        String cursor = pageOneJson.get("nextCursor").asText();
        assertFalse(cursor.isBlank());
        Response pageTwo = get("/v1/memories?limit=1&cursor=" + cursor, TOKEN);
        assertEquals(1, JSON.readTree(pageTwo.body()).get("items").size());
        assertNotEquals(
                JSON.readTree(pageOne.body()).at("/items/0/memoryId").asText(),
                JSON.readTree(pageTwo.body()).at("/items/0/memoryId").asText());

        assertProblem(get("/v1/memories?cursor=broken", TOKEN), 422, "REQUEST_SCHEMA_INVALID");
        assertProblem(get("/v1/memories?limit=0", TOKEN), 422, "REQUEST_SCHEMA_INVALID");
        assertProblem(get("/v1/memories?limit=51", TOKEN), 422, "REQUEST_SCHEMA_INVALID");
        assertProblem(get("/v1/memories?memoryType=CLAIM", TOKEN), 422, "REQUEST_SCHEMA_INVALID");
        assertProblem(get("/v1/memories?perspectiveActorId=" + UUID.randomUUID(), TOKEN),
                422, "REQUEST_SCHEMA_INVALID");
        assertProblem(get("/v1/memories?sourceAvailability=AVAILABLE", TOKEN),
                422, "REQUEST_SCHEMA_INVALID");
    }

    @Test
    @Order(4)
    void wrongMemoryRevisionAndDeletedMemoryDoNotLeakExistence() throws Exception {
        assertProblem(get("/v1/memories/" + UUID.randomUUID(), TOKEN), 404, "RETRIEVAL_NO_MATCH");
        assertProblem(get(
                        "/v1/memories/" + multi.memoryId() + "/evidence?revisionId=" + UUID.randomUUID(), TOKEN),
                404, "RETRIEVAL_NO_MATCH");
        UUID closureId = UUID.randomUUID();
        UUID actorId = dsl.fetchOne(
                        "SELECT actor_id FROM memory.decision WHERE target_id=? ORDER BY created_at DESC LIMIT 1",
                        single.memoryId())
                .get("actor_id", UUID.class);
        assertNotNull(actorId);
        UUID deletionDecisionId = UUID.randomUUID();
        dsl.transaction(configuration -> {
            DSLContext tx = DSL.using(configuration);
            tx.execute(
                    "INSERT INTO memory.deletion_closure(closure_id,root_memory_id,preview_revision,"
                            + "root_current_revision_id,root_revision_no,root_policy_id,root_policy_revision_no,"
                            + "request_idempotency_key,request_hash,manifest_hash,state,created_at,expires_at) "
                            + "SELECT ?,mr.memory_id,1,mr.current_revision_id,rev.revision_no,mr.policy_id,"
                            + "mr.current_policy_revision_no,?,decode(repeat('11',32),'hex'),"
                            + "decode(repeat('22',32),'hex'),'PREVIEWED',clock_timestamp(),"
                            + "clock_timestamp()+interval '1 hour' "
                            + "FROM memory.memory_record mr JOIN memory.memory_revision rev "
                            + "ON rev.memory_revision_id=mr.current_revision_id WHERE mr.memory_id=?",
                    closureId, "http-fence-" + closureId, single.memoryId());
            tx.execute(
                    "INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,"
                            + "target_revision_ref,disposition) VALUES (?,1,'MEMORY',?,NULL,'DELETE_REQUESTED')",
                    closureId, single.memoryId());
            tx.execute(
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,"
                            + "target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                            + "VALUES (?,'USER_DELETE_CONFIRM',?,'USER','DELETION_CLOSURE',?,1,"
                            + "'local-v1-http-test',?,clock_timestamp())",
                    deletionDecisionId, actorId, closureId, "http-delete-confirm-" + closureId);
            tx.execute(
                    "INSERT INTO memory.deletion_fence(fence_id,closure_id,target_kind,target_id,"
                            + "created_by_decision_id,created_at) VALUES (?,?,'MEMORY',?,?,clock_timestamp())",
                    UUID.randomUUID(), closureId, single.memoryId(), deletionDecisionId);
        });
        assertProblem(get("/v1/memories/" + single.memoryId(), TOKEN), 404, "RETRIEVAL_NO_MATCH");
    }

    @Test
    @Order(5)
    void missingAndHashTamperedPayloadsFailClosedWithoutInternalData() throws Exception {
        Fixture missing = createMemory(
                "missing", "缺文件记忆", List.of(new Message("hide", "缺文件证据", 1L)));
        Path missingFile = payloadFile(missing.unitIds().get(0));
        Files.delete(missingFile);
        Response missingResponse = get("/v1/memories/" + missing.memoryId() + "/evidence", TOKEN);
        assertEquals(503, missingResponse.status());
        assertNoLeak(missingResponse.body());
        assertFalse(missingResponse.body().contains("缺文件证据"));

        Fixture tampered = createMemory(
                "tampered", "篡改记忆", List.of(new Message("hide", "原始证据", 1L)));
        Files.write(payloadFile(tampered.unitIds().get(0)), "tampered".getBytes(StandardCharsets.UTF_8));
        Response tamperedResponse = get("/v1/memories/" + tampered.memoryId() + "/evidence", TOKEN);
        assertEquals(503, tamperedResponse.status());
        assertNoLeak(tamperedResponse.body());
        assertFalse(tamperedResponse.body().contains("原始证据"));
    }

    @Test
    @Order(6)
    void defaultProfileAndNonLoopbackSyntheticConfigurationFailStartup() {
        SpringApplication defaultApp = new SpringApplication(HideNestApiApplication.class);
        defaultApp.setWebApplicationType(WebApplicationType.NONE);
        assertThrows(Exception.class, () -> defaultApp.run("--spring.main.banner-mode=off"));

        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi("0.0.0.0", postgres.getPassword());
            context.close();
        });
    }

    private static ConfigurableApplicationContext startApi(String address, String password) {
        SpringApplication application = new SpringApplication(HideNestApiApplication.class);
        application.setDefaultProperties(Map.of(
                "spring.profiles.active", "local-v1-synthetic",
                "server.address", address,
                "server.port", "0",
                "spring.datasource.url", postgres.getJdbcUrl(),
                "spring.datasource.username", USER,
                "spring.datasource.password", password,
                "hidenest.local-v1.payload-root", payloadRoot.toString(),
                "hidenest.local-v1.synthetic-token", TOKEN,
                "logging.level.root", "WARN"));
        return application.run();
    }

    private static Fixture createMemory(String marker, String body, List<Message> messages) {
        UUID xiaolin = UUID.randomUUID();
        UUID hide = UUID.randomUUID();
        List<UUID> units = new ArrayList<>();
        List<LocalV1S1PrepareRequest.EvidenceMessage> selected = new ArrayList<>();
        List<LocalV1S1PrepareRequest.AnchorInput> anchors = new ArrayList<>();
        for (Message message : messages) {
            UUID unit = UUID.randomUUID();
            UUID anchor = UUID.randomUUID();
            UUID actor = "xiaolin".equals(message.actor()) ? xiaolin : hide;
            units.add(unit);
            selected.add(new LocalV1S1PrepareRequest.EvidenceMessage(
                    unit, actor, message.ordinal(), "unit-" + marker + "-" + message.ordinal(),
                    OffsetDateTime.now(CLOCK).plusSeconds(message.ordinal()), message.body()));
            anchors.add(new LocalV1S1PrepareRequest.AnchorInput(anchor, List.of(
                    new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(
                            unit, 0L, (long) message.body().codePointCount(0, message.body().length()),
                            message.ordinal()))));
        }
        String key = "http-" + marker + "-" + UUID.randomUUID();
        UUID perspectiveActor = messages.stream().anyMatch(message -> "xiaolin".equals(message.actor()))
                ? xiaolin
                : hide;
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key), perspectiveActor, "Interpretation", body, sha256(body), selected, anchors));
        UUID memoryId = UUID.randomUUID();
        String confirm = "confirm-" + key;
        var result = s1.confirm(new LocalV1S1ConfirmRequest(
                confirm, sha256(confirm), prepared.proposalRevisionId(), prepared.reviewSessionId(),
                memoryId, UUID.randomUUID(), new byte[32]));
        // Test-only fixture normalization: real imports may already carry these stable identities,
        // while S1 deliberately synthesizes opaque refs. Restore the immutable trigger immediately.
        dsl.execute("ALTER TABLE memory.actor_ref DISABLE TRIGGER actor_ref_immutable");
        try {
            dsl.execute("UPDATE memory.actor_ref SET stable_ref=?, display_label='hide' WHERE actor_id=?",
                    "hide:" + hide, hide);
            dsl.execute("UPDATE memory.actor_ref SET stable_ref=?, display_label='小林' WHERE actor_id=?",
                    "xiaolin:" + xiaolin, xiaolin);
        } finally {
            dsl.execute("ALTER TABLE memory.actor_ref ENABLE TRIGGER actor_ref_immutable");
        }
        assertEquals("O", dsl.fetchOne(
                        "SELECT tgenabled::text AS enabled FROM pg_trigger "
                                + "WHERE tgrelid='memory.actor_ref'::regclass AND tgname='actor_ref_immutable'")
                .get("enabled", String.class));
        return new Fixture(memoryId, result.currentRevisionId(), body, units);
    }

    private static Path payloadFile(UUID sourceUnitId) {
        String objectRef = dsl.fetchOne(
                        "SELECT object_ref FROM evidence.source_payload WHERE source_unit_id=?", sourceUnitId)
                .get("object_ref", String.class);
        assertNotNull(objectRef);
        return payloadRoot.resolve(objectRef);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Response get(String path, String token) throws Exception {
        return request("GET", path, token);
    }

    private static Response request(String method, String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static void assertSuccessHeaders(Response response) {
        assertEquals(200, response.status());
        assertEquals("no-store", header(response, "cache-control"));
        assertTrue(header(response, "content-type").startsWith("application/json"));
        UUID.fromString(header(response, "x-request-id"));
        assertEquals(
                header(response, "x-request-id"),
                JSON.readTree(response.body()).get("requestId").asText());
    }

    private static void assertProblem(Response response, int status, String failureCode) {
        assertEquals(status, response.status());
        assertEquals("no-store", header(response, "cache-control"));
        assertTrue(header(response, "content-type").startsWith("application/problem+json"));
        JsonNode body = JSON.readTree(response.body());
        assertEquals(failureCode, body.get("failureCode").asText());
        assertEquals(header(response, "x-request-id"), body.get("requestId").asText());
        assertNoLeak(response.body());
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElseThrow();
    }

    private static void assertNoLeak(String text) {
        assertFalse(text.contains(CHAT_CANARY));
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(payloadRoot.toString()));
        assertFalse(text.contains("objectRef"));
        assertFalse(text.contains("contentHash"));
        assertFalse(text.contains("secret"));
    }

    private static List<String> texts(JsonNode items) {
        List<String> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get("bodyText").asText()));
        return values;
    }

    private static List<Integer> ordinals(JsonNode items) {
        List<Integer> values = new ArrayList<>();
        items.forEach(item -> values.add(item.get("ordinal").asInt()));
        return values;
    }

    private record Fixture(UUID memoryId, UUID revisionId, String body, List<UUID> unitIds) {}

    private record Message(String actor, String body, Long ordinal) {}

    private record Response(int status, Map<String, List<String>> headers, String body) {}
}
