package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutCanonicalizer;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutVectorProjectionCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutWriteCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1VectorCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutReceipt;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.AnchorUnit;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.EvidenceMessage;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.HideSelection;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.SourceAnchor;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.ThreadReaderManifest;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission.UserConfirmation;
import io.github.candyxi0.hidenest.application.model.LocalV1RunStatus;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeQueryAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqVectorStoreAdapter;
import io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor;
import io.github.candyxi0.hidenest.embedding.HttpEmbeddingProviderAdapter;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.port.EmbeddingProviderPort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryVectorStorePort;
import io.github.candyxi0.hidenest.memory.port.ModelFingerprint;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Local V1 closeout vector projection vertical: canonical-first commit then a post-commit vector
 * index in a separate transaction boundary. Offline fake-HTTP embedding + real PostgreSQL/pgvector.
 */
class LocalV1CloseoutVectorProjectionTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String MODEL = "bge-small-zh-v1.5-f16";
    private static final int DIMENSION = 512;
    private static final String NORMALIZATION = "CALLER_L2";
    private static final byte[] GGUF_SHA = HexFormat.of()
            .parseHex("ab9b81d9cd329c712eee379cf0068eabe6a5e2a01d0def61535eba9384085e2c");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneId.of("UTC"));

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static String password;
    private static HttpServer server;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static MemoryGovernancePort governance;
    private static TransactionExecutor transactions;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1CloseoutWriteCoordinator closeout;
    private static JooqMemoryReadAdapter memoryRead;
    private static MemoryVectorStorePort vectorStore;
    private static HttpEmbeddingProviderAdapter embedding;
    private static LocalV1VectorCoordinator vector;
    private static LocalV1CloseoutVectorProjectionCoordinator projection;
    private static Path payloadRoot;

    private static volatile EmbedMode embedMode = EmbedMode.OK;

    private enum EmbedMode {
        OK,
        HTTP_400,
        HTTP_500,
        INVALID_JSON,
        WRONG_MODEL,
        WRONG_DIMENSION
    }

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (var connection = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(20,
                Flyway.configure()
                        .dataSource(postgres.getJdbcUrl(), USER, password)
                        .defaultSchema("public")
                        .locations("classpath:db/migration")
                        .cleanDisabled(true)
                        .load()
                        .migrate()
                        .migrationsExecuted);

        var raw = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(raw));
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new TransactionAwareDataSourceProxy(raw));
        dsl = new DefaultDSLContext(configuration);

        governance = new JooqMemoryGovernanceAdapter(dsl);
        transactions = new SpringTransactionExecutor(tx);
        EvidenceReferencePort evidence = new JooqEvidenceReferenceAdapter(dsl);
        RuntimeTransactionPort runtime = new JooqRuntimeTransactionAdapter(dsl);
        var publisher = new CanonicalPublishCoordinator(governance, runtime, transactions, CLOCK);
        payloadRoot = Files.createTempDirectory("closeout-vector-payload-");
        PayloadStore payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, transactions, publisher, payloadStore, CLOCK);

        closeout = new LocalV1CloseoutWriteCoordinator(
                runtime, new JooqRuntimeQueryAdapter(dsl), transactions, s1, CLOCK);
        memoryRead = new JooqMemoryReadAdapter(dsl);
        vectorStore = new JooqVectorStoreAdapter(dsl);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/embed", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            JsonNode root = MAPPER.readTree(body);
            String text = root.path("texts").get(0).asText();
            String response;
            int status = 200;
            switch (embedMode) {
                case OK -> response = "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                        + ",\"vectors\":[" + unitVectorLiteral() + "]}";
                case HTTP_400 -> {
                    status = 400;
                    response = "{\"error\":\"bad request\"}";
                }
                case HTTP_500 -> {
                    status = 500;
                    response = "{\"error\":\"boom\"}";
                }
                case INVALID_JSON -> response = "this is not json";
                case WRONG_MODEL -> response = "{\"model\":\"wrong-model\",\"dimension\":" + DIMENSION
                        + ",\"vectors\":[" + unitVectorLiteral() + "]}";
                case WRONG_DIMENSION -> response = "{\"model\":\"" + MODEL + "\",\"dimension\":256"
                        + ",\"vectors\":[" + unitVectorLiteral() + "]}";
                default -> response = "{\"model\":\"" + MODEL + "\",\"dimension\":" + DIMENSION
                        + ",\"vectors\":[" + unitVectorLiteral() + "]}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();

        embedding = new HttpEmbeddingProviderAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(), MODEL, DIMENSION, 30000, 32);
        var fingerprint = new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION);
        vector = new LocalV1VectorCoordinator(embedding, vectorStore, governance, transactions, fingerprint, CLOCK);
        projection = new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, vector, memoryRead, vectorStore, fingerprint);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        if (postgres != null) {
            postgres.stop();
        }
        if (payloadRoot != null && Files.exists(payloadRoot)) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best-effort
                    }
                });
            }
        }
    }

    @BeforeEach
    void resetMode() {
        embedMode = EmbedMode.OK;
    }

    // ── 1. canonical-first index → INDEX_READY ────────────────────────────

    @Test
    void submitIndexesCurrentRevisionAndReturnsIndexReady() {
        var submission = build("happy");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        LocalV1CloseoutReceipt receipt = projection.submit(submission);

        assertEquals(submissionId, receipt.runId());
        assertEquals(memoryId, receipt.memoryId());
        assertEquals("INDEX_READY", receipt.phase());

        UUID revisionId = currentRevisionId(memoryId);
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id=?", revisionId));
        assertEquals("INDEX_READY", projection.findRunStatus(submissionId).phase());
    }

    // ── 2. embedding failure keeps canonical facts committed ──────────────

    @Test
    void embeddingFailureKeepsCanonicalFactsCommitted() {
        embedMode = EmbedMode.HTTP_500;
        var submission = build("embed-fail");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        LocalV1CloseoutReceipt receipt = projection.submit(submission);

        assertCanonicalCommitted(receipt, submissionId, memoryId);
        assertEquals(0L, vectorCount(memoryId));
        assertEquals("CANONICAL_COMMITTED", projection.findRunStatus(submissionId).phase());
    }

    // ── 3. embedding runs after canonical commit, outside its transaction ─

    @Test
    void embeddingRunsAfterCanonicalCommitInSeparateTransaction() {
        var submission = build("after-commit");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        AtomicBoolean verified = new AtomicBoolean(false);
        EmbeddingProviderPort visibility = new EmbeddingProviderPort() {
            @Override
            public EmbeddingHealth health() {
                return embedding.health();
            }

            @Override
            public EmbeddingResult embed(List<String> texts) {
                // A fresh read here must already see the committed closeout facts; if the index
                // shared the closeout transaction these rows would still be uncommitted/invisible.
                assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
                assertEquals(1L, count(
                        "SELECT count(*) FROM runtime.closeout_run WHERE submission_id=? AND state='COMPLETED'",
                        submissionId));
                assertEquals(1L, count(
                        "SELECT count(*) FROM runtime.idempotency_receipt WHERE idempotency_key=?",
                        submissionId.toString()));
                verified.set(true);
                return embedding.embed(texts);
            }
        };
        var visibilityVector = new LocalV1VectorCoordinator(
                visibility, vectorStore, governance, transactions,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION), CLOCK);
        var visibilityProjection = new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, visibilityVector, memoryRead, vectorStore,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION));

        LocalV1CloseoutReceipt receipt = visibilityProjection.submit(submission);

        assertEquals("INDEX_READY", receipt.phase());
        assertTrue(verified.get(), "embedding must observe the already-committed closeout facts");
    }

    // ── 4. same-value replay backfills a missing vector ───────────────────

    @Test
    void replayBackfillsMissingVectorAndConvergesToIndexReady() {
        embedMode = EmbedMode.HTTP_500;
        var submission = build("backfill");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        LocalV1CloseoutReceipt first = projection.submit(submission);
        assertCanonicalCommitted(first, submissionId, memoryId);
        assertEquals(0L, vectorCount(memoryId));

        embedMode = EmbedMode.OK;
        LocalV1CloseoutReceipt replay = projection.submit(submission);

        assertEquals(submissionId, replay.runId());
        assertEquals(memoryId, replay.memoryId());
        assertEquals("INDEX_READY", replay.phase());
        assertEquals(1L, vectorCount(memoryId));
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
    }

    // ── 5. already-indexed replay does not produce a second vector fact ───

    @Test
    void alreadyIndexedReplayDoesNotProduceSecondVectorFact() {
        var submission = build("exact-replay");
        UUID memoryId = deterministicId("memory:", submission.submissionId());

        LocalV1CloseoutReceipt first = projection.submit(submission);
        LocalV1CloseoutReceipt replay = projection.submit(submission);

        assertEquals("INDEX_READY", first.phase());
        assertEquals("INDEX_READY", replay.phase());
        assertEquals(1L, vectorCount(memoryId));
    }

    // ── 6. same key different value is rejected without a vector fact ─────

    @Test
    void sameSubmissionDifferentContentIsRejectedWithoutVector() {
        UUID submissionId = UUID.randomUUID();
        var first = buildWithSubmissionId(submissionId, "same-key-a");
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals("INDEX_READY", projection.submit(first).phase());
        assertEquals(1L, vectorCount(memoryId));

        var different = buildWithSubmissionId(submissionId, "same-key-b");
        LocalV1CloseoutException ex =
                assertThrows(LocalV1CloseoutException.class, () -> projection.submit(different));
        assertEquals(LocalV1CloseoutException.Code.IDEMPOTENCY_KEY_REUSED, ex.code());
        assertEquals(1L, vectorCount(memoryId));
    }

    // ── 7. run status readiness: no vector → CANONICAL_COMMITTED ──────────

    @Test
    void runStatusReflectsReadinessAfterVectorRemoved() {
        var submission = build("status-remove");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals("INDEX_READY", projection.submit(submission).phase());
        assertEquals("INDEX_READY", projection.findRunStatus(submissionId).phase());

        UUID revisionId = currentRevisionId(memoryId);
        dsl.execute("DELETE FROM memory.memory_revision_embedding WHERE memory_revision_id=?", revisionId);

        LocalV1RunStatus status = projection.findRunStatus(submissionId);
        assertEquals("CANONICAL_COMMITTED", status.phase());
    }

    // ── 8. run status rejects wrong model fingerprint ─────────────────────

    @Test
    void runStatusRejectsWrongModelFingerprint() {
        var submission = build("status-model");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals("INDEX_READY", projection.submit(submission).phase());

        UUID revisionId = currentRevisionId(memoryId);
        dsl.execute("UPDATE memory.memory_revision_embedding SET model_name='wrong-model' WHERE memory_revision_id=?",
                revisionId);

        assertEquals("CANONICAL_COMMITTED", projection.findRunStatus(submissionId).phase());
    }

    // ── 9. run status rejects wrong body hash ─────────────────────────────

    @Test
    void runStatusRejectsWrongBodyHash() {
        var submission = build("status-hash");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals("INDEX_READY", projection.submit(submission).phase());

        UUID revisionId = currentRevisionId(memoryId);
        byte[] tampered = sha256("tampered-body");
        dsl.execute(
                "UPDATE memory.memory_revision_embedding SET embedded_body_sha256=? WHERE memory_revision_id=?",
                tampered, revisionId);

        assertEquals("CANONICAL_COMMITTED", projection.findRunStatus(submissionId).phase());
    }

    // ── 10. run status rejects a stale revision after pointer change ──────

    @Test
    void runStatusRejectsStaleRevisionAfterPointerChange() {
        var submission = build("status-stale");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        assertEquals("INDEX_READY", projection.submit(submission).phase());
        UUID oldRevisionId = currentRevisionId(memoryId);
        assertEquals(1L, vectorCount(memoryId));

        UUID actorId = submission.hideSelection().perspectiveActorId();
        long revisionNo = count("SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id=?", oldRevisionId);
        String originalBody = dsl.fetchOne(
                        "SELECT body_text FROM memory.memory_revision WHERE memory_revision_id=?", oldRevisionId)
                .get("body_text", String.class);
        UUID newRevisionId = reviseMemorySameBody(memoryId, actorId, oldRevisionId, revisionNo, originalBody);

        assertEquals(newRevisionId, currentRevisionId(memoryId));
        assertEquals("CANONICAL_COMMITTED", projection.findRunStatus(submissionId).phase());
    }

    // ── 11. embedding failure modes keep closeout committed, no vector ────

    @Test
    void embeddingFailureModesKeepCloseoutCommittedWithoutVector() {
        for (EmbedMode mode : List.of(
                EmbedMode.HTTP_400,
                EmbedMode.HTTP_500,
                EmbedMode.INVALID_JSON,
                EmbedMode.WRONG_MODEL,
                EmbedMode.WRONG_DIMENSION)) {
            embedMode = mode;
            var submission = build("mode-" + mode);
            UUID submissionId = submission.submissionId();
            UUID memoryId = deterministicId("memory:", submissionId);

            LocalV1CloseoutReceipt receipt = projection.submit(submission);

            assertCanonicalCommitted(receipt, submissionId, memoryId);
            assertEquals(0L, vectorCount(memoryId), "mode " + mode + " must not write a vector");
        }
    }

    // ── 12. current pointer change during index falls back, never fake ready ──

    @Test
    void currentPointerChangeDuringIndexFallsBackToCanonicalCommitted() throws Exception {
        var submission = build("race");
        UUID submissionId = submission.submissionId();
        UUID memoryId = deterministicId("memory:", submissionId);

        BlockingEmbeddingProvider blocking = new BlockingEmbeddingProvider(embedding);
        var racingVector = new LocalV1VectorCoordinator(
                blocking, vectorStore, governance, transactions,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION), CLOCK);
        var racingProjection = new LocalV1CloseoutVectorProjectionCoordinator(
                closeout, racingVector, memoryRead, vectorStore,
                new ModelFingerprint(MODEL, GGUF_SHA, DIMENSION, NORMALIZATION));

        AtomicReference<LocalV1CloseoutReceipt> result = new AtomicReference<>();
        Thread submitter = new Thread(() -> result.set(racingProjection.submit(submission)));
        submitter.start();

        assertTrue(blocking.entered.await(10, TimeUnit.SECONDS), "embed must be entered and blocked");
        UUID currentRevisionId = currentRevisionId(memoryId);
        long revisionNo = count("SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id=?", currentRevisionId);
        String originalBody = dsl.fetchOne(
                        "SELECT body_text FROM memory.memory_revision WHERE memory_revision_id=?", currentRevisionId)
                .get("body_text", String.class);
        UUID actorId = submission.hideSelection().perspectiveActorId();
        UUID newRevisionId = reviseMemorySameBody(memoryId, actorId, currentRevisionId, revisionNo, originalBody);
        blocking.release.countDown();
        submitter.join(10000);

        assertFalse(submitter.isAlive(), "submitter must finish");
        assertEquals("CANONICAL_COMMITTED", result.get().phase());
        assertEquals(0L, count(
                "SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id IN (?,?)",
                currentRevisionId, newRevisionId));
        assertEquals("CANONICAL_COMMITTED", projection.findRunStatus(submissionId).phase());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void assertCanonicalCommitted(LocalV1CloseoutReceipt receipt, UUID submissionId, UUID memoryId) {
        assertEquals(submissionId, receipt.runId());
        assertEquals(memoryId, receipt.memoryId());
        assertEquals("CANONICAL_COMMITTED", receipt.phase());
        assertEquals(1L, count("SELECT count(*) FROM memory.memory_record WHERE memory_id=?", memoryId));
        assertEquals(1L, count(
                "SELECT count(*) FROM runtime.closeout_run WHERE submission_id=? AND state='COMPLETED'",
                submissionId));
        assertEquals(1L, count(
                "SELECT count(*) FROM runtime.idempotency_receipt WHERE idempotency_key=?",
                submissionId.toString()));
    }

    private UUID currentRevisionId(UUID memoryId) {
        return dsl.fetchOne("SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?", memoryId)
                .get("current_revision_id", UUID.class);
    }

    private long vectorCount(UUID memoryId) {
        return count(
                "SELECT count(*) FROM memory.memory_revision_embedding WHERE memory_revision_id="
                        + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id=?)",
                memoryId);
    }

    private UUID reviseMemorySameBody(
            UUID memoryId, UUID actorId, UUID currentRevisionId, long currentRevisionNo, String body) {
        return transactions.executeInTransaction(() -> {
            long currentPolicyRev = count("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id=?", memoryId);
            UUID proposalId = UUID.randomUUID();
            UUID proposalRevisionId = UUID.randomUUID();
            UUID reviewId = UUID.randomUUID();
            UUID decisionId = UUID.randomUUID();
            UUID newRevisionId = UUID.randomUUID();
            long newRevisionNo = currentRevisionNo + 1;

            dsl.execute(
                    "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES (?::uuid,'REVISE',?::uuid,clock_timestamp())",
                    proposalId, memoryId);
            dsl.execute(
                    "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES (?::uuid,?::uuid,1,'REVISE',?::uuid,?,clock_timestamp())",
                    proposalRevisionId, proposalId, currentRevisionId, currentPolicyRev);
            dsl.execute(
                    "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) VALUES (?::uuid,'OPEN',?,decode(repeat('aa',32),'hex'),clock_timestamp())",
                    reviewId, "rev-review-" + reviewId);
            dsl.execute(
                    "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES (?::uuid,?::uuid,1)",
                    reviewId, proposalRevisionId);
            dsl.execute(
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES (?::uuid,'USER_CONFIRM',?::uuid,'USER',?::uuid,?::uuid,'MEMORY',?::uuid,?,'proof',?,clock_timestamp())",
                    decisionId, actorId, proposalRevisionId, reviewId, memoryId, newRevisionNo, "rev-dec-" + decisionId);
            governedOutbox("REVIEW_SESSION", reviewId, newRevisionNo, "review.decisions-committed.v1", decisionId);
            dsl.execute(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) VALUES (?::uuid,?::uuid,?,'Claim',?,?::uuid,clock_timestamp())",
                    newRevisionId, memoryId, newRevisionNo, body, decisionId);
            governedOutbox("MEMORY", memoryId, newRevisionNo, "memory.canonical-committed.v1", decisionId);
            dsl.execute(
                    "UPDATE memory.memory_record SET current_revision_id=?::uuid,updated_at=clock_timestamp() WHERE memory_id=?::uuid AND current_revision_id=?::uuid",
                    newRevisionId, memoryId, currentRevisionId);
            return newRevisionId;
        });
    }

    private void governedOutbox(String aggregateKind, UUID aggregateId, long revision, String eventType, UUID decisionId) {
        UUID changeId = UUID.randomUUID();
        dsl.execute(
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES (?::uuid,?,?,?::uuid,?,?::uuid,clock_timestamp())",
                changeId, eventType, aggregateKind, aggregateId, revision, decisionId);
        String manifest = "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":" + revision
                + ",\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + "ab".repeat(32) + "\"}";
        dsl.execute(
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,created_at) VALUES (?::uuid,?,'GOVERNED',?,?,?::uuid,?,'pink.event.v1','DATABASE_TEST',0,decode(repeat('ab',32),'hex'),?::jsonb,?::uuid,'READY',clock_timestamp(),clock_timestamp())",
                UUID.randomUUID(), "ob-" + UUID.randomUUID(), eventType, aggregateKind, aggregateId, revision, manifest, changeId);
    }

    private static final class BlockingEmbeddingProvider implements EmbeddingProviderPort {
        private final EmbeddingProviderPort delegate;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        BlockingEmbeddingProvider(EmbeddingProviderPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingHealth health() {
            return delegate.health();
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(exception);
            }
            return delegate.embed(texts);
        }
    }

    private LocalV1CloseoutSubmission build(String marker) {
        return buildWithSubmissionId(UUID.randomUUID(), marker);
    }

    private LocalV1CloseoutSubmission buildWithSubmissionId(UUID submissionId, String marker) {
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

        return new LocalV1CloseoutSubmission(
                submissionId,
                threadId,
                hideSelection,
                new UserConfirmation("CONFIRM", reviewManifestHash, confirmationUnit),
                anchors,
                manifest,
                proof);
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

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }
}
