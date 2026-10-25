package cn.agentplatform.engine.infra;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class RunAuthorityPersistencePostgresTest {
 @Test void actualPublishedInsertAndThreeScopesShareTheCallerTransaction()throws Exception{
  try(var f=new RunAuthorityFixture()){
   long before=f.count();
   assertThatCode(()->f.admitted(p->f.tx.execute(s->{long id=f.create(p);assertThat(f.db.queryForObject("select count(*) from engine.run_authority where run_id=?",Integer.class,id)).isEqualTo(1);assertThat(f.db.queryForObject("select count(*) from engine.material_scope where run_id=?",Integer.class,id)).isEqualTo(3);return id;}))).doesNotThrowAnyException();
   assertThat(f.count()).isEqualTo(before+1);
  }
 }
}
