from pathlib import Path
p=Path('admin-common/src/test/java/cn/agentplatform/common/authority/ResponseAuthorityTest.java')
s=p.read_text();pos=s.index('    @Test')
s=s[:pos]+'''    @Test void reviewI1ActiveRenewedAndClosedLeasesCannotClaimAnotherHolder()throws Exception{
        for(boolean integration:List.of(false,true))try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(integration?f.integration():f.portal(),call).value(),call);
            var lease=engine.open(current,binding(current,f,"single"));var cancelled=new AtomicInteger();
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,cancelled::incrementAndGet);var pool=Executors.newSingleThreadExecutor()){
                guard.markResponseAccepted();assertThat(pool.submit(()->{guard.beforeWrite();return true;}).get(2,TimeUnit.SECONDS)).isTrue();
                rejectHolder(engine,lease,f);
                f.clock.advance(6);var renewed=engine.check(lease);guard.beforeWrite();rejectHolder(engine,renewed,f);
                guard.close();guard.close();assertThat(cancelled).hasValue(1);assertThat(guard.watchdogShutdown()).isTrue();rejectHolder(engine,lease,f);
                assertThatThrownBy(()->engine.check(renewed)).isInstanceOf(PlatformException.class);
            }
        }
    }
    @Test void reviewI1ClosedSseLeaseCannotReplayConsumedSequence()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"sse-once"));
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){guard.markResponseAccepted();guard.beforeMaterialWrite(material(current,lease,1),guard.materialAccess());}
            assertThatThrownBy(()->{try(var replay=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){replay.markResponseAccepted();f.clock.advance(21);f.revoked=true;replay.beforeMaterialWrite(material(current,lease,1),replay.materialAccess());}}).isInstanceOf(PlatformException.class);
        }
    }
    @Test void reviewI1ClosedModelLeaseCannotReplayFinal()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var model=f.client("model")){
            var entry=LoopbackAuthority.call("gateway","engine");var first=engine.authorize(gateway.exchange(f.integration(),entry).value(),entry);var call=LoopbackAuthority.call("engine","model");var current=model.authorize(engine.forward(first,call).value(),call);
            var lease=model.open(current,new ResponseBinding("model-once","model",call.resource(),Digest.sha256("request"),f.clock.instant().plusSeconds(55)));
            try(var guard=new StreamAuthorityGuard(model,lease,f.clock,()->{})){guard.markResponseAccepted();guard.beforeMaterialWrite(modelMaterial(current,lease,"final"),guard.materialAccess());}
            assertThatThrownBy(()->{try(var replay=new StreamAuthorityGuard(model,lease,f.clock,()->{})){replay.markResponseAccepted();f.clock.advance(21);f.unavailable=true;replay.beforeMaterialWrite(modelMaterial(current,lease,"final"),replay.materialAccess());}}).isInstanceOf(PlatformException.class);
        }
    }
    @Test void reviewI1PublicCopiesCannotClaimOrLaunderSamePermit()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.integration(),call).value(),call);var lease=engine.open(current,binding(current,f,"copy"));
            var copy=new ResponseLease(lease.binding(),lease.sourceKind(),lease.continuationRef(),lease.openingDecisionId(),lease.checkedUntil(),lease.sourceExpiresAt(),lease.completionPermit());
            assertThat(copy).isEqualTo(lease);assertThat(copy.hashCode()).isEqualTo(lease.hashCode());assertThat(copy.toString()).isEqualTo("ResponseLease[redacted]");
            rejectHolder(engine,copy,f);assertThatThrownBy(()->engine.check(copy)).isInstanceOf(PlatformException.class);
            for(String field:List.of("binding","source","continuation","checked")){
                var changed=new ResponseLease(field.equals("binding")?binding(current,f,"other"):lease.binding(),field.equals("source")?"PORTAL":lease.sourceKind(),field.equals("continuation")?"other":lease.continuationRef(),lease.openingDecisionId(),field.equals("checked")?lease.checkedUntil().plusSeconds(90):lease.checkedUntil(),lease.sourceExpiresAt(),lease.completionPermit());
                rejectHolder(engine,changed,f);assertThatThrownBy(()->engine.check(changed)).isInstanceOf(PlatformException.class);
            }
            try(var guard=new StreamAuthorityGuard(engine,lease,f.clock,()->{})){guard.markResponseAccepted();rejectHolder(engine,copy,f);}rejectHolder(engine,copy,f);
        }
    }
    @Test void reviewI1FailedConstructorTerminatesClaimAndShutsScheduler()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine")){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);var lease=engine.open(current,binding(current,f,"failed-constructor"));
            var scheduler=new ScheduledThreadPoolExecutor(1);scheduler.shutdownNow();
            assertThatThrownBy(()->new StreamAuthorityGuard(engine,lease,f.clock,()->{},scheduler)).isInstanceOf(RejectedExecutionException.class);
            assertThat(scheduler.isShutdown()).isTrue();rejectHolder(engine,lease,f);
            var live=new ScheduledThreadPoolExecutor(1);
            assertThatThrownBy(()->new StreamAuthorityGuard(null,lease,f.clock,()->{},live)).isInstanceOf(NullPointerException.class);
            try{assertThat(live.isShutdown()).isTrue();}finally{live.shutdownNow();}
        }
    }
    static void rejectHolder(ResponseAuthorityClient client,ResponseLease lease,LoopbackAuthority f){
        var scheduler=new ScheduledThreadPoolExecutor(1);
        try{assertThatThrownBy(()->{try(var duplicate=new StreamAuthorityGuard(client,lease,f.clock,()->{},scheduler)){duplicate.beforeWrite();}}).isInstanceOf(PlatformException.class);assertThat(scheduler.isShutdown()).isTrue();}finally{scheduler.shutdownNow();}
    }
''' + s[pos:]
s=s.replace('try(var unopened=new StreamAuthorityGuard(engine,lease,f.clock,()->{});var guard=', 'var unopenedLease=engine.open(current,binding(current,f,"unopened-independent"));\n            try(var unopened=new StreamAuthorityGuard(engine,unopenedLease,f.clock,()->{});var guard=')
p.write_text(s)
p=Path('admin-common/src/test/java/cn/agentplatform/common/authority/CurrentAuthorityClientTest.java');s=p.read_text();pos=s.index('    @Test')
s=s[:pos]+'''    @Test void reviewI2NonLifoSameAuthorityNeverRestoresClosedFrame()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var pool=Executors.newSingleThreadExecutor()){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            var states=pool.submit(()->{
                var outer=AuthorityContext.bind(current);var inner=AuthorityContext.bind(current);
                outer.close();outer.close();boolean innerLive=contextIs(current);inner.close();inner.close();return List.of(innerLive,contextMissing());
            }).get(2,TimeUnit.SECONDS);
            assertThat(states).containsExactly(true,true);assertThat(contextMissing()).isTrue();
        }
    }
    @Test void reviewI2LifoExceptionOwnerAndChildBoundaries()throws Exception{
        try(var f=new LoopbackAuthority();var gateway=f.client("gateway");var engine=f.client("engine");var pool=Executors.newSingleThreadExecutor()){
            var call=LoopbackAuthority.call("gateway","engine");var current=engine.authorize(gateway.exchange(f.portal(),call).value(),call);
            try(var outer=AuthorityContext.bind(current)){
                try(var inner=AuthorityContext.bind(current)){assertThat(contextIs(current)).isTrue();}assertThat(contextIs(current)).isTrue();
                assertThat(pool.submit(()->{boolean missing=contextMissing();try{outer.close();return false;}catch(IllegalStateException expected){return missing&&contextMissing();}}).get(2,TimeUnit.SECONDS)).isTrue();
                assertThat(contextIs(current)).isTrue();
                assertThatThrownBy(()->{try(var inner=AuthorityContext.bind(current)){throw new IllegalArgumentException("fixture");}}).isInstanceOf(IllegalArgumentException.class);
                assertThat(contextIs(current)).isTrue();
            }
            assertThat(contextMissing()).isTrue();
            assertThatThrownBy(()->{try(var scope=AuthorityContext.bind(current)){throw new IllegalArgumentException("fixture");}}).isInstanceOf(IllegalArgumentException.class);assertThat(contextMissing()).isTrue();
        }
    }
    static boolean contextIs(VerifiedAuthority value){try{return AuthorityContext.current()==value;}catch(PlatformException missing){return false;}}
    static boolean contextMissing(){try{AuthorityContext.current();return false;}catch(PlatformException missing){return true;}}
''' + s[pos:];p.write_text(s)
