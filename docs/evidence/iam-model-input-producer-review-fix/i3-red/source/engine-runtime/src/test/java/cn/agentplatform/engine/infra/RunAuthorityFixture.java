package cn.agentplatform.engine.infra;
import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;
import cn.agentplatform.engine.api.RunController;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.context.request.*;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.flywaydb.core.Flyway;
final class RunAuthorityFixture implements AutoCloseable {
 final LoopbackAuthority iam=new LoopbackAuthority();
 final HttpCurrentAuthorityClient gateway,engine;
 final CountingDataSource ds;
 final JdbcTemplate db;
 final TransactionTemplate tx;
 final RunAuthorityStore store;
 static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("candidate"));
 static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
 static final ServiceCall LOCAL=new ServiceCall("engine","run.advance",ROOT);
 RunAuthorityFixture()throws Exception{this("agent_platform_iam_engine_v12");}
 RunAuthorityFixture(String database)throws Exception{
  if(!Set.of("agent_platform_iam_engine_v12","agent_platform_iam_flow_material_v12","agent_platform_iam_owned_flow_v12").contains(database))throw new IllegalArgumentException("ISOLATED_DATABASE_REQUIRED");
  iam.portalActor(actor(Set.of("A","B")));
  gateway=iam.client("gateway");engine=iam.client("engine");
  ds=new CountingDataSource(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/"+database,"agent_v12",System.getenv("AGENT_DB_PASSWORD")));
  db=new JdbcTemplate(ds);if(!database.equals(db.queryForObject("select current_database()",String.class)))throw new AssertionError();
  Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
  tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new RunAuthorityStore(ds,iam.clock());
 }
 static Actor actor(Set<String> regions){return new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",regions,"dataLabels",Set.of("initial")));}
 JsonNode snapshot(){return Json.read("{\"appCode\":\"app-a\",\"versionNo\":1,\"appVersionId\":12,\"candidateSha256\":\""+ROOT.versionDigest()+"\",\"config\":{}}");}
 JsonNode budget(){return Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",iam.clock().instant().plusSeconds(599).toString()));}
 <T>T admitted(Function<RetainedAuthorization,T> work)throws Exception{return admitted(MAIN,work);}
 <T>T admitted(ServiceCall call,Function<RetainedAuthorization,T> work)throws Exception{
  ServiceTicket ticket;try(var admissionGateway=iam.client("gateway")){ticket=admissionGateway.exchange(iam.portal(),call);}
  try(var decoder=new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true)){
   var request=new MockHttpServletRequest("POST","/admin/apps/app-a/runs");var response=new MockHttpServletResponse();
   request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");
   RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
   SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));
   var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));
   var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);
   try{guard.preHandle(request,response,handler);var proof=AuthorityContext.requireInitialFor(Actor.current(),call.resource());return work.apply(proof);}
   finally{guard.afterCompletion(request,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  }
 }
 long create(RetainedAuthorization proof){return store.createPublished(proof,snapshot(),Json.read("{\"a\":1}"),Json.read("{\"appVer\":1}"),budget(),"FULL",UUID.randomUUID().toString(),Set.of("config"));}
 long count(){return db.queryForObject("select count(*) from engine.run",Long.class);}
 static final class CountingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
  final java.util.concurrent.atomic.AtomicInteger payloadReads=new java.util.concurrent.atomic.AtomicInteger();
  volatile Runnable beforePayload=()->{};
  volatile java.util.function.IntConsumer beforeAuthorityUpdate=pid->{};
  volatile Runnable afterSuccessCommit=()->{};
  volatile Runnable afterPayload=()->{},afterMaterialCommit=()->{},afterReadCommit=()->{};
  volatile java.util.function.Consumer<String> beforeSql=sql->{};
  volatile java.util.function.IntConsumer beforeRunLock=pid->{};
  final java.util.List<String> payloadObservations=new java.util.concurrent.CopyOnWriteArrayList<>();
  CountingDataSource(DataSource target){super(target);}
  @Override public java.sql.Connection getConnection()throws java.sql.SQLException{return wrap(super.getConnection());}
  private java.sql.Connection wrap(java.sql.Connection connection){boolean[] successPending={false},materialPending={false},readPending={false};return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{
   try{Object result=method.invoke(connection,args);
    // The real PG commit completes first; only this connection's successful authority UPDATE arms it.
    if(method.getName().equals("commit")&&successPending[0]){successPending[0]=false;afterSuccessCommit.run();}
    if(method.getName().equals("commit")){if(materialPending[0])afterMaterialCommit.run();else if(readPending[0])afterReadCommit.run();materialPending[0]=false;readPending[0]=false;}
    if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql)beforeSql.accept(sql);
    if(method.getName().equals("prepareStatement")&&"select id from engine.run where id=? for update".equals(args[0])){
     var statement=(java.sql.PreparedStatement)result;
     return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{try{if(m.getName().equals("executeQuery")){try(var q=connection.createStatement();var rows=q.executeQuery("select pg_backend_pid()")){rows.next();beforeRunLock.accept(rows.getInt(1));}}return m.invoke(statement,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
    }
    if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.startsWith("update engine.run_authority set last_decision_id=")){
     var statement=(java.sql.PreparedStatement)result;boolean[] success={false};
     return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{try{
      if(m.getName().startsWith("set")&&a!=null&&a.length>=2&&Integer.valueOf(1).equals(a[0]))success[0]=a[1] instanceof String;
      if(m.getName().equals("executeUpdate")){try(var q=connection.createStatement();var rows=q.executeQuery("select pg_backend_pid()")){rows.next();beforeAuthorityUpdate.accept(rows.getInt(1));}}
      Object value=m.invoke(statement,a);if(m.getName().equals("executeUpdate")&&success[0]&&Integer.valueOf(1).equals(value))successPending[0]=true;return value;
     }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
    }
    if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&sql.startsWith("insert into engine.material_scope")){
     var statement=(java.sql.PreparedStatement)result;
     return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{try{var value=m.invoke(statement,a);if(m.getName().equals("executeUpdate"))materialPending[0]=true;return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
    }
    if(method.getName().equals("prepareStatement")&&args[0] instanceof String sql&&(sql.matches("(?is)select (snapshot|inputs|version_refs|budget|output|error|definition|request_snapshot|checkpoint)::text from engine\\.(run|node_instance|attempt).*"))||method.getName().equals("prepareStatement")&&args[0] instanceof String sql2&&sql2.startsWith("select row_to_json(material)::text")){
     var statement=(java.sql.PreparedStatement)result;
     return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{java.sql.PreparedStatement.class},(p,m,a)->{try{if(m.getName().equals("executeQuery")){payloadReads.incrementAndGet();readPending[0]=true;payloadObservations.add(System.identityHashCode(connection)+":"+connection.getTransactionIsolation()+":"+connection.isReadOnly()+":"+connection.getAutoCommit());beforePayload.run();}var value=m.invoke(statement,a);if(m.getName().equals("executeQuery"))afterPayload.run();return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});
    }return result;
   }catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
  });}
 }
 @Override public void close(){engine.close();gateway.close();iam.close();}
}

