package cn.agentplatform.common.authority;
import cn.agentplatform.common.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LocalRunAdvanceAuthorityTest {
 static final ResourceBinding ROOT=new ResourceBinding("app-version","app-a@1",Digest.sha256("candidate"));
 static final ServiceCall MAIN=new ServiceCall("engine","proxy.engine.post.RunController.run",ROOT);
 static final ServiceCall LOCAL=new ServiceCall("engine","run.advance",ROOT);
 @Test void mainPortalGrantResumesLocallyWithRealSignatureAndCurrent()throws Exception {
  try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
   var main=engine.authorizeRetained(gateway.exchange(f.portal(),MAIN).value(),MAIN);
   var initial=main.requireInitialCeiling();
   f.clock.advance(41);
   assertThatCode(()->{
   var ticket=engine.resume(main.current().delegation(),LOCAL);
   var current=engine.authorizeRetained(ticket.value(),LOCAL);current.requireVerified();
   assertThat(current.current().caller()).isEqualTo("engine");
   assertThat(current.current().operation().sourceKind()).isEqualTo("DELEGATED");
   assertThat(current.requireInitialCeiling()).isEqualTo(initial);
   }).doesNotThrowAnyException();
  }
 }
 @Test void localTupleNeverBecomesAnHttpRoute(){assertThat(new ServiceRoutePolicy().routes()).noneMatch(r->r.action().equals("run.advance"));}
}
