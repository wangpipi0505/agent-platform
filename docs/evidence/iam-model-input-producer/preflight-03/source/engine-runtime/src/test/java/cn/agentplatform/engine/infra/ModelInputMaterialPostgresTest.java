package cn.agentplatform.engine.infra;
import cn.agentplatform.common.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class ModelInputMaterialPostgresTest {
 @Test void preflight217AndSourceContinuity()throws Exception{try(var f=new ModelInputMaterialFixture(true)){assertThat(f.db.queryForList("select version from engine.flyway_schema_history where success order by installed_rank",String.class)).containsExactly("200","201","202","203","204","205","206","207","208","209","210","211","212","213","214","215","216","217");}}
 @Test void persistsRequestAndScopeAtomicallyWithFullParentUnion()throws Exception{try(var f=new ModelInputMaterialFixture()){long run=f.create();var m=f.materials.prepareInitial(run);assertThat(m).isNotNull();assertThat(f.count("model_input_snapshot")).isEqualTo(1);assertThat(m.parents()).hasSize(3);assertThat(m.scope().requiredDataLabels()).contains("initial","config","parent");}}
 @Test void missingRootScopeMustDeny()throws Exception{try(var f=new ModelInputMaterialFixture()){long run=f.create();f.db.execute("alter table engine.material_scope disable trigger material_scope_immutable");f.db.update("delete from engine.material_scope where run_id=? and kind='run-inputs'",run);assertThatThrownBy(()->f.materials.prepareInitial(run)).isInstanceOf(PlatformException.class);}}
}
