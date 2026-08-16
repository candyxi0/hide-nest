package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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
 * HTTP-level assembly proof: the closeout controller and run-status controller are wired through the
 * vector projection coordinator, with the embedding adapter base URL bound only to
 * {@code HIDE_NEST_EMBEDDING_BASE_URL}.
 */
class LocalV1CloseoutVectorProjectionHttpIntegrationTest {

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
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        var configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        payloadRoot = Files.createTempDirectory("local-v1-closeout-vector-http-");

        embeddingServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        embeddingServer.createContext("/embed", exchange -> {
            String response = "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                    + ",\"vectors\":[" + unitVectorLiteral() + "]}";
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
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void submitAndRunStatusReturnIndexReady() throws Exception {
        Built built = build("http-index");
        UUID submissionId = built.submission().submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        Response response = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, response.status());
        JsonNode receipt = JSON.readTree(response.body());
        assertEquals(submissionId.toString(), receipt.get("runId").asText());
        assertEquals("INDEX_READY", receipt.get("phase").asText());

        UUID revisionId = dsl.fetchOne(
                        "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId)
                .get("current_revision_id", UUID.class);
        assertEquals(1L, countRows(
                "SELECT 1 FROM memory.memory_revision_embedding WHERE memory_revision_id=?::uuid", revisionId));

        Response status = get("/v1/runs/" + submissionId, TOKEN);
        assertEquals(200, status.status());
        assertEquals("INDEX_READY", JSON.readTree(status.body()).get("phase").asText());
    }

    @Test
    void replayKeepsSingleVectorFactAndIndexReady() throws Exception {
        Built built = build("http-replay");
        UUID submissionId = built.submission().submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        Response replay = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, replay.status());
        assertEquals("INDEX_READY", JSON.readTree(replay.body()).get("phase").asText());

        UUID revisionId = dsl.fetchOne(
                        "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId)
                .get("current_revision_id", UUID.class);
        assertEquals(1L, countRows(
                "SELECT 1 FROM memory.memory_revision_embedding WHERE memory_revision_id=?::uuid", revisionId));
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
        UUID perspectiveActor = actor2;
        UUID anchor1 = deterministicId("anchor1:", submissionId);
        String msg1 = "协作者：只保存必要证据";
        String msg2 = "小林：已确认本版";

        EvidenceMessage m1 = new EvidenceMessage(
                msg1Unit, actor1, 1L, "unit-" + marker + "-1",
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), msg1, sha256Hex(msg1));
        EvidenceMessage m2 = new EvidenceMessage(
                msg2Unit, actor2, 2L, "unit-" + marker + "-2",
                OffsetDateTime.parse("2026-08-12T09:30:01Z"), msg2, sha256Hex(msg2));
        List<EvidenceMessage> messages = List.of(m1, m2);
        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, "", messages));

        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 2L, true, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(perspectiveActor, "INTERPRETATION", bodyText, bodyHash);
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

    private static Response post(String path, String body, String token, String capability) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Action-Capability", capability)
                .header("Idempotency-Key", JSON.readTree(body).get("submissionId").asText())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static Response get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET();
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static String unitVectorLiteral() {
        StringBuilder sb = new StringBuilder(DIMENSION * 12);
        sb.append('[');
        for (int i = 0; i < DIMENSION; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(i == 0 ? 1.0 : 0.0);
        }
        sb.append(']');
        return sb.toString();
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
    }

    private static long countRows(String sql, Object... binds) {
        return dsl.resultQuery(sql, binds).fetch().size();
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

    private record Response(int status, String body) {}
}
