package cn.agentplatform.model.infra;
import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.model.application.FrozenModelBindingConsumerFixture;
import org.junit.jupiter.api.Test;
class FrozenModelBindingStorePostgresTest {
 @Test void ordinaryStoreRequiresActualReadOnlyTransaction()throws Exception{
  try(var f=new FrozenModelBindingConsumerFixture()){
   assertThatThrownBy(()->new FrozenModelBindingStore(f.ds).read("tenant-a",f.id)).isInstanceOf(IllegalStateException.class);
   assertThat(f.ds.reads).hasValue(0);
  }
 }
}
