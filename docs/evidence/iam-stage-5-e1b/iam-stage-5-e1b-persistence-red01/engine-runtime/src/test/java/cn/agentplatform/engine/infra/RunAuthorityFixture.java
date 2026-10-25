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
 final DataSource ds;
 final JdbcTemplate db;
 final TransactionTemplate tx;
 final RunAuthorityStore store;
 static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("candidate"));
 static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
 static final ServiceCall LOCAL=new ServiceCall("engine","run.advance",ROOT);
 RunAuthorityFixture()throws Exception{
  iam.portalActor(actor(Set.of("A","B")));
  gateway=iam.client("gateway");engine=iam.client("engine");
  ds=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:25432/agent_platform_iam_engine_v12","agent_v12",System.getenv("AGENT_DB_PASSWORD"));
  db=new JdbcTemplate(ds);if(!"agent_platform_iam_engine_v12".equals(db.queryForObject("select current_database()",String.class)))throw new AssertionError();
  Flyway.configure().dataSource(ds).schemas("engine").defaultSchema("engine").locations("classpath:db/migration").load().migrate();
  tx=new TransactionTemplate(new DataSourceTransactionManager(ds));store=new RunAuthorityStore(ds,iam.clock());
 }
 static Actor actor(Set<String> regions){return new Actor("tenant-a","alice",Set.of("EDITOR"),Map.of("region",regions,"dataLabels",Set.of("initial")));}
 JsonNode snapshot(){return Json.read("{\"appCode\":\"app-a\",\"versionNo\":1,\"appVersionId\":12,\"candidateSha256\":\""+ROOT.versionDigest()+"\",\"config\":{}}");}
 JsonNode budget(){return Json.tree(Map.of("limits",Map.of("maxSteps",25,"modelCalls",40,"totalTokens",200000,"totalDurationMs",600000,"maxDepth",2),"consumed",Map.of("steps",0,"modelCalls",0,"tokens",0,"depth",0),"deadline",iam.clock().instant().plusSeconds(599).toString()));}
 <T>T admitted(Function<RetainedAuthorization,T> work)throws Exception{return admitted(MAIN,work);}
 <T>T admitted(ServiceCall call,Function<RetainedAuthorization,T> work)throws Exception{
  var ticket=gateway.exchange(iam.portal(),call);
  try(var decoder=new TrustedJwtDecoder(iam.config("engine"),iam.clock(),true)){
   var request=new MockHttpServletRequest("POST","/admin/apps/app-a/runs");var response=new MockHttpServletResponse();
   request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");
   RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
   SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(decoder.decode(ticket.value())));
   var handler=new HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));
   var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),engine);
   try{guard.preHandle(request,response,handler);var proof=AuthorityContext.requireInitialFor(Actor.current(),call.resource());return work.apply(proof);}
   finally{guard.afterCompletion(request,response,handler,null);SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
  }
 }
 long create(RetainedAuthorization proof){return store.createPublished(proof,snapshot(),Json.read("{\"a\":1}"),Json.read("{\"appVer\":1}"),budget(),"FULL",UUID.randomUUID().toString(),Set.of("config"));}
 long count(){return db.queryForObject("select count(*) from engine.run",Long.class);}
 @Override public void close(){engine.close();gateway.close();iam.close();}
}
