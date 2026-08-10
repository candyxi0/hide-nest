package io.github.candyxi0.hidenest.database;

import static org.junit.jupiter.api.Assertions.*;

import io.github.candyxi0.hidenest.application.coordinator.*;
import io.github.candyxi0.hidenest.application.model.*;
import io.github.candyxi0.hidenest.database.adapter.*;
import io.github.candyxi0.hidenest.memory.port.MemoryGovernancePort;
import io.github.candyxi0.hidenest.runtime.port.RuntimeTransactionPort;
import io.github.candyxi0.hidenest.runtime.port.TransactionExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultDSLContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

class SliceCCoordinatorTest {
    private static final String IMG = "pgvector/pgvector@sha256:2ac2c62ac8f030b414b19ea633a6d1d4d37c03abe52ce91887a4e5b4fbb5c73c";
    private static final String U = "hide_nest_migrator";
    private static final String HH = "00".repeat(32);
    private static final Clock CLK = Clock.fixed(Instant.parse("2026-08-09T00:00:00Z"), ZoneId.of("UTC"));
    private static PostgreSQLContainer<?> pg;
    private static DSLContext dsl;
    private static CanonicalPublishCoordinator pub;
    private static CanonicalRevisionCoordinator rev;
    private static String PW;

    @BeforeAll static void setUp() throws Exception {
        PW = UUID.randomUUID().toString() + UUID.randomUUID();
        pg = new PostgreSQLContainer<>(DockerImageName.parse(IMG).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hide_nest").withUsername(U).withPassword(PW).withStartupTimeout(Duration.ofSeconds(60));
        pg.start();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE hide_nest_api NOLOGIN"); s.execute("CREATE ROLE hide_nest_worker NOLOGIN");
        }
        Flyway fw = Flyway.configure().dataSource(pg.getJdbcUrl(), U, PW).defaultSchema("public")
                .locations("classpath:db/migration").cleanDisabled(true).baselineOnMigrate(false)
                .outOfOrder(false).validateMigrationNaming(true).load();
        assertEquals(8, fw.migrate().migrationsExecuted);
        var rds = new org.springframework.jdbc.datasource.DriverManagerDataSource(pg.getJdbcUrl(), U, PW);
        DataSource pds = new TransactionAwareDataSourceProxy(rds);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(rds));
        var cfg = new DefaultConfiguration(); cfg.setSQLDialect(SQLDialect.POSTGRES); cfg.setDataSource(pds);
        dsl = new DefaultDSLContext(cfg);
        var mp = new JooqMemoryGovernanceAdapter(dsl); var rp = new JooqRuntimeTransactionAdapter(dsl);
        TransactionExecutor te = new SpringTransactionExecutor(tx);
        pub = new CanonicalPublishCoordinator(mp, rp, te, CLK);
        rev = new CanonicalRevisionCoordinator(mp, rp, te, CLK);
    }
    @AfterAll static void tearDown() { if (pg != null) pg.stop(); }

    record S(UUID aid, UUID mid, UUID pid, UUID prid, UUID pridId, UUID rid, Set<UUID> dids, UUID did) {}

    private S sa(String pk, UUID tm, UUID tp, Long tr, UUID er, Long ep) {
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW)) {
            c.setAutoCommit(false); q(c, "SET CONSTRAINTS ALL DEFERRED");
            UUID a = UUID.randomUUID(), pi = UUID.randomUUID(), pr = UUID.randomUUID();
            UUID r = UUID.randomUUID(), d = UUID.randomUUID(), pd = UUID.randomUUID(), ce = UUID.randomUUID();
            String ts = tr != null ? tr.toString() : "NULL"; boolean cr = "CREATE".equals(pk);
            q(c, "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('%s','COMPLETED','rv-%s',decode('%s','hex'),clock_timestamp(),clock_timestamp())".formatted(r,r,HH));
            q(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('%s','SYNTHETIC','a-%s',clock_timestamp())".formatted(a,a));
            q(c, "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('%s','%s',%s,clock_timestamp())".formatted(pi,pk,cr?"NULL":"'"+tm+"'"));
            q(c, "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES ('%s','%s',1,'PUBLISH','t','Claim',%s,%s,clock_timestamp())".formatted(pr,pi,er!=null?"'"+er+"'":"NULL",ep!=null?ep.toString():"NULL"));
            q(c, "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('%s','%s',1)".formatted(r,pr));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'x','pd-%s',clock_timestamp())".formatted(pd,a,tp,pd));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','USER_CONFIRM','%s','HUMAN','%s','%s','MEMORY','%s',%s,'x','d-%s',clock_timestamp())".formatted(d,a,pr,r,tm,ts,d));
            q(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('%s','review.decisions-committed.v1','%s','REVIEW_SESSION','%s',%s,'%s',clock_timestamp())".formatted(ce,a,r,ts,d));
            q(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"+UUID.randomUUID()+"','ob-"+d+"','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','"+r+"',"+ts+",'pink.event.v1','REVIEW_SYNC',"+ts+",decode('"+HH+"','hex'),'{\"aggregateId\":\""+r+"\",\"aggregateRevision\":"+ts+",\"policyRevision\":"+ts+",\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""+HH+"\"}','"+ce+"','READY',clock_timestamp(),0,8,clock_timestamp())");
            c.commit(); return new S(a,tm,tp,pi,pr,r,Set.of(d),d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void q(Connection c, String s) throws Exception { try (Statement st = c.createStatement()) { st.execute(s); } }
    private static byte[] h(String in) { try { return MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8)); } catch (Exception e) { throw new RuntimeException(e); } }
    private static byte[] h0() { return new byte[32]; }

    // C1
    @Test void c1s() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        String k=UUID.randomUUID().toString(); var r=pub.publishFirst(new CanonicalPublishRequest(k,h(k),f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),"B",p,List.of(),h0()));
        assertNotNull(r); assertEquals(m,r.memoryId()); assertEquals(1L,r.revisionNo());
        var mr=dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD).where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD.MEMORY_ID.eq(m)).fetchOne();
        assertNotNull(mr); assertEquals("ACTIVE",mr.getState());
        assertTrue(dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT,io.github.candyxi0.hidenest.database.generated.runtime.tables.OutboxEvent.OUTBOX_EVENT.AGGREGATE_ID.eq(m))>=1);
    }
    @Test void c1rp() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        String k=UUID.randomUUID().toString(); byte[] rh=h(k); var req=new CanonicalPublishRequest(k,rh,f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),"R",p,List.of(),h0());
        pub.publishFirst(req); long rb=dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
        var r2=pub.publishFirst(req); assertEquals(m,r2.memoryId()); assertEquals(1L,r2.revisionNo()); assertNotNull(r2.revisionId());
        assertEquals(rb,(long)dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION));
    }
    @Test void c1kr() {
        UUID m1=UUID.randomUUID(),p1=UUID.randomUUID(),m2=UUID.randomUUID(),p2=UUID.randomUUID();
        S f1=sa("CREATE",m1,p1,1L,null,null); S f2=sa("CREATE",m2,p2,1L,null,null); String k=UUID.randomUUID().toString();
        pub.publishFirst(new CanonicalPublishRequest(k,h("a"),f1.dids(),f1.pridId(),f1.rid(),m1,"Claim",f1.aid(),"A",p1,List.of(),h0()));
        var ex=assertThrows(CanonicalPublishException.class,()->pub.publishFirst(new CanonicalPublishRequest(k,h("b"),f2.dids(),f2.pridId(),f2.rid(),m2,"Claim",f2.aid(),"B",p2,List.of(),h0())));
        assertEquals(CanonicalFailureCode.IDEMPOTENCY_KEY_REUSED,ex.failureCode());
    }
    @Test void c1mm() {
        UUID m1=UUID.randomUUID(),p1=UUID.randomUUID(),m2=UUID.randomUUID(),p2=UUID.randomUUID();
        S f1=sa("CREATE",m1,p1,1L,null,null); S f2=sa("CREATE",m2,p2,1L,null,null);
        var ex=assertThrows(CanonicalPublishException.class,()->pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f2.pridId(),f2.rid(),UUID.randomUUID(),"Claim",f1.aid(),"X",UUID.randomUUID(),List.of(),h0())));
        assertEquals(CanonicalFailureCode.REVIEW_MEMBER_MISMATCH,ex.failureCode());
    }
    @Test void c1nc() throws Exception {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        // Bypass trigger to change COMPLETED→OPEN for testing
        try(Connection c=DriverManager.getConnection(pg.getJdbcUrl(),U,PW);Statement s=c.createStatement()){
            s.execute("SET session_replication_role='replica'");
            s.execute("UPDATE memory.review_session SET state='OPEN',terminal_at=NULL WHERE review_session_id='"+f.rid()+"'");
            s.execute("SET session_replication_role='origin'");
        }
        var ex=assertThrows(CanonicalPublishException.class,()->pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),"X",p,List.of(),h0())));
        assertEquals(CanonicalFailureCode.REVIEW_SESSION_NOT_OPEN,ex.failureCode());
    }

    // C2
    @Test void c2s() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f1=sa("CREATE",m,p,1L,null,null);
        var r1=pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f1.pridId(),f1.rid(),m,"Claim",f1.aid(),"O",p,List.of(),h0()));
        S f2=sa("UPDATE",m,p,2L,r1.revisionId(),1L);
        var r2=rev.revise(new CanonicalRevisionRequest(UUID.randomUUID().toString(),h0(),f2.dids(),f2.pridId(),f2.rid(),m,1L,1L,"Claim",f2.aid(),"R",false,List.of(),h0()));
        assertEquals(m,r2.memoryId()); assertEquals(2L,r2.revisionNo());
        assertNotNull(dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION).where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.MEMORY_ID.eq(m)).and(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.REVISION_NO.eq(1L)).fetchOne());
        assertEquals(r2.revisionId(),dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD).where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD.MEMORY_ID.eq(m)).fetchOne().getCurrentRevisionId());
    }
    @Test void c2sr() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f1=sa("CREATE",m,p,1L,null,null);
        var r1=pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f1.pridId(),f1.rid(),m,"Claim",f1.aid(),"X",p,List.of(),h0()));
        S f2=sa("UPDATE",m,p,1000L,r1.revisionId(),1L);
        long cb=dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT);
        var ex=assertThrows(CanonicalPublishException.class,()->rev.revise(new CanonicalRevisionRequest(UUID.randomUUID().toString(),h0(),f2.dids(),f2.pridId(),f2.rid(),m,999L,1L,"Claim",f2.aid(),"X",false,List.of(),h0())));
        assertEquals(CanonicalFailureCode.EXPECTED_REVISION_STALE,ex.failureCode());
        assertEquals(cb,(long)dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT));
    }
    @Test void c2sp() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f1=sa("CREATE",m,p,1L,null,null);
        var r1=pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f1.pridId(),f1.rid(),m,"Claim",f1.aid(),"X",p,List.of(),h0()));
        S f2=sa("UPDATE",m,p,2L,r1.revisionId(),1L);
        var ex=assertThrows(CanonicalPublishException.class,()->rev.revise(new CanonicalRevisionRequest(UUID.randomUUID().toString(),h0(),f2.dids(),f2.pridId(),f2.rid(),m,1L,999L,"Claim",f2.aid(),"X",false,List.of(),h0())));
        assertEquals(CanonicalFailureCode.POLICY_REVISION_STALE,ex.failureCode());
    }
    @Test void c2rb() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f1=sa("CREATE",m,p,1L,null,null);
        var r1=pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f1.pridId(),f1.rid(),m,"Claim",f1.aid(),"O",p,List.of(),h0()));
        long rvB=dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
        S f2=sa("UPDATE",m,p,2L,r1.revisionId(),1L);
        assertThrows(CanonicalPublishException.class,()->rev.revise(new CanonicalRevisionRequest(UUID.randomUUID().toString(),h0(),f2.dids(),f2.pridId(),f2.rid(),m,1L,999L,"Claim",f2.aid(),"X",false,List.of(),h0())));
        assertEquals(rvB,(long)dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION));
        assertEquals(r1.revisionId(),dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD).where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD.MEMORY_ID.eq(m)).fetchOne().getCurrentRevisionId());
    }

    // CC
    @Test void ccp() throws Exception {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        String k=UUID.randomUUID().toString(); byte[] rh=h(k);
        var bar=new CountDownLatch(2); var go=new CountDownLatch(1); var ok=new AtomicInteger(0);
        ExecutorService ex=Executors.newFixedThreadPool(2);
        Runnable t=()->{try{bar.countDown();go.await();pub.publishFirst(new CanonicalPublishRequest(k,rh,f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),"R",p,List.of(),h0()));ok.incrementAndGet();}catch(Exception ignored){}};
        ex.submit(t);ex.submit(t);bar.await();go.countDown();ex.shutdown();assertTrue(ex.awaitTermination(15,TimeUnit.SECONDS));
        assertEquals(2,ok.get());assertEquals(1L,(long)dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION,io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.MEMORY_ID.eq(m)));
    }
    @Test void ccr() throws Exception {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f1=sa("CREATE",m,p,1L,null,null);
        var r1=pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f1.dids(),f1.pridId(),f1.rid(),m,"Claim",f1.aid(),"O",p,List.of(),h0()));
        var bar=new CountDownLatch(2); var go=new CountDownLatch(1); var ok=new AtomicInteger(0); var st=new AtomicInteger(0);
        ExecutorService ex=Executors.newFixedThreadPool(2);
        Runnable t=()->{try{S f=sa("UPDATE",m,p,2L,r1.revisionId(),1L);bar.countDown();go.await();rev.revise(new CanonicalRevisionRequest(UUID.randomUUID().toString(),h0(),f.dids(),f.pridId(),f.rid(),m,1L,1L,"Claim",f.aid(),"CR",false,List.of(),h0()));ok.incrementAndGet();}catch(CanonicalPublishException e){st.incrementAndGet();}catch(Exception e){st.incrementAndGet();}};
        ex.submit(t);ex.submit(t);bar.await();go.countDown();ex.shutdown();assertTrue(ex.awaitTermination(15,TimeUnit.SECONDS));
        assertTrue(dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION,io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.MEMORY_ID.eq(m).and(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.REVISION_NO.eq(2L)))<=1);
    }

    // Leak + FC
    @Test void leak() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        String can="SNSTV-"+UUID.randomUUID(),k=UUID.randomUUID().toString();
        pub.publishFirst(new CanonicalPublishRequest(k,h(k),f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),can,p,List.of(),h0()));
        for(var oe:dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT).where(io.github.candyxi0.hidenest.database.generated.runtime.tables.OutboxEvent.OUTBOX_EVENT.AGGREGATE_ID.eq(m)).fetch())assertFalse(oe.getPayloadManifest().data().contains(can));
        var rc=dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.runtime.Tables.IDEMPOTENCY_RECEIPT).where(io.github.candyxi0.hidenest.database.generated.runtime.tables.IdempotencyReceipt.IDEMPOTENCY_RECEIPT.IDEMPOTENCY_KEY.eq(k)).fetchOne();
        if(rc.getResponseManifest()!=null)assertFalse(rc.getResponseManifest().data().contains(can));
    }
    @Test void fcr() {
        UUID m=UUID.randomUUID(),p=UUID.randomUUID(); S f=sa("CREATE",m,p,1L,null,null);
        var rel=new CanonicalPublishRequest.RelationSpec("EVIDENCED_BY",null,UUID.randomUUID(),null);
        var ex=assertThrows(CanonicalPublishException.class,()->pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(),h0(),f.dids(),f.pridId(),f.rid(),m,"Claim",f.aid(),"X",p,List.of(rel),h0())));
        assertEquals(CanonicalFailureCode.CANONICAL_COMMIT_FAILED,ex.failureCode());
    }

    // ============================================================
    // V007 attack: CREATE with non-null target pointing to different existing memory
    // ============================================================
    @Test @DisplayName("V007: CREATE+nonNull target→different memory rejected") void v007a1() {
        // First create a "victim" memory that already exists (so FK is satisfied)
        UUID vicM = UUID.randomUUID(), vicP = UUID.randomUUID();
        S vic = sa("CREATE", vicM, vicP, 1L, null, null);
        pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(), h0(),
                vic.dids(), vic.pridId(), vic.rid(), vicM, "Claim", vic.aid(), "V", vicP, List.of(), h0()));

        // Now create an attack fixture: proposal_kind='CREATE' but target_memory_id=vicM
        // (a DIFFERENT existing memory — FK is valid, but V007 must reject)
        UUID atkM = UUID.randomUUID(), atkP = UUID.randomUUID();
        UUID a = UUID.randomUUID(), pr = UUID.randomUUID();
        UUID r = UUID.randomUUID(), d = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW)) {
            c.setAutoCommit(false); q(c, "SET CONSTRAINTS ALL DEFERRED");
            UUID pi = UUID.randomUUID(), pd = UUID.randomUUID(), ce = UUID.randomUUID();
            q(c, "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('%s','COMPLETED','rv-%s',decode('%s','hex'),clock_timestamp(),clock_timestamp())".formatted(r,r,HH));
            q(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('%s','SYNTHETIC','a-%s',clock_timestamp())".formatted(a,a));
            // ATTACK: CREATE but target_memory_id = vicM (different existing memory, FK valid)
            q(c, "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('%s','CREATE','%s',clock_timestamp())".formatted(pi,vicM));
            q(c, "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES ('%s','%s',1,'PUBLISH','t','Claim',NULL,NULL,clock_timestamp())".formatted(pr,pi));
            q(c, "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('%s','%s',1)".formatted(r,pr));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'x','pd-%s',clock_timestamp())".formatted(pd,a,atkP,pd));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','USER_CONFIRM','%s','HUMAN','%s','%s','MEMORY','%s',1,'x','d-%s',clock_timestamp())".formatted(d,a,pr,r,atkM,d));
            q(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('%s','review.decisions-committed.v1','%s','REVIEW_SESSION','%s',1,'%s',clock_timestamp())".formatted(ce,a,r,d));
            q(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"+UUID.randomUUID()+"','ob-"+d+"','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','"+r+"',1,'pink.event.v1','REVIEW_SYNC',1,decode('"+HH+"','hex'),'{\"aggregateId\":\""+r+"\",\"aggregateRevision\":1,\"policyRevision\":1,\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""+HH+"\"}','"+ce+"','READY',clock_timestamp(),0,8,clock_timestamp())");
            c.commit();
        } catch (Exception e) { throw new RuntimeException(e); }

        // V007 must reject at COMMIT (DEFERRED trigger fires)
        Throwable ex = assertThrows(RuntimeException.class, () -> pub.publishFirst(
                new CanonicalPublishRequest(UUID.randomUUID().toString(), h0(),
                        Set.of(d), pr, r,
                        atkM, "Claim", a, "X", atkP, List.of(), h0())));
        // Prove the exception chain reaches V007 trigger
        Throwable cause = ex;
        boolean foundV007 = false, foundSqlstate = false;
        while (cause != null) {
            String msg = cause.getMessage() != null ? cause.getMessage() : "";
            if (msg.contains("HDM005_MEMORY_REVISION_PROPOSAL_TARGET_MISMATCH")) foundV007 = true;
            // PSQLException has SQLSTATE via getSQLState(), not always in message
            if (cause instanceof org.postgresql.util.PSQLException psql) {
                if ("23514".equals(psql.getSQLState())) foundSqlstate = true;
            }
            cause = cause.getCause();
        }
        assertTrue(foundV007, "root cause must contain V007 error code");
        assertTrue(foundSqlstate, "root cause must contain SQLSTATE 23514");

        // Zero canonical writes: no MemoryRecord, MemoryRevision for atkM
        assertEquals(0L, (long) dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD,
                io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD.MEMORY_ID.eq(atkM)));
        assertEquals(0L, (long) dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION,
                io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.MEMORY_ID.eq(atkM)));
        // Zero ChangeEvent/governed outbox/receipt for this attack key
        long ceForAtk = dsl.fetchCount(
                io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT,
                io.github.candyxi0.hidenest.database.generated.memory.tables.ChangeEvent.CHANGE_EVENT.TARGET_ID.eq(atkM));
        assertEquals(0L, (long) ceForAtk);
    }

    // ============================================================
    // V007 attack: REVISE/UPDATE with NULL target_memory_id
    // ============================================================
    @Test @DisplayName("V007: REVISE+null target rejected, old current preserved") void v007a2() {
        UUID m = UUID.randomUUID(), p = UUID.randomUUID();
        S f1 = sa("CREATE", m, p, 1L, null, null);
        var r1 = pub.publishFirst(new CanonicalPublishRequest(UUID.randomUUID().toString(), h0(),
                f1.dids(), f1.pridId(), f1.rid(), m, "Claim", f1.aid(), "O", p, List.of(), h0()));

        // Attack: UPDATE proposal with target_memory_id=NULL (FK allows NULL)
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), U, PW)) {
            c.setAutoCommit(false); q(c, "SET CONSTRAINTS ALL DEFERRED");
            UUID a = UUID.randomUUID(), pi = UUID.randomUUID(), pr = UUID.randomUUID();
            UUID r = UUID.randomUUID(), d = UUID.randomUUID(), pd = UUID.randomUUID(), ce = UUID.randomUUID();
            q(c, "INSERT INTO memory.review_session(review_session_id,state,idempotency_key,request_hash,opened_at,terminal_at) VALUES ('%s','COMPLETED','rv-%s',decode('%s','hex'),clock_timestamp(),clock_timestamp())".formatted(r,r,HH));
            q(c, "INSERT INTO memory.actor_ref(actor_id,actor_kind,stable_ref,created_at) VALUES ('%s','SYNTHETIC','a-%s',clock_timestamp())".formatted(a,a));
            // ATTACK: UPDATE but target_memory_id=NULL
            q(c, "INSERT INTO memory.proposal(proposal_id,proposal_kind,target_memory_id,created_at) VALUES ('%s','UPDATE',NULL,clock_timestamp())".formatted(pi));
            q(c, "INSERT INTO memory.proposal_revision(proposal_revision_id,proposal_id,revision_no,action_code,body_text,memory_type,expected_memory_revision_id,expected_policy_revision_no,created_at) VALUES ('%s','%s',1,'PUBLISH','t','Claim','%s',1,clock_timestamp())".formatted(pr,pi,r1.revisionId()));
            q(c, "INSERT INTO memory.review_member(review_session_id,proposal_revision_id,ordinal) VALUES ('%s','%s',1)".formatted(r,pr));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','HIDE_SELECT','%s','USER','ACCESS_POLICY','%s',1,'x','pd-%s',clock_timestamp())".formatted(pd,a,p,pd));
            q(c, "INSERT INTO memory.decision(decision_id,decision_kind,actor_id,actor_role,proposal_revision_id,review_session_id,target_kind,target_id,target_revision_ref,authorization_ref,idempotency_key,created_at) VALUES ('%s','USER_CONFIRM','%s','HUMAN','%s','%s','MEMORY','%s',2,'x','d-%s',clock_timestamp())".formatted(d,a,pr,r,m,d));
            q(c, "INSERT INTO memory.change_event(change_event_id,event_type,actor_id,target_kind,target_id,target_revision_ref,decision_id,occurred_at) VALUES ('%s','review.decisions-committed.v1','%s','REVIEW_SESSION','%s',2,'%s',clock_timestamp())".formatted(ce,a,r,d));
            q(c, "INSERT INTO runtime.outbox_event(event_id,idempotency_key,event_category,event_type,aggregate_kind,aggregate_id,aggregate_revision,contract_version,purpose,policy_revision,manifest_hash,payload_manifest,change_event_id,state,available_at,attempt_count,max_attempts,created_at) VALUES ('"+UUID.randomUUID()+"','ob-"+d+"','GOVERNED','review.decisions-committed.v1','REVIEW_SESSION','"+r+"',2,'pink.event.v1','REVIEW_SYNC',2,decode('"+HH+"','hex'),'{\"aggregateId\":\""+r+"\",\"aggregateRevision\":2,\"policyRevision\":2,\"purpose\":\"REVIEW_SYNC\",\"manifestHash\":\""+HH+"\"}','"+ce+"','READY',clock_timestamp(),0,8,clock_timestamp())");
            c.commit();

            long rvBefore = dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION);
            long ceBefore = dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT);
            long oeBefore = dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT);
            long rcBefore = dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.runtime.Tables.IDEMPOTENCY_RECEIPT);

            // V007 fires at COMMIT → prove cause chain
            Throwable ex = assertThrows(RuntimeException.class, () -> rev.revise(
                    new CanonicalRevisionRequest(UUID.randomUUID().toString(), h0(),
                            Set.of(d), pr, r, m, 1L, 1L,
                            "Claim", a, "R2", false, List.of(), h0())));
            Throwable rc = ex; boolean fV007 = false, fSql = false;
            while (rc != null) {
                String msg = rc.getMessage() != null ? rc.getMessage() : "";
                if (msg.contains("HDM005_MEMORY_REVISION_PROPOSAL_TARGET_MISMATCH")) fV007 = true;
                if (rc instanceof org.postgresql.util.PSQLException psql) {
                    if ("23514".equals(psql.getSQLState())) fSql = true;
                }
                rc = rc.getCause();
            }
            assertTrue(fV007, "root cause must contain V007 error code");
            assertTrue(fSql, "root cause must contain SQLSTATE 23514");

            // Zero new rows: no revision 2, no new event/outbox/receipt
            assertEquals(rvBefore, (long) dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION));
            assertEquals(ceBefore, (long) dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.CHANGE_EVENT));
            assertEquals(oeBefore, (long) dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.runtime.Tables.OUTBOX_EVENT));
            assertEquals(rcBefore, (long) dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.runtime.Tables.IDEMPOTENCY_RECEIPT));
            // Revision 2 does not exist
            assertEquals(0L, (long) dsl.fetchCount(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_REVISION,
                    io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.MEMORY_ID.eq(m)
                            .and(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRevision.MEMORY_REVISION.REVISION_NO.eq(2L))));
            // Old current pointer unchanged
            assertEquals(r1.revisionId(), dsl.selectFrom(io.github.candyxi0.hidenest.database.generated.memory.Tables.MEMORY_RECORD).where(io.github.candyxi0.hidenest.database.generated.memory.tables.MemoryRecord.MEMORY_RECORD.MEMORY_ID.eq(m)).fetchOne().getCurrentRevisionId());
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
