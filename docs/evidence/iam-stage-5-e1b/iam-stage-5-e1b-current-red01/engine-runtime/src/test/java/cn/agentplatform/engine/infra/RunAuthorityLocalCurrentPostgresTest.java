package cn.agentplatform.engine.infra;
import cn.agentplatform.engine.application.RunAuthority;
import cn.agentplatform.common.authority.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RunAuthorityLocalCurrentPostgresTest {
 @Test void newDelegatedOperationSurvivesExpiredPortalButUsesOriginalGrant()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));f.iam.advanceClock(Duration.ofSeconds(41));
   int before=f.iam.authorityCalls();
   assertThatCode(()->{var current=new RunAuthority(f.store,f.engine,f.iam.clock(),Duration.ofSeconds(3)).current(id,RunAuthorityFixture.LOCAL);assertThat(current.operation().sourceKind()).isEqualTo("DELEGATED");}).doesNotThrowAnyException();
   assertThat(f.iam.authorityCalls()).isEqualTo(before+2);
   assertThat(f.db.queryForObject("select check_version from engine.run_authority where run_id=?",Long.class,id)).isEqualTo(1);
  }
 }
}
