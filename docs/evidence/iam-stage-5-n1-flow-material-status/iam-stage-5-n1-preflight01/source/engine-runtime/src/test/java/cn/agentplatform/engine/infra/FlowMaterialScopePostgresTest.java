package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static org.assertj.core.api.Assertions.*;

class FlowMaterialScopePostgresTest {
 static RunAuthorityFixture fixture()throws Exception{return new RunAuthorityFixture("agent_platform_iam_flow_material_v12");}
 static TraceStore trace(RunAuthorityFixture f){return new TraceStore(f.db,new DataSourceTransactionManager(f.ds));}
 @Test void realAdmissionRetainsThreeInitialsAndAddsFourActualNullableRunMaterials()throws Exception{
  try(var f=fixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));
   assertThat(f.store.load(id).scopes()).hasSize(3);
   assertThat(f.db.queryForList("select kind from engine.material_scope where run_id=? order by kind",String.class,id)).containsExactly("run-budget","run-error","run-inputs","run-output","run-snapshot","run-state","run-version-refs");
  }
 }
 @Test void protectedLegacyEntryPointsDenyBeforeAnyPayloadReadOrMutation()throws Exception{
  try(var f=fixture()){
   long id=f.admitted(p->f.tx.execute(s->f.create(p)));var b=f.store.load(id);var trace=trace(f);
   assertThatThrownBy(()->trace.run(b.publicId(),RunAuthorityFixture.actor(java.util.Set.of("A","B")))).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->trace.nodes(id)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->trace.events(id,0)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->trace.event(id,"BAD",java.util.Map.of())).isInstanceOf(PlatformException.class);
   assertThat(f.db.queryForObject("select event_seq from engine.run where id=?",Integer.class,id)).isZero();
  }
 }
}
