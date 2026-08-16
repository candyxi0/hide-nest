package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V017 historical closure-row upgrade: deterministic conversion + fail-closed CONFIRMED guard. */
class LocalV1V017SharedEvidenceMigrationTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final String HASH = "ab".repeat(32);
    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void setUp() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) postgres.stop();
    }

    /** Migrate to V016 and seed one PREVIEWED closure with an old AFFECTED_* member. */
    private static PostgreSQLContainer<?> v016ContainerWithOldClosure(boolean confirmed) throws Exception {
        String password = UUID.randomUUID().toString();
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        container.start();
        try (Connection conn = DriverManager.getConnection(container.getJdbcUrl(), USER, password)) {
            conn.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            conn.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway.configure().dataSource(container.getJdbcUrl(), USER, password)
                .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true)
                .target("16").load().migrate();

        UUID closureId = UUID.randomUUID();
        UUID rootMemoryId = UUID.randomUUID();
        UUID rootRevisionId = UUID.randomUUID();
        UUID rootPolicyId = UUID.randomUUID();
        UUID memberTarget = UUID.randomUUID();

        try (Connection conn = DriverManager.getConnection(container.getJdbcUrl(), USER, password)) {
            try (var st = conn.prepareStatement(
                    "INSERT INTO memory.deletion_closure(closure_id,root_memory_id,preview_revision,root_current_revision_id,"
                            + "root_revision_no,root_policy_id,root_policy_revision_no,request_idempotency_key,request_hash,"
                            + "manifest_hash,state,created_at,expires_at) VALUES (?,?,1,?,1,?,1,?,decode(?,'hex'),decode(?,'hex'),"
                            + "'PREVIEWED',clock_timestamp(),clock_timestamp()+interval '1 hour')")) {
                st.setObject(1, closureId);
                st.setObject(2, rootMemoryId);
                st.setObject(3, rootRevisionId);
                st.setObject(4, rootPolicyId);
                st.setString(5, "old-" + closureId);
                st.setString(6, HASH);
                st.setString(7, HASH);
                st.executeUpdate();
            }

            try (var st = conn.prepareStatement(
                    "INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,target_revision_ref,disposition) "
                            + "VALUES (?,1,'MEMORY',?,NULL,'DELETE_REQUESTED'), (?,2,'AFFECTED_MEMORY',?,1,'AFFECTED_PENDING_CHOICE')")) {
                st.setObject(1, closureId);
                st.setObject(2, rootMemoryId);
                st.setObject(3, closureId);
                st.setObject(4, memberTarget);
                st.executeUpdate();
            }

            if (confirmed) {
                UUID actorId = UUID.randomUUID();
                UUID decisionId = UUID.randomUUID();
                UUID fenceId = UUID.randomUUID();
                try (var st = conn.prepareStatement(
                        "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES (?, 'SYNTHETIC', ?, clock_timestamp())")) {
                    st.setObject(1, actorId);
                    st.setString(2, "actor-" + actorId);
                    st.executeUpdate();
                }
                try (var st = conn.prepareStatement(
                        "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,"
                                + "review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                                + "VALUES (?,'USER_DELETE_CONFIRM',?,'HUMAN',NULL,NULL,'DELETION_CLOSURE',?,1,'synthetic',?,clock_timestamp())")) {
                    st.setObject(1, decisionId);
                    st.setObject(2, actorId);
                    st.setObject(3, closureId);
                    st.setString(4, "decision-" + decisionId);
                    st.executeUpdate();
                }
                try (var st = conn.prepareStatement(
                        "INSERT INTO memory.deletion_fence(fence_id,closure_id,target_kind,target_id,target_revision_ref,created_by_decision_id,created_at) "
                                + "VALUES (?,?,'MEMORY',?,NULL,?,clock_timestamp())")) {
                    st.setObject(1, fenceId);
                    st.setObject(2, closureId);
                    st.setObject(3, rootMemoryId);
                    st.setObject(4, decisionId);
                    st.executeUpdate();
                }
                try (var st = conn.prepareStatement(
                        "UPDATE memory.deletion_closure SET state='CONFIRMED', confirmed_by_decision_id=?, confirmed_at=clock_timestamp() WHERE closure_id=?")) {
                    st.setObject(1, decisionId);
                    st.setObject(2, closureId);
                    st.executeUpdate();
                }
            }
        }
        return container;
    }

    @Test
    @DisplayName("V016 PREVIEWED old shared closure converts to V017: SHARED_REFERENCE/RETAIN_SHARED, identity preserved, repeat 0")
    void v016PreviewedClosureConvertsToV017() throws Exception {
        PostgreSQLContainer<?> container = v016ContainerWithOldClosure(false);
        try {
            String password = container.getPassword();
            Flyway fw = Flyway.configure().dataSource(container.getJdbcUrl(), USER, password)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load();
            assertEquals(4, fw.migrate().migrationsExecuted);
            assertEquals(0, fw.migrate().migrationsExecuted);

            try (Connection conn = DriverManager.getConnection(container.getJdbcUrl(), USER, password);
                    var st = conn.createStatement()) {
                try (var rs = st.executeQuery(
                        "SELECT count(*) FROM memory.deletion_closure_member WHERE disposition='AFFECTED_PENDING_CHOICE' OR member_kind='AFFECTED_MEMORY'")) {
                    rs.next();
                    assertEquals(0, rs.getLong(1), "no old choice-required members remain");
                }
                try (var rs = st.executeQuery(
                        "SELECT member_kind, disposition, target_revision_ref, ordinal FROM memory.deletion_closure_member WHERE member_kind='SHARED_REFERENCE'")) {
                    assertTrue(rs.next(), "expected a converted SHARED_REFERENCE member");
                    assertEquals("SHARED_REFERENCE", rs.getString(1));
                    assertEquals("RETAIN_SHARED", rs.getString(2));
                    assertEquals(1L, rs.getLong(3), "target_revision_ref preserved");
                    assertEquals(2L, rs.getLong(4), "ordinal preserved");
                }
            }
        } finally {
            container.stop();
        }
    }

    @Test
    @DisplayName("V016 CONFIRMED old shared closure: V017 migration fails closed")
    void v016ConfirmedChoiceClosureRejectedAtV017() throws Exception {
        PostgreSQLContainer<?> container = v016ContainerWithOldClosure(true);
        try {
            String password = container.getPassword();
            Flyway fw = Flyway.configure().dataSource(container.getJdbcUrl(), USER, password)
                    .defaultSchema("public").locations("classpath:db/migration").cleanDisabled(true).load();
            assertThrows(Exception.class, fw::migrate, "CONFIRMED choice closure must be rejected");
        } finally {
            container.stop();
        }
    }
}
