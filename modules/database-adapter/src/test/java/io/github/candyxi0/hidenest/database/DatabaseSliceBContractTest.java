package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseSliceBContractTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final String HASH_HEX = "ab".repeat(32);
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("hide_nest")
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withTmpFs(Map.of("/var/lib/postgresql", "rw,noexec,nosuid,size=536870912"));

    private static Flyway flyway;

    @BeforeAll
    static void migrateSyntheticDatabase() throws SQLException {
        POSTGRES.start();
        assertEquals(IMAGE, POSTGRES.getDockerImageName());
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE hide_nest_api NOLOGIN");
            statement.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .load();
        MigrateResult result = flyway.migrate();
        assertEquals(6, result.migrationsExecuted);
    }

    @AfterAll
    static void stopSyntheticDatabase() {
        POSTGRES.stop();
    }

    // ============================================================
    // Existing gates (updated for B01-B10 constraints)
    // ============================================================

    @Test
    @Order(1)
    @DisplayName("DatabaseMigrationIT: empty and repeated migration")
    void databaseMigrationIT() throws SQLException {
        flyway.validate();
        String before = catalogFingerprint();
        MigrateResult repeated = flyway.migrate();
        assertEquals(0, repeated.migrationsExecuted);
        assertEquals(before, catalogFingerprint());
        assertEquals(6, scalarLong("SELECT count(*) FROM public.flyway_schema_history WHERE success"));
    }

    @Test
    @Order(2)
    @DisplayName("FlywayChecksumDriftIT: changed applied migration is rejected")
    void flywayChecksumDriftIT() throws Exception {
        Path migrationRoot = moduleRoot().resolve("src/main/resources/db/migration");
        Map<String, String> before = fileHashes(migrationRoot);
        Path temporary = Files.createTempDirectory("hdm005-flyway-drift-");
        try {
            for (Path source : migrationFiles(migrationRoot)) {
                Files.copy(source, temporary.resolve(source.getFileName()));
            }
            Path changed = temporary.resolve("V003__core_tables.sql");
            Files.writeString(
                    changed,
                    "\n-- synthetic checksum drift\n",
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND);
            Flyway drift = Flyway.configure()
                    .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                    .defaultSchema("public")
                    .locations("filesystem:" + temporary.toAbsolutePath())
                    .cleanDisabled(true)
                    .load();
            assertThrows(RuntimeException.class, drift::validate);
            assertEquals(before, fileHashes(migrationRoot));
        } finally {
            deleteTree(temporary);
        }
    }

    @Test
    @Order(3)
    @DisplayName("CoreDatabaseInventoryIT: four schemas and sixteen approved tables")
    void coreDatabaseInventoryIT() throws SQLException {
        assertEquals(
                Set.of("evidence", "memory", "runtime", "security"),
                querySet("SELECT schema_name FROM information_schema.schemata "
                        + "WHERE schema_name IN ('evidence','memory','runtime','security')"));
        assertEquals(
                Set.of(
                        "memory.actor_ref",
                        "memory.memory_record",
                        "memory.memory_revision",
                        "memory.proposal",
                        "memory.proposal_revision",
                        "memory.review_session",
                        "memory.review_member",
                        "memory.decision",
                        "memory.access_policy",
                        "memory.access_policy_revision",
                        "memory.access_policy_grant",
                        "memory.change_event",
                        "runtime.idempotency_receipt",
                        "runtime.outbox_event",
                        "runtime.failure_code_registry",
                        "runtime.event_type_registry"),
                querySet("SELECT table_schema || '.' || table_name FROM information_schema.tables "
                        + "WHERE table_schema IN ('evidence','memory','runtime','security') "
                        + "AND table_type='BASE TABLE'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema IN ('semantic','governance')"));
    }

    @Test
    @Order(4)
    @DisplayName("SqlTypeAndCollationIT: UUID, bigint, bytea and C-collated closed values")
    void sqlTypeAndCollationIT() throws SQLException {
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema IN ('memory','runtime') AND data_type='character varying'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema IN ('evidence','memory','runtime','security') "
                        + "AND udt_name='vector'"));
        assertEquals(
                "C",
                scalarString("SELECT collation_name FROM information_schema.columns "
                        + "WHERE table_schema='memory' AND table_name='memory_record' AND column_name='state'"));
        assertEquals(
                "uuid",
                scalarString("SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema='memory' AND table_name='memory_revision' "
                        + "AND column_name='memory_revision_id'"));
        assertEquals(
                "bigint",
                scalarString(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='memory' AND table_name='memory_revision' AND column_name='revision_no'"));
        assertEquals(
                "bytea",
                scalarString(
                        "SELECT data_type FROM information_schema.columns "
                                + "WHERE table_schema='runtime' AND table_name='outbox_event' AND column_name='manifest_hash'"));
    }

    @Test
    @Order(5)
    @DisplayName("ForeignKeyDeletePolicyIT: every foreign key is explicit NO ACTION")
    void foreignKeyDeletePolicyIT() throws SQLException {
        long count = scalarLong("SELECT count(*) FROM information_schema.referential_constraints "
                + "WHERE constraint_schema IN ('memory','runtime')");
        assertTrue(count >= 20);
        assertEquals(
                count,
                scalarLong("SELECT count(*) FROM information_schema.referential_constraints "
                        + "WHERE constraint_schema IN ('memory','runtime') AND delete_rule='NO ACTION'"));
    }

    @Test
    @Order(6)
    @DisplayName("CurrentPointerConstraintIT: cross-owner and missing revisions fail at COMMIT")
    void currentPointerConstraintIT() throws SQLException {
        MemoryFixture left = insertMemory("left-current");
        MemoryFixture right = insertMemory("right-current");

        // Prepare a valid Decision for right's revision 2 (Stage A)
        DecisionFixture rightDecision = prepareCanonicalDecision(right, 2L);
        assertTrue(publishCanonicalRevision(right, rightDecision, "right-current-next"));

        // Cross-owner: left's current_revision_id pointing to right's revision (B01 Decision check fails)
        assertCommitSqlState(
                "23514",
                connection -> execute(
                        connection,
                        ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                        + "WHERE memory_id='%s'")
                                .formatted(rightDecision.newRevisionId(), left.memoryId())));

        // Verify left unchanged
        assertEquals(
                left.revisionId().toString(),
                scalarString("SELECT current_revision_id::text FROM memory.memory_record WHERE memory_id='%s'"
                        .formatted(left.memoryId())));

        // CAS backward: publish revision 2, then try to revert to revision 1
        DecisionFixture leftDecision = prepareCanonicalDecision(left, 2L);
        assertTrue(publishCanonicalRevision(left, leftDecision, "left-revision-2"));
        assertCommitSqlState(
                "23514",
                connection -> execute(
                        connection,
                        ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                        + "WHERE memory_id='%s'")
                                .formatted(left.revisionId(), left.memoryId())));

        // Missing policy revision
        assertCommitSqlState(
                "23503",
                connection -> execute(
                        connection,
                        "UPDATE memory.access_policy SET current_revision_no=2 WHERE policy_id='%s'"
                                .formatted(left.policyId())));
        assertEquals(
                1,
                scalarLong("SELECT current_revision_no FROM memory.access_policy WHERE policy_id='%s'"
                        .formatted(left.policyId())));
    }

    @Test
    @Order(7)
    @DisplayName("DecisionCanonicalUniquenessIT: two connections yield one verdict winner and one canonical winner")
    void decisionCanonicalUniquenessIT() throws Exception {
        // Verdict race: one member, two final verdicts → one winner
        ReviewFixture review = insertReviewFixture();
        int verdictWinners =
                race(() -> insertFinalVerdict(review, "USER_CONFIRM"), () -> insertFinalVerdict(review, "USER_REJECT"));
        assertEquals(1, verdictWinners);
        assertEquals(
                1,
                scalarLong("SELECT count(*) FROM memory.decision WHERE review_session_id='%s' "
                        .formatted(review.reviewSessionId())));

        // Canonical race: same pre-existing Decision, two publishers → one winner
        MemoryFixture memory = insertMemory("canonical-race");
        DecisionFixture decision = prepareCanonicalDecision(memory, 2L);
        int canonicalWinners = race(
                () -> publishCanonicalRevision(memory, decision, "candidate-a"),
                () -> publishCanonicalRevision(memory, decision, "candidate-b"));
        assertEquals(1, canonicalWinners);
        assertEquals(
                2,
                scalarLong(("SELECT revision_no FROM memory.memory_revision "
                                + "WHERE memory_id='%s' AND memory_revision_id=(SELECT current_revision_id "
                                + "FROM memory.memory_record WHERE memory_id='%s')")
                        .formatted(memory.memoryId(), memory.memoryId())));
    }

    @Test
    @Order(8)
    @DisplayName("AccessPolicyConcurrencyIT: one owner, one current authority, exact scope")
    void accessPolicyConcurrencyIT() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID policy = insertPolicy(owner);
        int winners = race(() -> insertPolicyRevision(policy, true), () -> insertPolicyRevision(policy, false));
        assertEquals(1, winners);
        assertEquals(
                2,
                scalarLong(
                        "SELECT current_revision_no FROM memory.access_policy WHERE policy_id='%s'".formatted(policy)));
        for (String forbidden : List.of("SELF", "*", "{\"scope\":\"owner\"}")) {
            assertStatementSqlState(
                    "23514",
                    "INSERT INTO memory.access_policy_grant "
                            + "(policy_id,revision_no,actor_role,purpose,effect,object_scope) VALUES "
                            + "('%s',1,'USER','TEST','READ','%s')".formatted(policy, forbidden.replace("'", "''")));
        }
    }

    @Test
    @Order(9)
    @DisplayName("ImmutableTableProtectionIT: immutable authority rows reject UPDATE and DELETE")
    void immutableTableProtectionIT() throws SQLException {
        MemoryFixture fixture = insertMemory("immutable");
        assertStatementSqlState(
                "55000",
                "UPDATE memory.memory_revision SET body_text='changed' "
                        + "WHERE memory_revision_id='%s'".formatted(fixture.revisionId()));
        assertStatementSqlState(
                "55000", "DELETE FROM memory.decision WHERE decision_id='%s'".formatted(fixture.decisionId()));
        UUID legalActor = UUID.randomUUID();
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE hide_nest_api");
            statement.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                    + "('%s','SYNTHETIC','legal-api-insert',clock_timestamp())".formatted(legalActor));
            SQLException denied = assertThrows(
                    SQLException.class,
                    () -> statement.execute("UPDATE memory.actor_ref SET stable_ref='changed' WHERE actor_id='%s'"
                            .formatted(legalActor)));
            assertEquals("42501", sqlState(denied));
        }
    }

    @Test
    @Order(10)
    @DisplayName("DatabasePrivilegeIT: migrator ownership and runtime least privilege")
    void databasePrivilegeIT() throws SQLException {
        assertEquals(
                16,
                scalarLong("SELECT count(*) FROM pg_catalog.pg_tables "
                        + "WHERE schemaname IN ('evidence','memory','runtime','security') "
                        + "AND tableowner='hide_nest_migrator'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM information_schema.role_table_grants "
                        + "WHERE table_schema IN ('memory','runtime') AND grantee='PUBLIC'"));
        assertTrue(scalarBoolean("SELECT has_table_privilege('hide_nest_api','memory.actor_ref','INSERT')"));
        assertFalse(scalarBoolean("SELECT has_table_privilege('hide_nest_api','memory.actor_ref','UPDATE')"));
        assertTrue(scalarBoolean(
                "SELECT has_column_privilege('hide_nest_worker','runtime.outbox_event','state','UPDATE')"));
        assertFalse(scalarBoolean("SELECT has_table_privilege('hide_nest_worker','memory.decision','INSERT')"));
    }

    @Test
    @Order(11)
    @DisplayName("AggregateOutboxGuardIT: governed and operational branches are database-proven")
    void aggregateOutboxGuardIT() throws SQLException {
        UUID governedId = UUID.randomUUID();
        // Missing ChangeEvent FK
        assertCommitSqlState(
                "23503",
                connection -> insertOutbox(
                        connection,
                        "GOVERNED",
                        "memory.canonical-committed.v1",
                        "MEMORY",
                        governedId,
                        1L,
                        UUID.randomUUID(),
                        uniqueKey()));

        // ChangeEvent with NULL decision_id (B04)
        assertCommitSqlState("23514", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection, changeId, "memory.canonical-committed.v1", "MEMORY", UUID.randomUUID(), 1L, null);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    governedId,
                    1L,
                    changeId,
                    uniqueKey());
        });

        // Valid governed path
        UUID validDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            UUID actorId = UUID.randomUUID();
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','gov-actor-%s',clock_timestamp())".formatted(actorId, actorId));
            execute(
                    connection,
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                            + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',1,'proof','gov-dec-%s',clock_timestamp())"
                                    .formatted(validDecisionId, actorId, governedId, validDecisionId));
            insertGovernedOutbox(
                    connection, "MEMORY", governedId, 1L, "memory.canonical-committed.v1", validDecisionId);
            connection.commit();
        }

        // Valid operational path
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertOutbox(
                    connection,
                    "OPERATIONAL",
                    "closeout.received.v1",
                    "CLOSEOUT_RUN",
                    UUID.randomUUID(),
                    1L,
                    null,
                    uniqueKey());
            connection.commit();
        }
        assertStatementSqlState("23514", operationalInsertSql(null, uniqueKey()));
    }

    @Test
    @Order(12)
    @DisplayName("FailureCodeContractDriftTest: database and OpenAPI sets match exactly")
    void failureCodeContractDriftTest() throws Exception {
        Set<String> contract = extractEnum(
                repositoryRoot().resolve("contracts/openapi/problem-detail.schema.json"), "\"FailureCode\"");
        assertEquals(50, contract.size());
        assertEquals(contract, querySet("SELECT failure_code FROM runtime.failure_code_registry"));
    }

    @Test
    @Order(13)
    @DisplayName("EventTypeContractDriftTest: database and event contract sets match exactly")
    void eventTypeContractDriftTest() throws Exception {
        Set<String> contract =
                extractEnum(repositoryRoot().resolve("contracts/events/pink-event-v1.schema.json"), "\"EventType\"");
        assertEquals(12, contract.size());
        assertEquals(contract, querySet("SELECT event_type FROM runtime.event_type_registry"));
    }

    @Test
    @Order(14)
    @DisplayName("UnknownFailureCodeIT: registry and outbox reject unknown codes")
    void unknownFailureCodeIT() throws SQLException {
        assertStatementSqlState(
                "55000", "INSERT INTO runtime.failure_code_registry(failure_code) " + "VALUES ('SYNTHETIC_UNKNOWN')");
        assertCommitSqlState(
                "23503",
                connection -> execute(
                        connection,
                        operationalInsertSql(1L, uniqueKey())
                                .replace("NULL,clock_timestamp()", "'SYNTHETIC_UNKNOWN',clock_timestamp()")));
    }

    @Test
    @Order(15)
    @DisplayName("JooqGenerationDeterminismIT: tracked generated tree is unique and package-isolated")
    void jooqGenerationDeterminismIT() throws IOException {
        Path generated = moduleRoot().resolve("src/generated/java");
        List<Path> files;
        try (Stream<Path> stream = Files.walk(generated)) {
            files = stream.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertFalse(files.isEmpty());
        for (Path file : files) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            assertTrue(source.contains("package io.github.candyxi0.hidenest.database.generated"));
        }
        try (Stream<Path> stream = Files.walk(repositoryRoot())) {
            List<Path> leaks = stream.filter(path -> path.toString().contains("src\\main\\java"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(moduleRoot()))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains("io.github.candyxi0.hidenest.database.generated");
                        } catch (IOException exception) {
                            throw new IllegalStateException(exception);
                        }
                    })
                    .toList();
            assertTrue(leaks.isEmpty(), () -> "generated type leaked to production source: " + leaks);
        }
    }

    @Test
    @Order(16)
    @DisplayName("DatabaseLeakageCanaryIT: body, prompt, token, credential, SQL and path are absent")
    void databaseLeakageCanaryIT() throws SQLException {
        String canary = "HDM005_CANARY_" + UUID.randomUUID();
        MemoryFixture fixture = insertMemory(canary);
        assertEquals(
                canary,
                scalarString("SELECT body_text FROM memory.memory_revision WHERE memory_revision_id='%s'"
                        .formatted(fixture.revisionId())));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.outbox_event "
                        + "WHERE payload_manifest::text LIKE '%%%s%%' OR coalesce(last_failure_code,'') LIKE '%%%s%%'"
                                .formatted(canary, canary)));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.idempotency_receipt "
                        + "WHERE coalesce(response_manifest::text,'') LIKE '%%%s%%'".formatted(canary)));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM memory.change_event "
                        + "WHERE coalesce(detail_manifest::text,'') LIKE '%%%s%%'".formatted(canary)));

        // Forbidden keys in outbox manifest (now enforced by valid_outbox_manifest)
        for (String forbiddenKey : List.of("body_text", "prompt", "token", "credential", "sql", "absolutePath")) {
            UUID testAggId = UUID.randomUUID();
            String invalidManifest = "{\"aggregateId\":\"" + testAggId
                    + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                    + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX
                    + "\",\"" + forbiddenKey + "\":\"x\"}";
            assertStatementSqlState("23514", operationalInsertSqlWithManifest(1L, uniqueKey(), invalidManifest));
        }
    }

    // ============================================================
    // B02: ExistingDecisionCanonicalBindingIT (7 wrong-binding attacks)
    // ============================================================

    @Test
    @Order(20)
    @DisplayName("ExistingDecisionCanonicalBindingIT: 7 wrong bindings fail; same Decision path passes")
    void existingDecisionCanonicalBindingIT() throws SQLException {
        MemoryFixture memory = insertMemory("binding-test");

        // Positive: same Decision binding
        DecisionFixture validDecision = prepareCanonicalDecision(memory, 2L);
        assertTrue(publishCanonicalRevision(memory, validDecision, "valid-canonical"));

        // Negative 1: borrow another memory's Decision
        MemoryFixture other = insertMemory("other-memory");
        DecisionFixture otherDecision = prepareCanonicalDecision(other, 2L);
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "wrong-decision-body");
                ps.setObject(4, otherDecision.decisionId());
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection,
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    "memory.canonical-committed.v1",
                    otherDecision.decisionId());
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s' AND current_revision_id='%s'")
                            .formatted(revisionId, memory.memoryId(), validDecision.newRevisionId()));
        });

        // Negative 2: old Decision reuse (created_by_decision_id UNIQUE violation)
        assertCommitSqlState("23505", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "old-decision-body");
                ps.setObject(4, memory.decisionId());
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 3L, "memory.canonical-committed.v1", memory.decisionId());
        });

        // Negative 3: target kind mismatch
        DecisionFixture wrongKindDecision = prepareDecisionWithTarget(memory, 3L, "ACCESS_POLICY", memory.policyId());
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "wrong-kind-body");
                ps.setObject(4, wrongKindDecision.decisionId());
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection,
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    "memory.canonical-committed.v1",
                    wrongKindDecision.decisionId());
        });

        // Negative 4: non-USER_CONFIRM Decision
        DecisionFixture nonConfirmDecision = prepareNonConfirmDecision(memory, 3L);
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "non-confirm-body");
                ps.setObject(4, nonConfirmDecision.decisionId());
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection,
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    "memory.canonical-committed.v1",
                    nonConfirmDecision.decisionId());
        });

        // Negative 5: ChangeEvent decision missing
        assertCommitSqlState("23514", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection, changeId, "memory.canonical-committed.v1", "MEMORY", memory.memoryId(), 3L, null);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    changeId,
                    uniqueKey());
        });

        // Negative 6: ChangeEvent decision mismatched from outbox guard perspective
        UUID wrongDecisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','wrong-actor-%s',clock_timestamp())".formatted(actorId, actorId));
            execute(
                    connection,
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                            + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',3,'proof','wrong-dec-%s',clock_timestamp())"
                                    .formatted(wrongDecisionId, actorId, UUID.randomUUID(), wrongDecisionId));
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection,
                    changeId,
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    wrongDecisionId);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    changeId,
                    uniqueKey());
            connection.commit();
        }

        // Negative 7: governed outbox references non-existent Decision (FK violation)
        UUID nonExistentDecisionId = UUID.randomUUID();
        assertCommitSqlState("23503", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection,
                    changeId,
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    nonExistentDecisionId);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    changeId,
                    uniqueKey());
        });

        // Verify memory still has revision 2
        assertEquals(
                2L,
                scalarLong("SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id="
                        + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id='%s')"
                                .formatted(memory.memoryId())));
    }

    // ============================================================
    // B01: MemoryPointerGovernanceIT
    // ============================================================

    @Test
    @Order(21)
    @DisplayName(
            "MemoryPointerGovernanceIT: old revision revert, no-Decision switch, cross-owner policy, old policy, bare state UPDATE all fail")
    void memoryPointerGovernanceIT() throws SQLException {
        MemoryFixture memory = insertMemory("pointer-governance");

        // Attack 1: Publish revision 2, then revert to revision 1 (CAS backward)
        DecisionFixture dec2 = prepareCanonicalDecision(memory, 2L);
        assertTrue(publishCanonicalRevision(memory, dec2, "revision-2-body"));
        assertEquals(
                2L,
                scalarLong("SELECT revision_no FROM memory.memory_revision WHERE memory_revision_id="
                        + "(SELECT current_revision_id FROM memory.memory_record WHERE memory_id='%s')"
                                .formatted(memory.memoryId())));
        // Now try to revert to revision 1 (CAS backward - 1 < 2)
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(memory.revisionId(), memory.memoryId()));
        });

        // Attack 2: Switch current_revision_id to non-existent revision (B01 CAS check catches it)
        UUID fakeRevisionId = UUID.randomUUID();
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(fakeRevisionId, memory.memoryId()));
        });

        // Attack 3: Cross-owner policy
        MemoryFixture other = insertMemory("other-pointer");
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET policy_id='%s', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(other.policyId(), memory.memoryId()));
        });

        // Attack 4: Bare state UPDATE without governed outbox
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(memory.memoryId()));
        });

        // Verify memory state unchanged
        assertEquals(
                "ACTIVE",
                scalarString(
                        "SELECT state FROM memory.memory_record WHERE memory_id='%s'".formatted(memory.memoryId())));
    }

    // ============================================================
    // B03: ReviewCompletionSetEqualityIT
    // ============================================================

    @Test
    @Order(22)
    @DisplayName(
            "ReviewCompletionSetEqualityIT: empty, missing, duplicate, non-member, NULL verdict fail; full set passes")
    void reviewCompletionSetEqualityIT() throws SQLException {
        // Empty completion (no members)
        UUID emptyReviewId = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,"
                                    + "request_hash,opened_at) VALUES ('%s','OPEN','empty-review',decode(repeat('ee',32),'hex'),"
                                    + "clock_timestamp())")
                            .formatted(emptyReviewId));
        }
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                    + "WHERE review_session_id='%s' AND state='OPEN'")
                            .formatted(emptyReviewId));
        });

        // Full set: two members, two verdicts → passes
        ReviewFixture fullReview = insertReviewFixtureWithMembers(2);
        assertTrue(completeReview(fullReview));
        assertEquals(
                "COMPLETED",
                scalarString("SELECT state FROM memory.review_session WHERE review_session_id='%s'"
                        .formatted(fullReview.reviewSessionId())));

        // Missing one verdict
        ReviewFixture incomplete = insertReviewFixtureWithMembers(2);
        // Only insert one verdict (for the first member)
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            UUID decisionId = UUID.randomUUID();
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,'proof','inc-1-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    incomplete.actorId(),
                                    incomplete.proposalRevisionIds().get(0),
                                    incomplete.reviewSessionId(),
                                    incomplete.proposalRevisionIds().get(0),
                                    decisionId));
            insertGovernedOutbox(
                    connection,
                    "REVIEW_SESSION",
                    incomplete.reviewSessionId(),
                    1L,
                    "review.decisions-committed.v1",
                    decisionId);
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                    + "WHERE review_session_id='%s' AND state='OPEN'")
                            .formatted(incomplete.reviewSessionId()));
        });

        // Non-member verdict: create Proposal + ProposalRevision NOT in review_member
        ReviewFixture nonMember = insertReviewFixtureWithMembers(1);
        UUID nonMemberProposalId = UUID.randomUUID();
        UUID nonMemberProposalRevisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    "INSERT INTO memory.proposal(proposal_id,proposal_kind,created_at) "
                            + "VALUES ('%s','CREATE',clock_timestamp())".formatted(nonMemberProposalId));
            execute(
                    connection,
                    "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,"
                            + "action_code,created_at) VALUES ('%s','%s',1,'CREATE',clock_timestamp())"
                                    .formatted(nonMemberProposalRevisionId, nonMemberProposalId));
        }
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            // Insert a Decision with proposal_revision_id NOT in review_member
            UUID decisionId = UUID.randomUUID();
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,'proof','nm-1-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    nonMember.actorId(),
                                    nonMemberProposalRevisionId,
                                    nonMember.reviewSessionId(),
                                    nonMemberProposalRevisionId,
                                    decisionId));
            insertGovernedOutbox(
                    connection,
                    "REVIEW_SESSION",
                    nonMember.reviewSessionId(),
                    1L,
                    "review.decisions-committed.v1",
                    decisionId);
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                    + "WHERE review_session_id='%s' AND state='OPEN'")
                            .formatted(nonMember.reviewSessionId()));
        });

        // NULL verdict (decision without final kind)
        ReviewFixture nullVerdictReview = insertReviewFixtureWithMembers(1);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            UUID decisionId = UUID.randomUUID();
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,'proof','nv-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    nullVerdictReview.actorId(),
                                    nullVerdictReview.proposalRevisionIds().get(0),
                                    nullVerdictReview.reviewSessionId(),
                                    nullVerdictReview.proposalRevisionIds().get(0),
                                    decisionId));
            insertGovernedOutbox(
                    connection,
                    "REVIEW_SESSION",
                    nullVerdictReview.reviewSessionId(),
                    1L,
                    "review.decisions-committed.v1",
                    decisionId);
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                    + "WHERE review_session_id='%s' AND state='OPEN'")
                            .formatted(nullVerdictReview.reviewSessionId()));
        });
    }

    // ============================================================
    // B04: GovernedFactBindingIT
    // ============================================================

    @Test
    @Order(23)
    @DisplayName("GovernedFactBindingIT: detached/NULL/wrong Decision ChangeEvent + duplicate governed facts fail")
    void governedFactBindingIT() throws SQLException {
        MemoryFixture memory = insertMemory("fact-binding");

        // Detached ChangeEvent (no decision_id) for governed outbox → fails
        assertCommitSqlState("23514", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection, changeId, "memory.canonical-committed.v1", "MEMORY", memory.memoryId(), 2L, null);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    changeId,
                    uniqueKey());
        });

        // Wrong event type in ChangeEvent
        UUID wrongTypeDecisionId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','wt-actor',clock_timestamp())".formatted(actorId));
            execute(
                    connection,
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                            + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',2,'proof','wt-dec',clock_timestamp())"
                                    .formatted(wrongTypeDecisionId, actorId, memory.memoryId()));
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection,
                    changeId,
                    "memory.state-changed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    wrongTypeDecisionId);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    changeId,
                    uniqueKey());
        });

        // Duplicate governed outbox (same ChangeEvent referenced twice)
        UUID dupDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','dup-actor',clock_timestamp())".formatted(UUID.randomUUID()));
            execute(
                    connection,
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                            + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',2,'proof','dup-dec',clock_timestamp())"
                                    .formatted(dupDecisionId, actorId, memory.memoryId()));
            connection.commit();
        }
        assertCommitSqlState("23505", connection -> {
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection,
                    changeId,
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    dupDecisionId);
            String reuseKey = uniqueKey();
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    changeId,
                    reuseKey);
            // Second outbox with same idempotency_key (violates UNIQUE constraint)
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    2L,
                    UUID.randomUUID(),
                    reuseKey);
        });
    }

    // ============================================================
    // B05: OperationalIdempotencyIT
    // ============================================================

    @Test
    @Order(24)
    @DisplayName("OperationalIdempotencyIT: same key one row, missing key fails, duplicate key fails")
    void operationalIdempotencyIT() throws SQLException {
        String key = uniqueKey();

        // First insert succeeds
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection, operationalInsertSql(1L, key));
            connection.commit();
        }

        // Same key second insert fails
        assertStatementSqlState("23505", operationalInsertSql(1L, key));

        // Different key succeeds
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection, operationalInsertSql(1L, uniqueKey()));
            connection.commit();
        }

        // Missing idempotency_key (NOT NULL constraint)
        assertStatementSqlState(
                "23502",
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                        + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                        + "manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,"
                        + "max_attempts,last_failure_code,created_at) VALUES "
                        + "('" + UUID.randomUUID() + "',NULL,'OPERATIONAL','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,'pink.event.v1','DATABASE_TEST',0,decode('" + HASH_HEX
                        + "','hex'),'" + manifestPayload(UUID.randomUUID(), 1L) + "'::jsonb,NULL,'READY',"
                        + "clock_timestamp(),0,8,NULL,clock_timestamp())");
    }

    // ============================================================
    // B06: ManifestShapeAndTypeIT
    // ============================================================

    @Test
    @Order(25)
    @DisplayName("ManifestShapeAndTypeIT: required keys, types, column consistency and nested attacks")
    void manifestShapeAndTypeIT() throws SQLException {
        UUID aggId = UUID.randomUUID();

        // Missing required keys
        for (String missingKey :
                List.of("aggregateId", "aggregateRevision", "policyRevision", "purpose", "manifestHash")) {
            StringBuilder json = new StringBuilder("{");
            for (String k : List.of("aggregateId", "aggregateRevision", "policyRevision", "purpose", "manifestHash")) {
                if (k.equals(missingKey)) continue;
                if (json.length() > 1) json.append(",");
                if (k.equals("aggregateId"))
                    json.append("\"aggregateId\":\"").append(aggId).append("\"");
                else if (k.equals("aggregateRevision")) json.append("\"aggregateRevision\":1");
                else if (k.equals("policyRevision")) json.append("\"policyRevision\":0");
                else if (k.equals("purpose")) json.append("\"purpose\":\"DATABASE_TEST\"");
                else if (k.equals("manifestHash"))
                    json.append("\"manifestHash\":\"").append(HASH_HEX).append("\"");
            }
            json.append("}");
            assertStatementSqlState("23514", operationalInsertSqlWithManifest(1L, uniqueKey(), json.toString()));
        }

        // Wrong type: aggregateId as number
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":12345,\"aggregateRevision\":1,\"policyRevision\":0,"
                                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX + "\"}"));

        // Wrong type: aggregateRevision as string
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":\"" + aggId + "\",\"aggregateRevision\":\"not-a-number\","
                                + "\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\""
                                + HASH_HEX + "\"}"));

        // Wrong type: manifestHash as number
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":\"" + aggId + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":12345}"));

        // Extra key
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":\"" + aggId + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX
                                + "\",\"extraKey\":\"value\"}"));

        // Nested object
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":\"" + aggId + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX
                                + "\",\"nested\":{\"inner\":\"value\"}}"));

        // Mismatched aggregateId vs column
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                            + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                            + "manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,"
                            + "max_attempts,last_failure_code,created_at) VALUES "
                            + "('" + UUID.randomUUID() + "','" + uniqueKey() + "','OPERATIONAL','closeout.received.v1',"
                            + "'CLOSEOUT_RUN','" + UUID.randomUUID() + "',1,'pink.event.v1','DATABASE_TEST',0,"
                            + "decode('" + HASH_HEX + "','hex'),'{\"aggregateId\":\"" + aggId
                            + "\",\"aggregateRevision\":1,\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\","
                            + "\"manifestHash\":\"" + HASH_HEX + "\"}'::jsonb,NULL,'READY',clock_timestamp(),0,8,NULL,"
                            + "clock_timestamp())");
        });
    }

    // ============================================================
    // B07: JsonBodyLeakageNegativeIT
    // ============================================================

    @Test
    @Order(26)
    @DisplayName("JsonBodyLeakageNegativeIT: random canary injection into all three JSON stores fails")
    void jsonBodyLeakageNegativeIT() throws SQLException {
        String canary = "CANARY_" + UUID.randomUUID().toString().replace("-", "");

        // Outbox manifest: forbidden key
        assertStatementSqlState(
                "23514",
                operationalInsertSqlWithManifest(
                        1L,
                        uniqueKey(),
                        "{\"aggregateId\":\"" + UUID.randomUUID()
                                + "\",\"aggregateRevision\":1,\"policyRevision\":0,"
                                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX
                                + "\",\"body_text\":\"" + canary + "\"}"));

        // ChangeEvent detail_manifest: forbidden key
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),'{\"prompt\":\"" + canary + "\"}'::jsonb)");

        // ChangeEvent: long string (>256 chars)
        String longString = "x".repeat(257);
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),'{\"note\":\"" + longString + "\"}'::jsonb)");

        // ChangeEvent: nested object
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),'{\"outer\":{\"secret\":\"x\"}}'::jsonb)");

        // IdempotencyReceipt: forbidden key
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"token\":\"" + canary + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // IdempotencyReceipt: long string
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"data\":\"" + longString + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // IdempotencyReceipt: nested object
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"wrapper\":{\"sql\":\"DROP TABLE\"}}'::jsonb,clock_timestamp(),clock_timestamp())");

        // Verify canary not in any JSON store
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.outbox_event WHERE payload_manifest::text LIKE '%%" + canary
                        + "%%'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM memory.change_event WHERE "
                        + "coalesce(detail_manifest::text,'') LIKE '%%" + canary + "%%'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.idempotency_receipt WHERE "
                        + "coalesce(response_manifest::text,'') LIKE '%%" + canary + "%%'"));
    }

    // ============================================================
    // B08: DatabaseFunctionPrivilegeIT
    // ============================================================

    @Test
    @Order(27)
    @DisplayName("DatabaseFunctionPrivilegeIT: PUBLIC function execute denied; unauthorized role access fails")
    void databaseFunctionPrivilegeIT() throws SQLException {
        // PUBLIC has no EXECUTE on memory functions
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM information_schema.role_routine_grants "
                        + "WHERE routine_schema IN ('memory','runtime') AND grantee='PUBLIC'"));

        // Create test role with no grants
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE hdm005_test_role NOLOGIN");
        }
        try {
            // Test role cannot call functions
            try (Connection connection = connection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE hdm005_test_role");
                SQLException denied = assertThrows(
                        SQLException.class,
                        () -> statement.execute("SELECT runtime.valid_outbox_manifest("
                                + "'{\"aggregateId\":\"" + UUID.randomUUID() + "\",\"aggregateRevision\":1,"
                                + "\"policyRevision\":0,\"purpose\":\"TEST\",\"manifestHash\":\"" + HASH_HEX
                                + "\"}'::jsonb,"
                                + "'" + UUID.randomUUID() + "',1::bigint,0::bigint,'TEST',decode('" + HASH_HEX
                                + "','hex'))"));
                assertEquals("42501", sqlState(denied));
            }

            // hide_nest_api cannot call functions (only migrator)
            try (Connection connection = connection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE hide_nest_api");
                SQLException denied = assertThrows(
                        SQLException.class,
                        () -> statement.execute("SELECT memory.require_governed_outbox(" + "'MEMORY','"
                                + UUID.randomUUID() + "',1,'memory.canonical-committed.v1',NULL)"));
                assertEquals("42501", sqlState(denied));
            }
        } finally {
            try (Connection connection = connection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP ROLE hdm005_test_role");
            }
        }
    }

    // ============================================================
    // B09: JooqJudgeMutationIT
    // ============================================================

    @Test
    @Order(28)
    @DisplayName("JooqJudgeMutationIT: GenerateCheck-equivalent hash judge detects all three mutation classes")
    void jooqJudgeMutationIT() throws Exception {
        Path generatedDir = moduleRoot().resolve("src/generated/java");

        // Build baseline tree manifest (SHA-256 per file, sorted by path)
        Map<String, String> baselineManifest = treeManifest(generatedDir);
        assertFalse(baselineManifest.isEmpty(), "generated tree must be non-empty");
        int baselineCount = baselineManifest.size();

        Path firstFile = generatedDir.resolve(
                baselineManifest.keySet().stream().sorted().findFirst().orElseThrow());
        byte[] savedContent = Files.readAllBytes(firstFile);
        String originalContent = Files.readString(firstFile, StandardCharsets.UTF_8);

        // --- Mutation 1: delete a tracked file ---
        Files.delete(firstFile);
        try {
            Map<String, String> deletedManifest = treeManifest(generatedDir);
            assertEquals(baselineCount - 1, deletedManifest.size(), "delete mutation: file count must decrease by 1");
            assertFalse(
                    baselineManifest.equals(deletedManifest),
                    "delete mutation: manifest must differ from baseline (non-zero judge)");
        } finally {
            Files.write(firstFile, savedContent);
        }

        // --- Mutation 2: modify a generated file ---
        Files.writeString(firstFile, originalContent + "\n// mutation injection\n", StandardCharsets.UTF_8);
        try {
            Map<String, String> modifiedManifest = treeManifest(generatedDir);
            assertEquals(baselineCount, modifiedManifest.size(), "modify mutation: file count unchanged");
            String originalHash = baselineManifest.get(
                    generatedDir.relativize(firstFile).toString().replace("\\", "/"));
            String modifiedHash = modifiedManifest.get(
                    generatedDir.relativize(firstFile).toString().replace("\\", "/"));
            assertFalse(
                    originalHash.equals(modifiedHash),
                    "modify mutation: file hash must differ from baseline (non-zero judge)");
        } finally {
            Files.writeString(firstFile, originalContent, StandardCharsets.UTF_8);
        }

        // --- Mutation 3: extra file outside allowed tree ---
        Path extraFile = generatedDir.resolve("ExtraMutation.java");
        Files.writeString(extraFile, "package io.github.candyxi0.hidenest.database.generated;\nclass ExtraMutation {}");
        try {
            Map<String, String> extraManifest = treeManifest(generatedDir);
            assertEquals(baselineCount + 1, extraManifest.size(), "extra-file mutation: file count must increase by 1");
            assertFalse(
                    baselineManifest.equals(extraManifest),
                    "extra-file mutation: manifest must differ from baseline (non-zero judge)");
        } finally {
            Files.delete(extraFile);
        }

        // --- Verify baseline fully restored ---
        Map<String, String> restoredManifest = treeManifest(generatedDir);
        assertEquals(
                baselineManifest, restoredManifest, "after all mutations: tree manifest must equal baseline exactly");
        assertEquals(
                originalContent,
                Files.readString(firstFile, StandardCharsets.UTF_8),
                "after all mutations: file content must match original");
    }

    private static Map<String, String> treeManifest(Path root) throws Exception {
        Map<String, String> manifest = new java.util.LinkedHashMap<>();
        if (!Files.exists(root)) {
            return manifest;
        }
        List<Path> files;
        try (Stream<Path> stream = Files.walk(root)) {
            files = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        for (Path file : files) {
            String relativePath = root.relativize(file).toString().replace("\\", "/");
            String hash = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            manifest.put(relativePath, hash);
        }
        return manifest;
    }

    // ============================================================
    // R2-02: Stale revision/policy/non-member/wrong-ReviewSession attacks
    // ============================================================

    @Test
    @Order(30)
    @DisplayName("R2StaleRevisionPolicyIT: stale expected revision, stale policy, non-member, wrong session all fail")
    void r2StaleRevisionPolicyIT() throws SQLException {
        MemoryFixture memory = insertMemory("r2-stale");

        // --- Stale revision attack ---
        // Prepare a Decision for revision 3 based on current state (revision 1)
        DecisionFixture staleDecision = prepareCanonicalDecision(memory, 3L);
        // Publish revision 2 first, making the first Decision's expected values stale
        DecisionFixture interceptor = prepareCanonicalDecision(memory, 2L);
        assertTrue(publishCanonicalRevision(memory, interceptor, "interceptor-body"));
        // Now staleDecision has stale expected_memory_revision_id (points to old revision 1,
        // but the chain check requires revision_no = NEW.revision_no - 1 = 3-1 = 2, not 1).
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "stale-revision-body");
                ps.setObject(4, staleDecision.decisionId());
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection,
                    "MEMORY",
                    memory.memoryId(),
                    3L,
                    "memory.canonical-committed.v1",
                    staleDecision.decisionId());
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET current_revision_id='%s', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s' AND current_revision_id='%s'")
                            .formatted(revisionId, memory.memoryId(), interceptor.newRevisionId()));
        });

        // --- Stale policy attack ---
        MemoryFixture mem2 = insertMemory("r2-stale-policy");
        // Update policy to revision 2
        UUID polDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',2,'proof','sp-dec',clock_timestamp())")
                            .formatted(polDecisionId, mem2.actorId(), mem2.policyId()));
            execute(
                    connection,
                    ("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,"
                                    + "maintenance_allowed,export_allowed,external_provider_allowed,isolated,"
                                    + "created_by_decision_id,created_at) VALUES "
                                    + "('%s',2,true,true,false,false,false,'%s',clock_timestamp())")
                            .formatted(mem2.policyId(), polDecisionId));
            insertGovernedOutbox(
                    connection, "ACCESS_POLICY", mem2.policyId(), 2L, "memory.policy-changed.v1", polDecisionId);
            execute(
                    connection,
                    ("UPDATE memory.access_policy SET current_revision_no=2 WHERE policy_id='%s' "
                                    + "AND current_revision_no=1")
                            .formatted(mem2.policyId()));
            connection.commit();
        }
        // Prepare Decision with stale expected_policy_revision_no=1 (actual is now 2)
        long stalePolicyRev = 1L;
        UUID stalePolDecisionId = UUID.randomUUID();
        UUID stalePropId = UUID.randomUUID();
        UUID stalePropRevId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(stalePropId, mem2.memoryId()));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,"
                                    + "action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',%d,clock_timestamp())")
                            .formatted(stalePropRevId, stalePropId, mem2.revisionId(), stalePolicyRev));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,target_kind,target_id,target_revision_ref,authorization_ref,"
                                    + "idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','%s','MEMORY','%s',2,'proof','sp-cd-%s',clock_timestamp())")
                            .formatted(
                                    stalePolDecisionId,
                                    mem2.actorId(),
                                    stalePropRevId,
                                    mem2.memoryId(),
                                    stalePolDecisionId));
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,2,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, mem2.memoryId());
                ps.setString(3, "stale-policy-body");
                ps.setObject(4, stalePolDecisionId);
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection, "MEMORY", mem2.memoryId(), 2L, "memory.canonical-committed.v1", stalePolDecisionId);
        });

        // --- Non-member ProposalRevision attack ---
        // Create a Decision with proposal_revision_id NOT in any ReviewSession
        UUID nonMemberPropId = UUID.randomUUID();
        UUID nonMemberPropRevId = UUID.randomUUID();
        UUID nonMemberDecId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(nonMemberPropId, memory.memoryId()));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,"
                                    + "action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',%d,clock_timestamp())")
                            .formatted(
                                    nonMemberPropRevId,
                                    nonMemberPropId,
                                    interceptor.newRevisionId(),
                                    scalarLong("SELECT current_policy_revision_no FROM memory.memory_record "
                                            + "WHERE memory_id='%s'".formatted(memory.memoryId()))));
            // Create a ReviewSession but DON'T add the ProposalRevision as a member
            UUID reviewId = UUID.randomUUID();
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,"
                                    + "request_hash,opened_at) VALUES ('%s','OPEN','nm-review',"
                                    + "decode(repeat('33',32),'hex'),clock_timestamp())")
                            .formatted(reviewId));
            // Decision references the ReviewSession but ProposalRevision is not a member
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',3,'proof','nm-cd-%s',clock_timestamp())")
                            .formatted(
                                    nonMemberDecId,
                                    memory.actorId(),
                                    nonMemberPropRevId,
                                    reviewId,
                                    memory.memoryId(),
                                    nonMemberDecId));
            insertGovernedOutbox(
                    connection, "REVIEW_SESSION", reviewId, 3L, "review.decisions-committed.v1", nonMemberDecId);
            connection.commit();
        }
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,3,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "non-member-body");
                ps.setObject(4, nonMemberDecId);
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 3L, "memory.canonical-committed.v1", nonMemberDecId);
        });
    }

    // ============================================================
    // R2-03: State/policy change exact Decision kind binding attacks
    // ============================================================

    @Test
    @Order(31)
    @DisplayName("R2StateChangeExactDecisionIT: wrong Decision kind for state change fails")
    void r2StateChangeExactDecisionIT() throws SQLException {
        MemoryFixture memory = insertMemory("r2-state");

        // Wrong Decision kind: use USER_CONFIRM instead of USER_ARCHIVE for ACTIVE→ARCHIVED
        UUID wrongKindDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',1,'proof','r2-wk-dec',clock_timestamp())")
                            .formatted(wrongKindDecisionId, memory.actorId(), memory.memoryId()));
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 1L, "memory.state-changed.v1", wrongKindDecisionId);
            connection.commit();
        }
        // Try to change state with wrong Decision kind (USER_CONFIRM, should be USER_ARCHIVE)
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(memory.memoryId()));
        });

        // Verify state unchanged
        assertEquals(
                "ACTIVE",
                scalarString(
                        "SELECT state FROM memory.memory_record WHERE memory_id='%s'".formatted(memory.memoryId())));

        // Correct state change: USER_ARCHIVE for ACTIVE→ARCHIVED
        UUID archiveDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_ARCHIVE','%s','USER','MEMORY','%s',1,'proof','r2-ar-dec',clock_timestamp())")
                            .formatted(archiveDecisionId, memory.actorId(), memory.memoryId()));
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 1L, "memory.state-changed.v1", archiveDecisionId);
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(memory.memoryId()));
            connection.commit();
        }
        assertEquals(
                "ARCHIVED",
                scalarString(
                        "SELECT state FROM memory.memory_record WHERE memory_id='%s'".formatted(memory.memoryId())));
    }

    // ============================================================
    // R2-04: Duplicate governed business fact attacks
    // ============================================================

    @Test
    @Order(32)
    @DisplayName("R2DuplicateGovernedFactIT: same business fact different key, same ChangeEvent multi-outbox fail")
    void r2DuplicateGovernedFactIT() throws SQLException {
        MemoryFixture memory = insertMemory("r2-dup");

        // Same business fact (Decision+event_type+target) → duplicate ChangeEvent fails
        UUID decisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s',1,'proof','r2-dup-dec',clock_timestamp())")
                            .formatted(decisionId, memory.actorId(), memory.memoryId()));
            connection.commit();
        }

        // First ChangeEvent succeeds
        UUID changeId1 = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            insertChangeEvent(
                    connection,
                    changeId1,
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    1L,
                    decisionId);
            insertOutbox(
                    connection,
                    "GOVERNED",
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    memory.memoryId(),
                    1L,
                    changeId1,
                    uniqueKey());
            connection.commit();
        }

        // Second ChangeEvent with same business fact (same event_type, target, decision_id) → fails
        assertStatementSqlState(
                "23505",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at) VALUES "
                        + "('" + UUID.randomUUID() + "','memory.canonical-committed.v1','MEMORY','"
                        + memory.memoryId() + "',1,'" + decisionId + "',clock_timestamp())");

        // Same ChangeEvent → multi-outbox fails (one-outbox-per-ChangeEvent unique)
        assertStatementSqlState(
                "23505",
                "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                        + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,"
                        + "policy_revision,manifest_hash,payload_manifest,change_event_id,state,"
                        + "available_at,created_at) VALUES "
                        + "('" + UUID.randomUUID() + "','" + uniqueKey() + "','GOVERNED',"
                        + "'memory.canonical-committed.v1','MEMORY','" + memory.memoryId()
                        + "',1,'pink.event.v1','DATABASE_TEST',0,decode('" + HASH_HEX + "','hex'),'"
                        + manifestPayload(memory.memoryId(), 1L) + "'::jsonb,'" + changeId1
                        + "','READY',clock_timestamp(),clock_timestamp())");
    }

    // ============================================================
    // R2-05: Final verdict NULL pair attack
    // ============================================================

    @Test
    @Order(33)
    @DisplayName("R2FinalVerdictNullPairIT: USER_CONFIRM with review_session_id but null proposal_revision_id fails")
    void r2FinalVerdictNullPairIT() throws SQLException {
        ReviewFixture review = insertReviewFixture();
        UUID actorId = review.actorId();

        // USER_CONFIRM with review_session_id but NULL proposal_revision_id → fails R2-05 constraint
        assertStatementSqlState(
                "23514",
                ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                + "authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','USER_CONFIRM','%s','USER',NULL,'%s','PROPOSAL_REVISION','%s',1,"
                                + "'proof','r2-null-pair',clock_timestamp())")
                        .formatted(UUID.randomUUID(), actorId, review.reviewSessionId(), UUID.randomUUID()));
    }

    // ============================================================
    // R2-06/R2-08: JSON allowlist and null semantics attacks
    // ============================================================

    @Test
    @Order(34)
    @DisplayName("R2JsonAllowlistIT: arbitrary keys in ChangeEvent/Receipt rejected; governed null revision passes")
    void r2JsonAllowlistIT() throws SQLException {
        String canary =
                "R2CANARY" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // ChangeEvent: arbitrary short key-value (not in allowlist) → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                        + "'{\"note\":\"" + canary + "\"}'::jsonb)");

        // ChangeEvent: valid allowlist keys pass (manifestHash + memoryRevisionId)
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                            + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                            + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                            + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                            + "'{\"manifestHash\":\"" + HASH_HEX + "\"}'::jsonb)");
            execute(
                    connection,
                    "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                            + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                            + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                            + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                            + "'{\"manifestHash\":\"" + HASH_HEX
                            + "\",\"memoryRevisionId\":\"" + UUID.randomUUID() + "\"}'::jsonb)");
            connection.commit();
        }

        // IdempotencyReceipt: arbitrary key (not in ProblemDetail allowlist) → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"randomKey\":\"" + canary + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // IdempotencyReceipt: ProblemDetail keys pass
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                            + "response_manifest,created_at,committed_at) VALUES "
                            + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                            + "'{\"type\":\"urn:test\",\"title\":\"OK\",\"status\":200}'::jsonb,"
                            + "clock_timestamp(),clock_timestamp())");
            connection.commit();
        }

        // R2-08: Governed outbox manifest with null aggregateRevision (JSON null)
        UUID governedAggId = UUID.randomUUID();
        UUID nullRevActorId = UUID.randomUUID();
        String nullRevManifest = "{\"aggregateId\":\"" + governedAggId
                + "\",\"aggregateRevision\":null,\"policyRevision\":0,"
                + "\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX + "\"}";
        // Create a valid Decision for the governed outbox
        UUID govDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                                    + "('%s','SYNTHETIC','null-rev-actor',clock_timestamp())")
                            .formatted(nullRevActorId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','MEMORY','%s','proof','null-rev-dec-%s',clock_timestamp())")
                            .formatted(govDecisionId, nullRevActorId, governedAggId, govDecisionId));
            UUID changeId = UUID.randomUUID();
            insertChangeEvent(
                    connection,
                    changeId,
                    "memory.canonical-committed.v1",
                    "MEMORY",
                    governedAggId,
                    null,
                    govDecisionId);
            execute(
                    connection,
                    ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                                    + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,"
                                    + "policy_revision,manifest_hash,payload_manifest,change_event_id,state,"
                                    + "available_at,created_at) VALUES "
                                    + "('%s','%s','GOVERNED','memory.canonical-committed.v1','MEMORY','%s',NULL,"
                                    + "'pink.event.v1','DATABASE_TEST',0,decode('%s','hex'),'" + nullRevManifest
                                    + "'::jsonb,'%s','READY',clock_timestamp(),clock_timestamp())")
                            .formatted(UUID.randomUUID(), uniqueKey(), governedAggId, HASH_HEX, changeId));
            connection.commit();
        }
    }

    // ============================================================
    // R4-01: Final verdict three NULL combination attacks
    // ============================================================

    @Test
    @Order(35)
    @DisplayName("R4FinalVerdictNullCombinationsIT: double-null, session-only, proposal-only all fail 23514")
    void r4FinalVerdictNullCombinationsIT() throws SQLException {
        UUID actorId = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    ("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                                    + "('%s','SYNTHETIC','r4-null-actor',clock_timestamp())")
                            .formatted(actorId));
        }

        // Attack 1: Double NULL (both review_session_id and proposal_revision_id NULL)
        assertStatementSqlState(
                "23514",
                ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,"
                                + "actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                + "authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','USER_CONFIRM','%s','USER',NULL,NULL,'MEMORY','%s',1,'proof','r4-dn-%s',clock_timestamp())")
                        .formatted(UUID.randomUUID(), actorId, UUID.randomUUID(), UUID.randomUUID()));

        // Attack 2: Session only (review_session_id NOT NULL, proposal_revision_id NULL)
        UUID reviewId = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,"
                                    + "request_hash,opened_at) VALUES ('%s','OPEN','r4-so',decode(repeat('44',32),'hex'),"
                                    + "clock_timestamp())")
                            .formatted(reviewId));
        }
        assertStatementSqlState(
                "23514",
                ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,"
                                + "actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                + "authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','USER_REJECT','%s','USER',NULL,'%s','PROPOSAL_REVISION','%s',1,'proof','r4-so-%s',clock_timestamp())")
                        .formatted(UUID.randomUUID(), actorId, reviewId, UUID.randomUUID(), UUID.randomUUID()));

        // Attack 3: Proposal only (proposal_revision_id NOT NULL, review_session_id NULL)
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevId = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,created_at) VALUES "
                                    + "('%s','CREATE',clock_timestamp())")
                            .formatted(proposalId));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,"
                                    + "revision_no,action_code,created_at) VALUES ('%s','%s',1,'CREATE',clock_timestamp())")
                            .formatted(proposalRevId, proposalId));
        }
        assertStatementSqlState(
                "23514",
                ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,"
                                + "actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                + "authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','USER_DEFER','%s','USER','%s',NULL,'PROPOSAL_REVISION','%s',1,'proof','r4-po-%s',clock_timestamp())")
                        .formatted(UUID.randomUUID(), actorId, proposalRevId, UUID.randomUUID(), UUID.randomUUID()));
    }

    // ============================================================
    // R4-02: Policy split-pointer attack
    // ============================================================

    @Test
    @Order(36)
    @DisplayName("R4PolicySplitPointerIT: access_policy rev=2, memory_record rev=1, expected=2 fails")
    void r4PolicySplitPointerIT() throws SQLException {
        MemoryFixture memory = insertMemory("r4-split-policy");

        // Update access_policy.current_revision_no to 2 but leave memory_record at 1
        UUID polDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',2,'proof','r4-sp-pol-dec',clock_timestamp())")
                            .formatted(polDecisionId, memory.actorId(), memory.policyId()));
            execute(
                    connection,
                    ("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,"
                                    + "maintenance_allowed,export_allowed,external_provider_allowed,isolated,"
                                    + "created_by_decision_id,created_at) VALUES "
                                    + "('%s',2,true,true,false,false,false,'%s',clock_timestamp())")
                            .formatted(memory.policyId(), polDecisionId));
            insertGovernedOutbox(
                    connection, "ACCESS_POLICY", memory.policyId(), 2L, "memory.policy-changed.v1", polDecisionId);
            execute(
                    connection,
                    ("UPDATE memory.access_policy SET current_revision_no=2 WHERE policy_id='%s' "
                                    + "AND current_revision_no=1")
                            .formatted(memory.policyId()));
            connection.commit();
        }
        // Now: access_policy.current_revision_no=2, memory_record.current_policy_revision_no=1

        // Prepare canonical Decision with expected_policy_revision_no=2 (matches access_policy but NOT memory_record)
        long currentPolicyRev =
                scalarLong("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id='%s'"
                        .formatted(memory.memoryId()));
        assertEquals(1L, currentPolicyRev, "memory_record policy rev should still be 1");

        UUID proposalId = UUID.randomUUID();
        UUID proposalRevId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(proposalId, memory.memoryId()));
            // expected_policy_revision_no=2 — but memory_record still at 1
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,"
                                    + "action_code,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',2,clock_timestamp())")
                            .formatted(proposalRevId, proposalId, memory.revisionId()));
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,"
                                    + "request_hash,opened_at) VALUES ('%s','OPEN','r4-sp-review',decode(repeat('ab',32),'hex'),"
                                    + "clock_timestamp())")
                            .formatted(reviewId));
            execute(
                    connection,
                    ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                    + "VALUES ('%s','%s',1)")
                            .formatted(reviewId, proposalRevId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',2,'proof','r4-sp-cd',clock_timestamp())")
                            .formatted(decisionId, memory.actorId(), proposalRevId, reviewId, memory.memoryId()));
            insertFinalVerdictGovernedOutbox(connection, reviewId, 2L, decisionId);
            connection.commit();
        }

        // Try canonical publish with expected_policy_revision_no=2 while memory_record is at 1 → must fail
        assertCommitSqlState("23514", connection -> {
            UUID revisionId = UUID.randomUUID();
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                            + "memory_type,body_text,created_by_decision_id,created_at) VALUES (?,?,2,'Claim',?,?,clock_timestamp())")) {
                ps.setObject(1, revisionId);
                ps.setObject(2, memory.memoryId());
                ps.setString(3, "split-policy-body");
                ps.setObject(4, decisionId);
                ps.executeUpdate();
            }
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 2L, "memory.canonical-committed.v1", decisionId);
        });
    }

    // ============================================================
    // R4-03: State old Decision revision attack
    // ============================================================

    @Test
    @Order(37)
    @DisplayName("R4StateOldDecisionRevisionIT: Decision targets old revision but ChangeEvent claims current → fail")
    void r4StateOldDecisionRevisionIT() throws SQLException {
        MemoryFixture memory = insertMemory("r4-old-rev-state");

        // Create a USER_ARCHIVE Decision targeting revision 1 (current)
        UUID archiveDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_ARCHIVE','%s','USER','MEMORY','%s',1,'proof','r4-old-ar',clock_timestamp())")
                            .formatted(archiveDecisionId, memory.actorId(), memory.memoryId()));
            // Create governed outbox with ChangeEvent that also targets revision 1
            insertGovernedOutbox(
                    connection, "MEMORY", memory.memoryId(), 1L, "memory.state-changed.v1", archiveDecisionId);
            connection.commit();
        }

        // Publish revision 2 (canonical)
        DecisionFixture dec2 = prepareCanonicalDecision(memory, 2L);
        assertTrue(publishCanonicalRevision(memory, dec2, "rev-2-body"));

        // Now try state change: the governed outbox has ChangeEvent targeting revision 1,
        // but current_revision is now 2. The state change trigger looks for
        // target_revision_ref = current_rev_no = 2 → won't find it → fails
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=clock_timestamp() "
                                    + "WHERE memory_id='%s'")
                            .formatted(memory.memoryId()));
        });

        // Verify state unchanged
        assertEquals(
                "ACTIVE",
                scalarString(
                        "SELECT state FROM memory.memory_record WHERE memory_id='%s'".formatted(memory.memoryId())));
    }

    // ============================================================
    // R4-04: Review bidirectional set — missing and extra
    // ============================================================

    @Test
    @Order(38)
    @DisplayName(
            "R4ReviewBidirectionalSetIT: missing verdict event fails, extra non-member event fails, full set passes")
    void r4ReviewBidirectionalSetIT() throws SQLException {
        // --- Missing verdict event: insert two verdicts separately, second without governed outbox ---
        ReviewFixture revMissing = insertReviewFixtureWithMembers(2);
        // Insert first verdict with governed outbox (committed)
        UUID dId1 = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,"
                                    + "'proof','r4-missing-0-%s',clock_timestamp())")
                            .formatted(
                                    dId1,
                                    revMissing.actorId(),
                                    revMissing.proposalRevisionIds().get(0),
                                    revMissing.reviewSessionId(),
                                    revMissing.proposalRevisionIds().get(0),
                                    dId1));
            insertFinalVerdictGovernedOutbox(connection, revMissing.reviewSessionId(), 1L, dId1);
            connection.commit();
        }
        // Insert second verdict WITHOUT governed outbox → deferred trigger fails at COMMIT
        assertCommitSqlState("23514", connection -> {
            UUID dId2 = UUID.randomUUID();
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',2,"
                                    + "'proof','r4-missing-1-%s',clock_timestamp())")
                            .formatted(
                                    dId2,
                                    revMissing.actorId(),
                                    revMissing.proposalRevisionIds().get(1),
                                    revMissing.reviewSessionId(),
                                    revMissing.proposalRevisionIds().get(1),
                                    dId2));
        });

        // --- Full set passes ---
        ReviewFixture revFull = insertReviewFixtureWithMembers(2);
        assertTrue(completeReview(revFull));
        assertEquals(
                "COMPLETED",
                scalarString("SELECT state FROM memory.review_session WHERE review_session_id='%s'"
                        .formatted(revFull.reviewSessionId())));

        // --- Extra non-member Decision event in governed outbox ---
        ReviewFixture revExtra = insertReviewFixtureWithMembers(1);
        UUID extraReviewId = revExtra.reviewSessionId();
        // First, insert the member's own verdict with governed outbox
        UUID memberDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,"
                                    + "'proof','r4-member-dec',clock_timestamp())")
                            .formatted(
                                    memberDecisionId,
                                    revExtra.actorId(),
                                    revExtra.proposalRevisionIds().get(0),
                                    extraReviewId,
                                    revExtra.proposalRevisionIds().get(0)));
            insertFinalVerdictGovernedOutbox(connection, extraReviewId, 1L, memberDecisionId);
            connection.commit();
        }
        // Now insert governed outbox for an extra Decision NOT from any review member
        UUID extraDecisionId = UUID.randomUUID();
        UUID extraPropId = UUID.randomUUID();
        UUID extraPropRevId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,created_at) VALUES "
                                    + "('%s','CREATE',clock_timestamp())")
                            .formatted(extraPropId));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,"
                                    + "revision_no,action_code,created_at) VALUES ('%s','%s',1,'CREATE',clock_timestamp())")
                            .formatted(extraPropRevId, extraPropId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                    + "authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',9,"
                                    + "'proof','r4-extra-dec',clock_timestamp())")
                            .formatted(
                                    extraDecisionId,
                                    revExtra.actorId(),
                                    extraPropRevId,
                                    extraReviewId,
                                    extraPropRevId));
            insertFinalVerdictGovernedOutbox(connection, extraReviewId, 9L, extraDecisionId);
            connection.commit();
        }
        // The governed outbox references extraDecisionId which is NOT a member verdict
        assertCommitSqlState("23514", connection -> {
            execute(
                    connection,
                    ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                    + "WHERE review_session_id='%s' AND state='OPEN'")
                            .formatted(extraReviewId));
        });
    }

    // ============================================================
    // R4-05: JSON allowlist illegal value format attacks
    // ============================================================

    @Test
    @Order(39)
    @DisplayName("R4JsonAllowlistIllegalValuesIT: canary in allowed keys with wrong format → all rejected")
    void r4JsonAllowlistIllegalValuesIT() throws SQLException {
        String canary =
                "R4CANARY" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // ChangeEvent manifestHash: not 64-char hex → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                        + "'{\"manifestHash\":\"" + canary + "\"}'::jsonb)");

        // ChangeEvent manifestHash: wrong length hex → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                        + "'{\"manifestHash\":\"abc123\"}'::jsonb)");

        // ChangeEvent memoryRevisionId: not UUID → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at,detail_manifest) VALUES "
                        + "('" + UUID.randomUUID() + "','closeout.received.v1','CLOSEOUT_RUN','"
                        + UUID.randomUUID() + "',1,NULL,clock_timestamp(),"
                        + "'{\"memoryRevisionId\":\"" + canary + "\"}'::jsonb)");

        // Receipt type: not URI → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"type\":\"" + canary + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // Receipt requestId: not UUID → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"requestId\":\"" + canary + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // Receipt resultCategory: not in enum → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"resultCategory\":\"" + canary + "\"}'::jsonb,clock_timestamp(),clock_timestamp())");

        // Receipt status: not integer range → rejected
        assertStatementSqlState(
                "23514",
                "INSERT INTO runtime.idempotency_receipt(idempotency_key,operation_code,request_hash,state,"
                        + "response_manifest,created_at,committed_at) VALUES "
                        + "('receipt-" + UUID.randomUUID() + "','TEST',decode(repeat('11',32),'hex'),'COMMITTED',"
                        + "'{\"status\":9999}'::jsonb,clock_timestamp(),clock_timestamp())");

        // Verify canary not in any JSON store
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.outbox_event WHERE payload_manifest::text LIKE '%%" + canary
                        + "%%'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM memory.change_event WHERE "
                        + "coalesce(detail_manifest::text,'') LIKE '%%" + canary + "%%'"));
        assertEquals(
                0,
                scalarLong("SELECT count(*) FROM runtime.idempotency_receipt WHERE "
                        + "coalesce(response_manifest::text,'') LIKE '%%" + canary + "%%'"));
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static boolean scalarBoolean(String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getBoolean(1);
        }
    }

    private static Set<String> querySet(String sql) throws SQLException {
        Set<String> values = new HashSet<>();
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                values.add(result.getString(1));
            }
        }
        return values;
    }

    private static String catalogFingerprint() throws SQLException {
        return scalarString("SELECT md5(string_agg(value, E'\\n' ORDER BY value)) FROM ("
                + "SELECT table_schema||'.'||table_name||'.'||column_name||':'||data_type||':'||is_nullable AS value "
                + "FROM information_schema.columns WHERE table_schema IN ('evidence','memory','runtime','security') "
                + "UNION ALL SELECT constraint_schema||'.'||table_name||'.'||constraint_name||':'||constraint_type "
                + "FROM information_schema.table_constraints WHERE constraint_schema IN ('evidence','memory','runtime','security')"
                + ") catalog");
    }

    private static String uniqueKey() {
        return "key-" + UUID.randomUUID();
    }

    private static String manifestPayload(UUID aggregateId, Long revision) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":"
                + (revision == null ? "null" : revision)
                + ",\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX + "\"}";
    }

    private static UUID insertPolicy(UUID ownerId) throws SQLException {
        UUID policyId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection, "SET CONSTRAINTS ALL DEFERRED");
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','pol-actor-%s',clock_timestamp())".formatted(actorId, actorId));
            execute(
                    connection,
                    "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                            + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                            + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'proof','pol-dec-%s',clock_timestamp())"
                                    .formatted(decisionId, actorId, policyId, decisionId));
            execute(
                    connection,
                    "INSERT INTO memory.access_policy(policy_id,owner_kind,owner_id,current_revision_no,created_at) "
                            + "VALUES ('%s','MEMORY','%s',1,clock_timestamp())".formatted(policyId, ownerId));
            execute(
                    connection,
                    "INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,maintenance_allowed,"
                            + "export_allowed,external_provider_allowed,isolated,created_by_decision_id,created_at) VALUES "
                            + "('%s',1,true,true,false,false,false,'%s',clock_timestamp())"
                                    .formatted(policyId, decisionId));
            insertGovernedOutbox(connection, "ACCESS_POLICY", policyId, 1L, "memory.policy-changed.v1", decisionId);
            connection.commit();
        }
        return policyId;
    }

    private static MemoryFixture insertMemory(String body) throws SQLException {
        UUID actorId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID policyDecisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(connection, "SET CONSTRAINTS ALL DEFERRED");
            execute(
                    connection,
                    ("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                                    + "('%s','SYNTHETIC','actor-%s',clock_timestamp())")
                            .formatted(actorId, actorId));
            // R3: Policy Decision uses HIDE_SELECT (not USER_CONFIRM) — not a review verdict
            execute(
                    connection,
                    ("INSERT INTO memory.access_policy(policy_id,owner_kind,owner_id,current_revision_no,created_at) "
                                    + "VALUES ('%s','MEMORY','%s',1,clock_timestamp())")
                            .formatted(policyId, memoryId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                    + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'proof','pol-dec-%s',clock_timestamp())")
                            .formatted(policyDecisionId, actorId, policyId, policyDecisionId));
            execute(
                    connection,
                    ("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,maintenance_allowed,"
                                    + "export_allowed,external_provider_allowed,isolated,created_by_decision_id,created_at) VALUES "
                                    + "('%s',1,true,true,false,false,false,'%s',clock_timestamp())")
                            .formatted(policyId, policyDecisionId));
            insertGovernedOutbox(
                    connection, "ACCESS_POLICY", policyId, 1L, "memory.policy-changed.v1", policyDecisionId);
            execute(
                    connection,
                    ("INSERT INTO memory.memory_record(memory_id,state,current_revision_id,policy_id,current_policy_revision_no,"
                                    + "created_at,updated_at) VALUES ('%s','ACTIVE','%s','%s',1,clock_timestamp(),clock_timestamp())")
                            .formatted(memoryId, revisionId, policyId));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','CREATE','%s',clock_timestamp())")
                            .formatted(proposalId, memoryId));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,"
                                    + "memory_type,created_at) VALUES ('%s','%s',1,'CREATE','synthetic','Claim',clock_timestamp())")
                            .formatted(proposalRevisionId, proposalId));
            // R3-01: USER_CONFIRM requires ReviewSession + frozen member
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                                    + "VALUES ('%s','OPEN','review-%s',decode(repeat('ee',32),'hex'),clock_timestamp())")
                            .formatted(reviewId, reviewId));
            execute(
                    connection,
                    ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                    + "VALUES ('%s','%s',1)")
                            .formatted(reviewId, proposalRevisionId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                    + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',1,'synthetic-proof','mem-dec-%s',clock_timestamp())")
                            .formatted(decisionId, actorId, proposalRevisionId, reviewId, memoryId, decisionId));
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO memory.memory_revision("
                    + "memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) "
                    + "VALUES (?,?,1,'Claim',?,?,clock_timestamp())")) {
                statement.setObject(1, revisionId);
                statement.setObject(2, memoryId);
                statement.setString(3, body);
                statement.setObject(4, decisionId);
                statement.executeUpdate();
            }
            insertGovernedOutbox(connection, "MEMORY", memoryId, 1L, "memory.canonical-committed.v1", decisionId);
            // R3-04: Complete the ReviewSession (requires governed outbox for review completion)
            insertFinalVerdictGovernedOutbox(connection, reviewId, 1L, decisionId);
            connection.commit();
        }
        return new MemoryFixture(memoryId, revisionId, policyId, decisionId, actorId);
    }

    private static void insertFinalVerdictGovernedOutbox(
            Connection connection, UUID reviewSessionId, Long revision, UUID decisionId) throws SQLException {
        UUID changeId = UUID.randomUUID();
        insertChangeEvent(
                connection,
                changeId,
                "review.decisions-committed.v1",
                "REVIEW_SESSION",
                reviewSessionId,
                revision,
                decisionId);
        insertOutbox(
                connection,
                "GOVERNED",
                "review.decisions-committed.v1",
                "REVIEW_SESSION",
                reviewSessionId,
                revision,
                changeId,
                uniqueKey());
    }

    private static DecisionFixture prepareCanonicalDecision(MemoryFixture fixture, long targetRevision)
            throws SQLException {
        // R2-02: Read actual current state to set correct expected values
        UUID currentRevisionId = UUID.fromString(
                scalarString("SELECT current_revision_id::text FROM memory.memory_record WHERE memory_id='%s'"
                        .formatted(fixture.memoryId())));
        long currentPolicyRev =
                scalarLong("SELECT current_policy_revision_no FROM memory.memory_record WHERE memory_id='%s'"
                        .formatted(fixture.memoryId()));

        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID newRevisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(proposalId, fixture.memoryId()));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                                    + "expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',%d,clock_timestamp())")
                            .formatted(proposalRevisionId, proposalId, currentRevisionId, currentPolicyRev));
            // R3-01: USER_CONFIRM requires ReviewSession + frozen member
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                                    + "VALUES ('%s','OPEN','canonical-review-%s',decode(repeat('cc',32),'hex'),clock_timestamp())")
                            .formatted(reviewId, reviewId));
            execute(
                    connection,
                    ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                    + "VALUES ('%s','%s',1)")
                            .formatted(reviewId, proposalRevisionId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                    + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',%d,'synthetic-proof','canonical-dec-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    fixture.actorId(),
                                    proposalRevisionId,
                                    reviewId,
                                    fixture.memoryId(),
                                    targetRevision,
                                    decisionId));
            // R3: Review decision requires governed outbox
            insertFinalVerdictGovernedOutbox(connection, reviewId, targetRevision, decisionId);
            connection.commit();
        }
        return new DecisionFixture(decisionId, proposalRevisionId, newRevisionId, targetRevision);
    }

    private static DecisionFixture prepareDecisionWithTarget(
            MemoryFixture fixture, long targetRevision, String targetKind, UUID targetId) throws SQLException {
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(proposalId, fixture.memoryId()));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                                    + "expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',1,clock_timestamp())")
                            .formatted(proposalRevisionId, proposalId, fixture.revisionId()));
            // R3-01: USER_CONFIRM requires ReviewSession + member
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                                    + "VALUES ('%s','OPEN','wrong-target-review-%s',decode(repeat('bb',32),'hex'),clock_timestamp())")
                            .formatted(reviewId, reviewId));
            execute(
                    connection,
                    ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                    + "VALUES ('%s','%s',1)")
                            .formatted(reviewId, proposalRevisionId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                    + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_CONFIRM','%s','USER','%s','%s','%s','%s',%d,'proof','wrong-kind-dec-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    fixture.actorId(),
                                    proposalRevisionId,
                                    reviewId,
                                    targetKind,
                                    targetId,
                                    targetRevision,
                                    decisionId));
            insertFinalVerdictGovernedOutbox(connection, reviewId, targetRevision, decisionId);
            connection.commit();
        }
        return new DecisionFixture(decisionId, proposalRevisionId, UUID.randomUUID(), targetRevision);
    }

    private static DecisionFixture prepareNonConfirmDecision(MemoryFixture fixture, long targetRevision)
            throws SQLException {
        UUID proposalId = UUID.randomUUID();
        UUID proposalRevisionId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                                    + "VALUES ('%s','REVISE','%s',clock_timestamp())")
                            .formatted(proposalId, fixture.memoryId()));
            execute(
                    connection,
                    ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,"
                                    + "expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES "
                                    + "('%s','%s',1,'REVISE','%s',1,clock_timestamp())")
                            .formatted(proposalRevisionId, proposalId, fixture.revisionId()));
            // R3-01: USER_REJECT also requires ReviewSession + member
            execute(
                    connection,
                    ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                                    + "VALUES ('%s','OPEN','non-confirm-review-%s',decode(repeat('dd',32),'hex'),clock_timestamp())")
                            .formatted(reviewId, reviewId));
            execute(
                    connection,
                    ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                    + "VALUES ('%s','%s',1)")
                            .formatted(reviewId, proposalRevisionId));
            execute(
                    connection,
                    ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                    + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                    + "('%s','USER_REJECT','%s','USER','%s','%s','MEMORY','%s',%d,'proof','non-confirm-dec-%s',clock_timestamp())")
                            .formatted(
                                    decisionId,
                                    fixture.actorId(),
                                    proposalRevisionId,
                                    reviewId,
                                    fixture.memoryId(),
                                    targetRevision,
                                    decisionId));
            insertFinalVerdictGovernedOutbox(connection, reviewId, targetRevision, decisionId);
            connection.commit();
        }
        return new DecisionFixture(decisionId, proposalRevisionId, UUID.randomUUID(), targetRevision);
    }

    private static boolean publishCanonicalRevision(MemoryFixture fixture, DecisionFixture decision, String body)
            throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO memory.memory_revision("
                        + "memory_revision_id,memory_id,revision_no,memory_type,body_text,created_by_decision_id,created_at) "
                        + "VALUES (?,?,?,'Claim',?,?,clock_timestamp())")) {
                    statement.setObject(1, decision.newRevisionId());
                    statement.setObject(2, fixture.memoryId());
                    statement.setLong(3, decision.targetRevision());
                    statement.setString(4, body);
                    statement.setObject(5, decision.decisionId());
                    statement.executeUpdate();
                }
                insertGovernedOutbox(
                        connection,
                        "MEMORY",
                        fixture.memoryId(),
                        decision.targetRevision(),
                        "memory.canonical-committed.v1",
                        decision.decisionId());
                int updated;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE memory.memory_record "
                                + "SET current_revision_id=?,updated_at=clock_timestamp() WHERE memory_id=? AND current_revision_id=?")) {
                    statement.setObject(1, decision.newRevisionId());
                    statement.setObject(2, fixture.memoryId());
                    statement.setObject(3, fixture.revisionId());
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if (Set.of("23505", "40001", "40P01").contains(sqlState(exception))) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private static ReviewFixture insertReviewFixture() throws SQLException {
        UUID actor = UUID.randomUUID();
        UUID proposal = UUID.randomUUID();
        UUID proposalRevision = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','review-actor-%s',clock_timestamp())".formatted(actor, actor));
            execute(
                    connection,
                    "INSERT INTO memory.proposal(proposal_id,proposal_kind,created_at) "
                            + "VALUES ('%s','CREATE',clock_timestamp())".formatted(proposal));
            execute(
                    connection,
                    "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,created_at) "
                            + "VALUES ('%s','%s',1,'CREATE',clock_timestamp())".formatted(proposalRevision, proposal));
            execute(
                    connection,
                    "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                            + "VALUES ('%s','OPEN','review-%s',decode(repeat('11',32),'hex'),clock_timestamp())"
                                    .formatted(review, review));
            execute(
                    connection,
                    "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                            + "VALUES ('%s','%s',1)".formatted(review, proposalRevision));
        }
        return new ReviewFixture(review, List.of(proposalRevision), actor);
    }

    private static ReviewFixture insertReviewFixtureWithMembers(int count) throws SQLException {
        UUID actor = UUID.randomUUID();
        UUID review = UUID.randomUUID();
        List<UUID> proposalRevisionIds = new ArrayList<>();
        try (Connection connection = connection()) {
            execute(
                    connection,
                    "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                            + "('%s','SYNTHETIC','multi-actor-%s',clock_timestamp())".formatted(actor, actor));
            execute(
                    connection,
                    "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at) "
                            + "VALUES ('%s','OPEN','multi-review-%s',decode(repeat('22',32),'hex'),clock_timestamp())"
                                    .formatted(review, review));
            for (int i = 1; i <= count; i++) {
                UUID proposal = UUID.randomUUID();
                UUID proposalRevision = UUID.randomUUID();
                execute(
                        connection,
                        "INSERT INTO memory.proposal(proposal_id,proposal_kind,created_at) "
                                + "VALUES ('%s','CREATE',clock_timestamp())".formatted(proposal));
                execute(
                        connection,
                        "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,created_at) "
                                + "VALUES ('%s','%s',1,'CREATE',clock_timestamp())"
                                        .formatted(proposalRevision, proposal));
                execute(
                        connection,
                        "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                                + "VALUES ('%s','%s',%d)".formatted(review, proposalRevision, i));
                proposalRevisionIds.add(proposalRevision);
            }
        }
        return new ReviewFixture(review, proposalRevisionIds, actor);
    }

    private static boolean completeReview(ReviewFixture fixture) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                for (int i = 0; i < fixture.proposalRevisionIds().size(); i++) {
                    UUID decisionId = UUID.randomUUID();
                    execute(
                            connection,
                            ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                            + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                                            + "authorization_ref,idempotency_key,created_at) VALUES "
                                            + "('%s','USER_CONFIRM','%s','USER','%s','%s','PROPOSAL_REVISION','%s',%d,"
                                            + "'proof','complete-%d-%s',clock_timestamp())")
                                    .formatted(
                                            decisionId,
                                            fixture.actorId(),
                                            fixture.proposalRevisionIds().get(i),
                                            fixture.reviewSessionId(),
                                            fixture.proposalRevisionIds().get(i),
                                            i + 1,
                                            i,
                                            decisionId));
                    insertGovernedOutbox(
                            connection,
                            "REVIEW_SESSION",
                            fixture.reviewSessionId(),
                            (long) (i + 1),
                            "review.decisions-committed.v1",
                            decisionId);
                }
                execute(
                        connection,
                        ("UPDATE memory.review_session SET state='COMPLETED', terminal_at=clock_timestamp() "
                                        + "WHERE review_session_id='%s' AND state='OPEN'")
                                .formatted(fixture.reviewSessionId()));
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if (Set.of("23505", "40001", "40P01").contains(sqlState(exception))) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private static boolean insertFinalVerdict(ReviewFixture fixture, String kind) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                UUID decision = UUID.randomUUID();
                execute(
                        connection,
                        "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','%s','%s','USER','%s','%s','PROPOSAL_REVISION','%s',1,'synthetic-proof','verdict-%s',clock_timestamp())"
                                        .formatted(
                                                decision,
                                                kind,
                                                fixture.actorId(),
                                                fixture.proposalRevisionIds().get(0),
                                                fixture.reviewSessionId(),
                                                fixture.proposalRevisionIds().get(0),
                                                decision));
                insertGovernedOutbox(
                        connection,
                        "REVIEW_SESSION",
                        fixture.reviewSessionId(),
                        1L,
                        "review.decisions-committed.v1",
                        decision);
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if (Set.of("23505", "40001", "40P01").contains(sqlState(exception))) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private static boolean insertPolicyRevision(UUID policyId, boolean companionAllowed) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                UUID decisionId = UUID.randomUUID();
                UUID actorId = UUID.randomUUID();
                execute(
                        connection,
                        "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES "
                                + "('%s','SYNTHETIC','pol-rev-actor-%s',clock_timestamp())"
                                        .formatted(actorId, actorId));
                execute(
                        connection,
                        "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                                + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                                + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',2,'proof','pol-rev-dec-%s',clock_timestamp())"
                                        .formatted(decisionId, actorId, policyId, decisionId));
                execute(
                        connection,
                        "INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,"
                                + "maintenance_allowed,export_allowed,external_provider_allowed,isolated,"
                                + "created_by_decision_id,created_at) VALUES "
                                + "('%s',2,%s,true,false,false,false,'%s',clock_timestamp())"
                                        .formatted(policyId, companionAllowed, decisionId));
                insertGovernedOutbox(connection, "ACCESS_POLICY", policyId, 2L, "memory.policy-changed.v1", decisionId);
                int updated;
                try (PreparedStatement statement = connection.prepareStatement("UPDATE memory.access_policy "
                        + "SET current_revision_no=2 WHERE policy_id=? AND current_revision_no=1")) {
                    statement.setObject(1, policyId);
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    connection.rollback();
                    return false;
                }
                connection.commit();
                return true;
            } catch (SQLException exception) {
                connection.rollback();
                if (Set.of("23505", "40001", "40P01").contains(sqlState(exception))) {
                    return false;
                }
                throw exception;
            }
        }
    }

    private static void insertGovernedOutbox(
            Connection connection,
            String aggregateKind,
            UUID aggregateId,
            Long revision,
            String eventType,
            UUID decisionId)
            throws SQLException {
        UUID changeId = UUID.randomUUID();
        insertChangeEvent(connection, changeId, eventType, aggregateKind, aggregateId, revision, decisionId);
        insertOutbox(connection, "GOVERNED", eventType, aggregateKind, aggregateId, revision, changeId, uniqueKey());
    }

    private static void insertChangeEvent(
            Connection connection,
            UUID changeId,
            String eventType,
            String targetKind,
            UUID targetId,
            Long revision,
            UUID decisionId)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,"
                        + "target_revision_ref,decision_id,occurred_at) VALUES "
                        + "('%s','%s','%s','%s',%s,%s,clock_timestamp())"
                                .formatted(
                                        changeId,
                                        eventType,
                                        targetKind,
                                        targetId,
                                        sqlLong(revision),
                                        sqlUuid(decisionId)));
    }

    private static void insertOutbox(
            Connection connection,
            String category,
            String eventType,
            String aggregateKind,
            UUID aggregateId,
            Long revision,
            UUID changeId,
            String idempotencyKey)
            throws SQLException {
        execute(
                connection,
                ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                                + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,"
                                + "payload_manifest,change_event_id,state,available_at,created_at) VALUES "
                                + "('%s','%s','%s','%s','%s','%s',%s,'pink.event.v1','DATABASE_TEST',0,"
                                + "decode('%s','hex'),'" + manifestPayload(aggregateId, revision)
                                + "'::jsonb,%s,'READY',clock_timestamp(),clock_timestamp())")
                        .formatted(
                                UUID.randomUUID(),
                                idempotencyKey,
                                category,
                                eventType,
                                aggregateKind,
                                aggregateId,
                                sqlLong(revision),
                                HASH_HEX,
                                sqlUuid(changeId)));
    }

    private static String operationalInsertSql(Long revision, String idempotencyKey) {
        UUID aggId = UUID.randomUUID();
        String manifest = manifestPayload(aggId, revision);
        return ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,"
                        + "payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,"
                        + "last_failure_code,created_at) VALUES "
                        + "('%s','%s','OPERATIONAL','closeout.received.v1','CLOSEOUT_RUN','%s',%s,'pink.event.v1',"
                        + "'DATABASE_TEST',0,decode('%s','hex'),'" + manifest.replace("'", "''")
                        + "'::jsonb,NULL,'READY',clock_timestamp(),0,8,NULL,clock_timestamp())")
                .formatted(UUID.randomUUID(), idempotencyKey, aggId, sqlLong(revision), HASH_HEX);
    }

    private static String operationalInsertSqlWithManifest(Long revision, String idempotencyKey, String manifestJson) {
        UUID aggId = UUID.randomUUID();
        return ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,"
                        + "payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,"
                        + "last_failure_code,created_at) VALUES "
                        + "('%s','%s','OPERATIONAL','closeout.received.v1','CLOSEOUT_RUN','%s',%s,'pink.event.v1',"
                        + "'DATABASE_TEST',0,decode('%s','hex'),'" + manifestJson.replace("'", "''")
                        + "'::jsonb,NULL,'READY',clock_timestamp(),0,8,NULL,clock_timestamp())")
                .formatted(UUID.randomUUID(), idempotencyKey, aggId, sqlLong(revision), HASH_HEX);
    }

    private static String sqlLong(Long value) {
        return value == null ? "NULL" : value.toString();
    }

    private static String sqlUuid(UUID value) {
        return value == null ? "NULL" : "'%s'".formatted(value);
    }

    private static int race(Callable<Boolean> left, Callable<Boolean> right) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> wrappedLeft = () -> {
            ready.countDown();
            start.await();
            return left.call();
        };
        Callable<Boolean> wrappedRight = () -> {
            ready.countDown();
            start.await();
            return right.call();
        };
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = executor.submit(wrappedLeft);
            Future<Boolean> second = executor.submit(wrappedRight);
            ready.await();
            start.countDown();
            return (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
        }
    }

    private static void assertStatementSqlState(String expected, String sql) throws SQLException {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            SQLException exception = assertThrows(SQLException.class, () -> statement.execute(sql));
            assertEquals(expected, sqlState(exception));
        }
    }

    private static void assertCommitSqlState(String expected, SqlOperation operation) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            SQLException exception = assertThrows(SQLException.class, () -> {
                operation.run(connection);
                connection.commit();
            });
            assertEquals(expected, sqlState(exception));
            connection.rollback();
        }
    }

    private static String sqlState(SQLException exception) {
        SQLException current = exception;
        while (current != null) {
            if (current.getSQLState() != null) {
                return current.getSQLState();
            }
            current = current.getNextException();
        }
        return null;
    }

    private static Path moduleRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.exists(current.resolve("src/main/resources/db/migration"))) {
            current = current.getParent();
        }
        assertNotNull(current, "database module root not found");
        return current;
    }

    private static Path repositoryRoot() {
        Path current = moduleRoot();
        while (current != null && !Files.exists(current.resolve("contracts/events/pink-event-v1.schema.json"))) {
            current = current.getParent();
        }
        assertNotNull(current, "repository root not found");
        return current;
    }

    private static List<Path> migrationFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.list(root)) {
            return stream.filter(path -> path.getFileName().toString().matches("V00[1-6]__.*\\.sql"))
                    .sorted()
                    .toList();
        }
    }

    private static Map<String, String> fileHashes(Path root) throws IOException {
        return migrationFiles(root).stream()
                .collect(Collectors.toMap(path -> path.getFileName().toString(), path -> {
                    try {
                        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
                    } catch (IOException | NoSuchAlgorithmException exception) {
                        throw new IllegalStateException(exception);
                    }
                }));
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value));
        }
        return result.toString();
    }

    private static Set<String> extractEnum(Path path, String sectionMarker) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8);
        int section = source.indexOf(sectionMarker);
        assertTrue(section >= 0, () -> "contract section missing: " + sectionMarker);
        Matcher enumMatcher = Pattern.compile("\\\"enum\\\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL)
                .matcher(source.substring(section));
        assertTrue(enumMatcher.find(), () -> "enum missing after " + sectionMarker);
        Matcher values = Pattern.compile("\\\"([^\\\"]+)\\\"").matcher(enumMatcher.group(1));
        Set<String> result = new HashSet<>();
        while (values.find()) {
            result.add(values.group(1));
        }
        return result;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths =
                    new ArrayList<>(stream.sorted(Comparator.reverseOrder()).toList());
            for (Path path : paths) {
                Files.delete(path);
            }
        }
    }

    private record MemoryFixture(UUID memoryId, UUID revisionId, UUID policyId, UUID decisionId, UUID actorId) {}

    private record DecisionFixture(UUID decisionId, UUID proposalRevisionId, UUID newRevisionId, long targetRevision) {}

    private record ReviewFixture(UUID reviewSessionId, List<UUID> proposalRevisionIds, UUID actorId) {}

    @FunctionalInterface
    private interface SqlOperation {
        void run(Connection connection) throws SQLException;
    }
}
