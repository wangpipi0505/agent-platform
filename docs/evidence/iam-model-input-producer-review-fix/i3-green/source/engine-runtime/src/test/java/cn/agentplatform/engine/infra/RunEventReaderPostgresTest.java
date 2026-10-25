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

class RunEventReaderPostgresTest {
    static final String DATABASE="agent_platform_iam_o2a_b_material_v12";
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
            var sentinel=new IllegalStateException("B_DAG_STORAGE_SENTINEL");
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
            append(run,"B_FIRST",Json.read("{\"integer\":123456789012345678901234567890,\"text\":\"观察\"}"));return store.load(run);
        }
        void append(long run,String type,Object value){var writer=new RunAuthority(store,auth.engine,auth.iam.clock(),java.time.Duration.ofSeconds(3)).currentRetained(run,new ServiceCall("engine","run.advance",RunEventReaderAuthorityFixture.ROOT));trace.event(run,type,value,writer);}
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
    @Test void realFifteenNodeWriterEventIsReadWithoutOnlineAuthorityInsideReadTransaction() throws Exception {
        try(var f=new Fixture()) {var b=f.seed(0);try(var response=f.open(b,0)){
            int calls=f.auth.iam.requestAttempts();f.source.start();
            try(var emission=f.reader.readNext(response,0).orElseThrow()){assertThat(emission.info().seq()).isEqualTo(1);}
            assertThat(f.auth.iam.requestAttempts()).isEqualTo(calls);
        }}
    }
    static void deniedRead(Runnable action){assertThatThrownBy(action::run).isInstanceOf(PlatformException.class);}
    static void capacity(Runnable action,String dimension){assertThatThrownBy(action::run).isInstanceOfSatisfying(RunEventReader.CapacityFailure.class,e->assertThat(e.dimension()).isEqualTo(dimension));}
    static long payloadGetters(Fixture f){return f.source.getters.stream().filter(s->s.equals("payload_text")||s.equals("event_text")).count();}
    @Test void payloadSinkMutationCannotChangeLaterWritesInTheSameRealBorrow() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0);var emission=f.reader.readNext(response,0).orElseThrow()){
            var retained=new AtomicReference<byte[]>();var original=new AtomicReference<byte[]>();var later=new java.io.ByteArrayOutputStream();
            emission.consume(view->{try{
                view.writePayload(new java.io.OutputStream(){public void write(int value){throw new AssertionError("ARRAY_WRITE_REQUIRED");}
                    @Override public void write(byte[] data){retained.set(data);original.set(data.clone());Arrays.fill(data,(byte)'!');}
                    @Override public void flush(){throw new AssertionError("CALLER_OWNS_FLUSH");}
                    @Override public void close(){throw new AssertionError("CALLER_OWNS_CLOSE");}});
                assertThat(Json.read(new String(original.get(),java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(Json.read("{\"integer\":123456789012345678901234567890,\"text\":\"观察\"}"));
                view.writePayload(later);assertThat(later.toByteArray()).isEqualTo(original.get());
                retained.get()[0]='?';var third=new java.io.ByteArrayOutputStream();view.writePayload(third);assertThat(third.toByteArray()).isEqualTo(original.get());
            }catch(java.io.IOException failure){throw new AssertionError(failure);}});
            Arrays.fill(retained.get(),(byte)'#');assertThat(later.toByteArray()).isEqualTo(original.get());
        }}
    }
    @Test void bothOutputMethodsRejectCrossReentryAndRecoverAfterSinkIOException() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0);var emission=f.reader.readNext(response,0).orElseThrow()){
            var retained=new AtomicReference<RunEventReader.Borrow>();var io=new java.io.IOException("BORROW_OUTPUT_IO");
            emission.consume(view->{retained.set(view);try{
                var rejectNested=new java.io.OutputStream(){public void write(int value){throw new AssertionError("NESTED_OUTPUT_REACHED");}};
                var reenter=new java.io.OutputStream(){public void write(int value){throw new AssertionError("ARRAY_WRITE_REQUIRED");}
                    @Override public void write(byte[] data){check();}
                    @Override public void write(byte[] data,int offset,int length){check();}
                    void check(){assertThatThrownBy(()->view.writePayload(rejectNested)).isInstanceOf(PlatformException.class);assertThatThrownBy(()->view.writeRequiredDataLabels(rejectNested)).isInstanceOf(PlatformException.class);}};
                view.writePayload(reenter);view.writeRequiredDataLabels(reenter);
                for(boolean labels:List.of(false,true)){
                    var failing=new java.io.OutputStream(){public void write(int value)throws java.io.IOException{throw io;}
                        @Override public void write(byte[] data)throws java.io.IOException{throw io;}
                        @Override public void write(byte[] data,int offset,int length)throws java.io.IOException{throw io;}};
                    assertThatThrownBy(()->{if(labels)view.writeRequiredDataLabels(failing);else view.writePayload(failing);}).isSameAs(io);
                    var recovered=new java.io.ByteArrayOutputStream();view.writePayload(recovered);assertThat(Json.read(recovered.toString(java.nio.charset.StandardCharsets.UTF_8)).path("text").textValue()).isEqualTo("观察");
                    view.writeRequiredDataLabels(new java.io.ByteArrayOutputStream());
                }
            }catch(java.io.IOException failure){throw new AssertionError(failure);}});
            assertThatThrownBy(()->retained.get().writePayload(new java.io.ByteArrayOutputStream())).isInstanceOf(PlatformException.class);
            // Uncaught output failure must also unwind the actual consumer finally and its permit.
            try(var failed=f.reader.readNext(response,0).orElseThrow()){
                assertThatThrownBy(()->failed.consume(view->{try{view.writePayload(new java.io.OutputStream(){public void write(int value)throws java.io.IOException{throw io;}});}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}})).isInstanceOf(java.io.UncheckedIOException.class);
            }
            try(var first=f.reader.readNext(response,0).orElseThrow();var second=f.reader.readNext(response,0).orElseThrow()){capacity(()->f.reader.readNext(response,0),"WORKSPACE");}
        }}
    }
    @Test void blockingActualPayloadWritesKeepBothPermitsUntilConsumerFinallyReturns() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0);var pool=Executors.newFixedThreadPool(2)){
            var first=f.reader.readNext(response,0).orElseThrow();var second=f.reader.readNext(response,0).orElseThrow();
            var entered=new CountDownLatch(2);var releaseFirst=new CountDownLatch(1);var releaseSecond=new CountDownLatch(1);
            var actualFinally=new AtomicInteger();var tasks=new ArrayList<Future<?>>();
            int index=0;for(var emission:List.of(first,second)){var release=index++==0?releaseFirst:releaseSecond;
                tasks.add(pool.submit(()->{try{assertThatThrownBy(()->emission.consume(view->{try{
                    view.writePayload(new java.io.OutputStream(){public void write(int value){throw new AssertionError("ARRAY_WRITE_REQUIRED");}
                        @Override public void write(byte[] data){entered.countDown();await(release);}});
                }catch(java.io.IOException failure){throw new AssertionError(failure);}})).isInstanceOf(PlatformException.class);}finally{actualFinally.incrementAndGet();}}));
            }
            try{
                assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();first.close();second.close();
                capacity(()->f.reader.readNext(response,0),"WORKSPACE");assertThat(actualFinally).hasValue(0);
                releaseFirst.countDown();tasks.get(0).get(2,TimeUnit.SECONDS);assertThat(actualFinally).hasValue(1);
                try(var pending=f.reader.readNext(response,0).orElseThrow()){capacity(()->f.reader.readNext(response,0),"WORKSPACE");}
                releaseSecond.countDown();tasks.get(1).get(2,TimeUnit.SECONDS);assertThat(actualFinally).hasValue(2);
                try(var next=f.reader.readNext(response,0).orElseThrow();var other=f.reader.readNext(response,0).orElseThrow()){capacity(()->f.reader.readNext(response,0),"WORKSPACE");}
            }finally{releaseFirst.countDown();releaseSecond.countDown();first.close();second.close();}
        }}
    }
    @Test void requiredLabelsAreWritableOnlyDuringTheLiveBorrowAndNeverAfterConsumeOrClose() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0)){
            var retained=new AtomicReference<RunEventReader.Borrow>();var labels=new java.io.ByteArrayOutputStream();
            try(var emission=f.reader.readNext(response,0).orElseThrow()){
                emission.consume(view->{retained.set(view);try{view.writeRequiredDataLabels(labels);}catch(java.io.IOException e){throw new AssertionError(e);}});
            }
            var decoded=Json.read(labels.toString(java.nio.charset.StandardCharsets.UTF_8));assertThat(decoded.isArray()).isTrue();assertThat(decoded.toString()).contains("\"initial\"");
            var deniedOutput=new java.io.ByteArrayOutputStream();
            assertThatThrownBy(()->retained.get().writeRequiredDataLabels(deniedOutput)).isInstanceOf(PlatformException.class);assertThat(deniedOutput.size()).isZero();
            try(var emission=f.reader.readNext(response,0).orElseThrow()){
                assertThatThrownBy(()->emission.consume(view->{emission.close();try{view.writeRequiredDataLabels(deniedOutput);}catch(java.io.IOException e){throw new AssertionError(e);}})).isInstanceOf(PlatformException.class);
                assertThat(deniedOutput.size()).isZero();
            }
        }}
    }
    @Test void realSampleMeasuresCompleteRepeatedParentArraysAndPayloadGetterOrder() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0)){
            f.source.start();var emission=f.reader.readNext(response,0).orElseThrow();
            assertThat(f.source.scopeTexts).hasSize(68);assertThat(f.source.getters.indexOf("payload_text")).isGreaterThan(f.source.getters.lastIndexOf("scope_text"));
            assertThat(new HashSet<>(f.source.observations)).hasSize(1);assertThat(f.source.observations.getFirst()).endsWith(":4:true:false");
            assertThat(f.source.sql).noneMatch(s->s.contains("a.*")||s.contains("r.*")||s.contains("inputs::text"));
            var scopes=f.source.scopeTexts.stream().map(s->RunEventReader.scope(RunEventReader.parse(s,false,()->{}))).toList();
            var selected=scopes.stream().filter(s->s.material().kind().equals("run-event")).findFirst().orElseThrow();assertThat(selected.parentScopeSha256()).hasSize(67);
            var entries=new ArrayList<Map<String,Object>>();long total=0,parents=0,max=0;
            for(int i=0;i<scopes.size();i++){var s=scopes.get(i);long bytes=f.source.scopeTexts.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;total+=bytes;parents+=s.parentScopeSha256().size();max=Math.max(max,bytes);entries.add(Map.of("kind",s.material().kind(),"locator",s.material().id(),"version",s.material().version(),"sha256",s.scopeSha256(),"parents",s.parentScopeSha256().size(),"utf8Bytes",bytes));}
            var labels=new java.io.ByteArrayOutputStream();var encoded=new java.io.ByteArrayOutputStream();emission.consume(borrow->{try{borrow.writeRequiredDataLabels(labels);borrow.writePayload(encoded);}catch(java.io.IOException e){throw new AssertionError(e);}});assertThat(Json.read(labels.toString(java.nio.charset.StandardCharsets.UTF_8)).toString()).contains("\"initial\"");
            assertThat(encoded.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("123456789012345678901234567890","观察");
            var sample=new LinkedHashMap<String,Object>();sample.put("database",DATABASE);sample.put("run",b.publicId());sample.put("N",15);sample.put("A",0);sample.put("E",1);sample.put("U",0);sample.put("C",67);sample.put("D_required_including_target",68);sample.put("H",parents);sample.put("scopeUtf8Bytes",total);sample.put("maxScopeUtf8Bytes",max);sample.put("eventEncodedBackingBytes",encoded.size());sample.put("metadata",entries);sample.put("sql",f.source.sql);sample.put("getters",f.source.getters);sample.put("connection",f.source.observations.getFirst());
            var out=java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-b-material-reader","writer-sample-"+UUID.randomUUID()+".json");java.nio.file.Files.writeString(out,Json.write(sample));
        }}
    }
    @Test void cursorBoundsAreCheckedBeforePermitAndSqlAndProgressCanAdvance() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);f.append(b.runId(),"B_SECOND",Map.of("n",2));try(var response=f.open(b,1)){
            f.source.start();deniedRead(()->f.reader.readNext(response,0));deniedRead(()->f.reader.readNext(response,-1));assertThat(f.source.sql).isEmpty();
            try(var event=f.reader.readNext(response,1).orElseThrow()){assertThat(event.info().seq()).isEqualTo(2);assertThat(event.info().afterSeq()).isEqualTo(1);}
            assertThat(f.reader.readNext(response,2)).isEmpty();assertThat(f.reader.readNext(response,10)).isEmpty();
        }}
    }
    @Test void historicalGrantExpiryDifferentLoginAndWrongOwnersOrResourceStaySeparated() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);f.auth.advance(301);f.auth.actor("tenant-a","alice",Set.of("A","B"));try(var r=f.open(b,0);var emission=f.reader.readNext(r,0).orElseThrow()){assertThat(emission.info().runId()).isEqualTo(b.publicId());assertThat(f.auth.lastOpeningOrigin).isNotEqualTo(b.originId());assertThat(b.delegation().expiresAt()).isBefore(f.auth.iam.clock().instant());}
            for(var owner:List.of(new SubjectKey("tenant-a","bob"),new SubjectKey("tenant-b","alice"))){try(var other=new RunEventReaderAuthorityFixture()){other.actor(owner.tenantId(),owner.principalId(),Set.of("A","B"));try(var r=other.open(owner,b.publicId(),b.snapshotSha(),0)){f.source.start();assertThatThrownBy(()->f.reader.readNext(r,0)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.code()).isEqualTo("RUN_NOT_FOUND"));assertThat(payloadGetters(f)).isZero();}}}
            f.auth.actor("tenant-a","alice",Set.of("A","B"));
            // Adversarial getter comparison, never a PG immutable-history rewrite or authority positive.
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("control_text")?v.toString().replace(b.snapshotSha(),Digest.sha256("wrong-resource")):v;deniedRead(()->f.reader.readNext(r,0));assertThat(payloadGetters(f)).isZero();}
        }
    }
    @Test void metadataTamperingAndCoverageFailBeforePayloadButActualPayloadTamperingReadsIt() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("scope_text")?v.toString().replace("\"sourceDecisionId\":", "\"unknownField\":"):v;deniedRead(()->f.reader.readNext(r,0));assertThat(payloadGetters(f)).isZero();}
            f.source.change=(k,v)->v;f.auth.narrow();try(var r=f.open(b,0)){f.source.start();deniedRead(()->f.reader.readNext(r,0));assertThat(payloadGetters(f)).isZero();}f.auth.resetCoverage();
            try(var r=f.open(b,0)){f.source.start();f.source.change=(k,v)->k.equals("event_text")?v.toString().replace("观察","篡改"):v;deniedRead(()->f.reader.readNext(r,0));assertThat(payloadGetters(f)).isGreaterThan(0);}
        }
    }
    @Test void ambientReadCommittedAndRepeatableReadAreBothRejected() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){for(int isolation:List.of(2,4)){f.tx.setIsolationLevel(isolation);f.source.start();f.tx.execute(s->{assertThatThrownBy(()->f.reader.readNext(r,0)).isInstanceOf(IllegalStateException.class);return null;});assertThat(f.source.sql).isEmpty();}}}
    }
    @Test void concurrentAppendDoesNotMixReadSnapshotAndNextRoundSeesIt() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){var once=new AtomicBoolean();f.source.start();f.source.onQuery=q->{if(q.contains("engine.material_scope")&&once.compareAndSet(false,true))outside(()->f.append(b.runId(),"B_CONCURRENT",Map.of("n",2)));};
            try(var e=f.reader.readNext(r,0).orElseThrow()){assertThat(e.info().seq()).isEqualTo(1);}f.source.onQuery=q->{};
            try(var e=f.reader.readNext(r,1).orElseThrow()){assertThat(e.info().seq()).isEqualTo(2);}
        }}
    }
    @Test void stopGenerationExpiryAndCommitAfterRevocationNeverMintAnEmission() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);for(String mode:List.of("stop","generation","expiry","commit")){try(var r=f.open(b,0)){f.source.start();var once=new AtomicBoolean();Runnable stop=()->{if(once.compareAndSet(false,true)){if(mode.equals("generation"))outside(()->f.auth.renew(r));else if(mode.equals("expiry"))f.auth.advance(6);else r.close();}};
                if(mode.equals("commit"))f.source.afterCommit=stop;else f.source.onQuery=q->{if(q.contains("engine.material_scope"))stop.run();};deniedRead(()->f.reader.readNext(r,0));f.source.afterCommit=()->{};f.source.onQuery=q->{};
                if(mode.equals("expiry"))f.auth.advance(-6);
            }}
        }
    }
    @Test void twoPendingOrActiveEmissionsOwnPermitsUntilActualConsumerFinally() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0);var pool=Executors.newSingleThreadExecutor()){
            var first=f.reader.readNext(r,0).orElseThrow();var second=f.reader.readNext(r,0).orElseThrow();capacity(()->f.reader.readNext(r,0),"WORKSPACE");
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var borrowed=new AtomicReference<RunEventReader.Borrow>();
            var active=pool.submit(()->assertThatThrownBy(()->first.consume(view->{borrowed.set(view);entered.countDown();await(release);view.requireLive();})).isInstanceOf(PlatformException.class));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();first.close();first.close();capacity(()->f.reader.readNext(r,0),"WORKSPACE");release.countDown();active.get(2,TimeUnit.SECONDS);deniedRead(()->borrowed.get().requireLive());
            second.close();second.close();try(var next=f.reader.readNext(r,0).orElseThrow()){assertThatThrownBy(()->next.consume(v->{throw new IllegalStateException("consumer");})).isInstanceOf(IllegalStateException.class);}
            try(var next=f.reader.readNext(r,0).orElseThrow()){f.auth.renew(r);deniedRead(()->next.consume(v->{}));}
            try(var next=f.reader.readNext(r,0).orElseThrow()){assertThat(next.info().seq()).isEqualTo(1);}
        }}
    }
    static void await(CountDownLatch latch){try{if(!latch.await(3,TimeUnit.SECONDS))throw new AssertionError("LATCH_TIMEOUT");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static void outside(Runnable work){try(var pool=Executors.newSingleThreadExecutor()){pool.submit(work).get(3,TimeUnit.SECONDS);}catch(Exception e){throw new AssertionError(e);}}
    @Test void logicalCancellationWhileTwoRealJdbcQueriesWaitDoesNotReturnPermitsEarly() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0);var callers=Executors.newFixedThreadPool(2);var lock=f.source.getTargetDataSource().getConnection()){
            lock.setAutoCommit(false);try(var s=lock.createStatement()){s.execute("lock table engine.run in access exclusive mode");}
            var entered=new CountDownLatch(2);var exited=new CountDownLatch(2);f.source.start();f.source.onQuery=q->{if(q.startsWith("select id,trigger_type"))entered.countDown();};
            var tasks=new ArrayList<Future<?>>();for(int i=0;i<2;i++)tasks.add(callers.submit(()->{try{deniedRead(()->f.reader.readNext(r,0));}finally{exited.countDown();}}));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();r.close();tasks.forEach(t->t.cancel(false));
            try(var fresh=f.open(b,0)){capacity(()->f.reader.readNext(fresh,0),"WORKSPACE");assertThat(exited.getCount()).isEqualTo(2);lock.commit();assertThat(exited.await(3,TimeUnit.SECONDS)).isTrue();f.source.onQuery=q->{};try(var e=f.reader.readNext(fresh,0).orElseThrow()){assertThat(e.info().seq()).isEqualTo(1);}}
        }}
    }
    @Test void realWriterEventOverPublicBudgetHasDistinctCapacityAndNoEventTextGetter() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);f.append(b.runId(),"B_OVERSIZED",Map.of("padding","x".repeat(262144)));try(var r=f.open(b,0)){f.source.start();capacity(()->f.reader.readNext(r,1),"EVENT_PAYLOAD");assertThat(f.source.getters).doesNotContain("event_text");}}
    }
    @Test void realPostgresValuesUseSameProbeAndCaseAtExactUtf8ByteBudgets() throws Exception {
        try(var f=new Fixture()){
            var cases=new ArrayList<Map<String,Object>>();for(int cap:List.of(1048576,8388608))for(int extra:List.of(0,1)){
                String value="\"中"+"x".repeat(cap-5+extra)+"\"";assertThat(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(cap+extra);
                var probe=f.db.queryForMap("select octet_length(value::text) as bytes,jsonb_typeof(value) as type from (values (?::jsonb)) material(value)",value);assertThat(((Number)probe.get("bytes")).longValue()).isEqualTo(cap+extra);assertThat(probe.get("type")).isEqualTo("string");
                var row=f.db.queryForMap("select case when octet_length(value::text)<=? then value::text end as limited from (values (?::jsonb)) material(value)",cap,value);if(extra==0)assertThat(row.get("limited")).isEqualTo(value);else assertThat(row.get("limited")).isNull();cases.add(Map.of("cap",cap,"extra",extra,"actualBytes",cap+extra,"textReturned",row.get("limited")!=null));
            }
            // Complete repeated records, not unique-hash accounting, hit 8MiB and +1 in PG.
            var sums=f.db.queryForList("select sum(octet_length(value::text)) as bytes from (values (?::jsonb),(?::jsonb)) material(value)","\""+"x".repeat(4194302)+"\"","\""+"x".repeat(4194302)+"\"");assertThat(((Number)sums.getFirst().get("bytes")).longValue()).isEqualTo(8388608);
            java.nio.file.Files.writeString(java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-b-material-reader","sql-boundaries-"+UUID.randomUUID()+".json"),Json.write(cases));
        }
    }
    @Test void statementTimeoutUsesRemainingFloorAfterParameterPreparation() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0)){
            var captured=response.currentCapture();f.source.start();var delayed=new AtomicBoolean();var trajectory=new ArrayList<Map<String,Object>>();
            f.source.duringBind=()->{if(delayed.compareAndSet(false,true))try{Thread.sleep(30);}catch(InterruptedException e){throw new AssertionError(e);}};
            f.source.onQuery=q->{if(!q.startsWith("select set_config")){long remaining=captured.remainingNanos();trajectory.add(Map.of("sql",q,"configuredTimeoutMs",f.source.configuredTimeout.get(),"remainingNanosAtDriverInvocation",remaining));assertThat(f.source.configuredTimeout.get()).isPositive().isLessThanOrEqualTo(remaining/1000000+1);}};
            try(var e=f.reader.readNext(response,0).orElseThrow()){assertThat(e.info().seq()).isEqualTo(1);}
            java.nio.file.Files.writeString(java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-b-material-reader","timeout-trajectory-"+UUID.randomUUID()+".json"),Json.write(trajectory));
        }}
    }
    @Test void newMeasurementRecordsTokenBackingCanonicalModelAndTotalPeaks() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var r=f.open(b,0)){
            f.source.start();try(var emission=f.reader.readNext(r,0).orElseThrow()){
                var measured=emission.info().measurements();assertThat(measured.modelReserved()).isPositive().isLessThanOrEqualTo(64L*1024*1024);assertThat(measured.totalReservedPeak()).isLessThanOrEqualTo(192L*1024*1024);assertThat(measured.canonicalActualUtf8Peak()).isPositive().isLessThanOrEqualTo(8L*1024*1024);assertThat(measured.tokens()).isPositive();assertThat(measured.parserBackingChars()).isPositive();
                var backing=new AtomicInteger();emission.consume(view->{try{view.writePayload(new java.io.OutputStream(){public void write(int c){}@Override public void write(byte[] data){backing.set(data.length);}});}catch(java.io.IOException e){throw new AssertionError(e);}});assertThat(backing.get()).isEqualTo(measured.eventBackingBytes());
                long parents=0,bytes=0,max=0;var revisions=new ArrayList<Map<String,Object>>();for(String raw:f.source.scopeTexts){var s=RunEventReader.scope(RunEventReader.parse(raw,false,()->{}));parents+=s.parentScopeSha256().size();long length=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;bytes+=length;max=Math.max(max,length);revisions.add(Map.of("kind",s.material().kind(),"locator",s.material().id(),"revision",s.material().version(),"parents",s.parentScopeSha256().size(),"bytes",length));}
                var report=new LinkedHashMap<String,Object>();report.put("run",b.publicId());report.put("database",DATABASE);report.put("N",15);report.put("A",0);report.put("E",1);report.put("U",0);report.put("D",f.source.scopeTexts.size());report.put("H",parents);report.put("scopeUtf8Bytes",bytes);report.put("maxScopeUtf8Bytes",max);report.put("measurements",measured);report.put("actualEventArrayBacking",backing.get());report.put("revisions",revisions);report.put("accountedOwnerUpperBytes",2*RunEventReader.REPRESENTATION_UPPER_PER_WORKSPACE);report.put("heapMeaning","reserved conservative representations; not measured exact JVM heap");
                java.nio.file.Files.writeString(java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-b-material-reader","representation-sample-"+UUID.randomUUID()+".json"),Json.write(report));
            }
        }}
    }
    @Test void realPostgresAggregateBudgetsAcceptExactEightMiBAndRefusePlusOneBeforeText() throws Exception {
        try(var f=new Fixture()){
            var evidence=new ArrayList<Map<String,Object>>();
            for(boolean root:List.of(false,true))for(int extra:List.of(0,1)){
                var lengths=new ArrayList<Integer>();if(root){lengths.add(4194304);lengths.add(4194304+extra);}else{for(int i=0;i<7;i++)lengths.add(1048576);lengths.add(1048572);lengths.add(4+extra);}
                var values=lengths.stream().map(n->"\""+"x".repeat(n-2)+"\"").toArray();String from=" from (values "+String.join(",",Collections.nCopies(values.length,"(?::jsonb)"))+") material(value)";
                f.source.start();var rows=f.db.queryForList("select octet_length(value::text) as bytes,jsonb_typeof(value) as type"+from,values);var limit=new RunEventReader.Limits();long actual=rows.stream().mapToLong(r->((Number)r.get("bytes")).longValue()).sum();assertThat(actual).isEqualTo(8388608+extra);
                Runnable admit=()->{for(var row:rows)if(root)limit.root(((Number)row.get("bytes")).longValue());else limit.metadata(((Number)row.get("bytes")).longValue());};
                if(extra==1){capacity(admit,root?"ROOT_BYTES":"METADATA_BYTES");assertThat(f.source.getters).doesNotContain("limited_text");}
                else{admit.run();var texts=f.db.queryForList("select case when octet_length(value::text)<="+(root?8388608:1048576)+" then value::text end as limited_text"+from,values);assertThat(texts).allSatisfy(row->assertThat(row.get("limited_text")).isNotNull());}
                evidence.add(Map.of("dimension",root?"ROOT_BYTES":"METADATA_BYTES","actualUtf8Bytes",actual,"extra",extra,"textGetters",f.source.getters.stream().filter(s->s.equals("limited_text")).count()));
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of("..","docs","evidence","iam-stage-4-o2a-b-material-reader","aggregate-boundaries-"+UUID.randomUUID()+".json"),Json.write(evidence));
        }
    }
    @Test void pendingCloseWinsBeginAndOnceOnlyConsumerCannotExportOrReuseBorrow() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0)){
            var closed=f.reader.readNext(response,0).orElseThrow();closed.close();deniedRead(()->closed.consume(v->{}));
            var first=f.reader.readNext(response,0).orElseThrow();var second=f.reader.readNext(response,0).orElseThrow();var borrow=new AtomicReference<RunEventReader.Borrow>();first.consume(borrow::set);deniedRead(()->first.consume(v->{}));deniedRead(()->borrow.get().info());second.close();
            try(var next=f.reader.readNext(response,0).orElseThrow()){assertThat(next.info().seq()).isEqualTo(1);}
        }}
    }
    @Test void commonRecyclerLargeBuffersDoNotSurviveBFinallyAndOtherThreadsAreUntouched() throws Exception {
        try(var f=new Fixture()){var b=f.seed(0);try(var response=f.open(b,0);var pool=Executors.newSingleThreadExecutor()){
            var recycler=com.fasterxml.jackson.core.util.BufferRecyclers.getBufferRecycler();
            assertThat(Json.mapper().getFactory()._getBufferRecycler()).isSameAs(recycler);
            assertThat(Json.mapper().copy().getFactory()._getBufferRecycler()).isSameAs(recycler);
            var ready=new CountDownLatch(1);var release=new CountDownLatch(1);
            var other=pool.submit(()->{var own=com.fasterxml.jackson.core.util.BufferRecyclers.getBufferRecycler();own.releaseCharBuffer(com.fasterxml.jackson.core.util.BufferRecycler.CHAR_TEXT_BUFFER,new char[262145]);ready.countDown();await(release);return own.allocCharBuffer(com.fasterxml.jackson.core.util.BufferRecycler.CHAR_TEXT_BUFFER).length;});
            assertThat(ready.await(2,TimeUnit.SECONDS)).isTrue();
            try{
                recycler.releaseCharBuffer(com.fasterxml.jackson.core.util.BufferRecycler.CHAR_TEXT_BUFFER,new char[262144]);
                assertThat(f.reader.readNext(response,1)).isEmpty();
                assertThat(recycler.allocCharBuffer(com.fasterxml.jackson.core.util.BufferRecycler.CHAR_TEXT_BUFFER)).hasSize(200);
            }finally{release.countDown();}
            assertThat(other.get(2,TimeUnit.SECONDS)).isEqualTo(262145);
        }}
    }
}
