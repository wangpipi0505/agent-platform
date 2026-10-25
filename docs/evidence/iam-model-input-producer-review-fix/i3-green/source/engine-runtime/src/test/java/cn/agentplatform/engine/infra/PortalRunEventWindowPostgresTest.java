package cn.agentplatform.engine.infra;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.GraphRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;

class PortalRunEventWindowPostgresTest {
    static final String DATABASE="agent_platform_iam_o2a_c_events_v12";
    static final class Fixture implements AutoCloseable {
        final RunEventReaderAuthorityFixture auth=new RunEventReaderAuthorityFixture();
        final ObservedSource source; final JdbcTemplate db; final TransactionTemplate tx;
        final RunAuthorityStore store; final TraceStore trace; final RunEventReader reader;
        Fixture() throws Exception {
            String password=System.getenv("AGENT_DB_PASSWORD");
            if(password==null||password.isBlank())throw new IllegalStateException("OWN_PG_CREDENTIAL_REQUIRED");
            var admin=new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/postgres","agent_v12",password));
            if(!"postgres".equals(admin.queryForObject("select current_database()",String.class)))throw new AssertionError("ADMIN_DATABASE");
            if(!Boolean.TRUE.equals(admin.queryForObject("select exists(select 1 from pg_database where datname=?)",Boolean.class,DATABASE)))admin.execute("create database "+DATABASE);
            source=new ObservedSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+DATABASE,"agent_v12",password));
            db=new JdbcTemplate(source);
            assertThat(db.queryForObject("select current_database()",String.class)).isEqualTo(DATABASE);
            Flyway.configure().dataSource(source).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
            tx=new TransactionTemplate(new DataSourceTransactionManager(source));
            store=new RunAuthorityStore(source,auth.iam.clock());
            trace=new TraceStore(db,new DataSourceTransactionManager(source),auth.iam.clock());reader=new RunEventReader(source);
        }
        JsonNode snapshot(int pad) {
            ObjectNode snap=Json.object();snap.put("appCode","app-a");snap.put("type","FLOW");snap.put("versionNo",1);snap.put("appVersionId",4294967297L);snap.put("candidateSha256",RunEventReaderAuthorityFixture.ROOT.versionDigest());snap.set("config",Json.object());
            var definition=Json.object();var nodes=definition.putArray("nodes");var edges=definition.putArray("edges");
            for(int i=0;i<15;i++){String id=nodeId(i);var n=nodes.addObject().put("id",id).put("type",i==0?"START":i==14?"END":"CODE");if(i>0&&i<14)n.put("expression","1");if(i>0)edges.addObject().put("from",nodeId(i-1)).put("to",id);}
            if(pad>0)((ObjectNode)nodes.get(1)).put("padding","x".repeat(pad));snap.set("definition",definition);return snap;
        }
        static String nodeId(int i){return i==0?"start":i==14?"end":"c%02d".formatted(i);}
        RunAuthorityStore.Binding seed(int pad) {
            var snap=snapshot(pad);GraphRules.requirePublishedFlow(snap);
            var sentinel=new IllegalStateException("C_DAG_STORAGE_SENTINEL");
            var validating=new TraceStore(db,new DataSourceTransactionManager(source),auth.iam.clock()){@Override public <T>T atomic(java.util.function.Supplier<T> w){throw sentinel;}};
            try(var idle=new RunScheduler(validating){@Override public Admission reserveInteractive(){throw new AssertionError("NO_QUEUE");}@Override public void created(long id,Admission a){throw new AssertionError("NO_WAKE");}}){
                var service=new RunService(validating,null,null,null,idle);var actor=new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of());
                assertThatThrownBy(()->service.createReservedSessionTurn("app-a",Json.read("{\"inputs\":{},\"mode\":\"FULL\"}"),snap,actor,null)).isSameAs(sentinel);
                var wrong=snap.deepCopy();((ObjectNode)wrong.path("definition")).set("edges",Json.read("[{\"source\":\"start\",\"target\":\"end\"}]"));
                assertThatThrownBy(()->service.createReservedSessionTurn("app-a",Json.read("{\"inputs\":{},\"mode\":\"FULL\"}"),wrong,actor,null)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.code()).isEqualTo("UNKNOWN_EDGE"));
            }
            long run=auth.admitted(p->tx.execute(s->{
                var budget=Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",auth.iam.clock().instant().plusSeconds(599).toString()));
                long id=store.createPublished(p,snap,Json.read("{\"privateInput\":\"not-consumed\"}"),Json.read("{\"appVer\":1}"),budget,"FULL",UUID.randomUUID().toString(),Set.of("initial"));
                for(int i=0;i<15;i++)trace.createNode(id,nodeId(i),i+1,i==0?"START":i==14?"END":"CODE",snap.path("definition").path("nodes").get(i),p);return id;
            }));
            append(run,"C_FIRST",Json.read("{\"integer\":123456789012345678901234567890,\"text\":\"观察\"}"));return store.load(run);
        }
        void append(long run,String type,Object value){var writer=new RunAuthority(store,auth.engine,auth.iam.clock(),java.time.Duration.ofSeconds(3)).currentRetained(run,new ServiceCall("engine","run.advance",RunEventReaderAuthorityFixture.ROOT));trace.event(run,type,value,writer);}
        void finish(long run){
            var repository=new RunScheduleRepository(trace,true);
            var dispatch=repository.claimSpecific(run,cn.agentplatform.engine.domain.RunDispatch.Queue.INTERACTIVE,"c-terminal-"+UUID.randomUUID(),java.time.Duration.ofSeconds(60));
            assertThat(dispatch).isNotNull();
            var writer=new RunAuthority(store,auth.engine,auth.iam.clock(),java.time.Duration.ofSeconds(3)).currentRetained(run,new ServiceCall("engine","run.advance",RunEventReaderAuthorityFixture.ROOT));
            trace.consume(run,"steps",25,25,writer);trace.stopFlowForBudget(dispatch,writer,"BUDGET_STEPS");
            assertThat(db.queryForObject("select status from engine.run where id=?",String.class,run)).isEqualTo("FAILED");
        }
        PortalEventsResponse open(RunAuthorityStore.Binding b,long cursor){return auth.open(b.owner(),b.publicId(),b.snapshotSha(),cursor);}
        public void close(){auth.close();}
    }
    static final class ObservedSource extends DelegatingDataSource {
        final List<String> sql=new CopyOnWriteArrayList<>(),getters=new CopyOnWriteArrayList<>(),observations=new CopyOnWriteArrayList<>();
        final List<String> scopeTexts=new CopyOnWriteArrayList<>();
        volatile Runnable beforeQuery=()->{},afterCommit=()->{};volatile java.util.function.BiFunction<String,Object,Object> change=(k,v)->v;
        volatile java.util.function.Consumer<String> onQuery=q->{};volatile boolean observe;
        volatile Runnable duringBind=()->{};
        final AtomicLong configuredTimeout=new AtomicLong();
        ObservedSource(DataSource target){super(target);}
        @Override public Connection getConnection()throws SQLException {
            var c=super.getConnection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                try {var value=m.invoke(c,a);if(m.getName().equals("commit")&&observe)afterCommit.run();
                    if(value instanceof PreparedStatement statement&&m.getName().equals("prepareStatement")){String query=(String)a[0];return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{PreparedStatement.class},(sp,sm,sa)->{try{
                        if(observe&&sm.getName().equals("setObject"))duringBind.run();
                        if(observe&&query.startsWith("select set_config")&&sm.getName().equals("setString"))configuredTimeout.set(Long.parseLong((String)sa[1]));
                        if(sm.getName().equals("executeQuery")&&observe){sql.add(query);observations.add(System.identityHashCode(c)+":"+c.getTransactionIsolation()+":"+c.isReadOnly()+":"+c.getAutoCommit());beforeQuery.run();onQuery.accept(query);}
                        Object result=sm.invoke(statement,sa);if(result instanceof ResultSet rs)return Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ResultSet.class},(rp,rm,ra)->{try{Object got=rm.invoke(rs,ra);if(observe&&rm.getName().startsWith("get")&&ra!=null&&ra.length>0){String key=ra[0] instanceof Integer index?rs.getMetaData().getColumnLabel(index):ra[0].toString();getters.add(key);if(key.equals("scope_text")&&got instanceof String s)scopeTexts.add(s);return change.apply(key,got);}return got;}catch(InvocationTargetException e){throw e.getCause();}});return result;
                    }catch(InvocationTargetException e){throw e.getCause();}});}
                    return value;
                }catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        void start(){sql.clear();getters.clear();observations.clear();scopeTexts.clear();observe=true;}
    }

    @Test void protectedWindowExistsForNonterminalEmptyAndCarriesRealState() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,1)){
            f.source.start();int calls=f.auth.iam.requestAttempts();
            try(var result=f.reader.readWindow(r,1L)) {result.consume(view->{assertThat(view.hasEvent()).isFalse();assertThat(view.window().status()).isEqualTo("PENDING");assertThat(view.window().watermark()).isEqualTo(1);assertThat(view.window().oldest()).isEqualTo(1);assertThat(view.window().drained()).isFalse();assertThat(view.window().resetReason()).isNull();});}
            assertThat(f.auth.iam.requestAttempts()).isEqualTo(calls);
            assertThat(f.source.observations).allMatch(o->o.endsWith(":4:true:false"));
            assertThat(f.source.getters).contains("state_text");
        }}
    }
    @Test void fullStateAndEventDependencyUnionIsReadOnceAndSharesOriginalBudgets()throws Exception{
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){
            f.source.start();try(var result=f.reader.readWindow(r,0)){result.consume(view->{assertThat(view.hasEvent()).isTrue();assertThat(view.info().seq()).isEqualTo(1);assertThat(view.info().measurements().totalReservedPeak()).isLessThanOrEqualTo(192L*1024*1024);});}
            var scopes=f.source.scopeTexts.stream().map(raw->RunEventReader.scope(RunEventReader.parse(raw,false,()->{}))).toList();
            assertThat(scopes.stream().filter(s->s.material().kind().equals("run-state")).toList()).anyMatch(s->s.parentScopeSha256().size()>64);
            // The target state/event scope text is read once, even when shared as a parent.
            assertThat(scopes.stream().map(MaterialScopeV1::scopeSha256).distinct().count()).isEqualTo(scopes.size());
            var limit=new RunEventReader.Limits();limit.root(8388607);limit.root(1);assertThatThrownBy(()->limit.root(1)).isInstanceOf(RunEventReader.CapacityFailure.class);
            java.nio.file.Files.writeString(java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-c-events","window-union-"+b.publicId()+".json"),Json.write(Map.of("run",b.publicId(),"database",DATABASE,"scopeCount",scopes.size(),"parentEntries",scopes.stream().mapToInt(s->s.parentScopeSha256().size()).sum(),"sql",f.source.sql,"transaction",f.source.observations.getFirst())));
        }}
    }
    @Test void concurrentLegalTerminalWriterIsInvisibleUntilNextCompleteReadWindow()throws Exception{
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){
            var once=new AtomicBoolean();f.source.start();f.source.onQuery=q->{if(q.contains("engine.material_scope")&&once.compareAndSet(false,true))outside(()->f.finish(b.runId()));};
            try(var e=f.reader.readWindow(r,0)){e.consume(v->{assertThat(v.window().status()).isEqualTo("PENDING");assertThat(v.window().watermark()).isEqualTo(1);assertThat(v.info().seq()).isEqualTo(1);});}
            f.source.onQuery=q->{};try(var e=f.reader.readWindow(r,1)){e.consume(v->{assertThat(v.window().status()).isEqualTo("FAILED");assertThat(v.window().watermark()).isEqualTo(2);assertThat(v.info().seq()).isEqualTo(2);assertThat(v.window().drained()).isFalse();});}
            try(var e=f.reader.readWindow(r,2)){e.consume(v->{assertThat(v.hasEvent()).isFalse();assertThat(v.window().drained()).isTrue();});}
        }}
    }
    @Test void terminalPartialAndFullPhysicalRetentionKeepsScopesAndBStrictness()throws Exception{
        try(var f=new Fixture()){var b=f.seed(0);f.finish(b.runId());
            long scopes=f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Long.class,b.runId());
            // Test maintenance only after the real terminal producer. This proves observation
            // semantics after deletion, not a production retention job or active writer support.
            assertThat(f.db.update("delete from engine.run_event where run_id=? and seq=1",b.runId())).isEqualTo(1);
            try(var r=f.open(b,0)){
                try(var e=f.reader.readWindow(r,0)){e.consume(v->{assertThat(v.window().resetReason()).isEqualTo("CURSOR_EXPIRED");assertThat(v.window().oldest()).isEqualTo(2);});}
                assertThatThrownBy(()->f.reader.readNext(r,1)).isInstanceOf(PlatformException.class);
                try(var e=f.reader.readWindow(r,1)){e.consume(v->assertThat(v.info().seq()).isEqualTo(2));}
                assertThat(f.db.update("delete from engine.run_event where run_id=?",b.runId())).isEqualTo(1);
                for(long after:List.of(1L,2L,3L))try(var e=f.reader.readWindow(r,after)){e.consume(v->{assertThat(v.hasEvent()).isFalse();assertThat(v.window().oldest()).isNull();assertThat(v.window().watermark()).isEqualTo(2);assertThat(v.window().resetReason()).isEqualTo(after==1?"CURSOR_EXPIRED":after==3?"CURSOR_AHEAD":null);assertThat(v.window().drained()).isEqualTo(after>=2);});}
            }
            assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Long.class,b.runId())).isEqualTo(scopes);
        }
    }
    @Test void stateDigestMetadataCoverageAndCrossRunParentRemainClosedBeforeWindowMint()throws Exception{
        try(var f=new Fixture()){var b=f.seed(0);
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("scope_text")?v.toString().replace("\"sourceDecisionId\":","\"unknownField\":"):v;assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(PlatformException.class);assertThat(f.source.getters).doesNotContain("payload_text","event_text","state_text");}
            f.source.change=(k,v)->v;f.auth.narrow();try(var r=f.open(b,0)){f.source.start();assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(PlatformException.class);assertThat(f.source.getters).doesNotContain("payload_text","event_text","state_text");}f.auth.resetCoverage();
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("state_text")?v.toString().replace("\"output_ref\":null","\"output_ref\":\"tampered\""):v;assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(PlatformException.class);assertThat(f.source.getters).contains("state_text");assertThat(f.source.getters).doesNotContain("oldest_seq");}
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("run_id")?b.runId()+100000:v;assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(PlatformException.class);}
        }
    }
    @Test void explicitCaptureGuardClassifiesHealthySupersessionButNeverDigestFailure()throws Exception{
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){
            var capture=r.currentCapture();assertThat(r.isSuperseded(capture)).isFalse();f.auth.renew(r);assertThat(r.isSuperseded(capture)).isTrue();
            f.source.start();var once=new AtomicBoolean();f.source.afterCommit=()->{if(once.compareAndSet(false,true))outside(()->f.auth.renew(r));};
            assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(RunEventReader.Superseded.class);f.source.afterCommit=()->{};
            try(var e=f.reader.readWindow(r,0)){e.consume(v->assertThat(v.info().seq()).isEqualTo(1));}
            f.source.change=(k,v)->k.equals("state_text")?v.toString().replace("\"output_ref\":null","\"output_ref\":\"bad\""):v;
            assertThatThrownBy(()->f.reader.readWindow(r,0)).isInstanceOf(PlatformException.class).isNotInstanceOf(RunEventReader.Superseded.class);
            r.close();assertThatThrownBy(()->r.isSuperseded(capture)).isInstanceOf(PlatformException.class);
        }}
    }
    static void outside(Runnable action){try(var pool=Executors.newSingleThreadExecutor()){pool.submit(action).get(4,TimeUnit.SECONDS);}catch(Exception failure){throw new AssertionError(failure);}}
}
