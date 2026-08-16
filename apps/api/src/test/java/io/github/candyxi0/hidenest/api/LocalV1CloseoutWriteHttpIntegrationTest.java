package io.github.candyxi0.hidenest.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
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
import java.sql.Statement;
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
class LocalV1CloseoutWriteHttpIntegrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String TOKEN = "SyntheticOnly-7xP3qW9vN2mK5sR8dF1hJ4cB6yT0uLz";
    private static final String CAPABILITY = "SyntheticCap-9xQ2zW8vB5nM3kR7dF1hJ4cT6yU0lP9w";
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
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        var configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);
        payloadRoot = Files.createTempDirectory("local-v1-closeout-http-");

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

    // ── gate rejection + zero writes ──────────────────────────────────────

    @Test
    @Order(1)
    void gateRejectsMissingOrWrongSecretsWithZeroWrites() throws Exception {
        Built built = build("gate");
        long memBefore = count("memory.memory_record");
        long srcBefore = count("evidence.source");

        assertProblem(post("/v1/closeout-submissions", built.json(), null, CAPABILITY), 401, "ACCESS_DENIED");
        assertProblem(
                post("/v1/closeout-submissions", built.json(), "wrong-token-with-enough-length-but-wrong-value", CAPABILITY),
                403, "ACCESS_DENIED");
        assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, null), 403, "CAPABILITY_REQUIRED");
        assertProblem(
                post("/v1/closeout-submissions", built.json(), TOKEN, "wrong-capability-with-enough-length"),
                403, "CAPABILITY_REQUIRED");

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(srcBefore, count("evidence.source"));
    }

    @Test
    @Order(2)
    void nonLoopbackConfigurationFailsStartup() {
        assertThrows(() -> {
            ConfigurableApplicationContext context = startApi("0.0.0.0", postgres.getPassword());
            context.close();
        });
    }

    // ── happy path + canonical facts + canary ─────────────────────────────

    @Test
    @Order(3)
    void validSingleCandidateCloseoutProducesRealRunAndCanonicalFacts() throws Exception {
        Built built = build("happy");
        Response response = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, response.status());
        assertSuccessHeaders(response, 202);
        JsonNode receipt = JSON.readTree(response.body());
        UUID submissionId = built.submission().submissionId();
        assertEquals(submissionId.toString(), receipt.get("runId").asText());
        assertEquals("CANONICAL_COMMITTED", receipt.get("phase").asText());
        String statusUrl = receipt.get("statusUrl").asText();
        assertTrue(statusUrl.endsWith("/v1/runs/" + submissionId), statusUrl);

        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.closeout_run WHERE run_id=?::uuid", submissionId));

        Response status = get("/v1/runs/" + submissionId, TOKEN);
        assertEquals(200, status.status());
        assertEquals("CANONICAL_COMMITTED", JSON.readTree(status.body()).get("phase").asText());

        UUID memoryId = deterministicId("memory:", submissionId);
        var record = dsl.fetchOne(
                "SELECT state, current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId);
        assertNotNull(record);
        assertEquals("ACTIVE", record.get("state", String.class));
        UUID revisionId = record.get("current_revision_id", UUID.class);
        assertNotNull(revisionId);
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM memory.memory_revision WHERE memory_id=?::uuid AND revision_no=1", memoryId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM memory.decision WHERE decision_kind='USER_CONFIRM' AND target_id=?::uuid", memoryId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM memory.memory_relation WHERE from_revision_id=?::uuid AND relation_type='EVIDENCED_BY'",
                revisionId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM memory.change_event WHERE event_type='memory.canonical-committed.v1' AND target_id=?::uuid",
                memoryId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.outbox_event WHERE event_type='memory.canonical-committed.v1' AND aggregate_id=?::uuid",
                memoryId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.idempotency_receipt WHERE idempotency_key=? AND operation_code='LOCAL_V1_CLOSEOUT'",
                submissionId.toString()));

        var scopeRow = dsl.fetchOne(
                "SELECT scope_id FROM runtime.closeout_run WHERE submission_id=?::uuid", submissionId);
        assertNotNull(scopeRow);
        UUID scopeId = scopeRow.get("scope_id", UUID.class);
        assertEquals(2, (long) countRows(
                "SELECT 1 FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId));

        assertNoCanary();

        Response detail = get("/v1/memories/" + memoryId, TOKEN);
        assertEquals(200, detail.status());
        assertEquals(built.submission().hideSelection().bodyText(),
                JSON.readTree(detail.body()).at("/memory/bodyText").asText());
        Response evidence = get("/v1/memories/" + memoryId + "/evidence?revisionId=" + revisionId, TOKEN);
        assertEquals(200, evidence.status());
        assertEquals(2, JSON.readTree(evidence.body()).get("evidenceItems").size());
    }

    // ── R1-01 hash attack matrix ──────────────────────────────────────────

    @Test
    @Order(4)
    void hashAttackMatrixRejectsAllTamperingBeforeWrite() throws Exception {
        long memBefore = count("memory.memory_record");
        long srcBefore = count("evidence.source");

        // 1. message field tampering (manifest hash stale); ordinal trips the range gate first.
        Map<String, String> messageAttacks = Map.of(
                "actorId", "REQUEST_SCHEMA_INVALID",
                "ordinal", "SOURCE_RANGE_GAP",
                "externalUnitRef", "REQUEST_SCHEMA_INVALID",
                "occurredAt", "REQUEST_SCHEMA_INVALID",
                "bodyHash", "REQUEST_SCHEMA_INVALID");
        for (var attack : messageAttacks.entrySet()) {
            ObjectNode mutated = (ObjectNode) JSON.readTree(build("atk-msg-" + attack.getKey()).json());
            ObjectNode message = (ObjectNode) mutated.at("/threadReaderManifest/selectedEvidenceMessages/0");
            switch (attack.getKey()) {
                case "actorId" -> message.put("actorId", UUID.randomUUID().toString());
                case "ordinal" -> message.put("ordinal", 99);
                case "externalUnitRef" -> message.put("externalUnitRef", "tampered-unit");
                case "occurredAt" -> message.put("occurredAt", "2030-01-01T00:00:00Z");
                case "bodyHash" -> message.put("bodyHash", sha256Hex("tampered-body"));
                default -> throw new AssertionError(attack.getKey());
            }
            assertProblem(post("/v1/closeout-submissions", mutated.toString(), TOKEN, CAPABILITY),
                    422, attack.getValue());
        }

        // 2. change bodyText + bodyHash but keep review hash stale
        Built body = build("atk-body");
        ObjectNode bodyNode = (ObjectNode) JSON.readTree(body.json());
        ((ObjectNode) bodyNode.get("hideSelection")).put("bodyText", "被篡改的候选正文");
        ((ObjectNode) bodyNode.get("hideSelection")).put("bodyHash", sha256Hex("被篡改的候选正文"));
        assertProblem(post("/v1/closeout-submissions", bodyNode.toString(), TOKEN, CAPABILITY),
                422, "REQUEST_SCHEMA_INVALID");

        // 3. change perspectiveActorId / memoryType / threadId / anchorId / unit boundary
        for (String field : List.of("perspectiveActorId", "memoryType", "threadId", "anchorId", "unitBoundary")) {
            ObjectNode mutated = (ObjectNode) JSON.readTree(build("atk-struct-" + field).json());
            switch (field) {
                case "perspectiveActorId" -> ((ObjectNode) mutated.get("hideSelection"))
                        .put("perspectiveActorId", UUID.randomUUID().toString());
                case "memoryType" -> ((ObjectNode) mutated.get("hideSelection")).put("memoryType", "CLAIM");
                case "threadId" -> mutated.put("threadId", UUID.randomUUID().toString());
                case "anchorId" -> ((ObjectNode) mutated.at("/sourceAnchors/0"))
                        .put("anchorId", UUID.randomUUID().toString());
                case "unitBoundary" -> ((ObjectNode) mutated.at("/sourceAnchors/0/units/0"))
                        .put("toOffset", 1);
                default -> throw new AssertionError(field);
            }
            assertProblem(post("/v1/closeout-submissions", mutated.toString(), TOKEN, CAPABILITY),
                    422, "REQUEST_SCHEMA_INVALID");
        }

        // 4. change manifest hash + local field, keep review hash stale
        Built base = build("atk-manifest");
        LocalV1CloseoutSubmission sub = base.submission();
        EvidenceMessage first = sub.threadReaderManifest().selectedEvidenceMessages().get(0);
        EvidenceMessage tamperedMessage = new EvidenceMessage(
                first.sourceUnitId(), first.actorId(), first.ordinal(), "tampered-unit",
                first.occurredAt(), first.bodyText(), first.bodyHash());
        List<EvidenceMessage> tamperedMessages =
                List.of(tamperedMessage, sub.threadReaderManifest().selectedEvidenceMessages().get(1));
        String newManifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest(
                        sub.threadReaderManifest().schemaVersion(),
                        sub.threadReaderManifest().fromOrdinal(),
                        sub.threadReaderManifest().toOrdinal(),
                        sub.threadReaderManifest().continuous(),
                        "",
                        tamperedMessages));
        ObjectNode manifestNode = (ObjectNode) JSON.readTree(base.json());
        ((ObjectNode) manifestNode.at("/threadReaderManifest/selectedEvidenceMessages/0"))
                .put("externalUnitRef", "tampered-unit");
        ((ObjectNode) manifestNode.get("threadReaderManifest")).put("manifestHash", newManifestHash);
        assertProblem(post("/v1/closeout-submissions", manifestNode.toString(), TOKEN, CAPABILITY),
                422, "REQUEST_SCHEMA_INVALID");

        // 5. review hash correct but confirmationProof stale
        ObjectNode proofNode = (ObjectNode) JSON.readTree(build("atk-proof").json());
        proofNode.put("confirmationProof", sha256Hex("stale-proof"));
        assertProblem(post("/v1/closeout-submissions", proofNode.toString(), TOKEN, CAPABILITY),
                403, "USER_CONFIRMATION_PROOF_INVALID");

        // idempotency key missing
        Response missingKey = postNoKey("/v1/closeout-submissions", build("atk-nokey").json(), TOKEN, CAPABILITY);
        assertProblem(missingKey, 400, "IDEMPOTENCY_KEY_REQUIRED");

        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(srcBefore, count("evidence.source"));
    }

    // ── replay / conflict ─────────────────────────────────────────────────

    @Test
    @Order(5)
    void replaySameRequestConvergesAndDifferentRequestConflicts() throws Exception {
        Built built = build("replay");
        Response first = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, first.status());
        JsonNode firstReceipt = JSON.readTree(first.body());
        UUID memoryId = deterministicId("memory:", built.submission().submissionId());

        long memBefore = count("memory.memory_record");
        Response replay = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, replay.status());
        assertEquals(firstReceipt.get("runId").asText(), JSON.readTree(replay.body()).get("runId").asText());
        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.closeout_run WHERE submission_id=?::uuid", built.submission().submissionId()));
        assertEquals(1, (long) countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", memoryId));

        Built different = buildWithSubmissionId(built.submission().submissionId(), "different");
        assertProblem(post("/v1/closeout-submissions", different.json(), TOKEN, CAPABILITY),
                409, "IDEMPOTENCY_KEY_REUSED");
    }

    // ── R1-02 corrupted-fact replay rejection ─────────────────────────────

    @Test
    @Order(6)
    void replayRejectsDeletedCloseoutRun() throws Exception {
        Built built = build("mut-run");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        UUID submissionId = built.submission().submissionId();

        var row = dsl.fetchOne("SELECT * FROM runtime.closeout_run WHERE submission_id=?::uuid", submissionId);
        assertNotNull(row);
        dsl.execute("DELETE FROM runtime.closeout_run WHERE submission_id=?::uuid", submissionId);
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            dsl.execute(
                    "INSERT INTO runtime.closeout_run(run_id,scope_id,state,retry_of,submission_id,started_at,"
                            + "terminal_at,failure_code,created_at) VALUES (?::uuid,?::uuid,?,?::uuid,?::uuid,?,?,?,?)",
                    row.get("run_id", UUID.class), row.get("scope_id", UUID.class),
                    row.get("state", String.class), row.get("retry_of", UUID.class),
                    row.get("submission_id", UUID.class), row.get("started_at", java.sql.Timestamp.class),
                    row.get("terminal_at", java.sql.Timestamp.class), row.get("failure_code", String.class),
                    row.get("created_at", java.sql.Timestamp.class));
        }
    }

    @Test
    @Order(7)
    void replayRejectsCorruptedCaptureScopeManifestHash() throws Exception {
        Built built = build("mut-scope");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        UUID scopeId = deterministicId("scope:", built.submission().submissionId());

        byte[] original = dsl.fetchOne(
                        "SELECT manifest_hash FROM runtime.capture_scope WHERE scope_id=?::uuid", scopeId)
                .get("manifest_hash", byte[].class);
        try {
            disableTrigger("runtime.capture_scope", "capture_scope_frozen_guard");
            dsl.execute("UPDATE runtime.capture_scope SET manifest_hash=decode(repeat('ab',32),'hex') "
                    + "WHERE scope_id=?::uuid", scopeId);
        } finally {
            enableTrigger("runtime.capture_scope", "capture_scope_frozen_guard");
        }
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            disableTrigger("runtime.capture_scope", "capture_scope_frozen_guard");
            try {
                dsl.execute("UPDATE runtime.capture_scope SET manifest_hash=? WHERE scope_id=?::uuid",
                        original, scopeId);
            } finally {
                enableTrigger("runtime.capture_scope", "capture_scope_frozen_guard");
            }
        }
    }

    @Test
    @Order(8)
    void replayRejectsCorruptedCaptureScopeUnitSet() throws Exception {
        Built built = build("mut-unit");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        UUID scopeId = deterministicId("scope:", built.submission().submissionId());

        var unit = dsl.fetchOne(
                "SELECT source_unit_id, ordinal FROM runtime.capture_scope_unit WHERE scope_id=?::uuid LIMIT 1", scopeId);
        assertNotNull(unit);
        UUID unitSourceId = unit.get("source_unit_id", UUID.class);
        Long unitOrdinal = unit.get("ordinal", Long.class);
        try {
            disableTrigger("runtime.capture_scope_unit", "capture_scope_unit_frozen_guard");
            dsl.execute("DELETE FROM runtime.capture_scope_unit WHERE scope_id=?::uuid AND source_unit_id=?::uuid",
                    scopeId, unitSourceId);
        } finally {
            enableTrigger("runtime.capture_scope_unit", "capture_scope_unit_frozen_guard");
        }
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            disableTrigger("runtime.capture_scope_unit", "capture_scope_unit_frozen_guard");
            try {
                dsl.execute("INSERT INTO runtime.capture_scope_unit(scope_id,source_unit_id,ordinal,exclusion_reason) "
                        + "VALUES (?::uuid,?::uuid,?,NULL)", scopeId, unitSourceId, unitOrdinal);
            } finally {
                enableTrigger("runtime.capture_scope_unit", "capture_scope_unit_frozen_guard");
            }
        }
    }

    @Test
    @Order(9)
    void replayRejectsCorruptedFacadeReceipt() throws Exception {
        Built built = build("mut-receipt");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        String key = built.submission().submissionId().toString();

        UUID originalResourceId = dsl.fetchOne(
                        "SELECT resource_id FROM runtime.idempotency_receipt WHERE idempotency_key=?", key)
                .get("resource_id", UUID.class);
        try {
            disableTrigger("runtime.idempotency_receipt", "idempotency_receipt_immutable");
            dsl.execute("UPDATE runtime.idempotency_receipt SET resource_id=?::uuid WHERE idempotency_key=?",
                    UUID.randomUUID(), key);
        } finally {
            enableTrigger("runtime.idempotency_receipt", "idempotency_receipt_immutable");
        }
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            disableTrigger("runtime.idempotency_receipt", "idempotency_receipt_immutable");
            try {
                dsl.execute("UPDATE runtime.idempotency_receipt SET resource_id=?::uuid WHERE idempotency_key=?",
                        originalResourceId, key);
            } finally {
                enableTrigger("runtime.idempotency_receipt", "idempotency_receipt_immutable");
            }
        }
    }

    @Test
    @Order(10)
    void replayRejectsMissingCanonicalRelation() throws Exception {
        Built built = build("mut-relation");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        UUID memoryId = deterministicId("memory:", built.submission().submissionId());

        var relation = dsl.fetchOne(
                "SELECT * FROM memory.memory_relation WHERE from_revision_id="
                        + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid) LIMIT 1",
                memoryId);
        assertNotNull(relation);
        UUID relationId = relation.get("relation_id", UUID.class);
        dsl.execute("DELETE FROM memory.memory_relation WHERE relation_id=?::uuid", relationId);
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            dsl.execute(
                    "INSERT INTO memory.memory_relation(relation_id,from_revision_id,relation_type,to_revision_id,"
                            + "to_anchor_id,perspective_actor_id,created_by_decision_id,created_at) "
                            + "VALUES (?::uuid,?::uuid,?,?::uuid,?::uuid,?::uuid,?::uuid,?)",
                    relation.get("relation_id", UUID.class), relation.get("from_revision_id", UUID.class),
                    relation.get("relation_type", String.class), relation.get("to_revision_id", UUID.class),
                    relation.get("to_anchor_id", UUID.class), relation.get("perspective_actor_id", UUID.class),
                    relation.get("created_by_decision_id", UUID.class),
                    relation.get("created_at", java.sql.Timestamp.class));
        }
    }

    // ── fault injection recovery ──────────────────────────────────────────

    @Test
    @Order(11)
    void recoveryConvergesAfterPrepareThenScopeFailure() throws Exception {
        installTrigger("""
                CREATE OR REPLACE FUNCTION public.force_scope_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'INJECTED_FAILURE: scope'; END; $$ LANGUAGE plpgsql
                """, "CREATE TRIGGER injected_scope_failure BEFORE INSERT ON runtime.capture_scope "
                + "FOR EACH ROW EXECUTE FUNCTION public.force_scope_failure()");
        Built built = build("rec-scope");
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            dropTrigger("injected_scope_failure", "runtime.capture_scope");
        }
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.closeout_run WHERE submission_id=?::uuid", built.submission().submissionId()));
    }

    @Test
    @Order(12)
    void recoveryConvergesAfterRunThenConfirmFailure() throws Exception {
        installTrigger("""
                CREATE OR REPLACE FUNCTION public.force_confirm_failure() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'INJECTED_FAILURE: confirm'; END; $$ LANGUAGE plpgsql
                """, "CREATE TRIGGER injected_confirm_failure BEFORE INSERT ON memory.memory_record "
                + "FOR EACH ROW EXECUTE FUNCTION public.force_confirm_failure()");
        Built built = build("rec-confirm");
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            dropTrigger("injected_confirm_failure", "memory.memory_record");
        }
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        assertEquals(1, (long) countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid",
                deterministicId("memory:", built.submission().submissionId())));
    }

    @Test
    @Order(13)
    void recoveryConvergesAfterConfirmThenRunTransitionFailure() throws Exception {
        installTrigger("""
                CREATE OR REPLACE FUNCTION public.force_transition_failure() RETURNS trigger AS $$
                BEGIN IF NEW.state = 'COMPLETED' THEN RAISE EXCEPTION 'INJECTED_FAILURE: transition'; END IF;
                RETURN NEW; END; $$ LANGUAGE plpgsql
                """, "CREATE TRIGGER injected_transition_failure BEFORE UPDATE ON runtime.closeout_run "
                + "FOR EACH ROW EXECUTE FUNCTION public.force_transition_failure()");
        Built built = build("rec-transition");
        try {
            assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY),
                    500, "INTERNAL_FAILURE");
        } finally {
            dropTrigger("injected_transition_failure", "runtime.closeout_run");
        }
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        assertEquals("COMPLETED", dsl.fetchOne(
                        "SELECT state FROM runtime.closeout_run WHERE submission_id=?::uuid",
                        built.submission().submissionId())
                .get("state", String.class));
    }

    // ── R1-03 real concurrency ────────────────────────────────────────────

    @Test
    @Order(14)
    void concurrentSameRequestConvergesToSingleFact() throws Exception {
        Built built = build("conc-same");
        List<Response> responses = runConcurrently(2, built.json());

        assertEquals(List.of(202, 202), responses.stream().map(Response::status).sorted().toList());
        assertEquals(built.submission().submissionId().toString(),
                JSON.readTree(responses.get(0).body()).get("runId").asText());
        assertEquals(built.submission().submissionId().toString(),
                JSON.readTree(responses.get(1).body()).get("runId").asText());

        assertSingleFact(built.submission().submissionId());
    }

    @Test
    @Order(15)
    void concurrentDifferentRequestOne202One409() throws Exception {
        UUID shared = UUID.randomUUID();
        Built a = buildWithSubmissionId(shared, "diff-a");
        Built b = buildWithSubmissionId(shared, "diff-b");

        List<Response> responses = runConcurrently(List.of(a.json(), b.json()));

        assertEquals(List.of(202, 409), responses.stream().map(Response::status).sorted().toList());
        assertSingleFact(shared);
    }

    // ── restart persistence ───────────────────────────────────────────────

    @Test
    @Order(16)
    void runAndMemorySurviveApiRestart() throws Exception {
        Built built = build("restart");
        assertEquals(202, post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY).status());
        UUID memoryId = deterministicId("memory:", built.submission().submissionId());

        api.close();
        api = startApi("127.0.0.1", postgres.getPassword());
        int port = ((ServletWebServerApplicationContext) api).getWebServer().getPort();
        base = URI.create("http://127.0.0.1:" + port);

        assertEquals(200, get("/v1/runs/" + built.submission().submissionId(), TOKEN).status());
        assertEquals("CANONICAL_COMMITTED",
                JSON.readTree(get("/v1/runs/" + built.submission().submissionId(), TOKEN).body())
                        .get("phase").asText());
        assertEquals(200, get("/v1/memories/" + memoryId, TOKEN).status());
    }

    // ── R4: multi-segment continuous=false is a legal wire shape ──────────

    @Test
    @Order(17)
    void multiSegmentContinuousFalseIsAcceptedAndGrouped() throws Exception {
        Built built = buildMultiSegment("multi-segment");
        Response response = post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY);
        assertEquals(202, response.status());
        assertEquals("CANONICAL_COMMITTED", JSON.readTree(response.body()).get("phase").asText());

        UUID memoryId = deterministicId("memory:", built.submission().submissionId());
        UUID revisionId = dsl.fetchOne(
                        "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId)
                .get("current_revision_id", UUID.class);

        Response evidence = get("/v1/memories/" + memoryId + "/evidence?revisionId=" + revisionId, TOKEN);
        assertEquals(200, evidence.status());
        JsonNode items = JSON.readTree(evidence.body()).get("evidenceItems");
        assertEquals(4, items.size());
        // Two separated segments must surface as two distinct anchors, not four.
        List<String> ordinals = new ArrayList<>();
        List<String> anchorIds = new ArrayList<>();
        for (JsonNode item : items) {
            ordinals.add(item.get("ordinal").asText());
            anchorIds.add(item.get("anchorId").asText());
        }
        assertEquals(List.of("1", "2", "5", "6"), ordinals.stream().sorted().toList());
        assertEquals(2, anchorIds.stream().distinct().count());
    }

    // ── R4-R1: selected-messages ⇄ anchor-partition exact-partition gate ──

    @Test
    @Order(18)
    void partitionAttackMatrixRejectsAllWithZeroWrites() throws Exception {
        // 1. same source unit in two anchors.
        UUID id1 = UUID.randomUUID();
        EvidenceMessage c1m1 = partMsg(id1, "c1m1", 1L, false);
        EvidenceMessage c1m2 = partMsg(id1, "c1m2", 2L, true);
        EvidenceMessage c1m3 = partMsg(id1, "c1m3", 4L, false);
        assertPartitionRejected(buildPartitionRequest(id1, List.of(c1m1, c1m2, c1m3),
                List.of(anchor(partUnit(c1m1), partUnit(c1m2)), anchor(partUnit(c1m2), partUnit(c1m3))),
                false, 1L, 4L), "SOURCE_ORDER_INVALID");

        // 2. selected message missing from every anchor.
        UUID id2 = UUID.randomUUID();
        EvidenceMessage c2m1 = partMsg(id2, "c2m1", 1L, false);
        EvidenceMessage c2m2 = partMsg(id2, "c2m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id2, List.of(c2m1, c2m2),
                List.of(anchor(partUnit(c2m1))), true, 1L, 2L), "SOURCE_ORDER_INVALID");

        // 3. anchor unit ordinal != corresponding selected message ordinal.
        UUID id3 = UUID.randomUUID();
        EvidenceMessage c3m1 = partMsg(id3, "c3m1", 1L, false);
        EvidenceMessage c3m2 = partMsg(id3, "c3m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id3, List.of(c3m1, c3m2),
                List.of(anchor(partUnit(c3m1), new AnchorUnit(c3m2.sourceUnitId(), 0L, 1L, 3L))),
                true, 1L, 2L), "SOURCE_ORDER_INVALID");

        // 4. anchor internal ordinal gap.
        UUID id4 = UUID.randomUUID();
        EvidenceMessage c4m1 = partMsg(id4, "c4m1", 1L, false);
        EvidenceMessage c4m2 = partMsg(id4, "c4m2", 2L, true);
        EvidenceMessage c4m3 = partMsg(id4, "c4m3", 3L, false);
        assertPartitionRejected(buildPartitionRequest(id4, List.of(c4m1, c4m2, c4m3),
                List.of(anchor(partUnit(c4m1), partUnit(c4m3))), true, 1L, 3L), "SOURCE_ORDER_INVALID");

        // 5. two adjacent anchors masquerading as two segments.
        UUID id5 = UUID.randomUUID();
        EvidenceMessage c5m1 = partMsg(id5, "c5m1", 1L, false);
        EvidenceMessage c5m2 = partMsg(id5, "c5m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id5, List.of(c5m1, c5m2),
                List.of(anchor(partUnit(c5m1)), anchor(partUnit(c5m2))), false, 1L, 2L), "SOURCE_ORDER_INVALID");

        // 6. two anchors but continuous=true.
        UUID id6 = UUID.randomUUID();
        EvidenceMessage c6m1 = partMsg(id6, "c6m1", 1L, false);
        EvidenceMessage c6m2 = partMsg(id6, "c6m2", 2L, true);
        EvidenceMessage c6m3 = partMsg(id6, "c6m3", 4L, false);
        EvidenceMessage c6m4 = partMsg(id6, "c6m4", 5L, true);
        assertPartitionRejected(buildPartitionRequest(id6, List.of(c6m1, c6m2, c6m3, c6m4),
                List.of(anchor(partUnit(c6m1), partUnit(c6m2)), anchor(partUnit(c6m3), partUnit(c6m4))),
                true, 1L, 5L), "REQUEST_SCHEMA_INVALID");

        // 7. one anchor but continuous=false.
        UUID id7 = UUID.randomUUID();
        EvidenceMessage c7m1 = partMsg(id7, "c7m1", 1L, false);
        EvidenceMessage c7m2 = partMsg(id7, "c7m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id7, List.of(c7m1, c7m2),
                List.of(anchor(partUnit(c7m1), partUnit(c7m2))), false, 1L, 2L), "REQUEST_SCHEMA_INVALID");

        // 8. manifest fromOrdinal/toOrdinal != true first/last ordinal.
        UUID id8 = UUID.randomUUID();
        EvidenceMessage c8m1 = partMsg(id8, "c8m1", 1L, false);
        EvidenceMessage c8m2 = partMsg(id8, "c8m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id8, List.of(c8m1, c8m2),
                List.of(anchor(partUnit(c8m1), partUnit(c8m2))), true, 1L, 3L), "SOURCE_RANGE_GAP");

        // 9. selected messages in descending order (hash/proof recomputed).
        UUID id9 = UUID.randomUUID();
        EvidenceMessage c9m1 = partMsg(id9, "c9m1", 1L, false);
        EvidenceMessage c9m2 = partMsg(id9, "c9m2", 2L, true);
        assertPartitionRejected(buildPartitionRequest(id9, List.of(c9m2, c9m1),
                List.of(anchor(partUnit(c9m1), partUnit(c9m2))), true, 1L, 2L), "SOURCE_ORDER_INVALID");
    }

    // ── helpers ───────────────────────────────────────────────────────────

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

    private static void assertThrows(Runnable runnable) {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, runnable::run);
    }

    private static Built build(String marker) {
        return buildWithSubmissionId(UUID.randomUUID(), marker);
    }

    private static Built buildWithSubmissionId(UUID submissionId, String marker) {
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyText = "合成记忆正文-" + marker;
        String bodyHash = sha256Hex(bodyText);

        UUID msg1Unit = deterministicId("msg1:", submissionId);
        UUID msg2Unit = deterministicId("msg2:", submissionId);
        UUID actor1 = deterministicId("a1:", submissionId);
        UUID actor2 = deterministicId("a2:", submissionId);
        UUID perspectiveActor = actor2; // 小林 is the perspective actor
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
                submissionId,
                threadId,
                hideSelection,
                new UserConfirmation("CONFIRM", reviewManifestHash, confirmationUnit),
                anchors,
                manifest,
                proof);
        return new Built(submission, toJson(submission));
    }

    private static Built buildMultiSegment(String marker) {
        UUID submissionId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        String bodyText = "合成记忆正文-" + marker;
        String bodyHash = sha256Hex(bodyText);

        UUID actor1 = deterministicId("a1:", submissionId);
        UUID actor2 = deterministicId("a2:", submissionId);
        UUID perspectiveActor = actor2;
        UUID anchor1 = deterministicId("anchor1:", submissionId);
        UUID anchor2 = deterministicId("anchor2:", submissionId);

        EvidenceMessage m1 = new EvidenceMessage(deterministicId("m1:", submissionId), actor1, 1L,
                "unit-" + marker + "-1", OffsetDateTime.parse("2026-08-12T09:30:00Z"), "段一消息一", sha256Hex("段一消息一"));
        EvidenceMessage m2 = new EvidenceMessage(deterministicId("m2:", submissionId), actor2, 2L,
                "unit-" + marker + "-2", OffsetDateTime.parse("2026-08-12T09:30:01Z"), "段一消息二", sha256Hex("段一消息二"));
        EvidenceMessage m3 = new EvidenceMessage(deterministicId("m3:", submissionId), actor1, 5L,
                "unit-" + marker + "-5", OffsetDateTime.parse("2026-08-12T09:31:00Z"), "段二消息一", sha256Hex("段二消息一"));
        EvidenceMessage m4 = new EvidenceMessage(deterministicId("m4:", submissionId), actor2, 6L,
                "unit-" + marker + "-6", OffsetDateTime.parse("2026-08-12T09:31:01Z"), "段二消息二", sha256Hex("段二消息二"));
        List<EvidenceMessage> messages = List.of(m1, m2, m3, m4);
        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 6L, false, "", messages));

        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", 1L, 6L, false, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(perspectiveActor, "INTERPRETATION", bodyText, bodyHash);
        UserConfirmation placeholder = new UserConfirmation("CONFIRM", "", confirmationUnit);
        List<SourceAnchor> anchors = List.of(
                new SourceAnchor(anchor1, List.of(
                        new AnchorUnit(m1.sourceUnitId(), 0L, (long) m1.bodyText().codePointCount(0, m1.bodyText().length()), 1L),
                        new AnchorUnit(m2.sourceUnitId(), 0L, (long) m2.bodyText().codePointCount(0, m2.bodyText().length()), 2L))),
                new SourceAnchor(anchor2, List.of(
                        new AnchorUnit(m3.sourceUnitId(), 0L, (long) m3.bodyText().codePointCount(0, m3.bodyText().length()), 5L),
                        new AnchorUnit(m4.sourceUnitId(), 0L, (long) m4.bodyText().codePointCount(0, m4.bodyText().length()), 6L))));

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

    private static EvidenceMessage partMsg(UUID submissionId, String label, long ordinal, boolean perspective) {
        UUID actor = perspective ? deterministicId("a2:", submissionId) : deterministicId("a1:", submissionId);
        String text = "分-" + label;
        return new EvidenceMessage(
                deterministicId(label + ":", submissionId), actor, ordinal, "unit-" + label,
                OffsetDateTime.parse("2026-08-12T09:30:00Z"), text, sha256Hex(text));
    }

    private static AnchorUnit partUnit(EvidenceMessage message) {
        return new AnchorUnit(message.sourceUnitId(), 0L, 1L, message.ordinal());
    }

    private static SourceAnchor anchor(AnchorUnit... units) {
        return new SourceAnchor(UUID.randomUUID(), List.of(units));
    }

    private static Built buildPartitionRequest(
            UUID submissionId,
            List<EvidenceMessage> messages,
            List<SourceAnchor> anchors,
            boolean continuous,
            long fromOrdinal,
            long toOrdinal) {
        UUID threadId = UUID.randomUUID();
        UUID confirmationUnit = UUID.randomUUID();
        UUID perspectiveActor = deterministicId("a2:", submissionId);
        String bodyText = "合成记忆正文-partition-attack";
        String bodyHash = sha256Hex(bodyText);

        String manifestHash = LocalV1CloseoutCanonicalizer.threadManifestHash(
                new ThreadReaderManifest("local-v1-synthetic-v1", fromOrdinal, toOrdinal, continuous, "", messages));
        ThreadReaderManifest manifest =
                new ThreadReaderManifest("local-v1-synthetic-v1", fromOrdinal, toOrdinal, continuous, manifestHash, messages);
        HideSelection hideSelection = new HideSelection(perspectiveActor, "INTERPRETATION", bodyText, bodyHash);
        UserConfirmation placeholder = new UserConfirmation("CONFIRM", "", confirmationUnit);

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

    private static void assertPartitionRejected(Built built, String failureCode) throws Exception {
        long memBefore = count("memory.memory_record");
        long srcBefore = count("evidence.source");
        assertProblem(post("/v1/closeout-submissions", built.json(), TOKEN, CAPABILITY), 422, failureCode);
        assertEquals(memBefore, count("memory.memory_record"));
        assertEquals(srcBefore, count("evidence.source"));
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

    private static List<Response> runConcurrently(int threads, String body) throws Exception {
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            bodies.add(body);
        }
        return runConcurrently(bodies);
    }

    private static List<Response> runConcurrently(List<String> bodies) throws Exception {
        int threads = bodies.size();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Response>> futures = new ArrayList<>();
        for (String body : bodies) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return post("/v1/closeout-submissions", body, TOKEN, CAPABILITY);
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

    private static void assertSingleFact(UUID submissionId) {
        UUID memoryId = deterministicId("memory:", submissionId);
        UUID scopeId = deterministicId("scope:", submissionId);
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM runtime.closeout_run WHERE submission_id=?::uuid", submissionId));
        assertEquals(1, (long) countRows("SELECT 1 FROM runtime.capture_scope WHERE scope_id=?::uuid", scopeId));
        assertEquals(2, (long) countRows(
                "SELECT 1 FROM runtime.capture_scope_unit WHERE scope_id=?::uuid", scopeId));
        assertEquals(1, (long) countRows("SELECT 1 FROM memory.memory_record WHERE memory_id=?::uuid", memoryId));
        UUID sourceId = dsl.fetchOne(
                        "SELECT source_id FROM runtime.capture_scope WHERE scope_id=?::uuid", scopeId)
                .get("source_id", UUID.class);
        assertEquals(1, (long) countRows("SELECT 1 FROM evidence.source WHERE source_id=?::uuid", sourceId));
        assertEquals(2, (long) countRows("SELECT 1 FROM evidence.source_unit WHERE source_id=?::uuid", sourceId));
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM evidence.source_anchor WHERE source_id=?::uuid", sourceId));
        UUID revisionId = dsl.fetchOne(
                        "SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?::uuid", memoryId)
                .get("current_revision_id", UUID.class);
        assertEquals(1, (long) countRows(
                "SELECT 1 FROM memory.memory_relation WHERE from_revision_id=?::uuid", revisionId));
    }

    private static UUID deterministicId(String label, UUID submissionId) {
        return UUID.nameUUIDFromBytes((label + submissionId).getBytes(StandardCharsets.UTF_8));
    }

    private static long count(String table) {
        return countRows("SELECT 1 FROM " + table);
    }

    private static long countRows(String sql, Object... binds) {
        return dsl.resultQuery(sql, binds).fetch().size();
    }

    private static void disableTrigger(String table, String trigger) {
        dsl.execute("ALTER TABLE " + table + " DISABLE TRIGGER " + trigger);
    }

    private static void enableTrigger(String table, String trigger) {
        dsl.execute("ALTER TABLE " + table + " ENABLE TRIGGER " + trigger);
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
                    "runtime.capture_scope", "runtime.capture_scope_unit", "runtime.closeout_run",
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

    private static void installTrigger(String functionSql, String triggerSql) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute(functionSql);
            s.execute(triggerSql);
        }
    }

    private static void dropTrigger(String trigger, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), USER, postgres.getPassword());
                Statement s = c.createStatement()) {
            s.execute("DROP TRIGGER IF EXISTS " + trigger + " ON " + table);
            s.execute("DROP FUNCTION IF EXISTS public." + trigger.replace("injected_", "force_") + "()");
        }
    }

    private static Response post(String path, String body, String token, String capability) throws Exception {
        return request("POST", path, body, token, capability);
    }

    private static Response postNoKey(String path, String body, String token, String capability) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-Action-Capability", capability)
                .method("POST", HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static Response get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET();
        HttpResponse<String> response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.headers().map(), response.body());
    }

    private static Response request(String method, String path, String body, String token, String capability)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (capability != null) builder.header("X-Action-Capability", capability);
        if (body != null) {
            builder.header("Idempotency-Key", JSON.readTree(body).get("submissionId").asText());
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
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

    private record Response(int status, Map<String, List<String>> headers, String body) {}
}
