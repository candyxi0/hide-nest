package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.candyxi0.hidenest.database.adapter.JooqBubbleStoreAdapter;
import io.github.candyxi0.hidenest.runtime.domain.BubbleDeliveryItem;
import io.github.candyxi0.hidenest.runtime.domain.BubbleRoomRevisionLedgerEntry;
import io.github.candyxi0.hidenest.runtime.domain.BubbleTurnReceipt;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** V022 migration, closure, privilege, immutability and exact room-purge contract. */
class LocalV1BubbleCoreDatabaseTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";

    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static TransactionTemplate transactions;
    private static JooqBubbleStoreAdapter store;
    private static String password;

    @BeforeAll
    static void setUp() throws Exception {
        password = UUID.randomUUID().toString();
        postgres = container("bubble_v022", password);
        postgres.start();
        createRoles(postgres, password);
        Flyway flyway = flyway(postgres, null);
        assertEquals(22, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);

        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), USER, password);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        dsl = DSL.using(new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);
        store = new JooqBubbleStoreAdapter(dsl);
    }

    @AfterAll
    static void tearDown() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void v021UpgradeAndRepeatAreExactlyOneAndZero() throws Exception {
        String upgradePassword = UUID.randomUUID().toString();
        try (PostgreSQLContainer<?> upgrade = container("bubble_upgrade", upgradePassword)) {
            upgrade.start();
            createRoles(upgrade, upgradePassword);
            assertEquals(21, flyway(upgrade, "21").migrate().migrationsExecuted);
            Flyway v22 = flyway(upgrade, null);
            assertEquals(1, v22.migrate().migrationsExecuted);
            assertEquals(0, v22.migrate().migrationsExecuted);
        }
    }

    @Test
    void schemaHasNoQueryOrBodyColumnAndNoMemoryDeletionBlockingForeignKey() {
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM information_schema.columns "
                        + "WHERE table_schema='runtime' AND table_name LIKE 'bubble_%' "
                        + "AND (column_name LIKE '%query_text%' OR column_name LIKE '%body%')"));
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.table_constraints tc "
                        + "ON tc.constraint_schema=rc.constraint_schema "
                        + "AND tc.constraint_name=rc.constraint_name "
                        + "WHERE tc.table_schema='runtime' AND tc.table_name LIKE 'bubble_%' "
                        + "AND rc.unique_constraint_schema='memory'"));
    }

    @Test
    void readyFactSetIsAtomicImmutableAndClosed() {
        String turn = "turn-ready";
        UUID memoryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T10:00:00Z");
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-a", turn, "BUBBLE_READY", now));
            store.insertDeliveryItem(new BubbleDeliveryItem(
                    "family-space", "room-a", turn, memoryId, revisionId, 1, 2, 0.70, "EVENT", 3));
            store.insertLedgerEntry(new BubbleRoomRevisionLedgerEntry("family-space", "room-a", revisionId, turn, now));
            return null;
        });
        assertEquals(1, scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key='turn-ready'"));
        assertEquals(1, scalarInt("SELECT count(*) FROM runtime.bubble_delivery_item WHERE turn_key='turn-ready'"));
        assertEquals(
                1, scalarInt("SELECT count(*) FROM runtime.bubble_room_revision_ledger WHERE turn_key='turn-ready'"));

        assertThrows(
                RuntimeException.class,
                () -> dsl.execute(
                        "UPDATE runtime.bubble_delivery_item SET evidence_age_days=4 WHERE turn_key='turn-ready'"));

        assertThrows(
                RuntimeException.class,
                () -> transactions.execute(status -> {
                    store.insertReceipt(receipt("room-a", "turn-broken", "BUBBLE_READY", now));
                    return null;
                }));
        assertEquals(0, scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key='turn-broken'"));
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-a", "turn-broken", "NO_MATCH", now));
            return null;
        });
        assertEquals(1, scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key='turn-broken'"));
    }

    @Test
    void crossTransactionChildInsertsCannotPoisonCommittedNoMatch() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T12:00:00Z");
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-attack", "turn-attack-ledger", "NO_MATCH", now));
            store.insertReceipt(receipt("room-attack", "turn-attack-item", "NO_MATCH", now));
            store.insertReceipt(receipt("room-attack", "turn-attack-both", "NO_MATCH", now));
            return null;
        });

        UUID memoryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        assertClosureViolation(() -> transactions.execute(status -> {
            store.insertLedgerEntry(
                    new BubbleRoomRevisionLedgerEntry("family-space", "room-attack", revisionId, "turn-attack-ledger", now));
            return null;
        }));
        assertClosureViolation(() -> transactions.execute(status -> {
            store.insertDeliveryItem(new BubbleDeliveryItem(
                    "family-space", "room-attack", "turn-attack-item", memoryId, revisionId, 1, 2, 0.70, "EVENT", 3));
            return null;
        }));
        assertClosureViolation(() -> transactions.execute(status -> {
            store.insertDeliveryItem(new BubbleDeliveryItem(
                    "family-space", "room-attack", "turn-attack-both", memoryId, revisionId, 1, 2, 0.70, "EVENT", 3));
            store.insertLedgerEntry(
                    new BubbleRoomRevisionLedgerEntry("family-space", "room-attack", revisionId, "turn-attack-both", now));
            return null;
        }));

        assertEquals(
                3,
                scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE room_key='room-attack'"));
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM runtime.bubble_delivery_item WHERE room_key='room-attack'"));
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM runtime.bubble_room_revision_ledger WHERE room_key='room-attack'"));

        UUID readyRevision = UUID.randomUUID();
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-attack", "turn-attack-ready", "BUBBLE_READY", now));
            store.insertDeliveryItem(new BubbleDeliveryItem(
                    "family-space", "room-attack", "turn-attack-ready", memoryId, readyRevision, 1, 2, 0.70, "EVENT", 3));
            store.insertLedgerEntry(
                    new BubbleRoomRevisionLedgerEntry("family-space", "room-attack", readyRevision, "turn-attack-ready", now));
            return null;
        });
        assertEquals(
                1,
                scalarInt("SELECT count(*) FROM runtime.bubble_delivery_item WHERE turn_key='turn-attack-ready'"));

        transactions.execute(status -> {
            store.purgeRoom("family-space", "room-attack");
            return null;
        });
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE room_key='room-attack'"));
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-attack", "turn-after-purge", "NO_MATCH", now));
            return null;
        });
        assertEquals(
                1,
                scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key='turn-after-purge'"));
    }

    private static void assertClosureViolation(Runnable attack) {
        RuntimeException exception = assertThrows(RuntimeException.class, attack::run);
        SQLException sql = null;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException) {
                sql = (SQLException) cause;
                break;
            }
        }
        assertTrue(sql != null, "expected SQLException in chain: " + exception);
        assertEquals("23514", sql.getSQLState());
        assertTrue(
                sql.getMessage().contains("HDM022_BUBBLE_RESULT_ITEM_CLOSURE_INVALID"),
                "unexpected message: " + sql.getMessage());
    }

    @Test
    void roomPurgeDeletesOnlyExactBubbleFactsAndIsIdempotent() {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-28T11:00:00Z");
        transactions.execute(status -> {
            store.insertReceipt(receipt("room-purge", "turn-purge", "NO_MATCH", now));
            store.insertReceipt(receipt("room-keep", "turn-keep", "NO_MATCH", now));
            return null;
        });

        transactions.execute(status -> {
            store.purgeRoom("family-space", "room-purge");
            return null;
        });
        transactions.execute(status -> {
            store.purgeRoom("family-space", "room-purge");
            return null;
        });

        assertEquals(0, scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE room_key='room-purge'"));
        assertEquals(1, scalarInt("SELECT count(*) FROM runtime.bubble_turn_receipt WHERE turn_key='turn-keep'"));
    }

    @Test
    void privilegesAreNarrowAndPublicHasNone() {
        for (String table : Arrays.asList(
                "runtime.bubble_turn_receipt", "runtime.bubble_delivery_item", "runtime.bubble_room_revision_ledger")) {
            String[] parts = table.split("\\.");
            assertEquals(
                    0,
                    scalarInt("SELECT count(*) FROM information_schema.role_table_grants "
                            + "WHERE grantee='PUBLIC' AND table_schema='"
                            + parts[0]
                            + "' AND table_name='"
                            + parts[1]
                            + "'"));
            assertTrue(bool("SELECT has_table_privilege('hide_nest_api', '" + table + "', 'SELECT')"));
            assertTrue(bool("SELECT has_table_privilege('hide_nest_api', '" + table + "', 'INSERT')"));
            assertFalse(bool("SELECT has_table_privilege('hide_nest_api', '" + table + "', 'UPDATE')"));
            assertFalse(bool("SELECT has_table_privilege('hide_nest_api', '" + table + "', 'DELETE')"));
        }
        assertTrue(bool(
                "SELECT has_function_privilege('hide_nest_api', 'runtime.purge_bubble_room(text,text)', 'EXECUTE')"));
        assertEquals(
                0,
                scalarInt("SELECT count(*) FROM information_schema.routine_privileges "
                        + "WHERE grantee='PUBLIC' AND specific_schema='runtime' "
                        + "AND routine_name='purge_bubble_room'"));
    }

    private static BubbleTurnReceipt receipt(String roomKey, String turnKey, String category, OffsetDateTime issuedAt) {
        byte[] requestHash = new byte[32];
        byte[] manifestHash = new byte[32];
        requestHash[0] = (byte) turnKey.hashCode();
        manifestHash[0] = (byte) category.hashCode();
        return new BubbleTurnReceipt(
                "family-space",
                roomKey,
                turnKey,
                requestHash,
                12,
                category,
                "BUBBLE_V1_R1",
                0.70,
                manifestHash,
                issuedAt);
    }

    private static int scalarInt(String sql) {
        return dsl.fetchOne(sql).get(0, Integer.class);
    }

    private static boolean bool(String sql) {
        return Boolean.TRUE.equals(dsl.fetchOne(sql).get(0, Boolean.class));
    }

    private static PostgreSQLContainer<?> container(String database, String containerPassword) {
        return new PostgreSQLContainer<>(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database)
                .withUsername(USER)
                .withPassword(containerPassword)
                .withStartupTimeout(Duration.ofSeconds(120));
    }

    private static Flyway flyway(PostgreSQLContainer<?> container, String target) {
        var configuration = Flyway.configure()
                .dataSource(container.getJdbcUrl(), USER, container.getPassword())
                .defaultSchema("public")
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .validateMigrationNaming(true);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static void createRoles(PostgreSQLContainer<?> container, String containerPassword) throws Exception {
        try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), USER, containerPassword)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
    }
}
