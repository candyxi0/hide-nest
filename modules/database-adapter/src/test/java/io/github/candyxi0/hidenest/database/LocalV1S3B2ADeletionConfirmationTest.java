package io.github.candyxi0.hidenest.database;

import static io.github.candyxi0.hidenest.database.generated.memory.Tables.DELETION_CLOSURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.adapter.JooqDeletionConfirmationAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.memory.port.DeletionConfirmationPort;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalV1S3B2ADeletionConfirmationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final byte[] HASH = "0".repeat(32).getBytes(StandardCharsets.UTF_8);
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;

    @BeforeAll
    static void setUp() throws Exception {
        postgres = container("hide_nest");
        postgres.start();
        createRoles(postgres);
        MigrateResult result = flyway(postgres).migrate();
        assertEquals(19, result.migrationsExecuted);
        dsl = dsl(postgres);
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) postgres.stop();
    }

    @Test
    @DisplayName("V012 start, upgrade to V016, and repeat migration are 12/4/0")
    void migrationCounts() throws Exception {
        PostgreSQLContainer<?> upgrade = container("hide_nest_upgrade");
        try {
            upgrade.start();
            createRoles(upgrade);
            MigrateResult first = flyway(upgrade, "12").migrate();
            MigrateResult second = flyway(upgrade).migrate();
            MigrateResult repeat = flyway(upgrade).migrate();
            assertEquals(12, first.migrationsExecuted);
            assertEquals(7, second.migrationsExecuted);
            assertEquals(0, repeat.migrationsExecuted);
            assertEquals(19L, scalar(upgrade, "SELECT count(*) FROM public.flyway_schema_history WHERE success"));
        } finally {
            upgrade.stop();
        }
    }

    @Test
    void snapshotLocksAndReturnsCompleteStableDefensiveProjection() {
        Fixture fixture = fixture(3, false);
        DeletionConfirmationPort port = new JooqDeletionConfirmationAdapter(dsl);
        DeletionConfirmationPort.ConfirmationSnapshot snapshot = port.lockConfirmationSnapshot(fixture.closureId);
        assertNotNull(snapshot);
        assertEquals(fixture.closureId, snapshot.closureId());
        assertEquals(fixture.rootMemoryId, snapshot.rootMemoryId());
        assertEquals(7L, snapshot.previewRevision());
        assertEquals(List.of(1L, 2L, 3L), snapshot.members().stream().map(DeletionConfirmationPort.Member::ordinal).toList());
        assertEquals(32, snapshot.requestHash().length);
        assertEquals(32, snapshot.manifestHash().length);
        byte[] request = snapshot.requestHash();
        request[0] = 'X';
        byte[] manifest = snapshot.manifestHash();
        manifest[0] = 'X';
        assertEquals('0', snapshot.requestHash()[0]);
        assertEquals('0', snapshot.manifestHash()[0]);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.members().add(null));
    }

    @Test
    void legalDecisionCompleteFenceSetAndConfirmCommitAtomically() {
        Fixture fixture = fixture(3, false);
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            insertDecision(tx, fixture.confirmationDecisionId, fixture.closureId, fixture.rootMemoryId, 7L);
            DeletionFencePort fences = new JooqDeletionFenceAdapter(tx);
            for (MemberSpec member : fixture.members) {
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), fixture.closureId, member.kind, member.id, member.revision,
                        fixture.confirmationDecisionId, fixture.now)));
            }
            assertTrue(new JooqDeletionConfirmationAdapter(tx).confirmClosure(
                    fixture.closureId, 7L, HASH, fixture.rootRevisionId, 1L, fixture.policyId, 1L,
                    fixture.confirmationDecisionId, fixture.now));
        });
        assertEquals("CONFIRMED", state(fixture.closureId));
        assertEquals(3L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.closureId));
    }

    @Test
    void affectedMembersRemainUnfencedWhileConfirmationStillSucceeds() {
        Fixture fixture = fixture(2, true);
        confirm(fixture, false);
        assertEquals("CONFIRMED", state(fixture.closureId));
        assertEquals(2L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.closureId));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence f JOIN memory.deletion_closure_member m "
                + "ON m.closure_id=f.closure_id AND m.member_kind=f.target_kind AND m.target_id=f.target_id "
                + "AND m.target_revision_ref IS NOT DISTINCT FROM f.target_revision_ref "
                + "WHERE f.closure_id=? AND m.disposition='RETAIN_SHARED'", fixture.closureId));
    }

    @Test
    void missingExtraWrongDecisionAffectedAndNoRootAreRejectedAtCommit() {
        rejectedConfirmation(fixture(2, false), 1, false, false, false);
        rejectedConfirmation(fixture(1, false), 1, false, false, false, true);
        rejectedConfirmation(fixture(2, false), 2, true, false, false);
        rejectedConfirmation(fixture(2, false), 2, false, true, false);
        rejectedConfirmation(fixture(2, false), 2, false, false, true);
        rejectedConfirmation(fixture(2, true), 2, false, true, false);
    }

    @Test
    void stalePointersHashAndExpiredPreviewProduceNoConfirmationFact() {
        Fixture stale = fixture(1, false);
        JooqDeletionConfirmationAdapter port = new JooqDeletionConfirmationAdapter(dsl);
        assertFalse(port.confirmClosure(stale.closureId, 99L, HASH, stale.rootRevisionId, 1L,
                stale.policyId, 1L, UUID.randomUUID(), stale.now));
        assertFalse(port.confirmClosure(stale.closureId, 7L, "1".repeat(32).getBytes(StandardCharsets.UTF_8),
                stale.rootRevisionId, 1L, stale.policyId, 1L, UUID.randomUUID(), stale.now));
        assertFalse(port.confirmClosure(stale.closureId, 7L, HASH, UUID.randomUUID(), 1L,
                stale.policyId, 1L, UUID.randomUUID(), stale.now));
        assertFalse(port.confirmClosure(stale.closureId, 7L, HASH, stale.rootRevisionId, 2L,
                stale.policyId, 1L, UUID.randomUUID(), stale.now));
        assertFalse(port.confirmClosure(stale.closureId, 7L, HASH, stale.rootRevisionId, 1L,
                UUID.randomUUID(), 1L, UUID.randomUUID(), stale.now));
        assertFalse(port.confirmClosure(stale.closureId, 7L, HASH, stale.rootRevisionId, 1L,
                stale.policyId, 2L, UUID.randomUUID(), stale.now));
        assertEquals("PREVIEWED", state(stale.closureId));

        Fixture expired = fixture(1, false);
        dsl.transaction(tx -> {
            insertDecision(tx.dsl(), expired.confirmationDecisionId, expired.closureId, expired.rootMemoryId, 7L);
            assertThrowsDb013(() -> new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    expired.closureId, 7L, HASH, expired.rootRevisionId, 1L, expired.policyId, 1L,
                    expired.confirmationDecisionId, expired.now.plusHours(2)));
        });
        assertEquals("PREVIEWED", state(expired.closureId));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_id=?", expired.confirmationDecisionId));
    }

    @Test
    void decisionKindTargetKindTargetIdAndRevisionMustMatchExactly() {
        rejectedDecisionBinding("USER_ARCHIVE", "DELETION_CLOSURE", null, 7L);
        rejectedDecisionBinding("USER_DELETE_CONFIRM", "MEMORY", null, 7L);
        rejectedDecisionBinding("USER_DELETE_CONFIRM", "DELETION_CLOSURE", UUID.randomUUID(), 7L);
        rejectedDecisionBinding("USER_DELETE_CONFIRM", "DELETION_CLOSURE", null, 8L);
    }

    @Test
    void confirmedReconfirmRollbackAndMutationDeletionAreRejected() {
        Fixture fixture = fixture(1, false);
        confirm(fixture, false);
        assertThrowsDb013(() -> dsl.execute(
                "UPDATE memory.deletion_closure SET confirmed_at=confirmed_at WHERE closure_id=?", fixture.closureId));
        assertThrowsDb013(() -> dsl.execute("DELETE FROM memory.deletion_closure WHERE closure_id=?", fixture.closureId));
        assertThrowsDb013(() -> dsl.execute(
                "INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,disposition) "
                        + "VALUES (?,99,'MEMORY',?,'DELETE_REQUESTED')",
                fixture.closureId, UUID.randomUUID()));
        assertEquals("CONFIRMED", state(fixture.closureId));
    }

    @Test
    void decisionFenceAndClosureFailureRollBackWithoutHalfCommit() {
        Fixture fixture = fixture(2, false);
        assertThrowsDb013(() -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), fixture.confirmationDecisionId, fixture.closureId, fixture.rootMemoryId, 7L);
            new JooqDeletionFenceAdapter(tx.dsl()).insertFences(List.of(new DeletionFencePort.FenceDraft(
                    UUID.randomUUID(), fixture.closureId, fixture.members.get(0).kind, fixture.members.get(0).id,
                    fixture.members.get(0).revision, fixture.confirmationDecisionId, fixture.now)));
            new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    fixture.closureId, 7L, HASH, fixture.rootRevisionId, 1L, fixture.policyId, 1L,
                    fixture.confirmationDecisionId, fixture.now);
        }));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_id=?", fixture.confirmationDecisionId));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.closureId));
        assertEquals("PREVIEWED", state(fixture.closureId));
    }

    @Test
    void injectedFailuresAfterDecisionPartialFencesAndClosureUpdateLeaveNoHalfCommit() {
        Fixture afterDecision = fixture(2, false);
        assertThrows(InjectedFailure.class, () -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), afterDecision.confirmationDecisionId, afterDecision.closureId,
                    afterDecision.rootMemoryId, 7L);
            throw new InjectedFailure();
        }));
        assertRolledBack(afterDecision);

        Fixture afterPartialFences = fixture(2, false);
        assertThrows(InjectedFailure.class, () -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), afterPartialFences.confirmationDecisionId, afterPartialFences.closureId,
                    afterPartialFences.rootMemoryId, 7L);
            MemberSpec first = afterPartialFences.members.get(0);
            new JooqDeletionFenceAdapter(tx.dsl()).insertFences(List.of(new DeletionFencePort.FenceDraft(
                    UUID.randomUUID(), afterPartialFences.closureId, first.kind, first.id, first.revision,
                    afterPartialFences.confirmationDecisionId, afterPartialFences.now)));
            throw new InjectedFailure();
        }));
        assertRolledBack(afterPartialFences);

        Fixture afterClosureUpdate = fixture(2, false);
        assertThrows(InjectedFailure.class, () -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), afterClosureUpdate.confirmationDecisionId, afterClosureUpdate.closureId,
                    afterClosureUpdate.rootMemoryId, 7L);
            DeletionFencePort fences = new JooqDeletionFenceAdapter(tx.dsl());
            for (MemberSpec member : afterClosureUpdate.members) {
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), afterClosureUpdate.closureId, member.kind, member.id, member.revision,
                        afterClosureUpdate.confirmationDecisionId, afterClosureUpdate.now)));
            }
            assertTrue(new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    afterClosureUpdate.closureId, 7L, HASH, afterClosureUpdate.rootRevisionId, 1L,
                    afterClosureUpdate.policyId, 1L, afterClosureUpdate.confirmationDecisionId,
                    afterClosureUpdate.now));
            throw new InjectedFailure();
        }));
        assertRolledBack(afterClosureUpdate);
    }

    private static void rejectedDecisionBinding(
            String decisionKind, String targetKind, UUID targetIdOverride, long targetRevision) {
        Fixture fixture = fixture(1, false);
        assertThrowsDb013(() -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), fixture.confirmationDecisionId, decisionKind, targetKind,
                    targetIdOverride == null ? fixture.closureId : targetIdOverride, targetRevision);
            new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    fixture.closureId, 7L, HASH, fixture.rootRevisionId, 1L, fixture.policyId, 1L,
                    fixture.confirmationDecisionId, fixture.now);
        }));
        assertRolledBack(fixture);
    }

    private static void assertRolledBack(Fixture fixture) {
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_id=?", fixture.confirmationDecisionId));
        assertEquals(0L, count("SELECT count(*) FROM memory.deletion_fence WHERE closure_id=?", fixture.closureId));
        assertEquals("PREVIEWED", state(fixture.closureId));
    }

    private static void rejectedConfirmation(Fixture fixture, int fenceCount, boolean wrongDecision,
            boolean affectedFence, boolean omitRoot) {
        rejectedConfirmation(fixture, fenceCount, wrongDecision, affectedFence, omitRoot, false);
    }

    private static void rejectedConfirmation(Fixture fixture, int fenceCount, boolean wrongDecision,
            boolean affectedFence, boolean omitRoot, boolean addExtraFence) {
        assertThrowsDb013(() -> dsl.transaction(tx -> {
            insertDecision(tx.dsl(), fixture.confirmationDecisionId, fixture.closureId, fixture.rootMemoryId, 7L);
            UUID fenceDecision = wrongDecision ? UUID.randomUUID() : fixture.confirmationDecisionId;
            if (wrongDecision) insertDecision(tx.dsl(), fenceDecision, fixture.closureId, fixture.rootMemoryId, 7L);
            List<MemberSpec> members = fixture.members.subList(omitRoot ? 1 : 0, Math.min(fenceCount, fixture.members.size()));
            DeletionFencePort fences = new JooqDeletionFenceAdapter(tx.dsl());
            for (MemberSpec member : members) {
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), fixture.closureId, affectedFence ? "MEMORY" : member.kind, member.id,
                        member.revision, fenceDecision, fixture.now)));
            }
            if (addExtraFence) {
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), fixture.closureId, "SOURCE_PAYLOAD", UUID.randomUUID(), null,
                        fixture.confirmationDecisionId, fixture.now)));
            }
            new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    fixture.closureId, 7L, HASH, fixture.rootRevisionId, 1L, fixture.policyId, 1L,
                    fixture.confirmationDecisionId, fixture.now);
        }));
        assertEquals("PREVIEWED", state(fixture.closureId));
        assertEquals(0L, count("SELECT count(*) FROM memory.decision WHERE decision_id=?", fixture.confirmationDecisionId));
    }

    private static void confirm(Fixture fixture, boolean wrongDecision) {
        dsl.transaction(tx -> {
            insertDecision(tx.dsl(), fixture.confirmationDecisionId, fixture.closureId, fixture.rootMemoryId, 7L);
            DeletionFencePort fences = new JooqDeletionFenceAdapter(tx.dsl());
            for (MemberSpec member : fixture.members) {
                fences.insertFences(List.of(new DeletionFencePort.FenceDraft(
                        UUID.randomUUID(), fixture.closureId, member.kind, member.id, member.revision,
                        fixture.confirmationDecisionId, fixture.now)));
            }
            assertTrue(new JooqDeletionConfirmationAdapter(tx.dsl()).confirmClosure(
                    fixture.closureId, 7L, HASH, fixture.rootRevisionId, 1L, fixture.policyId, 1L,
                    fixture.confirmationDecisionId, fixture.now));
        });
    }

    private static Fixture fixture(int memberCount, boolean includeAffected) {
        UUID closureId = UUID.randomUUID();
        UUID rootMemoryId = UUID.randomUUID();
        UUID rootRevisionId = UUID.randomUUID();
        UUID policyId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        dsl.execute("INSERT INTO memory.deletion_closure(closure_id,root_memory_id,preview_revision,root_current_revision_id,root_revision_no,root_policy_id,root_policy_revision_no,request_idempotency_key,request_hash,manifest_hash,state,created_at,expires_at) VALUES (?,?,?,?,?,?,?, ?,decode(?,'hex'),decode(?,'hex'),'PREVIEWED',?::timestamptz,?::timestamptz)",
                closureId, rootMemoryId, 7L, rootRevisionId, 1L, policyId, 1L, "s3b2a-" + closureId,
                "30".repeat(32), "30".repeat(32), now, now.plusHours(1));
        List<MemberSpec> members = new java.util.ArrayList<>();
        for (int ordinal = memberCount; ordinal >= 1; ordinal--) {
            String kind = ordinal == 1 ? "MEMORY" : "SOURCE_UNIT";
            UUID id = ordinal == 1 ? rootMemoryId : UUID.randomUUID();
            Long revision = null;
            dsl.execute("INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,target_revision_ref,disposition) VALUES (?,?,?,?,?,'DELETE_CANDIDATE')"
                    .replace("'DELETE_CANDIDATE'", ordinal == 1 ? "'DELETE_REQUESTED'" : "'DELETE_CANDIDATE'"),
                    closureId, ordinal, kind, id, revision);
            members.add(new MemberSpec(kind, id, revision));
        }
        if (includeAffected) {
            dsl.execute("INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,disposition) VALUES (?,99,'SHARED_REFERENCE',?,'RETAIN_SHARED')",
                    closureId, UUID.randomUUID());
        }
        members.sort(java.util.Comparator.comparing(MemberSpec::kind));
        return new Fixture(closureId, rootMemoryId, rootRevisionId, policyId, decisionId, now, members);
    }

    private static void insertDecision(DSLContext tx, UUID decisionId, UUID closureId, UUID actorId, long revision) {
        insertDecision(tx, decisionId, "USER_DELETE_CONFIRM", "DELETION_CLOSURE", closureId, revision);
    }

    private static void insertDecision(DSLContext tx, UUID decisionId, String decisionKind,
            String targetKind, UUID targetId, long revision) {
        UUID actorRef = UUID.randomUUID();
        tx.execute("INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES (?,'SYNTHETIC',?,clock_timestamp())",
                actorRef, "s3b2a-" + actorRef);
        tx.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES (?,?,?,'USER',?,?,?, 'synthetic',?,clock_timestamp())",
                decisionId, decisionKind, actorRef, targetKind, targetId, revision, "decision-" + decisionId);
    }

    private static PostgreSQLContainer<?> container(String database) {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database).withUsername(USER).withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    private static void createRoles(PostgreSQLContainer<?> container) throws SQLException {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), USER, container.getPassword())) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
    }

    private static Flyway flyway(PostgreSQLContainer<?> container) {
        return flyway(container, null);
    }

    private static Flyway flyway(PostgreSQLContainer<?> container, String target) {
        var config = Flyway.configure().dataSource(container.getJdbcUrl(), USER, container.getPassword())
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                .baselineOnMigrate(false).outOfOrder(false).validateMigrationNaming(true);
        if (target != null) config.target(target);
        return config.load();
    }

    private static DSLContext dsl(PostgreSQLContainer<?> container) {
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.setSQLDialect(SQLDialect.POSTGRES);
        configuration.setDataSource(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                container.getJdbcUrl(), USER, container.getPassword()));
        return new DefaultDSLContext(configuration);
    }

    private static long scalar(PostgreSQLContainer<?> container, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(container.getJdbcUrl(), USER, container.getPassword());
                var statement = c.createStatement();
                var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String state(UUID closureId) {
        return dsl.select(DELETION_CLOSURE.STATE).from(DELETION_CLOSURE)
                .where(DELETION_CLOSURE.CLOSURE_ID.eq(closureId)).fetchOne(DELETION_CLOSURE.STATE);
    }

    private static long count(String sql, Object... args) {
        return ((Number) dsl.fetchValue(sql, args)).longValue();
    }

    private static void assertThrowsDb013(Runnable operation) {
        Throwable error = assertThrows(Throwable.class, operation::run);
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sql) {
                assertEquals("23514", sql.getSQLState());
                assertTrue(sql.getMessage().contains("HDM013_DELETION_CONFIRMATION_INVALID")
                        || sql.getMessage().contains("HDM012_DELETION_FENCED"), sql.getMessage());
                return;
            }
            current = current.getCause();
        }
        throw new AssertionError("expected PostgreSQL 23514 HDM013 rejection", error);
    }

    private record Fixture(UUID closureId, UUID rootMemoryId, UUID rootRevisionId, UUID policyId,
            UUID confirmationDecisionId, OffsetDateTime now, List<MemberSpec> members) {}

    private record MemberSpec(String kind, UUID id, Long revision) {}

    private static final class InjectedFailure extends RuntimeException {}
}
