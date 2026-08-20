package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LocalV1PrivateModeHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "PrivateOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CAPABILITY = "PrivateCap-9xQ2zW8vB5nM3kR7dF1hJ4cT6yU0lP9w";
    private static final String WEAK_TOKEN = "short-token";
    private static final String WEAK_CAPABILITY = "weak-capability";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-12T09:30:00Z"), ZoneId.of("UTC"));
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper JSON = new ObjectMapper();

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static Path payloadRoot;
    private static ConfigurableApplicationContext api;
    private static URI base;
    private static Fixture fixture;

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
        payloadRoot = Files.createTempDirectory("local-v1-private-http-");
        var governance = new JooqMemoryGovernanceAdapter(dsl);
        var runtime = new JooqRuntimeTransactionAdapter(dsl);
        var evidence = new JooqEvidenceReferenceAdapter(dsl);
        var executor = new SpringTransactionExecutor(tx);
        var payloadStore = new LocalPayloadStore(payloadRoot);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, executor, CLOCK);
        var s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);

        String body = "   \n" + "忆".repeat(39) + "😀尾\n第二行不会进入标题，摘要仍来自同一正文" + "界".repeat(90);
        fixture = createMemory("private-read", body, s1);

        api = startApi("local-private", "127.0.0.1", TOKEN, CAPABILITY, password);
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
    void localPrivateProfileStartsWithLoopbackAndStrongSecrets() {
        assertNotNull(api);
        assertTrue(api.isActive());
        String boundAddress = api.getEnvironment().getProperty("server.address");
        assertEquals("127.0.0.1", boundAddress);
    }

    @Test
    @Order(2)
    void noProfileAndUnknownProfileFailClosed() {
        SpringApplication defaultApp = new SpringApplication(HideNestApiApplication.class);
        defaultApp.setWebApplicationType(WebApplicationType.NONE);
        assertThrows(Exception.class, () -> defaultApp.run("--spring.main.banner-mode=off"));

        SpringApplication unknownApp = new SpringApplication(HideNestApiApplication.class);
        unknownApp.setWebApplicationType(WebApplicationType.NONE);
        unknownApp.setDefaultProperties(Map.of("spring.profiles.active", "some-unknown-profile"));
        assertThrows(Exception.class, () -> unknownApp.run("--spring.main.banner-mode=off"));
    }

    @Test
    @Order(3)
    void nonLoopbackAddressFailsStartup() {
        String password = postgres.getPassword();
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi(
                    "local-private", "127.0.0.1", TOKEN, CAPABILITY, password, "--server.address=0.0.0.0");
            context.close();
        });
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi(
                    "local-private", "127.0.0.1", TOKEN, CAPABILITY, password, "--server.address=192.168.1.100");
            context.close();
        });
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi(
                    "local-private", "127.0.0.1", TOKEN, CAPABILITY, password, "--server.address=100.64.0.1");
            context.close();
        });
    }

    @Test
    @Order(4)
    void weakOrMissingBearerFailsStartup() {
        String password = postgres.getPassword();
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi("local-private", "127.0.0.1", null, CAPABILITY, password);
            context.close();
        });
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi("local-private", "127.0.0.1", WEAK_TOKEN, CAPABILITY, password);
            context.close();
        });
    }

    @Test
    @Order(5)
    void weakOrMissingCapabilityFailsStartup() {
        String password = postgres.getPassword();
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi("local-private", "127.0.0.1", TOKEN, null, password);
            context.close();
        });
        assertThrows(Exception.class, () -> {
            ConfigurableApplicationContext context = startApi("local-private", "127.0.0.1", TOKEN, WEAK_CAPABILITY, password);
            context.close();
        });
    }

    @Test
    @Order(6)
    void fixtureBeansAbsentAndEndpointReturns404() throws Exception {
        long fixtureControllers = api.getBeanProvider(LocalV1DeletionFixtureController.class).stream().count();
        long fixtureCoordinators = api.getBeanProvider(LocalV1SharedEvidenceFixtureCoordinator.class).stream().count();
        assertEquals(0, fixtureControllers);
        assertEquals(0, fixtureCoordinators);

        Response response = post("/v1/deletion-fixtures", null, TOKEN, CAPABILITY);
        assertEquals(404, response.status());
        assertNoLeak(response.body());
    }

    @Test
    @Order(7)
    void readEndpointReachableWithLegalBearerAndRejectsIllegalBearer() throws Exception {
        Response missing = get("/v1/memories", null);
        assertProblem(missing, 401, "ACCESS_DENIED");

        Response wrong = get("/v1/memories", "wrong-token-with-enough-characters-but-not-the-right-value");
        assertProblem(wrong, 403, "ACCESS_DENIED");

        Response list = get("/v1/memories?limit=30", TOKEN);
        assertSuccessHeaders(list);
        JsonNode listJson = JSON.readTree(list.body());
        assertEquals(1, listJson.get("items").size());
        assertNoLeak(list.body());
    }

    @Test
    @Order(8)
    void closeoutCandidateSetContextPackDeletionEndpointsRegisteredAndGated() throws Exception {
        // Closeout: capability gate blocks without capability, controller reached with capability.
        Response closeoutNoCap = post("/v1/closeout-submissions", "{}", TOKEN, null);
        assertProblem(closeoutNoCap, 403, "CAPABILITY_REQUIRED");

        Response closeoutBadBody = post("/v1/closeout-submissions", "{}", TOKEN, CAPABILITY);
        assertEquals(422, closeoutBadBody.status());

        // CandidateSet: bearer gate only; invalid body proves controller is registered.
        String candidatePath = "/v1/review-sessions/" + UUID.randomUUID() + "/final-submissions";
        Response candidateNoAuth = post(candidatePath, "{}", null, null);
        assertProblem(candidateNoAuth, 401, "ACCESS_DENIED");

        Response candidateBadBody = post(candidatePath, "{}", TOKEN, null);
        assertEquals(422, candidateBadBody.status());

        // ContextPack: bearer gate only.
        Response contextNoAuth = post("/v1/context-packs", "{}", null, null);
        assertProblem(contextNoAuth, 401, "ACCESS_DENIED");

        Response contextBadBody = post("/v1/context-packs", "{}", TOKEN, null);
        assertEquals(422, contextBadBody.status());

        // Deletion preview: bearer gate only.
        Response deletionNoAuth = post("/v1/deletion-previews", "{}", null, null);
        assertProblem(deletionNoAuth, 401, "ACCESS_DENIED");

        Response deletionBadBody = post("/v1/deletion-previews", "{}", TOKEN, null);
        assertEquals(422, deletionBadBody.status());
    }

    private static ConfigurableApplicationContext startApi(
            String profile, String address, String token, String capability, String password, String... args) {
        SpringApplication application = new SpringApplication(HideNestApiApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.profiles.active", profile);
        defaults.put("server.address", address);
        defaults.put("server.port", "0");
        defaults.put("spring.datasource.url", postgres.getJdbcUrl());
        defaults.put("spring.datasource.username", USER);
        defaults.put("spring.datasource.password", password);
        defaults.put("hidenest.local-v1.payload-root", payloadRoot.toString());
        if (token != null) {
            defaults.put("hidenest.local-v1.synthetic-token", token);
        }
        if (capability != null) {
            defaults.put("hidenest.local-v1.synthetic-capability", capability);
        }
        defaults.put("logging.level.root", "WARN");
        application.setDefaultProperties(defaults);
        return application.run(args);
    }

    private static Fixture createMemory(String marker, String body, LocalV1S1WindowCloseCoordinator s1) {
        UUID xiaolin = UUID.randomUUID();
        UUID hide = UUID.randomUUID();
        List<UUID> units = new ArrayList<>();
        List<LocalV1S1PrepareRequest.EvidenceMessage> selected = new ArrayList<>();
        List<LocalV1S1PrepareRequest.AnchorInput> anchors = new ArrayList<>();
        for (Message message : List.of(new Message("hide", "私有模式测试证据原文。", 1L))) {
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
        String key = "private-" + marker + "-" + UUID.randomUUID();
        UUID perspectiveActor = List.of(new Message("hide", "私有模式测试证据原文。", 1L)).stream()
                .anyMatch(message -> "xiaolin".equals(message.actor()))
                ? xiaolin
                : hide;
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key), perspectiveActor, "Interpretation", body, sha256(body), selected, anchors));
        UUID memoryId = UUID.randomUUID();
        String confirm = "confirm-" + key;
        var result = s1.confirm(new LocalV1S1ConfirmRequest(
                confirm, sha256(confirm), prepared.proposalRevisionId(), prepared.reviewSessionId(),
                memoryId, UUID.randomUUID(), new byte[32]));
        return new Fixture(memoryId, result.currentRevisionId());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static Response get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .GET();
        if (token != null) builder.header("Authorization", "Bearer " + token);
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static Response post(String path, String body, String token, String capability) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (capability != null) builder.header("X-Action-Capability", capability);
        builder.method("POST", body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
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
        return response.headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElseThrow();
    }

    private static void assertNoLeak(String text) {
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains(CAPABILITY));
        assertFalse(text.contains(payloadRoot.toString()));
    }

    private record Fixture(UUID memoryId, UUID revisionId) {}

    private record Response(int status, Map<String, List<String>> headers, String body) {}

    private record Message(String actor, String body, Long ordinal) {}
}
