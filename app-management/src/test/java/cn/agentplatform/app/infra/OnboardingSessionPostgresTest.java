package cn.agentplatform.app.infra;

import cn.agentplatform.common.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** CR05独立DEV库：真实持久、创建重放、用户归属与CAS；不清表、不操作共享schema。 */
@EnabledIfEnvironmentVariable(named="CR05_DB_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:35432/agent_cr05_20261104")
class OnboardingSessionPostgresTest {
    static JdbcTemplate db;
    static PlatformTransactionManager manager;
    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource(System.getenv("CR05_DB_URL"), System.getenv("CR05_DB_USER"), System.getenv("CR05_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                .locations("filesystem:" + System.getenv("CR05_MIGRATIONS")).load().migrate();
        db = new JdbcTemplate(ds); manager = new DataSourceTransactionManager(ds);
    }
    Object repository() throws Exception { return Class.forName("cn.agentplatform.app.infra.OnboardingSessionRepository")
            .getConstructor(JdbcTemplate.class, PlatformTransactionManager.class).newInstance(db, manager); }
    @SuppressWarnings("unchecked") Map<String,Object> call(Object repo,String name,Class<?>[] types,Object... args) throws Exception {
        try { return (Map<String,Object>) repo.getClass().getMethod(name,types).invoke(repo,args); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof RuntimeException ex) throw ex; throw failure; }
    }
    Actor actor() { return new Actor("cr05-"+UUID.randomUUID(),"editor",Set.of("EDITOR"),Map.of()); }
    Map<String,Object> seed() { return Map.of("sessionId","os_"+UUID.randomUUID().toString().replace("-",""),
            "templateCode","tpl_query","templateRev",1,"appCode","query_app","appName","Query app",
            "template",Map.of("sourcePackageVersionId",1),"plan",Map.of("values",Map.of()),"planSha256","a".repeat(64)); }
    Map<String,Object> create(Object repo,Actor actor,Map<String,Object> seed) throws Exception {
        return call(repo,"create",new Class<?>[]{Actor.class,Map.class,String.class,String.class},actor,seed,"create-1","b".repeat(64));
    }
    @Test void sessionCreationAndReplayAreDurableAndOwned() throws Exception {
        var repo=repository(); var actor=actor(); var seed=seed(); var first=create(repo,actor,seed);
        assertEquals(1,((Number)first.get("rev")).intValue());
        assertEquals(first.get("sessionId"),create(repo,actor,seed).get("sessionId"));
        var restarted=repository();
        assertEquals(first.get("plan"),call(restarted,"get",new Class<?>[]{Actor.class,String.class},actor,first.get("sessionId")).get("plan"));
        var outsider=new Actor(actor.tenantId(),"other",Set.of("EDITOR"),Map.of());
        var denied=assertThrows(PlatformException.class,()->call(repo,"get",new Class<?>[]{Actor.class,String.class},outsider,first.get("sessionId")));
        assertEquals(404,denied.status());
        assertEquals(1,db.queryForObject("select count(*) from app.onboarding_session where tenant_id=?",Integer.class,actor.tenantId()));
    }
    @Test void planUpdatesUseCasAndPreserveFalseZeroAndNull() throws Exception {
        var repo=repository(); var actor=actor(); var current=create(repo,actor,seed());
        var values=new LinkedHashMap<String,Object>(); values.put("enabled",false); values.put("count",0); values.put("note",null);
        var plan=Map.<String,Object>of("values",values);
        var types=new Class<?>[]{Actor.class,String.class,int.class,Map.class,String.class,String.class,String.class};
        var saved=call(repo,"save",types,actor,current.get("sessionId"),1,plan,"c".repeat(64),"save-1","d".repeat(64));
        assertEquals(plan,saved.get("plan")); assertEquals(2,((Number)saved.get("rev")).intValue());
        var stale=assertThrows(PlatformException.class,()->call(repo,"save",types,actor,current.get("sessionId"),1,plan,"c".repeat(64),"save-2","e".repeat(64)));
        assertEquals("CONFIG_REV_CONFLICT",stale.code());
        assertEquals(2,((Number)call(repo,"save",types,actor,current.get("sessionId"),1,plan,"c".repeat(64),"save-1","d".repeat(64)).get("rev")).intValue());
        assertThrows(PlatformException.class,()->call(repo,"save",types,actor,current.get("sessionId"),2,plan,"c".repeat(64),"save-1","e".repeat(64)));
    }
    @Test void cancellationPersistsReasonAndTerminalPlanCannotBeRewritten() throws Exception {
        var repo=repository();var actor=actor();var created=create(repo,actor,seed());String id=created.get("sessionId").toString();
        var cancelled=call(repo,"cancel",new Class<?>[]{Actor.class,String.class,int.class,String.class,String.class,String.class},actor,id,1,"cancel-1","f".repeat(64),"不再接入此资源");
        assertEquals("CANCELLED",cancelled.get("status"));assertEquals("不再接入此资源",call(repository(),"get",new Class<?>[]{Actor.class,String.class},actor,id).get("cancelReason"));
        assertThrows(PlatformException.class,()->call(repo,"save",new Class<?>[]{Actor.class,String.class,int.class,Map.class,String.class,String.class,String.class},actor,id,2,Map.of(),"a".repeat(64),"after-cancel","b".repeat(64)));
    }
    @Test void lateWorkerCannotReplaceNewGenerationAndOriginalRunReceiptSurvivesReclaim() throws Exception {
        var repo=repository();var actor=actor();var created=create(repo,actor,seed());String id=created.get("sessionId").toString();
        var start=repo.getClass().getMethod("start",Actor.class,String.class,String.class,String.class,String.class,String.class,String.class);
        var finish=repo.getClass().getMethod("finish",Actor.class,String.class,String.class,String.class,String.class,String.class,Map.class,Map.class);
        start.invoke(repo,actor,id,"a".repeat(64),"VERIFY","verify-1","b".repeat(64),"token-one");
        finish.invoke(repo,actor,id,"a".repeat(64),"VERIFY","token-one","RUNNING",Map.of("runId","r_original"),null);
        start.invoke(repo,actor,id,"a".repeat(64),"VERIFY","verify-2","c".repeat(64),"token-two");
        var failure=assertThrows(InvocationTargetException.class,()->finish.invoke(repo,actor,id,"a".repeat(64),"VERIFY","token-one","SUCCEEDED",Map.of("runId","r_wrong"),null));
        assertInstanceOf(PlatformException.class,failure.getCause());
        var current=call(repo,"get",new Class<?>[]{Actor.class,String.class},actor,id);
        assertEquals("r_original",Json.tree(current.get("steps")).path(0).path("receipt").path("runId").asText());
    }
}
