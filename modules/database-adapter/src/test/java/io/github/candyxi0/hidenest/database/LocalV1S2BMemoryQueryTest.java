package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1S1WindowCloseCoordinator;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BException;
import io.github.candyxi0.hidenest.application.coordinator.LocalV1S2BQueryCoordinator;
import io.github.candyxi0.hidenest.application.model.LocalV1S1ConfirmRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S1PrepareRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BListRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1S2BMemoryItem;
import io.github.candyxi0.hidenest.database.adapter.JooqEvidenceReferenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqDeletionFenceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryGovernanceAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqMemoryReadAdapter;
import io.github.candyxi0.hidenest.database.adapter.JooqRuntimeTransactionAdapter;
import io.github.candyxi0.hidenest.evidence.domain.PayloadHeadResult;
import io.github.candyxi0.hidenest.evidence.domain.PayloadPutResult;
import io.github.candyxi0.hidenest.evidence.domain.Source;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchor;
import io.github.candyxi0.hidenest.evidence.domain.SourceAnchorUnit;
import io.github.candyxi0.hidenest.evidence.domain.SourcePayload;
import io.github.candyxi0.hidenest.evidence.domain.SourceUnit;
import io.github.candyxi0.hidenest.evidence.port.EvidenceReferencePort;
import io.github.candyxi0.hidenest.evidence.port.PayloadStore;
import io.github.candyxi0.hidenest.memory.domain.ActorRef;
import io.github.candyxi0.hidenest.memory.domain.DeletionFence;
import io.github.candyxi0.hidenest.memory.domain.MemoryRecord;
import io.github.candyxi0.hidenest.memory.domain.MemoryRelation;
import io.github.candyxi0.hidenest.memory.domain.MemoryRevision;
import io.github.candyxi0.hidenest.memory.port.DeletionFencePort;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.memory.port.MemoryReadFilter;
import io.github.candyxi0.hidenest.memory.port.MemoryReadPort;
import io.github.candyxi0.hidenest.payload.LocalPayloadStore;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class LocalV1S2BMemoryQueryTest {

    private static final String IMAGE =
            "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String USER = "hide_nest_migrator";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> postgres;
    private static DSLContext dsl;
    private static EvidenceReferencePort evidence;
    private static MemoryGovernancePort governance;
    private static RuntimeTransactionPort runtime;
    private static PayloadStore payloadStore;
    private static LocalV1S1WindowCloseCoordinator s1;
    private static LocalV1S2BQueryCoordinator query;
    private static Path payloadRoot;

    @BeforeAll
    static void setUp() throws Exception {
        String password = UUID.randomUUID().toString();
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest")
                .withUsername(USER)
                .withPassword(password)
                .withStartupTimeout(Duration.ofSeconds(120));
        postgres.start();
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), USER, password)) {
            connection.createStatement().execute("CREATE ROLE hide_nest_api NOLOGIN");
            connection.createStatement().execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        assertEquals(22, Flyway.configure()
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
        runtime = new JooqRuntimeTransactionAdapter(dsl);
        evidence = new JooqEvidenceReferenceAdapter(dsl);
        TransactionExecutor executor = new io.github.candyxi0.hidenest.database.adapter.SpringTransactionExecutor(tx);
        var publisher = new io.github.candyxi0.hidenest.application.coordinator.CanonicalPublishCoordinator(
                governance, runtime, executor, CLOCK);
        payloadRoot = Files.createTempDirectory("s2b-payload-test-");
        payloadStore = new LocalPayloadStore(payloadRoot);
        s1 = new LocalV1S1WindowCloseCoordinator(
                evidence, governance, runtime, executor, publisher, payloadStore, CLOCK);
        JooqDeletionFenceAdapter deletionFence = new JooqDeletionFenceAdapter(dsl);
        query = new LocalV1S2BQueryCoordinator(
                new JooqMemoryReadAdapter(dsl), evidence, payloadStore, deletionFence);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (postgres != null) {
            postgres.stop();
        }
        if (payloadRoot != null) {
            try (var paths = Files.walk(payloadRoot)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    void listDetailAndFullEvidenceFollowCurrentRevision() {
        Fixture fixture = createMemory("s2b-basic", "当前正式记忆正文");

        var list = query.listMemories(new LocalV1S2BListRequest("ACTIVE", "正式记忆", null, 10, null, null));
        var item = list.items().stream()
                .filter(candidate -> fixture.memoryId().equals(candidate.memoryId()))
                .findFirst()
                .orElseThrow();
        assertEquals(1L, item.revisionNo());
        assertEquals("Interpretation", item.memoryType());
        assertEquals(2, item.evidenceCount());

        var detail = query.getMemoryDetail(fixture.memoryId());
        assertEquals("当前正式记忆正文", detail.bodyText());
        assertEquals(fixture.revisionId(), findCurrentRevision(fixture.memoryId()));
        assertEquals(2, detail.evidenceCount());

        var full = query.getFullEvidence(fixture.memoryId());
        assertEquals(List.of(fixture.unitOne(), fixture.unitTwo()),
                full.messages().stream().map(message -> message.sourceUnitId()).toList());
        assertEquals(List.of(1L, 2L), full.messages().stream().map(message -> message.ordinal()).toList());
        assertEquals(List.of("证据一-s2b-basic", "证据二-s2b-basic"),
                full.messages().stream().map(message -> message.bodyText()).toList());
        assertEquals(fixture.actorOne(), full.messages().get(0).actorId());
        assertEquals("SYNTHETIC", full.messages().get(0).actorKind());
        assertEquals("a-" + fixture.actorOne(), full.messages().get(0).actorStableRef());
    }

    @Test
    void currentPolicyRevisionComesFromDatabaseNotRevisionNo() {
        Fixture fixture = createMemory("s2b-policy-rev", "策略版本来自数据库");
        bumpPolicyRevision(fixture, 7L);

        var detail = query.getMemoryDetail(fixture.memoryId());
        assertEquals(7L, detail.currentPolicyRevisionNo());
        assertEquals(1L, detail.revisionNo());
    }

    @Test
    void stateKeywordLiteralPagingAndSortingAreBounded() {
        Fixture first = createMemory("s2b-page-a", "分页命中 A");
        Fixture second = createMemory("s2b-page-b", "分页命中 B");
        Fixture third = createMemory("s2b-page-c", "分页命中 C");
        updateTime(first.memoryId(), "2026-08-09T00:00:01Z");
        updateTime(second.memoryId(), "2026-08-09T00:00:02Z");
        updateTime(third.memoryId(), "2026-08-09T00:00:03Z");
        archive(third);

        var page = query.listMemories(new LocalV1S2BListRequest("ALL", "分页命中", null, 2, null, null));
        assertEquals(List.of(third.memoryId(), second.memoryId()),
                page.items().stream().map(item -> item.memoryId()).toList());
        assertEquals(List.of(first.memoryId()),
                query.listMemories(new LocalV1S2BListRequest("ACTIVE", "分页命中", null, 50, null, null))
                        .items().stream().map(item -> item.memoryId()).filter(first.memoryId()::equals).toList());
        assertEquals(List.of(third.memoryId()),
                query.listMemories(new LocalV1S2BListRequest("ARCHIVED", "分页命中", null, 50, null, null))
                        .items().stream().map(item -> item.memoryId()).filter(third.memoryId()::equals).toList());
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("BAD", "x", null, 1, null, null)));
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("ALL", "%", null, 0, null, null)));
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest(
                        "ALL", "x", null, 1, OffsetDateTime.parse("2026-08-09T00:00:00Z"), null)));
        assertTrue(query.listMemories(new LocalV1S2BListRequest("ALL", "%_\\", null, 50, null, null))
                .items().stream().noneMatch(item -> item.memoryId().equals(first.memoryId())));
    }

    @Test
    void sixTypesReturnExactlyTheirOwnRowAndAllTypesReturnAll() {
        List<String> persistenceTypes = List.of(
                "Event", "Claim", "Quote", "Interpretation", "Calibration", "Principle");
        Map<String, Fixture> byType = new LinkedHashMap<>();
        for (String type : persistenceTypes) {
            byType.put(type, createMemoryOfType("type-" + type, "类型命中-" + type, type));
        }
        for (String type : persistenceTypes) {
            var matches = query.listMemories(new LocalV1S2BListRequest(
                            "ALL", "类型命中", type.toUpperCase(java.util.Locale.ROOT), 50, null, null))
                    .items().stream()
                    .filter(i -> byType.values().stream().anyMatch(f -> f.memoryId().equals(i.memoryId())))
                    .toList();
            assertEquals(1, matches.size(), "expected exactly one " + type);
            assertEquals(byType.get(type).memoryId(), matches.get(0).memoryId());
        }
        var all = query.listMemories(new LocalV1S2BListRequest("ALL", "类型命中", null, 50, null, null));
        assertEquals(6, all.items().size());
    }

    @Test
    void stateKeywordAndTypeIntersectOnCurrentRevision() {
        Fixture event = createMemoryOfType("inter-ev", "类型交集事件", "Event");
        Fixture claim = createMemoryOfType("inter-cl", "类型交集判断", "Claim");

        var events = query.listMemories(new LocalV1S2BListRequest("ACTIVE", "交集", "EVENT", 50, null, null))
                .items().stream().map(i -> i.memoryId()).toList();
        assertEquals(List.of(event.memoryId()), events);
        var claims = query.listMemories(new LocalV1S2BListRequest("ACTIVE", "交集", "CLAIM", 50, null, null))
                .items().stream().map(i -> i.memoryId()).toList();
        assertEquals(List.of(claim.memoryId()), claims);
    }

    @Test
    void typeFilterBindsToCurrentRevisionTypeOnly() {
        Fixture claim = createMemoryOfType("rev-type", "版本类型变化", "Claim");
        // The type predicate is judged against MEMORY_RECORD.CURRENT_REVISION_ID's memory_type, so a
        // memory is only ever matched by the type of its current revision (here 'Claim'), never by any
        // other formal type.
        for (String other : List.of("Event", "Quote", "Interpretation", "Calibration", "Principle")) {
            assertTrue(query.listMemories(new LocalV1S2BListRequest(
                            "ALL", null, other.toUpperCase(java.util.Locale.ROOT), 50, null, null))
                    .items().stream().noneMatch(i -> i.memoryId().equals(claim.memoryId())),
                    other + " must not match a Claim current revision");
        }
        assertEquals(1, query.listMemories(new LocalV1S2BListRequest("ALL", null, "CLAIM", 50, null, null))
                .items().stream().filter(i -> i.memoryId().equals(claim.memoryId())).count());
    }

    // ─── R1-02 最终 current-revision 复核（TOCTOU 封口） ───────────────────────────

    private static UUID spyMemoryId = UUID.randomUUID();
    private static UUID spyListRevId = UUID.randomUUID();

    private static MemoryRecord spyListRecord(String state, OffsetDateTime updatedAt) {
        return new MemoryRecord(
                spyMemoryId, state, spyListRevId, UUID.randomUUID(), 1L, updatedAt, updatedAt);
    }

    private static MemoryRevision spyRevision(String memoryType, String bodyText) {
        return new MemoryRevision(
                spyListRevId, spyMemoryId, 1L, memoryType, UUID.randomUUID(), bodyText,
                OffsetDateTime.now(CLOCK), null, null, UUID.randomUUID(), OffsetDateTime.now(CLOCK));
    }

    @Test
    void finalRecheckSkipsItemWhoseCurrentRevisionTypeNoLongerMatches() {
        OffsetDateTime t = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        MemoryReadSpy spy = new MemoryReadSpy(
                spyListRecord("ACTIVE", t), spyListRecord("ACTIVE", t), spyRevision("Event", "Claim 正文"));
        // The DB list had matched a Claim current revision, but by assembly time the current
        // revision reads as Event: a CLAIM request must not return Event content.
        var result = spy.coordinator().listMemories(
                new LocalV1S2BListRequest("ALL", null, "CLAIM", 50, null, null));
        assertEquals(0, result.items().size());
        assertEquals(0, spy.relationReads, "non-matching item must not read evidence");
    }

    @Test
    void finalRecheckSkipsItemWhenCurrentRevisionTypeChangesAfterList() {
        OffsetDateTime t = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        // TOCTOU spy: the DB list snapshot had matched a Claim current revision, but by assembly time
        // the current revision reads as Event, so a CLAIM request must skip it and never return Event
        // content. (This is a narrow port spy; the real historical/current revision facts are covered
        // by the dedicated database test below.)
        MemoryReadSpy spy = new MemoryReadSpy(
                spyListRecord("ACTIVE", t), spyListRecord("ACTIVE", t), spyRevision("Event", "当前判断正文"));
        var eventHit = spy.coordinator().listMemories(
                new LocalV1S2BListRequest("ALL", null, "CLAIM", 50, null, null));
        assertEquals(0, eventHit.items().size());
        assertEquals(0, spy.relationReads);
    }

    @Test
    void finalRecheckSkipsItemWhoseStateOrKeywordChanged() {
        OffsetDateTime t = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        // state changed ACTIVE -> ARCHIVED
        MemoryReadSpy stateSpy = new MemoryReadSpy(
                spyListRecord("ACTIVE", t), spyListRecord("ARCHIVED", t), spyRevision("Claim", "含关键字正文"));
        var stateResult = stateSpy.coordinator().listMemories(
                new LocalV1S2BListRequest("ACTIVE", null, "CLAIM", 50, null, null));
        assertEquals(0, stateResult.items().size());
        assertEquals(0, stateSpy.relationReads);

        // keyword no longer present in the fresh current revision body
        MemoryReadSpy kwSpy = new MemoryReadSpy(
                spyListRecord("ACTIVE", t), spyListRecord("ACTIVE", t), spyRevision("Claim", "正文不含目标词"));
        var kwResult = kwSpy.coordinator().listMemories(
                new LocalV1S2BListRequest("ACTIVE", "关键字", "CLAIM", 50, null, null));
        assertEquals(0, kwResult.items().size());
        assertEquals(0, kwSpy.relationReads);
    }

    @Test
    void finalRecheckSkipsItemWhoseOrderingFactsChanged() {
        OffsetDateTime listT = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        OffsetDateTime freshT = OffsetDateTime.parse("2026-08-16T00:00:00Z");
        MemoryReadSpy spy = new MemoryReadSpy(
                spyListRecord("ACTIVE", listT), spyListRecord("ACTIVE", freshT), spyRevision("Claim", "含关键字正文"));
        var result = spy.coordinator().listMemories(
                new LocalV1S2BListRequest("ACTIVE", "关键字", "CLAIM", 50, null, null));
        assertEquals(0, result.items().size());
        assertEquals(0, spy.relationReads);
    }

    @Test
    void finalRecheckKeepsUnchangedLegalChainByteForByte() {
        OffsetDateTime t = OffsetDateTime.parse("2026-08-15T00:00:00Z");
        MemoryReadSpy spy = new MemoryReadSpy(
                spyListRecord("ACTIVE", t), spyListRecord("ACTIVE", t), spyRevision("Claim", "含关键字正文"));
        var result = spy.coordinator().listMemories(
                new LocalV1S2BListRequest("ACTIVE", "关键字", "CLAIM", 50, null, null));
        assertEquals(1, result.items().size());
        assertEquals("Claim", result.items().get(0).memoryType());
        assertEquals("含关键字正文", result.items().get(0).bodyText());
        assertTrue(spy.relationReads >= 1, "matching item still reads evidence");
    }

    @Test
    void historicalEventRevisionAndCurrentClaimBindToCurrentRevision() throws Exception {
        // The memory genuinely has two revisions: an earlier Event revision and a current Claim
        // revision, with memory_record.current_revision_id pointing at the Claim revision.
        Fixture fixture = createMemoryOfType("hist-ev", "历史事件正文", "Event");
        UUID claimRevisionId = UUID.randomUUID();
        makeCurrentRevision(fixture, claimRevisionId, "Claim", "当前判断正文");
        assertEquals(claimRevisionId, findCurrentRevision(fixture.memoryId()));

        Map<String, Long> databaseBefore = databaseRowSnapshot();
        Map<String, FileFingerprint> filesBefore = payloadFileSnapshot();

        // EVENT must not match: the type predicate binds to the CURRENT (Claim) revision, not the
        // historical Event revision. A broken implementation that matches any revision of the memory
        // by memory_id would wrongly return it here.
        assertTrue(query.listMemories(new LocalV1S2BListRequest("ALL", null, "EVENT", 50, null, null))
                .items().stream().noneMatch(i -> i.memoryId().equals(fixture.memoryId())));
        // CLAIM matches exactly once, returning the current Claim revision's content.
        var claimMatches = query.listMemories(new LocalV1S2BListRequest("ALL", null, "CLAIM", 50, null, null))
                .items().stream().filter(i -> i.memoryId().equals(fixture.memoryId())).toList();
        assertEquals(1, claimMatches.size());
        assertEquals(claimRevisionId, claimMatches.get(0).currentRevisionId());
        assertEquals("当前判断正文", claimMatches.get(0).bodyText());

        // Direct DB-adapter binding: the adapter must filter by current_revision_id, so EVENT returns
        // nothing and CLAIM returns the memory, independent of the coordinator recheck backstop.
        JooqMemoryReadAdapter adapter = new JooqMemoryReadAdapter(dsl);
        assertTrue(adapter.listCurrentMemoryRecords(
                        new MemoryReadFilter("ALL", "", "Event", 50, null, null))
                .stream().noneMatch(r -> r.memoryId().equals(fixture.memoryId())));
        assertEquals(1, adapter.listCurrentMemoryRecords(
                        new MemoryReadFilter("ALL", "", "Claim", 50, null, null))
                .stream().filter(r -> r.memoryId().equals(fixture.memoryId())).count());

        assertEquals(databaseBefore, databaseRowSnapshot());
        assertEquals(filesBefore, payloadFileSnapshot());
    }

    @Test
    void sameUpdatedAtPagesAreStableWithoutDuplicatesOrSkips() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 35; i++) {
            Fixture fixture = createMemory("same-ts-" + i, "同时间戳记忆 " + i);
            updateTime(fixture.memoryId(), "2026-08-10T00:00:00Z");
            fixtures.add(fixture);
        }
        var page1 = query.listMemories(new LocalV1S2BListRequest("ALL", "同时间戳", null, 20, null, null));
        assertEquals(20, page1.items().size());
        LocalV1S2BMemoryItem last = page1.items().get(page1.items().size() - 1);
        var page2 = query.listMemories(new LocalV1S2BListRequest(
                "ALL", "同时间戳", null, 20, last.updatedAt(), last.memoryId()));
        assertEquals(15, page2.items().size());

        Set<UUID> seen = new HashSet<>();
        page1.items().forEach(i -> assertTrue(seen.add(i.memoryId())));
        page2.items().forEach(i -> assertTrue(seen.add(i.memoryId())));
        assertEquals(35, seen.size());

        // Every loaded row must share the exact same timestamp, so the same-timestamp ordering
        // (memory_id ASC) fully determines page2. This is the seek-stability requirement.
        Set<OffsetDateTime> timestamps = new HashSet<>();
        page1.items().forEach(i -> timestamps.add(i.updatedAt()));
        page2.items().forEach(i -> timestamps.add(i.updatedAt()));
        assertEquals(Set.of(last.updatedAt()), timestamps);

        List<UUID> ids = page2.items().stream().map(i -> i.memoryId()).toList();
        // PostgreSQL orders UUIDs by their unsigned 128-bit byte value, which is exactly the
        // lexicographic order of the canonical lowercase hex string; Java's UUID.compareTo uses
        // signed longs and disagrees at the 0x80 high-bit boundary. Compare by the canonical string.
        for (int i = 1; i < ids.size(); i++) {
            assertTrue(ids.get(i - 1).toString().compareTo(ids.get(i).toString()) < 0,
                    "page2 must stay UUID ASC");
        }
    }

    @Test
    void insertAfterFirstPageDoesNotDuplicateOnNextPage() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Fixture fixture = createMemory("ins-" + i, "插入回归 " + i);
            updateTime(fixture.memoryId(), "2026-08-11T00:00:00Z");
            fixtures.add(fixture);
        }
        var page1 = query.listMemories(new LocalV1S2BListRequest("ALL", "插入回归", null, 20, null, null));
        assertEquals(20, page1.items().size());
        LocalV1S2BMemoryItem last = page1.items().get(page1.items().size() - 1);

        // A brand-new memory with a newer updated_at sorts at the top in DESC order.
        Fixture fresh = createMemory("ins-fresh", "插入回归新记忆");
        updateTime(fresh.memoryId(), "2026-08-12T00:00:00Z");

        var page2 = query.listMemories(new LocalV1S2BListRequest(
                "ALL", "插入回归", null, 20, last.updatedAt(), last.memoryId()));
        Set<UUID> seen = new HashSet<>();
        page1.items().forEach(i -> seen.add(i.memoryId()));
        page2.items().forEach(i -> seen.add(i.memoryId()));
        assertFalse(seen.contains(fresh.memoryId()));
        assertEquals(30, seen.size());
    }

    @Test
    void deleteSeenRowThenNextPageDoesNotSkip() {
        List<Fixture> fixtures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Fixture fixture = createMemory("del-" + i, "删除回归 " + i);
            updateTime(fixture.memoryId(), "2026-08-13T00:00:00Z");
            fixtures.add(fixture);
        }
        var page1 = query.listMemories(new LocalV1S2BListRequest("ALL", "删除回归", null, 20, null, null));
        assertEquals(20, page1.items().size());
        LocalV1S2BMemoryItem last = page1.items().get(page1.items().size() - 1);
        UUID deletedId = page1.items().get(0).memoryId();
        insertDeletionFence(fixtures.stream()
                .filter(f -> f.memoryId().equals(deletedId)).findFirst().orElseThrow());

        var page2 = query.listMemories(new LocalV1S2BListRequest(
                "ALL", "删除回归", null, 20, last.updatedAt(), last.memoryId()));
        // The fenced row must never appear again, and page2 must not skip the item that originally
        // followed `last` (content-based seek, not offset). Collecting only the still-alive rows
        // across both pages must yield exactly the 29 non-fenced memories, each exactly once.
        Set<UUID> seen = new HashSet<>();
        for (var item : page1.items()) {
            if (!item.memoryId().equals(deletedId)) {
                assertTrue(seen.add(item.memoryId()));
            }
        }
        for (var item : page2.items()) {
            assertTrue(seen.add(item.memoryId()));
            assertNotEquals(deletedId, item.memoryId());
        }
        assertEquals(29, seen.size());
        assertTrue(query.listMemories(new LocalV1S2BListRequest("ALL", "删除回归", null, 50, null, null))
                .items().stream().noneMatch(i -> i.memoryId().equals(deletedId)));
    }

    @Test
    void invalidSeekAndTypeAndLimitAreRejectedBeforeSql() {
        Fixture fixture = createMemory("reject-seek", "拒绝检测");
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("ALL", null, "EVENT", 0, null, null)));
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("ALL", null, "EVENT", 51, null, null)));
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("ALL", null, "bogus-type", 10, null, null)));
        String longKeyword = "长".repeat(101);
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest("ALL", longKeyword, null, 10, null, null)));
        assertThrows(LocalV1S2BException.class,
                () -> query.listMemories(new LocalV1S2BListRequest(
                        "ALL", null, null, 10, OffsetDateTime.now(), null)));
    }

    @Test
    void previewIsCodePointSafeAndEvidenceIsNotSearchable() {
        String longBody = "s2b-preview " + "好".repeat(107) + "😀" + "尾部";
        Fixture fixture = createMemory("s2b-preview", longBody);
        var item = query.listMemories(new LocalV1S2BListRequest("ACTIVE", "s2b-preview", null, 50, null, null))
                .items().stream().filter(i -> i.memoryId().equals(fixture.memoryId())).findFirst().orElseThrow();
        assertEquals(120, item.preview().codePointCount(0, item.preview().length()));
        assertFalse(item.preview().contains("尾部"));
        assertFalse(item.preview().endsWith("\uD83D"));
        assertTrue(query.listMemories(new LocalV1S2BListRequest("ALL", "证据一-s2b-preview", null, 50, null, null))
                .items().stream().noneMatch(i -> i.memoryId().equals(fixture.memoryId())));
    }

    @Test
    void missingFileAndHashTamperFailClosed() throws Exception {
        Fixture missing = createMemory("s2b-missing-file", "缺文件测试");
        SourcePayload missingPayload = payloadFor(missing.unitOne());
        Files.delete(payloadRoot.resolve(missingPayload.objectRef()));
        assertCode(LocalV1S2BException.Code.PAYLOAD_UNAVAILABLE,
                () -> query.getFullEvidence(missing.memoryId()));

        Fixture tampered = createMemory("s2b-hash-tamper", "hash 篡改测试");
        SourcePayload payload = payloadFor(tampered.unitOne());
        byte[] wrongHash = payload.contentHash().clone();
        wrongHash[0] ^= 1;
        dsl.execute("UPDATE evidence.source_payload SET content_hash=? WHERE payload_id=?",
                wrongHash, payload.payloadId());
        assertCode(LocalV1S2BException.Code.PAYLOAD_UNAVAILABLE,
                () -> query.getFullEvidence(tampered.memoryId()));
    }

    @Test
    void duplicatePayloadAndInvalidUtf8FailClosed() throws Exception {
        Fixture duplicate = createMemory("s2b-multiple-payload", "多 payload 测试");
        SourcePayload original = payloadFor(duplicate.unitOne());
        dsl.execute("INSERT INTO evidence.source_payload "
                        + "(payload_id,source_unit_id,payload_kind,store_adapter,object_ref,content_type,size_bytes,content_hash,"
                        + "policy_id,current_policy_revision_no,retention_class,created_at) "
                        + "SELECT ?,source_unit_id,payload_kind,store_adapter,object_ref,content_type,size_bytes,content_hash,"
                        + "policy_id,current_policy_revision_no,retention_class,created_at "
                        + "FROM evidence.source_payload WHERE payload_id=?",
                UUID.randomUUID(), original.payloadId());
        assertCode(LocalV1S2BException.Code.PAYLOAD_INVALID,
                () -> query.getFullEvidence(duplicate.memoryId()));

        Fixture utf8 = createMemory("s2b-invalid-utf8", "非法 UTF8 测试");
        SourcePayload payload = payloadFor(utf8.unitOne());
        Path file = payloadRoot.resolve(payload.objectRef());
        byte[] fileContent = Files.readAllBytes(file);
        int contentTypeLength = ((fileContent[0] & 0xFF) << 8) | (fileContent[1] & 0xFF);
        byte[] invalid = new byte[2 + contentTypeLength + 1];
        System.arraycopy(fileContent, 0, invalid, 0, 2 + contentTypeLength);
        invalid[invalid.length - 1] = (byte) 0xC3;
        Files.write(file, invalid);
        dsl.execute("UPDATE evidence.source_payload SET size_bytes=?, content_hash=? WHERE payload_id=?",
                1L, sha256(new byte[] {(byte) 0xC3}), payload.payloadId());
        assertCode(LocalV1S2BException.Code.UTF8_INVALID,
                () -> query.getFullEvidence(utf8.memoryId()));
    }

    @Test
    void noEvidenceIsAValidEmptyResultAndMissingMemoryIsNotFound() {
        Fixture fixture = createMemoryWithoutEvidence("s2b-empty", "无证据记忆");
        assertTrue(query.getFullEvidence(fixture.memoryId()).messages().isEmpty());
        assertCode(LocalV1S2BException.Code.NOT_FOUND,
                () -> query.getMemoryDetail(UUID.randomUUID()));
    }

    @Test
    void damagedCurrentPointerAndOwnerBindingFailClosed() {
        UUID memoryId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        MemoryRevision validRevision = stubRevision(revisionId, memoryId);

        MemoryReadStub pointerMismatch = new MemoryReadStub(
                stubRecord(memoryId, UUID.randomUUID()), validRevision, List.of(), Map.of());
        assertCode(LocalV1S2BException.Code.OWNER_BINDING_INVALID,
                () -> pointerMismatch.coordinator().getMemoryDetail(memoryId));

        MemoryReadStub ownerMismatch = new MemoryReadStub(
                stubRecord(memoryId, revisionId),
                stubRevision(revisionId, UUID.randomUUID()),
                List.of(), Map.of());
        assertCode(LocalV1S2BException.Code.OWNER_BINDING_INVALID,
                () -> ownerMismatch.coordinator().getMemoryDetail(memoryId));

        MemoryReadStub missingRevision = new MemoryReadStub(
                stubRecord(memoryId, revisionId), null, List.of(), Map.of());
        assertCode(LocalV1S2BException.Code.CURRENT_POINTER_INVALID,
                () -> missingRevision.coordinator().getMemoryDetail(memoryId));
    }

    @Test
    void evidenceBindingAttacksFailClosedWithStableClasses() {
        StubEvidenceScenario crossSource = new StubEvidenceScenario(1, 1);
        UUID unitId = crossSource.units.keySet().iterator().next();
        SourceUnit unit = crossSource.units.get(unitId);
        crossSource.evidence.units.put(unitId, new SourceUnit(
                unit.sourceUnitId(), UUID.randomUUID(), unit.externalUnitRef(), unit.sourceVersion(),
                unit.ordinal(), unit.actorId(), unit.occurredAt(), unit.createdAt()));
        assertCode(LocalV1S2BException.Code.SOURCE_UNIT_INVALID,
                () -> crossSource.coordinator().getFullEvidence(crossSource.memoryId));

        StubEvidenceScenario missingActor = new StubEvidenceScenario(1, 1);
        UUID missingActorUnitId = missingActor.units.keySet().iterator().next();
        SourceUnit actorless = missingActor.units.get(missingActorUnitId);
        missingActor.evidence.units.put(missingActorUnitId, new SourceUnit(
                actorless.sourceUnitId(), actorless.sourceId(), actorless.externalUnitRef(),
                actorless.sourceVersion(), actorless.ordinal(), null, actorless.occurredAt(),
                actorless.createdAt()));
        assertCode(LocalV1S2BException.Code.ACTOR_INVALID,
                () -> missingActor.coordinator().getFullEvidence(missingActor.memoryId));

        StubEvidenceScenario unknownActor = new StubEvidenceScenario(1, 1);
        unknownActor.actors.clear();
        assertCode(LocalV1S2BException.Code.ACTOR_INVALID,
                () -> unknownActor.coordinator().getFullEvidence(unknownActor.memoryId));

        StubEvidenceScenario duplicateAnchor = new StubEvidenceScenario(1, 1);
        MemoryRelation relation = duplicateAnchor.relations.get(0);
        duplicateAnchor.memory.relations = List.of(
                relation,
                new MemoryRelation(
                        UUID.randomUUID(), relation.fromRevisionId(), relation.relationType(),
                        relation.toRevisionId(), relation.toAnchorId(), relation.perspectiveActorId(),
                        relation.createdByDecisionId(), relation.createdAt().plusSeconds(1)));
        assertCode(LocalV1S2BException.Code.EVIDENCE_RELATION_INVALID,
                () -> duplicateAnchor.coordinator().getFullEvidence(duplicateAnchor.memoryId));
    }

    @Test
    void evidenceMessageAndByteLimitsAreEnforcedByFormalCoordinator() {
        StubEvidenceScenario oneHundred = new StubEvidenceScenario(100, 1);
        assertEquals(100, oneHundred.coordinator().getFullEvidence(oneHundred.memoryId).messages().size());

        StubEvidenceScenario oneHundredOne = new StubEvidenceScenario(101, 1);
        assertCode(LocalV1S2BException.Code.EVIDENCE_LIMIT_EXCEEDED,
                () -> oneHundredOne.coordinator().getFullEvidence(oneHundredOne.memoryId));

        StubEvidenceScenario exactBytes = new StubEvidenceScenario(1, 4 * 1024 * 1024);
        assertEquals(1, exactBytes.coordinator().getFullEvidence(exactBytes.memoryId).messages().size());

        StubEvidenceScenario overBytes = new StubEvidenceScenario(1, 4 * 1024 * 1024 + 1);
        assertCode(LocalV1S2BException.Code.PAYLOAD_INVALID,
                () -> overBytes.coordinator().getFullEvidence(overBytes.memoryId));
    }

    @Test
    void formalReadPathPreservesDatabaseRowsAndPayloadFileSet() throws Exception {
        Fixture fixture = createMemory("s2b-r1-readonly", "R1只读快照记忆");
        Map<String, Long> databaseBefore = databaseRowSnapshot();
        Map<String, FileFingerprint> filesBefore = payloadFileSnapshot();

        query.listMemories(new LocalV1S2BListRequest("ACTIVE", "s2b-r1-readonly", null, 10, null, null));
        query.getMemoryDetail(fixture.memoryId());
        query.getFullEvidence(fixture.memoryId());

        assertEquals(databaseBefore, databaseRowSnapshot());
        assertEquals(filesBefore, payloadFileSnapshot());
    }

    private Map<String, Long> databaseRowSnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        var tableRows = dsl.fetch("SELECT table_schema, table_name FROM information_schema.tables "
                + "WHERE table_schema IN ('memory','evidence','runtime','security') "
                + "AND table_type='BASE TABLE' ORDER BY table_schema, table_name");
        for (var row : tableRows) {
            String schema = row.get("table_schema", String.class);
            String table = row.get("table_name", String.class);
            Long count = dsl.selectCount().from(DSL.table(DSL.name(schema, table)))
                    .fetchOne(0, Long.class);
            result.put(schema + "." + table, count);
        }
        return result;
    }

    private Map<String, FileFingerprint> payloadFileSnapshot() throws Exception {
        try (var paths = Files.walk(payloadRoot)) {
            return paths.filter(Files::isRegularFile)
                    .sorted()
                    .collect(Collectors.toMap(
                            path -> payloadRoot.relativize(path).toString(),
                            path -> {
                                try {
                                    return new FileFingerprint(
                                            Files.size(path), Arrays.toString(sha256(Files.readAllBytes(path))));
                                } catch (Exception ex) {
                                    throw new RuntimeException(ex);
                                }
                            },
                            (left, right) -> left,
                            LinkedHashMap::new));
        }
    }

    private Fixture createMemory(String marker, String memoryText) {
        return createMemoryOfType(marker, memoryText, "Interpretation");
    }

    private Fixture createMemoryOfType(String marker, String memoryText, String memoryType) {
        UUID actorOne = UUID.randomUUID();
        UUID perspective = actorOne;
        UUID actorTwo = UUID.randomUUID();
        UUID unitOne = UUID.randomUUID();
        UUID unitTwo = UUID.randomUUID();
        UUID anchorOne = UUID.randomUUID();
        UUID anchorTwo = UUID.randomUUID();
        String key = "s2b-" + marker + "-" + UUID.randomUUID();
        var prepared = s1.prepare(new LocalV1S1PrepareRequest(
                key, sha256(key.getBytes(StandardCharsets.UTF_8)), perspective, memoryType,
                memoryText, sha256(memoryText.getBytes(StandardCharsets.UTF_8)),
                List.of(
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitOne, actorOne, 1L, "unit-one-" + marker, OffsetDateTime.now(CLOCK),
                                "证据一-" + marker),
                        new LocalV1S1PrepareRequest.EvidenceMessage(
                                unitTwo, actorTwo, 2L, "unit-two-" + marker, OffsetDateTime.now(CLOCK),
                                "证据二-" + marker)),
                List.of(
                        new LocalV1S1PrepareRequest.AnchorInput(anchorOne, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitOne, 0L, 1L, 1L))),
                        new LocalV1S1PrepareRequest.AnchorInput(anchorTwo, List.of(
                                new LocalV1S1PrepareRequest.AnchorInput.AnchorUnitRef(unitTwo, 0L, 1L, 2L))))));
        UUID memoryId = UUID.randomUUID();
        String confirmKey = "confirm-" + key;
        var confirmed = s1.confirm(new LocalV1S1ConfirmRequest(
                confirmKey, sha256(confirmKey.getBytes(StandardCharsets.UTF_8)),
                prepared.proposalRevisionId(), prepared.reviewSessionId(), memoryId,
                UUID.randomUUID(), new byte[32]));
        dsl.execute("UPDATE memory.memory_relation SET created_at=CAST(? AS timestamptz) WHERE to_anchor_id=?",
                "2026-08-09T00:00:01Z", anchorOne);
        dsl.execute("UPDATE memory.memory_relation SET created_at=CAST(? AS timestamptz) WHERE to_anchor_id=?",
                "2026-08-09T00:00:02Z", anchorTwo);
        return new Fixture(memoryId, confirmed.currentRevisionId(), actorOne, actorTwo,
                unitOne, unitTwo, anchorOne, anchorTwo);
    }

    private Fixture createMemoryWithoutEvidence(String marker, String memoryText) {
        Fixture fixture = createMemory(marker, memoryText);
        dsl.execute("DELETE FROM memory.memory_relation WHERE from_revision_id=?", fixture.revisionId());
        return fixture;
    }

    private SourcePayload payloadFor(UUID sourceUnitId) {
        return evidence.findSourcePayloadsBySourceUnitId(sourceUnitId).stream().findFirst().orElseThrow();
    }

    private UUID findCurrentRevision(UUID memoryId) {
        var row = dsl.selectFrom(
                        io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD)
                .where(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD.MEMORY_ID
                        .eq(memoryId))
                .fetchOne();
        return row.getCurrentRevisionId();
    }

    private void updateTime(UUID memoryId, String timestamp) {
        dsl.execute("UPDATE memory.memory_record SET updated_at=CAST(? AS timestamptz) WHERE memory_id=?",
                timestamp, memoryId);
    }

    /**
     * Test-only fixture: adds a newer revision of a different type and repoints the current pointer to
     * it, so a memory genuinely has both a historical revision and a current revision. The revision
     * governance / current-revision update guards are temporarily disabled and always restored; no
     * production migration or trigger is modified. This must be able to kill an implementation that
     * matches any revision by memory_id instead of binding to current_revision_id.
     */
    private void makeCurrentRevision(Fixture fixture, UUID newRevisionId, String newType, String newBody) {
        UUID decisionId = UUID.randomUUID();
        dsl.execute("ALTER TABLE memory.memory_revision DISABLE TRIGGER memory_revision_governance_guard");
        dsl.execute("ALTER TABLE memory.memory_record DISABLE TRIGGER memory_record_update_guard");
        try {
            dsl.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,"
                            + "target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                            + "VALUES (?::uuid,'USER_ARCHIVE',?::uuid,'USER','MEMORY',?::uuid,2,'s2b-test',"
                            + "?::text,clock_timestamp())",
                    decisionId, fixture.actorOne(), fixture.memoryId(), "s2b-rev2-" + UUID.randomUUID());
            dsl.execute("INSERT INTO memory.memory_revision(memory_revision_id,memory_id,revision_no,memory_type,"
                            + "perspective_actor_id,body_text,created_by_decision_id,created_at) "
                            + "VALUES (?::uuid,?::uuid,2,?::text,?::uuid,?::text,?::uuid,clock_timestamp())",
                    newRevisionId, fixture.memoryId(), newType, fixture.actorOne(), newBody, decisionId);
            dsl.execute("UPDATE memory.memory_record SET current_revision_id=?, updated_at=clock_timestamp() "
                            + "WHERE memory_id=?",
                    newRevisionId, fixture.memoryId());
        } finally {
            dsl.execute("ALTER TABLE memory.memory_record ENABLE TRIGGER memory_record_update_guard");
            dsl.execute("ALTER TABLE memory.memory_revision ENABLE TRIGGER memory_revision_governance_guard");
        }
    }

    private void archive(Fixture fixture) {
        UUID decisionId = UUID.randomUUID();
        UUID changeId = UUID.randomUUID();
        String manifest = "{\"aggregateId\":\"" + fixture.memoryId()
                + "\",\"aggregateRevision\":1,\"policyRevision\":0,\"purpose\":\"S2B_TEST\","
                + "\"manifestHash\":\"" + "00".repeat(32) + "\"}";
        dsl.execute(("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,"
                        + "target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                        + "('%s','USER_ARCHIVE','%s','USER','MEMORY','%s',1,'s2b-test','%s',clock_timestamp())")
                .formatted(decisionId, fixture.actorOne(), fixture.memoryId(), "s2b-archive-" + decisionId));
        dsl.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES ('%s','memory.state-changed.v1','MEMORY','%s',1,'%s',clock_timestamp())")
                .formatted(changeId, fixture.memoryId(), decisionId));
        dsl.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,created_at) VALUES "
                        + "('%s','s2b-outbox-%s','GOVERNED','memory.state-changed.v1','MEMORY','%s',1,'pink.event.v1',"
                        + "'S2B_TEST',0,decode(repeat('00',32),'hex'),'%s'::jsonb,'%s','READY',clock_timestamp(),clock_timestamp())")
                .formatted(UUID.randomUUID(), decisionId, fixture.memoryId(), manifest, changeId));
        dsl.execute("UPDATE memory.memory_record SET state='ARCHIVED', updated_at=clock_timestamp() WHERE memory_id=?",
                fixture.memoryId());
    }

    /** Inserts a valid MEMORY deletion fence so the memory leaves the read list. */
    private void insertDeletionFence(Fixture fixture) {
        UUID closureId = UUID.randomUUID();
        UUID decisionId = UUID.randomUUID();
        UUID fenceId = UUID.randomUUID();
        UUID policyId = dsl.fetchOne(
                        "SELECT policy_id FROM memory.memory_record WHERE memory_id=?", fixture.memoryId())
                .get("policy_id", UUID.class);
        dsl.execute("INSERT INTO memory.deletion_closure(closure_id,root_memory_id,preview_revision,"
                        + "root_current_revision_id,root_revision_no,root_policy_id,root_policy_revision_no,"
                        + "request_idempotency_key,request_hash,manifest_hash,state,created_at,expires_at) "
                        + "VALUES (?::uuid,?::uuid,1,?::uuid,1,?::uuid,1,"
                        + "'s2b-del-'||gen_random_uuid(),decode(repeat('00',32),'hex'),"
                        + "decode(repeat('00',32),'hex'),'PREVIEWED',clock_timestamp(),"
                        + "clock_timestamp()+interval '1 hour')",
                closureId, fixture.memoryId(), fixture.revisionId(), policyId);
        dsl.execute("INSERT INTO memory.deletion_closure_member(closure_id,ordinal,member_kind,target_id,"
                        + "target_revision_ref,disposition) VALUES (?::uuid,1,'MEMORY',?::uuid,NULL,'DELETE_REQUESTED')",
                closureId, fixture.memoryId());
        dsl.execute("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,"
                        + "target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) "
                        + "VALUES (?::uuid,'USER_DELETE_CONFIRM',?::uuid,'USER','DELETION_CLOSURE',?::uuid,1,"
                        + "'s2b-test','s2b-del-confirm-'||gen_random_uuid(),clock_timestamp())",
                decisionId, fixture.actorOne(), closureId);
        dsl.execute("INSERT INTO memory.deletion_fence(fence_id,closure_id,target_kind,target_id,"
                        + "target_revision_ref,created_by_decision_id,created_at) "
                        + "VALUES (?::uuid,?::uuid,'MEMORY',?::uuid,NULL,?::uuid,clock_timestamp())",
                fenceId, closureId, fixture.memoryId(), decisionId);
    }

    /** Governed policy revision bump mirroring the canonical publish/isolation writes. */
    private void bumpPolicyRevision(Fixture fixture, long newPolicyRevision) {
        UUID policyId = dsl.fetchOne(
                        "SELECT policy_id FROM memory.memory_record WHERE memory_id=?", fixture.memoryId())
                .get("policy_id", UUID.class);
        UUID decisionId = UUID.randomUUID();
        UUID policyChangeId = UUID.randomUUID();
        UUID memoryPolicyChangeId = UUID.randomUUID();
        String manifestHash = "00".repeat(32);

        dsl.execute(("INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,"
                        + "target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES "
                        + "('%s','USER_ISOLATE','%s','USER','MEMORY','%s',1,'s2b-policy','%s',clock_timestamp())")
                .formatted(decisionId, fixture.actorOne(), fixture.memoryId(), "s2b-policy-" + decisionId));

        dsl.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES ('%s','memory.policy-changed.v1','ACCESS_POLICY','%s',%d,'%s',clock_timestamp())")
                .formatted(policyChangeId, policyId, newPolicyRevision, decisionId));
        String policyManifest = "{\"aggregateId\":\"" + policyId + "\",\"aggregateRevision\":" + newPolicyRevision
                + ",\"policyRevision\":0,\"purpose\":\"S2B_TEST\",\"manifestHash\":\"" + manifestHash + "\"}";
        dsl.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES "
                        + "('%s','s2b-policy-outbox-%s','GOVERNED','memory.policy-changed.v1','ACCESS_POLICY','%s',%d,"
                        + "'pink.event.v1','S2B_TEST',0,decode('%s','hex'),'%s'::jsonb,'%s','READY',clock_timestamp(),0,8,clock_timestamp())")
                .formatted(UUID.randomUUID(), policyChangeId, policyId, newPolicyRevision,
                        manifestHash, policyManifest, policyChangeId));

        dsl.execute(("INSERT INTO memory.access_policy_revision(policy_id,revision_no,companion_allowed,maintenance_allowed,"
                        + "export_allowed,external_provider_allowed,isolated,created_by_decision_id,created_at) "
                        + "SELECT policy_id,%d,companion_allowed,maintenance_allowed,export_allowed,external_provider_allowed,"
                        + "isolated,'%s',clock_timestamp() FROM memory.access_policy_revision WHERE policy_id='%s' AND revision_no=1")
                .formatted(newPolicyRevision, decisionId, policyId));

        dsl.execute(("INSERT INTO memory.change_event(change_event_id,event_type,target_kind,target_id,target_revision_ref,"
                        + "decision_id,occurred_at) VALUES ('%s','memory.policy-changed.v1','MEMORY','%s',1,'%s',clock_timestamp())")
                .formatted(memoryPolicyChangeId, fixture.memoryId(), decisionId));
        String memoryManifest = "{\"aggregateId\":\"" + fixture.memoryId() + "\",\"aggregateRevision\":1,"
                + "\"policyRevision\":0,\"purpose\":\"S2B_TEST\",\"manifestHash\":\"" + manifestHash + "\"}";
        dsl.execute(("INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,"
                        + "aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,"
                        + "change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES "
                        + "('%s','s2b-policy-mem-outbox-%s','GOVERNED','memory.policy-changed.v1','MEMORY','%s',1,"
                        + "'pink.event.v1','S2B_TEST',0,decode('%s','hex'),'%s'::jsonb,'%s','READY',clock_timestamp(),0,8,clock_timestamp())")
                .formatted(UUID.randomUUID(), memoryPolicyChangeId, fixture.memoryId(),
                        manifestHash, memoryManifest, memoryPolicyChangeId));

        dsl.execute("UPDATE memory.memory_record SET current_policy_revision_no=? WHERE memory_id=?",
                newPolicyRevision, fixture.memoryId());
    }

    private static void assertCode(LocalV1S2BException.Code code, org.junit.jupiter.api.function.Executable executable) {
        var exception = assertThrows(LocalV1S2BException.class, executable);
        assertEquals(code, exception.code());
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static MemoryRecord stubRecord(UUID memoryId, UUID currentRevisionId) {
        return new MemoryRecord(
                memoryId,
                "ACTIVE",
                currentRevisionId,
                UUID.randomUUID(),
                1L,
                OffsetDateTime.now(CLOCK),
                OffsetDateTime.now(CLOCK));
    }

    private static MemoryRevision stubRevision(UUID revisionId, UUID memoryId) {
        return new MemoryRevision(
                revisionId,
                memoryId,
                1L,
                "Interpretation",
                UUID.randomUUID(),
                "stub memory",
                OffsetDateTime.now(CLOCK),
                null,
                null,
                UUID.randomUUID(),
                OffsetDateTime.now(CLOCK));
    }

    private static final class MemoryReadStub implements MemoryReadPort {
        private final MemoryRecord record;
        private final MemoryRevision revision;
        private final Map<UUID, ActorRef> actors;
        private List<MemoryRelation> relations;

        private MemoryReadStub(
                MemoryRecord record,
                MemoryRevision revision,
                List<MemoryRelation> relations,
                Map<UUID, ActorRef> actors) {
            this.record = record;
            this.revision = revision;
            this.relations = relations;
            this.actors = actors;
        }

        private LocalV1S2BQueryCoordinator coordinator() {
            return new LocalV1S2BQueryCoordinator(
                    this, new StubEvidence(), new StubPayloadStore(), new TestDeletionFencePort());
        }

        @Override
        public List<MemoryRecord> listCurrentMemoryRecords(MemoryReadFilter filter) {
            return record == null ? List.of() : List.of(record);
        }

        @Override
        public MemoryRecord findMemoryRecordById(UUID memoryId) {
            return record != null && record.memoryId().equals(memoryId) ? record : null;
        }

        @Override
        public MemoryRevision findCurrentRevisionByMemoryId(UUID memoryId) {
            return revision;
        }

        @Override
        public MemoryRevision findMemoryRevisionById(UUID memoryRevisionId) {
            return revision;
        }

        @Override
        public List<MemoryRelation> findRelationsByFromRevisionId(UUID revisionId) {
            return relations;
        }

        @Override
        public ActorRef findActorRefById(UUID actorId) {
            return actors.get(actorId);
        }
    }

    private static final class StubEvidenceScenario {
        private final UUID memoryId = UUID.randomUUID();
        private final UUID revisionId = UUID.randomUUID();
        private final UUID sourceId = UUID.randomUUID();
        private final UUID anchorId = UUID.randomUUID();
        private final UUID actorId = UUID.randomUUID();
        private final Map<UUID, SourceUnit> units = new LinkedHashMap<>();
        private final Map<UUID, ActorRef> actors = new LinkedHashMap<>();
        private final List<MemoryRelation> relations = new ArrayList<>();
        private final StubEvidence evidence = new StubEvidence();
        private final StubPayloadStore payloadStore = new StubPayloadStore();
        private final MemoryReadStub memory;

        private StubEvidenceScenario(int unitCount, int payloadBytes) {
            actors.put(actorId, new ActorRef(
                    actorId, "HUMAN", "actor-" + actorId, "Synthetic actor", OffsetDateTime.now(CLOCK)));
            evidence.sources.put(sourceId, new Source(
                    sourceId,
                    "CHAT_EXPORT",
                    "LOCAL",
                    "stub-source-" + sourceId,
                    true,
                    false,
                    UUID.randomUUID(),
                    OffsetDateTime.now(CLOCK),
                    OffsetDateTime.now(CLOCK)));
            evidence.anchors.put(anchorId, new SourceAnchor(
                    anchorId, sourceId, "MESSAGE_SEGMENT", OffsetDateTime.now(CLOCK)));

            for (int index = 0; index < unitCount; index++) {
                UUID unitId = UUID.randomUUID();
                SourceUnit unit = new SourceUnit(
                        unitId,
                        sourceId,
                        "unit-" + index,
                        "v1",
                        (long) index + 1,
                        actorId,
                        OffsetDateTime.now(CLOCK).plusSeconds(index),
                        OffsetDateTime.now(CLOCK));
                units.put(unitId, unit);
                evidence.units.put(unitId, unit);
                evidence.anchorUnits.add(new SourceAnchorUnit(anchorId, unitId, 0L, 1L, (long) index + 1));

                byte[] body = new byte[payloadBytes];
                Arrays.fill(body, (byte) 'x');
                String objectRef = "stub-payload-" + unitId;
                byte[] hash = sha256(body);
                evidence.payloadsByUnit.put(unitId, List.of(new SourcePayload(
                        UUID.randomUUID(),
                        unitId,
                        "TEXT",
                        "LOCAL_FILE",
                        objectRef,
                        null,
                        "text/plain; charset=UTF-8",
                        (long) payloadBytes,
                        hash,
                        UUID.randomUUID(),
                        1L,
                        "MINIMUM_EVIDENCE",
                        null,
                        OffsetDateTime.now(CLOCK))));
                payloadStore.bodies.put(objectRef, body);
            }

            relations.add(new MemoryRelation(
                    UUID.randomUUID(),
                    revisionId,
                    "EVIDENCED_BY",
                    null,
                    anchorId,
                    null,
                    UUID.randomUUID(),
                    OffsetDateTime.now(CLOCK)));
            memory = new MemoryReadStub(
                    stubRecord(memoryId, revisionId), stubRevision(revisionId, memoryId), relations, actors);
        }

        private LocalV1S2BQueryCoordinator coordinator() {
            return new LocalV1S2BQueryCoordinator(memory, evidence, payloadStore, new TestDeletionFencePort());
        }
    }

    private static final class TestDeletionFencePort implements DeletionFencePort {
        @Override
        public void insertFences(List<FenceDraft> drafts) {
            throw new UnsupportedOperationException("S2B query tests do not write deletion fences");
        }

        @Override
        public boolean isFenced(String targetKind, UUID targetId, Long targetRevisionRef) {
            return false;
        }

        @Override
        public List<DeletionFence> findByClosureId(UUID closureId) {
            return List.of();
        }
    }

    /**
     * Narrow R1-02 spy over {@link MemoryReadPort}: lets a test drive the current revision/record that
     * the coordinator re-reads during list assembly differently from the record the DB list query
     * returned, closing/observing the TOCTOU window. Also counts relation reads to prove that a
     * non-matching item never reaches evidence assembly.
     */
    private static final class MemoryReadSpy implements MemoryReadPort {
        private final MemoryRecord listRecord;
        private final MemoryRecord currentRecord;
        private final MemoryRevision currentRevision;
        private final StubEvidence evidence = new StubEvidence();
        private final StubPayloadStore payloadStore = new StubPayloadStore();
        private final DeletionFencePort fence = new TestDeletionFencePort();
        private int relationReads;

        private MemoryReadSpy(MemoryRecord listRecord, MemoryRecord currentRecord, MemoryRevision currentRevision) {
            this.listRecord = listRecord;
            this.currentRecord = currentRecord;
            this.currentRevision = currentRevision;
        }

        private LocalV1S2BQueryCoordinator coordinator() {
            return new LocalV1S2BQueryCoordinator(this, evidence, payloadStore, fence);
        }

        @Override
        public List<MemoryRecord> listCurrentMemoryRecords(MemoryReadFilter filter) {
            return listRecord == null ? List.of() : List.of(listRecord);
        }

        @Override
        public MemoryRecord findMemoryRecordById(UUID memoryId) {
            return currentRecord != null && currentRecord.memoryId().equals(memoryId) ? currentRecord : null;
        }

        @Override
        public MemoryRevision findCurrentRevisionByMemoryId(UUID memoryId) {
            return currentRevision;
        }

        @Override
        public MemoryRevision findMemoryRevisionById(UUID memoryRevisionId) {
            return currentRevision;
        }

        @Override
        public List<MemoryRelation> findRelationsByFromRevisionId(UUID revisionId) {
            relationReads++;
            return List.of();
        }

        @Override
        public ActorRef findActorRefById(UUID actorId) {
            return null;
        }
    }

    private static class StubEvidence implements EvidenceReferencePort {
        private final Map<UUID, Source> sources = new LinkedHashMap<>();
        private final Map<UUID, SourceUnit> units = new LinkedHashMap<>();
        private final Map<UUID, List<SourcePayload>> payloadsByUnit = new LinkedHashMap<>();
        private final Map<UUID, SourceAnchor> anchors = new LinkedHashMap<>();
        private final List<SourceAnchorUnit> anchorUnits = new ArrayList<>();

        @Override
        public void insertSource(Source source) {}

        @Override
        public Source findSourceById(UUID sourceId) {
            return sources.get(sourceId);
        }

        @Override
        public void insertSourceUnit(SourceUnit unit) {}

        @Override
        public SourceUnit findSourceUnitById(UUID sourceUnitId) {
            return units.get(sourceUnitId);
        }

        @Override
        public void insertSourcePayload(SourcePayload payload) {}

        @Override
        public SourcePayload findSourcePayloadById(UUID payloadId) {
            return payloadsByUnit.values().stream()
                    .flatMap(List::stream)
                    .filter(payload -> payload.payloadId().equals(payloadId))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<SourcePayload> findSourcePayloadsBySourceUnitId(UUID sourceUnitId) {
            return payloadsByUnit.getOrDefault(sourceUnitId, List.of());
        }

        @Override
        public void insertSourceAnchor(SourceAnchor anchor) {}

        @Override
        public SourceAnchor findSourceAnchorById(UUID anchorId) {
            return anchors.get(anchorId);
        }

        @Override
        public void insertSourceAnchorUnits(List<SourceAnchorUnit> units) {}

        @Override
        public List<SourceAnchorUnit> findSourceAnchorUnitsByAnchorId(UUID anchorId) {
            return anchorUnits.stream()
                    .filter(unit -> unit.anchorId().equals(anchorId))
                    .sorted(Comparator.comparing(SourceAnchorUnit::ordinal))
                    .toList();
        }

        @Override
        public void verifyAnchorsExist(Set<UUID> anchorIds) {}

        @Override
        public Source findSourceByExternalRef(String platform, String externalRef) {
            return sources.values().stream()
                    .filter(source -> source.platform().equals(platform) && source.externalRef().equals(externalRef))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<SourceAnchor> findSourceAnchorsBySourceId(UUID sourceId) {
            return anchors.values().stream()
                    .filter(anchor -> anchor.sourceId().equals(sourceId))
                    .toList();
        }
    }

    private static final class StubPayloadStore implements PayloadStore {
        private final Map<String, byte[]> bodies = new LinkedHashMap<>();

        @Override
        public PayloadPutResult put(UUID payloadId, String contentType, byte[] bytes, byte[] expectedHash) {
            return new PayloadPutResult(
                    payloadId, "stub-payload-" + payloadId, bytes.length, expectedHash, "LOCAL_FILE", true);
        }

        @Override
        public byte[] get(String objectRef, byte[] expectedHash, long maxBytes) {
            byte[] body = bodies.get(objectRef);
            return body == null ? null : body.clone();
        }

        @Override
        public PayloadHeadResult head(String objectRef) {
            byte[] body = bodies.get(objectRef);
            return body == null ? null : new PayloadHeadResult(body.length, sha256(body), "text/plain; charset=UTF-8");
        }

        @Override
        public void delete(String objectRef, byte[] expectedHash) {}
    }

    private record FileFingerprint(long size, String hash) {}

    private record Fixture(
            UUID memoryId,
            UUID revisionId,
            UUID actorOne,
            UUID actorTwo,
            UUID unitOne,
            UUID unitTwo,
            UUID anchorOne,
            UUID anchorTwo) {}
}
