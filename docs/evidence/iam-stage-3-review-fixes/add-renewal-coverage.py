from pathlib import Path
p=Path('admin-common/src/test/java/cn/agentplatform/common/authority/ResponseAuthorityTest.java');s=p.read_text(encoding='utf8');pos=s.index('    @Test')
s=s[:pos]+'''    @Test void reviewI1RenewalPreservesCursorAcceptanceAndFinalReservation()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"renew-cursor"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var first=material(current,lease,1);guard.beforeMaterialWrite(first,access);
                f.clock.advance(6);guard.beforeWrite();assertThatThrownBy(()->guard.beforeMaterialWrite(first,access)).isInstanceOf(PlatformException.class);
                guard.beforeMaterialWrite(material(current,lease,2),access);f.clock.advance(15);f.revoked=true;
                guard.beforeMaterialWrite(material(current,lease,3),access);assertThat(guard.draining()).isTrue();
                assertThatThrownBy(()->guard.beforeMaterialWrite(material(current,lease,2),access)).isInstanceOf(PlatformException.class);
            }
        }
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var first=engine.authorize(gateway.exchange(f.integration(),entry).value(),entry);var call=LoopbackAuthority.call("engine","model");var current=model.authorize(engine.forward(first,call).value(),call);
            var lease=model.open(current,new ResponseBinding("renew-final","model",call.resource(),Digest.sha256("request"),f.clock.instant().plusSeconds(55)));
            try(var guard=new StreamAuthorityGuard(model,lease,f.clock,()->{})){
                guard.markResponseAccepted();var access=guard.materialAccess();var result=modelMaterial(current,lease,"final");guard.beforeMaterialWrite(result,access);
                f.clock.advance(6);var renewed=model.check(lease);rejectHolder(model,renewed,f);
                assertThatThrownBy(()->guard.beforeMaterialWrite(result,access)).isInstanceOf(PlatformException.class);assertThatThrownBy(guard::beforeWrite).isInstanceOf(PlatformException.class);
                guard.close();assertThatThrownBy(()->model.check(renewed)).isInstanceOf(PlatformException.class);
            }
        }
    }
''' + s[pos:];p.write_text(s,encoding='utf8')
