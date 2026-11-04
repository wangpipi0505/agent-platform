package cn.agentplatform.app.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** CR08：专属DEV功能库真实验证追加修订/CAS，失败不清表、不重建共享schema。 */
@EnabledIfEnvironmentVariable(named="CR08_DB_URL", matches="jdbc:postgresql://127[.]0[.]0[.]1:35432/agent_cr08_20261104")
class AssistantExperienceRevisionPostgresTest {
    static JdbcTemplate db;
    static AssistantExperienceRepository repository;

    @BeforeAll static void database() {
        var ds = new DriverManagerDataSource(System.getenv("CR08_DB_URL"),
                System.getenv("CR08_DB_USER"), System.getenv("CR08_DB_PASSWORD"));
        Flyway.configure().dataSource(ds).schemas("app").defaultSchema("app")
                .locations("classpath:db/migration").load().migrate();
        db = new JdbcTemplate(ds);
        repository = new AssistantExperienceRepository(db, new DataSourceTransactionManager(ds));
    }
    private Actor actor() {
        return new Actor("cr08-"+UUID.randomUUID(), "editor", Set.of("ADMIN"), Map.of());
    }
    private void app(Actor a) {
        db.update("insert into app.application(tenant_id,code,name,type,status,created_by)"
                +" values(?,'orders','Orders','CHAT','DRAFT',?)",a.tenantId(),a.principalId());
        repository.ensure(a,"orders");
    }
    private AssistantExperienceRepository.Revision revision(String title) {
        return new AssistantExperienceRepository.Revision(title,"Welcome",List.of(),List.of(),
                Map.of(),Map.of(),List.of(),"a".repeat(64));
    }
    @Test void approvedRevisionStartsNewDraftAndPreservesOldVersionAndCas() {
        var a=actor(); app(a);
        repository.append(a,"orders",revision("Original"),0,"create",()->Map.of());
        repository.transition(a,"orders",1,"DRAFT","APPROVED","approve",()->Map.of());
        repository.append(a,"orders",revision("Changed"),1,"revise",()->Map.of());
        var view=repository.view(a,"orders").orElseThrow();
        assertEquals("DRAFT",view.get("status"));
        assertEquals(2,((Number)view.get("experienceRev")).intValue());
        assertEquals("Original",db.queryForObject("select title from app.assistant_experience_revision"
                +" where tenant_id=? and app_code='orders' and experience_rev=1",String.class,a.tenantId()));
        var conflict=assertThrows(PlatformException.class,()->repository.append(a,"orders",revision("Stale"),1,"stale",()->Map.of()));
        assertEquals("CONFIG_REV_CONFLICT",conflict.code());
        assertEquals(2,db.queryForObject("select count(*) from app.assistant_experience_revision where tenant_id=?",Integer.class,a.tenantId()));
        repository.append(a,"orders",revision("Draft edited"),2,"edit",()->Map.of());
        repository.transition(a,"orders",3,"DRAFT","APPROVED","approve2",()->Map.of());
        assertEquals("APPROVED",repository.view(a,"orders").orElseThrow().get("status"));
    }
    @Test void disabledExperienceCannotBeRevivedByAppending() {
        var a=actor(); app(a);
        repository.append(a,"orders",revision("Original"),0,"create",()->Map.of());
        repository.transition(a,"orders",1,"DRAFT","DISABLED","disable",()->Map.of());
        var denied=assertThrows(PlatformException.class,()->repository.append(a,"orders",revision("Revive"),1,"revive",()->Map.of()));
        assertEquals("CONFIG_STATE_TRANSITION_INVALID",denied.code());
        assertEquals(1,db.queryForObject("select count(*) from app.assistant_experience_revision where tenant_id=?",Integer.class,a.tenantId()));
    }
}
