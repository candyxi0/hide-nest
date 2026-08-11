package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.evidence.domain.*;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.memory.domain.*;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DatabaseSliceC2AEvidenceMemoryAdapterTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String PASSWORD = UUID.randomUUID().toString() + UUID.randomUUID();
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("hide_nest")
            .withUsername(USER)
            .withPassword(PASSWORD)
            .withStartupTimeout(Duration.ofSeconds(60))
            .withTmpFs(Map.of("/var/lib/postgresql", "rw,noexec,nosuid,size=536870912"));

    private static DSLContext dsl;
    private static EvidenceReferencePort evidencePort;
    private static MemoryGovernancePort memoryPort;
    private static Connection rawConnection;

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-10T10:00:00Z"), ZoneId.of("UTC"));
    private static final String HASH_HEX = "ab".repeat(32);

    // Cached prerequisite IDs
    private static UUID cachedPolicyId;
    private static long cachedPolicyRevisionNo;
    private static UUID cachedActorId;
    private static UUID cachedDecisionId;
    private static UUID cachedMemoryId;
    private static UUID cachedRevisionId;

    @BeforeAll
    static void setUp() throws Exception {
        POSTGRES.start();
        assertEquals(IMAGE, POSTGRES.getDockerImageName());
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);
                Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN");
            s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway flyway = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD)
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true)
                .load();
        assertEquals(10, flyway.migrate().migrationsExecuted);

        var rds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USER, PASSWORD);
        DataSource pds = new TransactionAwareDataSourceProxy(rds);
        var cfg = new DefaultConfiguration();
        cfg.setSQLDialect(SQLDialect.POSTGRES);
        cfg.setDataSource(pds);
        dsl = new DefaultDSLContext(cfg);

        evidencePort = new JooqEvidenceReferenceAdapter(dsl);
        memoryPort = new JooqMemoryGovernanceAdapter(dsl);
        rawConnection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD);

        // Insert prerequisite data for FK references
        insertPrerequisites();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (rawConnection != null) rawConnection.close();
        POSTGRES.stop();
    }

    // ---- helpers ----

    private static void execute(String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement()) {
            s.execute(sql);
        }
    }

    private static void executeAs(String role, String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement()) {
            s.execute("SET ROLE " + role);
            s.execute(sql);
        }
    }

    private static long scalarLong(String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarString(String sql) throws SQLException {
        try (Statement s = rawConnection.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void assertCommitSqlState(String expectedSqlState, Consumer<Connection> operation) {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
            c.setAutoCommit(false);
            operation.accept(c);
            c.commit();
            fail("Expected SQL state " + expectedSqlState + " but commit succeeded");
        } catch (SQLException e) {
            assertEquals(expectedSqlState, e.getSQLState(), "unexpected sql state: " + e.getMessage());
        }
    }

    private static void withinTransaction(Consumer<Connection> operation) throws SQLException {
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), USER, PASSWORD)) {
            c.setAutoCommit(false);
            operation.accept(c);
            c.commit();
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(CLOCK);
    }

    private static byte[] hash32() {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(UUID.randomUUID().toString().getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void insertPrerequisites() throws SQLException {
        withinTransaction(c -> {
            try {
                execute(c, "SET CONSTRAINTS ALL DEFERRED");

                // Actor ref
                cachedActorId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,display_label,created_at) "
                        + "VALUES ('%s','SYNTHETIC','prereq-actor-%s','Prereq Actor',clock_timestamp())")
                        .formatted(cachedActorId, cachedActorId));

                // Access policy
                cachedPolicyId = UUID.randomUUID();
                cachedMemoryId = UUID.randomUUID();
                UUID policyDecisionId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.access_policy(policy_id,owner_kind,owner_id,current_revision_no,created_at) "
                        + "VALUES ('%s','MEMORY','%s',1,clock_timestamp())").formatted(cachedPolicyId, cachedMemoryId));
                execute(c, ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                        + "target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                        + "('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'proof','pol-dec-%s',clock_timestamp())")
                        .formatted(policyDecisionId, cachedActorId, cachedPolicyId, policyDecisionId));
                cachedPolicyRevisionNo = 1L;
                execute(c, ("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,"
                        + "maintenance_allowed,export_allowed,external_provider_allowed,isolated,"
                        + "created_by_decision_id,created_at) VALUES "
                        + "('%s',1,true,true,false,false,false,'%s',clock_timestamp())")
                        .formatted(cachedPolicyId, policyDecisionId));
                insertGovernedOutbox(
                        c, "ACCESS_POLICY", cachedPolicyId, 1L, "memory.policy-changed.v1", policyDecisionId);

                // Memory record
                cachedRevisionId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.memory_record(memory_id,state,current_revision_id,policy_id,"
                        + "current_policy_revision_no,created_at,updated_at) VALUES "
                        + "('%s','ACTIVE','%s','%s',1,clock_timestamp(),clock_timestamp())")
                        .formatted(cachedMemoryId, cachedRevisionId, cachedPolicyId));

                // Proposal + ProposalRevision for USER_CONFIRM decision
                UUID proposalId = UUID.randomUUID();
                UUID proposalRevisionId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) "
                        + "VALUES ('%s','CREATE',NULL,clock_timestamp())").formatted(proposalId));
                execute(c, ("INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,"
                        + "action_code,body_text,memory_type,created_at) VALUES "
                        + "('%s','%s',1,'CREATE','synthetic','Claim',clock_timestamp())")
                        .formatted(proposalRevisionId, proposalId));

                // ReviewSession + ReviewMember
                UUID reviewId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.review_session(review_session_id,state,idempotency_key,"
                        + "request_hash,opened_at) VALUES ('%s','OPEN','prereq-review-%s',"
                        + "decode(repeat('ee',32),'hex'),clock_timestamp())").formatted(reviewId, reviewId));
                execute(c, ("INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) "
                        + "VALUES ('%s','%s',1)").formatted(reviewId, proposalRevisionId));

                // USER_CONFIRM decision
                cachedDecisionId = UUID.randomUUID();
                execute(c, ("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,"
                        + "proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,"
                        + "authorization_ref,idempotency_key,created_at) VALUES "
                        + "('%s','USER_CONFIRM','%s','USER','%s','%s','MEMORY','%s',1,"
                        + "'synthetic-proof','mem-dec-%s',clock_timestamp())")
                        .formatted(cachedDecisionId, cachedActorId, proposalRevisionId, reviewId,
                                cachedMemoryId, cachedDecisionId));
                insertFinalVerdictGovernedOutbox(c, reviewId, 1L, cachedDecisionId);

                // MemoryRevision
                execute(c, ("INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,"
                        + "memory_type,body_text,created_by_decision_id,created_at) VALUES "
                        + "('%s','%s',1,'Claim','prereq-body','%s',clock_timestamp())")
                        .formatted(cachedRevisionId, cachedMemoryId, cachedDecisionId));
                insertGovernedOutbox(c, "MEMORY", cachedMemoryId, 1L, "memory.canonical-committed.v1", cachedDecisionId);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String uniqueKey() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private static String sqlLong(Long value) {
        return value == null ? "NULL" : value.toString();
    }

    private static String sqlUuid(UUID value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String manifestPayload(UUID aggregateId, Long revision) {
        return "{\"aggregateId\":\"" + aggregateId + "\",\"aggregateRevision\":"
                + (revision == null ? "null" : revision)
                + ",\"policyRevision\":0,\"purpose\":\"DATABASE_TEST\",\"manifestHash\":\"" + HASH_HEX + "\"}";
    }

    private static void insertChangeEvent(Connection c, UUID changeId, String eventType,
            String kind, UUID id, Long revision, UUID decisionId) throws SQLException {
        execute(c, ("INSERT INTO memory.change_event(change_event_id,event_type,actor_id,"
                + "target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES "
                + "('%s','%s','%s','%s','%s',%s,%s,clock_timestamp())")
                .formatted(changeId, eventType, cachedActorId, kind, id,
                        sqlLong(revision), sqlUuid(decisionId)));
    }

    private static void insertOutbox(Connection c, String category, String eventType,
            String kind, UUID id, Long revision, UUID changeId, String idempotencyKey) throws SQLException {
        execute(c, ("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,"
                + "aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,"
                + "manifest_hash,payload_manifest,change_event_id,state,available_at,created_at) VALUES "
                + "('%s','%s','%s','%s','%s','%s',%s,'pink.event.v1','DATABASE_TEST',0,"
                + "decode('%s','hex'),'" + manifestPayload(id, revision) + "'::jsonb,%s,'READY',clock_timestamp(),clock_timestamp())")
                .formatted(UUID.randomUUID(), idempotencyKey, category, eventType, kind, id,
                        sqlLong(revision), HASH_HEX, sqlUuid(changeId)));
    }

    private static void insertGovernedOutbox(Connection c, String kind, UUID id,
            Long revision, String eventType, UUID decisionId) throws SQLException {
        UUID changeId = UUID.randomUUID();
        insertChangeEvent(c, changeId, eventType, kind, id, revision, decisionId);
        insertOutbox(c, "GOVERNED", eventType, kind, id, revision, changeId, uniqueKey());
    }

    private static void insertFinalVerdictGovernedOutbox(Connection c, UUID reviewSessionId,
            Long revision, UUID decisionId) throws SQLException {
        UUID changeId = UUID.randomUUID();
        insertChangeEvent(c, changeId, "review.decisions-committed.v1", "REVIEW_SESSION",
                reviewSessionId, revision, decisionId);
        insertOutbox(c, "GOVERNED", "review.decisions-committed.v1", "REVIEW_SESSION",
                reviewSessionId, revision, changeId, uniqueKey());
    }

    // ============================================================
    // 1. Source complete round-trip + (platform, external_ref) duplicate
    // ============================================================
    @Test
    @Order(1)
    @DisplayName("Source round-trip and platform external_ref uniqueness")
    void sourceRoundTripAndUniqueness() throws Exception {
        UUID sourceId = UUID.randomUUID();
        OffsetDateTime ts = now();
        Source source = new Source(sourceId, "CODEX", "TEST_PLATFORM", "REF-001",
                true, false, cachedPolicyId, ts, ts);

        // Insert
        evidencePort.insertSource(source);

        // Read back
        Source found = evidencePort.findSourceById(sourceId);
        assertNotNull(found);
        assertEquals(sourceId, found.sourceId());
        assertEquals("CODEX", found.sourceKind());
        assertEquals("TEST_PLATFORM", found.platform());
        assertEquals("REF-001", found.externalRef());
        assertTrue(found.observedAccessible());
        assertFalse(found.compressedObserved());
        assertEquals(cachedPolicyId, found.policyId());

        // Duplicate (platform, external_ref) must be rejected
        UUID dupId = UUID.randomUUID();
        Source dupSource = new Source(dupId, "CODEX", "TEST_PLATFORM", "REF-001",
                true, false, cachedPolicyId, ts, ts);
        assertThrows(Exception.class, () -> evidencePort.insertSource(dupSource));
    }

    // ============================================================
    // 2. SourceUnit complete round-trip + version unique constraint
    // ============================================================
    @Test
    @Order(2)
    @DisplayName("SourceUnit round-trip and version uniqueness")
    void sourceUnitRoundTripAndUniqueness() throws Exception {
        // Prerequisite: Source
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "UNIT-PLAT", "UNIT-REF",
                true, false, cachedPolicyId, now(), now()));

        UUID unitId = UUID.randomUUID();
        SourceUnit unit = new SourceUnit(unitId, sourceId, "ext-unit-1", "v1.0", 1L,
                cachedActorId, now(), now());

        evidencePort.insertSourceUnit(unit);

        SourceUnit found = evidencePort.findSourceUnitById(unitId);
        assertNotNull(found);
        assertEquals(unitId, found.sourceUnitId());
        assertEquals(sourceId, found.sourceId());
        assertEquals("ext-unit-1", found.externalUnitRef());
        assertEquals("v1.0", found.sourceVersion());
        assertEquals(1L, found.ordinal());
        assertEquals(cachedActorId, found.actorId());

        // Same (source_id, external_unit_ref, source_version) must be rejected
        UUID dupUnitId = UUID.randomUUID();
        SourceUnit dupUnit = new SourceUnit(dupUnitId, sourceId, "ext-unit-1", "v1.0", 2L,
                cachedActorId, now(), now());
        assertThrows(Exception.class, () -> evidencePort.insertSourceUnit(dupUnit));
    }

    // ============================================================
    // 3. SourcePayload metadata round-trip, 32-byte hash, policy revision FK
    // ============================================================
    @Test
    @Order(3)
    @DisplayName("SourcePayload metadata round-trip with 32-byte hash and policy revision FK")
    void sourcePayloadMetadataRoundTrip() throws Exception {
        // Prerequisites
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "PAYLOAD-PLAT", "PAYLOAD-REF",
                true, false, cachedPolicyId, now(), now()));
        UUID unitId = UUID.randomUUID();
        evidencePort.insertSourceUnit(new SourceUnit(unitId, sourceId, "pay-unit", "v1", 1L,
                cachedActorId, now(), now()));

        UUID payloadId = UUID.randomUUID();
        byte[] contentHash = hash32();
        assertEquals(32, contentHash.length);

        SourcePayload payload = new SourcePayload(payloadId, unitId, "TEXT",
                "LOCAL_FILE", "obj-ref-1", "obj-ver-1", "text/plain",
                1024L, contentHash, cachedPolicyId, 1L,
                "PERSISTENT", now().plusDays(30), now());

        evidencePort.insertSourcePayload(payload);

        SourcePayload found = evidencePort.findSourcePayloadById(payloadId);
        assertNotNull(found);
        assertEquals(payloadId, found.payloadId());
        assertEquals(unitId, found.sourceUnitId());
        assertEquals("TEXT", found.payloadKind());
        assertEquals("LOCAL_FILE", found.storeAdapter());
        assertEquals("obj-ref-1", found.objectRef());
        assertEquals("obj-ver-1", found.objectVersionRef());
        assertEquals("text/plain", found.contentType());
        assertEquals(1024L, found.sizeBytes());
        assertArrayEquals(contentHash, found.contentHash());
        assertEquals(cachedPolicyId, found.policyId());
        assertEquals(1L, found.currentPolicyRevisionNo());
        assertEquals("PERSISTENT", found.retentionClass());

        // Policy revision FK: insert with non-existent policy revision must be rejected
        UUID badPayloadId = UUID.randomUUID();
        SourcePayload badPayload = new SourcePayload(badPayloadId, unitId, "TEXT",
                "LOCAL_FILE", "obj-bad", null, "text/plain",
                512L, contentHash, cachedPolicyId, 999L,
                "PERSISTENT", now().plusDays(30), now());
        assertThrows(Exception.class, () -> evidencePort.insertSourcePayload(badPayload));
    }

    // ============================================================
    // 4. SourceAnchor + multi AnchorUnit ordered round-trip
    // ============================================================
    @Test
    @Order(4)
    @DisplayName("SourceAnchor and multi AnchorUnit ordered round-trip")
    void sourceAnchorAndAnchorUnitOrderedRoundTrip() throws Exception {
        // Prerequisites
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "ANCHOR-PLAT", "ANCHOR-REF",
                true, false, cachedPolicyId, now(), now()));

        UUID unitId1 = UUID.randomUUID();
        UUID unitId2 = UUID.randomUUID();
        UUID unitId3 = UUID.randomUUID();
        evidencePort.insertSourceUnit(new SourceUnit(unitId1, sourceId, "au-1", "v1", 1L, null, now(), now()));
        evidencePort.insertSourceUnit(new SourceUnit(unitId2, sourceId, "au-2", "v1", 2L, null, now(), now()));
        evidencePort.insertSourceUnit(new SourceUnit(unitId3, sourceId, "au-3", "v1", 3L, null, now(), now()));

        // Insert anchor
        UUID anchorId = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(anchorId, sourceId, "MESSAGE", now()));

        SourceAnchor foundAnchor = evidencePort.findSourceAnchorById(anchorId);
        assertNotNull(foundAnchor);
        assertEquals(anchorId, foundAnchor.anchorId());
        assertEquals(sourceId, foundAnchor.sourceId());
        assertEquals("MESSAGE", foundAnchor.anchorKind());

        // Insert anchor units with ordinals 3, 1, 2
        List<SourceAnchorUnit> units = List.of(
                new SourceAnchorUnit(anchorId, unitId3, 10L, 20L, 3L),
                new SourceAnchorUnit(anchorId, unitId1, null, null, 1L),
                new SourceAnchorUnit(anchorId, unitId2, 0L, 50L, 2L));
        evidencePort.insertSourceAnchorUnits(units);

        // Read back - must be ordered by ordinal ASC
        List<SourceAnchorUnit> found = evidencePort.findSourceAnchorUnitsByAnchorId(anchorId);
        assertEquals(3, found.size());
        // ordinal 1
        assertEquals(unitId1, found.get(0).sourceUnitId());
        assertEquals(1L, found.get(0).ordinal());
        assertNull(found.get(0).fromOffset());
        assertNull(found.get(0).toOffset());
        // ordinal 2
        assertEquals(unitId2, found.get(1).sourceUnitId());
        assertEquals(2L, found.get(1).ordinal());
        assertEquals(0L, found.get(1).fromOffset());
        assertEquals(50L, found.get(1).toOffset());
        // ordinal 3
        assertEquals(unitId3, found.get(2).sourceUnitId());
        assertEquals(3L, found.get(2).ordinal());
        assertEquals(10L, found.get(2).fromOffset());
        assertEquals(20L, found.get(2).toOffset());
    }

    // ============================================================
    // 5. Cross-source AnchorUnit rejected by V008 same-source trigger
    // ============================================================
    @Test
    @Order(5)
    @DisplayName("Cross-source AnchorUnit rejected by V008 same-source trigger")
    void crossSourceAnchorUnitRejected() throws Exception {
        // Two sources
        UUID srcA = UUID.randomUUID();
        UUID srcB = UUID.randomUUID();
        evidencePort.insertSource(new Source(srcA, "CODEX", "CROSS-A", "REF-A",
                true, false, cachedPolicyId, now(), now()));
        evidencePort.insertSource(new Source(srcB, "CODEX", "CROSS-B", "REF-B",
                true, false, cachedPolicyId, now(), now()));

        // Units belonging to different sources
        UUID unitA = UUID.randomUUID();
        UUID unitB = UUID.randomUUID();
        evidencePort.insertSourceUnit(new SourceUnit(unitA, srcA, "cross-a", "v1", 1L, null, now(), now()));
        evidencePort.insertSourceUnit(new SourceUnit(unitB, srcB, "cross-b", "v1", 1L, null, now(), now()));

        // Anchor belongs to srcA
        UUID anchorA = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(anchorA, srcA, "MESSAGE", now()));

        // Cross-source: anchorA(srcA) + unitB(srcB) → rejected by same-source trigger
        assertThrows(Exception.class, () -> {
            evidencePort.insertSourceAnchorUnits(List.of(
                    new SourceAnchorUnit(anchorA, unitB, null, null, 1L)));
        });
    }

    // ============================================================
    // 6. verifyAnchorsExist all present / missing one rejected
    // ============================================================
    @Test
    @Order(6)
    @DisplayName("verifyAnchorsExist pass and fail")
    void verifyAnchorsExistPassAndFail() throws Exception {
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "VERIFY-PLAT", "VERIFY-REF",
                true, false, cachedPolicyId, now(), now()));

        UUID anchor1 = UUID.randomUUID();
        UUID anchor2 = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(anchor1, sourceId, "MESSAGE", now()));
        evidencePort.insertSourceAnchor(new SourceAnchor(anchor2, sourceId, "MESSAGE", now()));

        // Both exist → pass
        assertDoesNotThrow(() -> evidencePort.verifyAnchorsExist(Set.of(anchor1, anchor2)));

        // One missing → fail
        UUID missingAnchor = UUID.randomUUID();
        assertThrows(RuntimeException.class, () ->
                evidencePort.verifyAnchorsExist(Set.of(anchor1, missingAnchor)));

        // Empty set → pass
        assertDoesNotThrow(() -> evidencePort.verifyAnchorsExist(Set.of()));
        assertDoesNotThrow(() -> evidencePort.verifyAnchorsExist(null));
    }

    // ============================================================
    // 7. ActorRef id/kind+stableRef round-trip, duplicate stable ref rejected
    // ============================================================
    @Test
    @Order(7)
    @DisplayName("ActorRef round-trip and duplicate stable ref rejection")
    void actorRefRoundTripAndUniqueness() throws Exception {
        UUID actorId = UUID.randomUUID();
        OffsetDateTime ts = now();
        ActorRef actor = new ActorRef(actorId, "SYNTHETIC", "c2a-test-actor", "Test Actor", ts);

        // Insert via adapter
        memoryPort.insertActorRef(actor);

        // Find by id
        ActorRef found = memoryPort.findActorRefById(actorId);
        assertNotNull(found);
        assertEquals(actorId, found.actorId());
        assertEquals("SYNTHETIC", found.actorKind());
        assertEquals("c2a-test-actor", found.stableRef());
        assertEquals("Test Actor", found.displayLabel());

        // Find by kind + stableRef
        ActorRef found2 = memoryPort.findActorRefByKindAndStableRef("SYNTHETIC", "c2a-test-actor");
        assertNotNull(found2);
        assertEquals(actorId, found2.actorId());
        assertEquals("SYNTHETIC", found2.actorKind());
        assertEquals("c2a-test-actor", found2.stableRef());

        // Duplicate kind+stableRef must be rejected
        UUID dupId = UUID.randomUUID();
        ActorRef dupActor = new ActorRef(dupId, "SYNTHETIC", "c2a-test-actor", "Dup Actor", ts);
        assertThrows(Exception.class, () -> memoryPort.insertActorRef(dupActor));
    }

    // ============================================================
    // 8. MemoryRelation two legal targets each pass
    // ============================================================
    @Test
    @Order(8)
    @DisplayName("MemoryRelation EVIDENCED_BY->anchor and INTERPRETS->revision both pass")
    void memoryRelationTwoLegalTargets() throws Exception {
        // EVIDENCED_BY → anchor
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "REL-PLAT", "REL-REF",
                true, false, cachedPolicyId, now(), now()));
        UUID anchorId = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(anchorId, sourceId, "MESSAGE", now()));

        UUID rel1Id = UUID.randomUUID();
        MemoryRelation rel1 = new MemoryRelation(rel1Id, cachedRevisionId, "EVIDENCED_BY",
                null, anchorId, null, cachedDecisionId, now());
        memoryPort.insertMemoryRelations(List.of(rel1));

        List<MemoryRelation> found1 = memoryPort.findMemoryRelationsByFromRevisionId(cachedRevisionId);
        assertFalse(found1.isEmpty());
        assertTrue(found1.stream().anyMatch(r -> r.relationId().equals(rel1Id)));

        // INTERPRETS → revision
        UUID rel2Id = UUID.randomUUID();
        MemoryRelation rel2 = new MemoryRelation(rel2Id, cachedRevisionId, "INTERPRETS",
                cachedRevisionId, null, null, cachedDecisionId, now());
        memoryPort.insertMemoryRelations(List.of(rel2));

        List<MemoryRelation> found2 = memoryPort.findMemoryRelationsByFromRevisionId(cachedRevisionId);
        assertTrue(found2.stream().anyMatch(r -> r.relationId().equals(rel2Id)));
    }

    // ============================================================
    // 9. EVIDENCED_BY->revision and non-EVIDENCED_BY->anchor attacks rejected
    // ============================================================
    @Test
    @Order(9)
    @DisplayName("Relation target attacks: EVIDENCED_BY->revision + SUPPORTS->anchor rejected")
    void relationTargetAttacksRejected() throws Exception {
        // EVIDENCED_BY → revision (violates target_check)
        UUID relA = UUID.randomUUID();
        MemoryRelation bad1 = new MemoryRelation(relA, cachedRevisionId, "EVIDENCED_BY",
                cachedRevisionId, null, null, cachedDecisionId, now());
        assertThrows(Exception.class, () -> memoryPort.insertMemoryRelations(List.of(bad1)));

        // SUPPORTS → anchor (violates target_check)
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "ATK-PLAT", "ATK-REF",
                true, false, cachedPolicyId, now(), now()));
        UUID anchorId = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(anchorId, sourceId, "MESSAGE", now()));

        UUID relB = UUID.randomUUID();
        MemoryRelation bad2 = new MemoryRelation(relB, cachedRevisionId, "SUPPORTS",
                null, anchorId, null, cachedDecisionId, now());
        assertThrows(Exception.class, () -> memoryPort.insertMemoryRelations(List.of(bad2)));
    }

    // ============================================================
    // 10. find-not-found returns null precisely, no pseudo objects
    // ============================================================
    @Test
    @Order(10)
    @DisplayName("Not-found returns null for all adapter find methods")
    void notFoundReturnsNull() {
        UUID nonexistent = UUID.randomUUID();

        assertNull(evidencePort.findSourceById(nonexistent));
        assertNull(evidencePort.findSourceUnitById(nonexistent));
        assertNull(evidencePort.findSourcePayloadById(nonexistent));
        assertNull(evidencePort.findSourceAnchorById(nonexistent));
        assertNull(memoryPort.findActorRefById(nonexistent));
        assertNull(memoryPort.findActorRefByKindAndStableRef("NONEXISTENT", "never"));

        // Empty list (not null) for anchor units query
        List<SourceAnchorUnit> emptyUnits = evidencePort.findSourceAnchorUnitsByAnchorId(nonexistent);
        assertNotNull(emptyUnits); // port returns List, not null
        assertTrue(emptyUnits.isEmpty());

        // Empty list for relations query
        List<MemoryRelation> emptyRels = memoryPort.findMemoryRelationsByFromRevisionId(nonexistent);
        assertNotNull(emptyRels); // port returns List, not null
        assertTrue(emptyRels.isEmpty());
    }

    // ============================================================
    // 11. Body text canary: adapter query/return objects must not contain body text
    // ============================================================
    @Test
    @Order(11)
    @DisplayName("Body text canary: adapter returns no bodyText/prompt/answer/rawPayload fields")
    void bodyTextCanaryHitZero() throws Exception {
        // SourcePayload domain record has NO bodyText/prompt/answer/rawPayload fields (verified by construction)
        // Let's verify after a round-trip that no forbidden field name appears in toString()
        UUID sourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(sourceId, "CODEX", "CANARY-PLAT", "CANARY-REF",
                true, false, cachedPolicyId, now(), now()));
        UUID unitId = UUID.randomUUID();
        evidencePort.insertSourceUnit(new SourceUnit(unitId, sourceId, "canary-unit", "v1", 1L,
                null, now(), now()));

        UUID payloadId = UUID.randomUUID();
        SourcePayload payload = new SourcePayload(payloadId, unitId, "TEXT",
                "LOCAL_FILE", "canary-obj", null, "text/plain",
                64L, hash32(), cachedPolicyId, 1L, "PERSISTENT", null, now());
        evidencePort.insertSourcePayload(payload);
        SourcePayload found = evidencePort.findSourcePayloadById(payloadId);
        assertNotNull(found);

        // Verify no body text fields exist in SourcePayload
        String str = found.toString();
        assertFalse(str.contains("bodyText"), "bodyText must not appear in SourcePayload toString");
        assertFalse(str.contains("prompt"), "prompt must not appear in SourcePayload toString");
        assertFalse(str.contains("answer"), "answer must not appear in SourcePayload toString");
        assertFalse(str.contains("rawPayload"), "rawPayload must not appear in SourcePayload toString");

        // Also check Source domain record
        Source src = evidencePort.findSourceById(sourceId);
        assertNotNull(src);
        String srcStr = src.toString();
        assertFalse(srcStr.contains("bodyText"));
        assertFalse(srcStr.contains("prompt"));
        assertFalse(srcStr.contains("answer"));
        assertFalse(srcStr.contains("rawPayload"));

        // Also check ActorRef
        ActorRef actor = memoryPort.findActorRefById(cachedActorId);
        assertNotNull(actor);
        String actorStr = actor.toString();
        assertFalse(actorStr.contains("bodyText"));
        assertFalse(actorStr.contains("prompt"));
        assertFalse(actorStr.contains("answer"));
        assertFalse(actorStr.contains("rawPayload"));
    }

    // ============================================================
    // 12. Database exceptions not swallowed or converted to empty collections
    // ============================================================
    @Test
    @Order(12)
    @DisplayName("Database exceptions propagate, not swallowed or converted to empty collection")
    void databaseExceptionsNotSwallowed() throws Exception {
        // Missing FK: insert SourcePayload with non-existent source_unit_id
        UUID badPayloadId = UUID.randomUUID();
        UUID nonexistentUnitId = UUID.randomUUID();
        SourcePayload badPayload = new SourcePayload(badPayloadId, nonexistentUnitId, "TEXT",
                "LOCAL_FILE", "bad-obj", null, "text/plain",
                64L, hash32(), cachedPolicyId, 1L, "PERSISTENT", null, now());
        assertThrows(Exception.class, () -> evidencePort.insertSourcePayload(badPayload));

        // Missing FK: insert AnchorUnit with non-existent anchor_id
        UUID nonexistentAnchor = UUID.randomUUID();
        assertThrows(Exception.class, () -> {
            evidencePort.insertSourceAnchorUnits(List.of(
                    new SourceAnchorUnit(nonexistentAnchor, UUID.randomUUID(), null, null, 1L)));
        });

        // Missing FK: insert MemoryRelation with non-existent from_revision_id
        UUID badRelationId = UUID.randomUUID();
        UUID nonexistentRevision = UUID.randomUUID();
        MemoryRelation badRelation = new MemoryRelation(badRelationId, nonexistentRevision, "INTERPRETS",
                cachedRevisionId, null, null, cachedDecisionId, now());
        assertThrows(Exception.class, () -> memoryPort.insertMemoryRelations(List.of(badRelation)));
    }

    // ============================================================
    // 13. Empty batch no-op for anchor units and memory relations
    // ============================================================
    @Test
    @Order(13)
    @DisplayName("Empty batch insert is deterministic no-op")
    void emptyBatchNoOp() {
        assertDoesNotThrow(() -> evidencePort.insertSourceAnchorUnits(List.of()));
        assertDoesNotThrow(() -> evidencePort.insertSourceAnchorUnits(null));
        assertDoesNotThrow(() -> memoryPort.insertMemoryRelations(List.of()));
        assertDoesNotThrow(() -> memoryPort.insertMemoryRelations(null));
    }

    // ============================================================
    // 14. MemoryRelation find ordering: created_at ASC, relation_id ASC
    // ============================================================
    @Test
    @Order(14)
    @DisplayName("MemoryRelation query ordered by created_at ASC, relation_id ASC")
    void memoryRelationOrderedQuery() throws Exception {
        // Insert several relations from a new revision for clean ordering test
        UUID newSourceId = UUID.randomUUID();
        evidencePort.insertSource(new Source(newSourceId, "CODEX", "ORD-PLAT", "ORD-REF",
                true, false, cachedPolicyId, now(), now()));
        UUID ordAnchor = UUID.randomUUID();
        evidencePort.insertSourceAnchor(new SourceAnchor(ordAnchor, newSourceId, "MESSAGE", now()));

        // Insert relations with different times
        UUID rel1 = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID rel2 = UUID.fromString("00000000-0000-0000-0000-000000000102");
        UUID rel3 = UUID.fromString("00000000-0000-0000-0000-000000000103");

        OffsetDateTime t1 = OffsetDateTime.now(CLOCK).minusMinutes(10);
        OffsetDateTime t2 = OffsetDateTime.now(CLOCK).minusMinutes(5);
        OffsetDateTime t3 = OffsetDateTime.now(CLOCK);

        // Insert in reverse time order
        memoryPort.insertMemoryRelations(List.of(
                new MemoryRelation(rel3, cachedRevisionId, "EVIDENCED_BY", null, ordAnchor, null, cachedDecisionId, t3),
                new MemoryRelation(rel1, cachedRevisionId, "EVIDENCED_BY", null, ordAnchor, null, cachedDecisionId, t1),
                new MemoryRelation(rel2, cachedRevisionId, "EVIDENCED_BY", null, ordAnchor, null, cachedDecisionId, t2)
        ));

        List<MemoryRelation> found = memoryPort.findMemoryRelationsByFromRevisionId(cachedRevisionId);
        // All relations for this revision sorted by created_at ASC, relation_id ASC
        // Verify the C2A relations are in correct order
        List<MemoryRelation> c2aRels = found.stream()
                .filter(r -> r.relationId().toString().startsWith("00000000-0000-0000-0000-"))
                .toList();
        assertEquals(3, c2aRels.size());
        assertEquals(rel1, c2aRels.get(0).relationId());
        assertEquals(rel2, c2aRels.get(1).relationId());
        assertEquals(rel3, c2aRels.get(2).relationId());
    }
}
