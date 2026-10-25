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
 @Test void localCannotBeExchangedForwardedOrMintedByOtherService()throws Exception {
  try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
   var main=engine.authorizeRetained(gateway.exchange(f.portal(),MAIN).value(),MAIN);
   assertThatThrownBy(()->gateway.exchange(f.portal(),LOCAL)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->engine.client(LOCAL)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->engine.forward(main.current(),LOCAL)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->gateway.resume(main.current().delegation(),LOCAL)).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->engine.resume(main.current().delegation(),new ServiceCall("app","run.advance",ROOT))).isInstanceOf(PlatformException.class);
   assertThatThrownBy(()->engine.resume(main.current().delegation(),new ServiceCall("engine","run.advance",new ResourceBinding("run","wrong",ROOT.versionDigest())))).isInstanceOf(PlatformException.class);
  }
 }
 @Test void sameRootAppReadAndDebugGrantsCannotBecomeLocalMain()throws Exception {
  for(var call:List.of(new ServiceCall("app","proxy.app.get.AppController.get",ROOT),new ServiceCall("app",MetadataSelectors.ACTION,MetadataSelectors.published("tenant-a","app-a")),new ServiceCall("engine","proxy.engine.post.RunController.debug",ROOT),new ServiceCall("engine","proxy.engine.get.RunController.status",ROOT))){
   try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var target=f.client(call.target());var engine=f.client("engine")){
    var proof=target.authorizeRetained(gateway.exchange(f.portal(),call).value(),call);
    assertThatThrownBy(()->engine.resume(proof.current().delegation(),LOCAL)).isInstanceOf(PlatformException.class);
   }
  }
 }
 @Test void localWrongSourceOrOriginIsRejectedBySignedTicketValidation()throws Exception {
  for(String field:List.of("source_kind","origin_kind"))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
   var main=engine.authorizeRetained(gateway.exchange(f.portal(),MAIN).value(),MAIN);
   f.changeTicket=c->{var op=new LinkedHashMap<String,Object>();((Map<?,?>)c.get("operation")).forEach((k,v)->op.put((String)k,v));op.put(field,"INTEGRATION");c.put("operation",op);};
   assertThatThrownBy(()->engine.resume(main.current().delegation(),LOCAL)).isInstanceOf(PlatformException.class);
  }
 }
 @Test void localTupleNeverBecomesAnHttpRoute(){assertThat(new ServiceRoutePolicy().routes()).noneMatch(r->r.action().equals("run.advance"));}
}
