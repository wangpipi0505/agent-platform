package cn.agentplatform.model.application;

import static org.assertj.core.api.Assertions.*;
import cn.agentplatform.common.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import cn.agentplatform.common.authority.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class FrozenModelBindingVerifierTest {
  static void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code){assertThatThrownBy(action).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.code()).isEqualTo(code));}
  @Test void realFreezeAndAcceptedConsumerPositiveControl()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.accepted(input->{var proof=new FrozenModelBindingVerifier(f.ds).verify(input);assertThat(proof.bindingSha256()).isEqualTo(f.binding.path("sha256").asText());assertThat(f.ds.reads).hasValue(1);assertThat(f.ds.borrowed).hasValue(0);return null;});
    }
  }
  @Test void transactionBudgetRejectsDelayedReadBeforeFinalRefresh()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.accepted(input->{
        int calls=f.consumer.iam.authorityCalls();
        f.ds.beforeRead=()->{
          assertThat(f.ds.transactions.getLast()).containsEntry("readOnly",true).containsEntry("autoCommit",false);
          try{Thread.sleep(3250);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        };
        long start=System.nanoTime();
        var failure=catchThrowable(()->new FrozenModelBindingVerifier(f.ds).verify(input));
        long elapsed=java.time.Duration.ofNanos(System.nanoTime()-start).toMillis();
        assertThat(elapsed).isBetween(3000L,7900L);
        assertThat(f.ds.borrowed).hasValue(0);
        assertThat(failure).as("expired transaction cannot mint a binding proof").isInstanceOfSatisfying(PlatformException.class,e->{
          assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("SYS-MODEL-503");assertThat(e.getMessage()).isEqualTo("模型绑定暂不可用");
        });
        assertThat(f.consumer.iam.authorityCalls()).isEqualTo(calls);
        return null;
      });
    }
  }
  @Test void transactionCleanupOverrunCannotReachFinalRefresh()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.accepted(input->{
        int calls=f.consumer.iam.authorityCalls();
        f.ds.afterRelease=()->{try{Thread.sleep(3250);}catch(InterruptedException e){Thread.currentThread().interrupt();}};
        assertThatThrownBy(()->new FrozenModelBindingVerifier(f.ds).verify(input)).isInstanceOfSatisfying(PlatformException.class,e->{
          assertThat(e.status()).isEqualTo(503);assertThat(e.code()).isEqualTo("SYS-MODEL-503");assertThat(e.getMessage()).isEqualTo("模型绑定暂不可用");
        });
        assertThat(f.consumer.iam.authorityCalls()).isEqualTo(calls);assertThat(f.ds.borrowed).hasValue(0);return null;
      });
    }
  }
  @Test void rejectsChangedSnapshotUnderOldSha()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      var snapshot=(ObjectNode)Json.read(f.original);snapshot.put("modelName","tampered");f.snapshot(snapshot,false);
      f.accepted(input->{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"MATERIAL_SCOPE_MISSING_OR_CHANGED");assertThat(f.ds.reads).hasValue(1);return null;});
    }
  }
  @Test void retainsLostLabelObligation()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      var snapshot=(ObjectNode)Json.read(f.original);((ObjectNode)snapshot.path("provider")).putArray("trustLabels").add("initial").add("restricted");f.snapshot(snapshot,true);
      f.accepted(input->{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-EGRESS-4031");assertThat(f.ds.reads).hasValue(1);return null;});
    }
  }
  @Test void nullClosedReplacedCrossThreadAndAmbientTransactionDenyBeforeQuery()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      var v=new FrozenModelBindingVerifier(f.ds);
      denied(()->v.verify(null),"MATERIAL_SCOPE_MISSING_OR_CHANGED");
      var old=f.accepted(input->{
        try(var workers=Executors.newSingleThreadExecutor()){
          assertThatThrownBy(()->workers.submit(()->v.verify(input)).get()).hasCauseInstanceOf(PlatformException.class);
        }
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThatThrownBy(()->v.verify(input)).isInstanceOf(IllegalStateException.class).hasMessage("CURRENT_NETWORK_REQUIRES_NO_TRANSACTION");}
        finally{org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);}
        AuthorityContext.requireInitialFor(AuthorityContext.current().actor(),AuthorityContext.current().call().resource());
        assertThatThrownBy(()->v.verify(input)).isInstanceOf(PlatformException.class);
        return input;
      });
      assertThatThrownBy(()->v.verify(old)).isInstanceOf(PlatformException.class);
      assertThat(f.ds.reads).hasValue(0);
    }
  }
  @Test void numericDigestCopiesAndResultLifetime()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      ((ObjectNode)f.consumer.request.path("params")).set("temperature",new com.fasterxml.jackson.databind.node.DecimalNode(new java.math.BigDecimal("0.00001")));
      f.consumer.rebuildManifest();f.consumer.manifest.put("modelEntryId",f.entry);f.consumer.refreshResponse();
      var result=f.accepted(input->{
        byte[] accepted=input.acceptedCanonicalRequestUtf8();
        assertThat(new String(accepted,java.nio.charset.StandardCharsets.UTF_8)).contains("0.00001");
        var proof=new FrozenModelBindingVerifier(f.ds).verify(input);
        assertThat(proof.requestSha256()).isEqualTo(f.consumer.sha());
        ((ObjectNode)proof.frozenMetadata()).removeAll();
        assertThat(Digest.sha256(Json.canonical(proof.frozenMetadata()))).isEqualTo(proof.bindingSha256());
        assertThatThrownBy(input::requireVerified).isInstanceOf(PlatformException.class);
        return proof;
      });
      assertThatThrownBy(result::bindingId).isInstanceOf(PlatformException.class);
      f.accepted(input->{
        var proof=new FrozenModelBindingVerifier(f.ds).verify(input);
        try(var workers=Executors.newSingleThreadExecutor()){
          assertThatThrownBy(()->workers.submit(proof::requireVerified).get()).hasCauseInstanceOf(PlatformException.class);
        }
        AuthorityContext.requireInitialFor(AuthorityContext.current().actor(),AuthorityContext.current().call().resource());
        assertThatThrownBy(proof::frozenMetadata).isInstanceOf(PlatformException.class);return null;
      });
    }
  }
  @Test void missingTenantRowAndNestedIdsHaveNoMutableFallback()throws Exception{
    for(String mode:List.of("tenant","nestedProvider","entry","provider","missing")){
      try(var f=new FrozenModelBindingConsumerFixture()){
        switch(mode){
          case "tenant"->f.db.update("update model.binding_snapshot set tenant_id='tenant-other' where id=?",f.id);
          case "missing"->{((ObjectNode)f.consumer.request.path("modelBindingRef")).put("bindingId",f.id+100000);f.consumer.rebuildManifest();f.consumer.manifest.put("modelEntryId",f.entry);f.consumer.refreshResponse();}
          case "entry"->{var n=(ObjectNode)Json.read(f.original);n.put("id",f.entry+1);f.snapshot(n,true);}
          case "provider"->{var n=(ObjectNode)Json.read(f.original);n.put("providerId",f.provider+1);f.snapshot(n,true);}
          default->{var n=(ObjectNode)Json.read(f.original);((ObjectNode)n.get("provider")).put("id",f.provider+1);f.snapshot(n,true);}
        }
        f.ds.reset();f.accepted(input->{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"MATERIAL_SCOPE_MISSING_OR_CHANGED");assertThat(f.ds.reads).hasValue(1);return null;});
      }
    }
  }
  @Test void frozenStateSurvivesMutableDisableAndEndpointLabelModelChanges()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.db.update("update model.provider set status='DISABLED',base_url='http://mutable.invalid',trust_labels='[]'::jsonb where id=?",f.provider);
      f.db.update("update model.entry set status='DISABLED',model_name='changed' where id=?",f.entry);f.ds.reset();
      f.accepted(input->{var p=new FrozenModelBindingVerifier(f.ds).verify(input);assertThat(p.frozenMetadata().path("modelName").asText()).isEqualTo("fixture-model");assertThat(f.ds.sql).hasSize(1).allMatch(s->s.contains("from model.binding_snapshot"));return null;});
    }
  }
  @Test void fullTreeHashRetainsArrayOrderAndObjectOrderIsCanonical()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      var n=(ObjectNode)Json.read(f.original);var a=(com.fasterxml.jackson.databind.node.ArrayNode)n.path("capabilities");a.add(a.remove(0));f.snapshot(n,false);
      f.accepted(input->{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"MATERIAL_SCOPE_MISSING_OR_CHANGED");return null;});
      var original=Json.read(f.original);var reverse=Json.object();var keys=new ArrayList<String>();original.fieldNames().forEachRemaining(keys::add);Collections.reverse(keys);keys.forEach(k->reverse.set(k,original.get(k)));f.snapshot(reverse,false);
      f.accepted(input->{assertThat(new FrozenModelBindingVerifier(f.ds).verify(input)).isNotNull();return null;});
    }
  }
  @Test void metadataBoundsAreStrictBeforeNewProof()throws Exception{
    for(String mode:List.of("unknown","secretRef","status","null","coercion","fraction","negative","overflow","duplicateCapabilities","duplicateTrust","longString","wrongProtocol","userinfo","fragment","foreignTokenizer","depth","oversize")){
      try(var f=new FrozenModelBindingConsumerFixture()){
        var n=(ObjectNode)Json.read(f.original);
        switch(mode){
          case "unknown","secretRef","status"->n.put(mode,"forbidden");
          case "null"->n.putNull("modelName");case "coercion"->n.put("contextWindow","8192");case "fraction"->n.put("id",1.5);case "negative"->n.put("id",-1);case "overflow"->n.put("contextWindow",Long.MAX_VALUE);
          case "duplicateCapabilities"->n.withArray("capabilities").add("chat");case "duplicateTrust"->((ObjectNode)n.get("provider")).withArray("trustLabels").add("parent");
          case "longString"->n.put("modelName","x".repeat(129));case "wrongProtocol"->n.put("protocol","OTHER");case "userinfo"->n.put("baseUrl","https://u:p@localhost");case "fragment"->n.put("baseUrl","https://localhost/#fragment");case "foreignTokenizer"->n.put("tokenizerUrl","https://elsewhere.invalid/count");
          case "depth"->{ObjectNode cursor=n;for(int i=0;i<34;i++)cursor=cursor.putObject("unknown");}
          case "oversize"->n.put("modelName","x".repeat(262145));
        }
        f.snapshot(n,true);
        f.accepted(input->{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"MATERIAL_SCOPE_MISSING_OR_CHANGED");return null;});
      }
    }
  }
  @Test void capabilitiesAndEffectiveOutputAreChecked()throws Exception{
    for(String mode:List.of("chat","output","outputType","outputPrecedence")){
      try(var f=new FrozenModelBindingConsumerFixture()){
        var n=(ObjectNode)Json.read(f.original);
        if(mode.equals("chat"))n.putArray("capabilities").add("tools");
        else {var params=(ObjectNode)f.consumer.request.path("params");params.putObject("output").put("maxTokens",mode.equals("outputPrecedence")?128:5000);if(mode.equals("outputType"))((ObjectNode)params.path("output")).put("maxTokens","128");if(mode.equals("outputPrecedence"))params.put("maxOutputTokens",6000);}
        f.snapshot(n,true);
        f.accepted(input->{if(mode.equals("outputPrecedence"))assertThat(new FrozenModelBindingVerifier(f.ds).verify(input)).isNotNull();else denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-MODEL-4002");return null;});
      }
    }
  }
  @Test void refreshFollowsActualConnectionReleaseAndNarrowingRevocationOutageDeny()throws Exception{
    for(String mode:List.of("healthy","region","roles","revoke","unavailable")){
      try(var f=new FrozenModelBindingConsumerFixture()){
        f.accepted(input->{
          int calls=f.consumer.iam.authorityCalls();
          f.ds.beforeRead=()->{assertThat(f.ds.borrowed).hasValue(1);assertThat(f.consumer.iam.authorityCalls()).isEqualTo(calls);};
          f.ds.afterRelease=()->{
            assertThat(f.ds.borrowed).hasValue(0);
            switch(mode){case "region"->f.consumer.iam.decisionMutation(c->{if(c.containsKey("attributes"))c.put("attributes",Map.of("region",Set.of("A"),"dataLabels",Set.of("initial","restricted")));});case "roles"->f.consumer.iam.decisionMutation(c->{if(c.containsKey("roles"))c.put("roles",Set.of("READER"));});case "revoke"->f.consumer.iam.revoked(true);case "unavailable"->f.consumer.iam.unavailable(true);default->{}}
          };
          if(mode.equals("healthy")){assertThat(new FrozenModelBindingVerifier(f.ds).verify(input)).isNotNull();assertThat(f.consumer.iam.authorityCalls()).isGreaterThan(calls);}
          else assertThatThrownBy(()->new FrozenModelBindingVerifier(f.ds).verify(input)).isInstanceOfSatisfying(PlatformException.class,e->assertThat(e.status()).isEqualTo(mode.equals("unavailable")?503:403));
          assertThat(f.ds.borrowed).hasValue(0);return null;
        });
      }
    }
  }
  @Test void interruptionExpiryAndBoundedPoolNeverMintOrRetry()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.accepted(input->{Thread.currentThread().interrupt();try{denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-MODEL-503");assertThat(Thread.currentThread().isInterrupted()).isTrue();}finally{Thread.interrupted();}assertThat(f.ds.reads).hasValue(0);return null;});
      f.ds.beforeRead=()->f.consumer.iam.advanceClock(java.time.Duration.ofSeconds(61));
      f.accepted(input->{assertThatThrownBy(()->new FrozenModelBindingVerifier(f.ds).verify(input)).isInstanceOf(PlatformException.class);assertThat(f.ds.borrowed).hasValue(0);return null;});
    }
    try(var f=new FrozenModelBindingConsumerFixture();var one=f.pool.getConnection();var two=f.pool.getConnection()){
      f.accepted(input->{long start=System.nanoTime();denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-MODEL-503");assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()).isBetween(1500L,3500L);assertThat(f.ds.acquisitions).hasValue(1);assertThat(f.ds.reads).hasValue(0);return null;});
    }
  }
  @Test void realPgLockTimeoutThenFreshHealthyFrame()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture();var lock=f.pool.getConnection()){
      lock.setAutoCommit(false);try(var statement=lock.createStatement()){statement.execute("lock table model.binding_snapshot in access exclusive mode");}
      f.accepted(input->{long start=System.nanoTime();denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-MODEL-503");assertThat(java.time.Duration.ofNanos(System.nanoTime()-start).toMillis()).isLessThan(3500);assertThat(f.ds.acquisitions).hasValue(1);assertThat(f.ds.reads).hasValue(1);assertThat(f.ds.borrowed).hasValue(0);return null;});
      lock.rollback();f.ds.reset();f.accepted(input->{assertThat(new FrozenModelBindingVerifier(f.ds).verify(input)).isNotNull();return null;});
    }
  }
  @Test void actualHttpObserverPositiveControlsAndNoBusinessWrites()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      try(var http=java.net.http.HttpClient.newHttpClient()){
        for(String path:List.of("/provider","/tokenizer"))assertThat(http.send(java.net.http.HttpRequest.newBuilder(f.consumer.origin().resolve(path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
      }
      assertThat(f.providerHttp).hasValue(1);assertThat(f.tokenizerHttp).hasValue(1);f.providerHttp.set(0);f.tokenizerHttp.set(0);
      long before=f.db.queryForObject("select count(*) from model.call_log",Long.class);f.ds.reset();
      f.accepted(input->{assertThat(new FrozenModelBindingVerifier(f.ds).verify(input)).isNotNull();return null;});
      assertThat(f.ds.sql).hasSize(1).allMatch(s->s.contains("from model.binding_snapshot"));
      assertThat(f.db.queryForObject("select count(*) from model.call_log",Long.class)).isEqualTo(before);
      assertThat(f.db.queryForObject("select count(*) from model.quota_reservation",Long.class)).isZero();
      assertThat(f.consumer.iam.observedOperations()).doesNotContain("response_open","response_check");
    }
  }
  @Test void signedPostPgOwnerRootInitialOriginAndNewLabelChangesAreRechecked()throws Exception{
    for(String mode:List.of("owner","root","initial","origin","label","labelOverflow")){
      try(var f=new FrozenModelBindingConsumerFixture()){
        f.accepted(input->{
          f.ds.afterRelease=()->f.consumer.iam.decisionMutation(c->{
            if(!c.containsKey("attributes"))return;
            switch(mode){
              case "owner"->c.put("sub","mallory");
              case "root"->{var r=new HashMap<String,Object>();((Map<?,?>)c.get("resource")).forEach((k,v)->r.put(k.toString(),v));r.put("version_digest",Digest.sha256("other"));c.put("resource",r);}
              case "initial"->c.put("initial_ceiling",Map.of("tenant_id","tenant-a","sub","alice","roles",Set.of("EDITOR"),"attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial","restricted"))));
              case "origin"->c.put("origin_authentication_id","other-origin");
              case "label"->c.put("attributes",Map.of("region",Set.of("A","B"),"dataLabels",Set.of("initial","restricted","new-classification")));
              default->{var labels=new HashSet<String>();labels.add("initial");labels.add("restricted");for(int i=0;i<255;i++)labels.add("label-"+i);c.put("attributes",Map.of("region",Set.of("A","B"),"dataLabels",labels));}
            }
          });
          assertThatThrownBy(()->new FrozenModelBindingVerifier(f.ds).verify(input)).as(mode).isInstanceOfSatisfying(PlatformException.class,e->{assertThat(e.status()).isEqualTo(403);if(mode.equals("label"))assertThat(e.code()).isEqualTo("SYS-EGRESS-4031");if(mode.equals("labelOverflow"))assertThat(e.code()).isEqualTo("MATERIAL_SCOPE_MISSING_OR_CHANGED");});return null;
        });
      }
    }
  }
  @Test void emptyClassificationRequiresLocalAndRetainedExpiryRetiresNewProof()throws Exception{
    for(boolean local:List.of(false,true)){
      try(var f=new FrozenModelBindingConsumerFixture()){
        var actor=new Actor("tenant-a","alice",Set.of("EDITOR","READER"),Map.of("region",Set.of("A","B"),"dataLabels",Set.of()));
        f.consumer.iam.portalActor(actor);f.initialActorOverride=actor;
        ((ObjectNode)f.consumer.request).putArray("requiredLabels");
        var n=(ObjectNode)Json.read(f.original);var trusted=((ObjectNode)n.path("provider")).putArray("trustLabels");if(local)trusted.add("LOCAL");f.snapshot(n,true);
        var parents=f.consumer.manifest.withArray("parents");String initial=MaterialAuthorization.ceilingSha256(actor);ObjectNode snapshot=null,refs=null;
        for(var parent:parents){var p=(ObjectNode)parent;p.put("initialCeilingSha256",initial);p.putArray("requiredDataLabels");if(p.path("material").path("kind").asText().equals("run-snapshot"))snapshot=p;if(p.path("material").path("kind").asText().equals("run-version-refs"))refs=p;}
        for(var parent:parents)rehash((ObjectNode)parent);
        refs.putArray("parentScopeSha256").add(snapshot.path("scopeSha256").asText());rehash(refs);
        var child=(ObjectNode)f.consumer.manifest.path("scope");child.put("initialCeilingSha256",initial);child.putArray("requiredDataLabels");var hashes=new TreeSet<String>();parents.forEach(p->hashes.add(p.path("scopeSha256").asText()));child.set("parentScopeSha256",Json.tree(hashes));rehash(child);f.consumer.refreshResponse();
        f.accepted(input->{if(!local)denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-EGRESS-4031");else{var proof=new FrozenModelBindingVerifier(f.ds).verify(input);proof.requireVerified();f.consumer.iam.advanceClock(java.time.Duration.ofSeconds(61));denied(proof::requireVerified,"AUTHORITY_EXPIRED");}return null;});
      }
    }
  }
  static void rehash(ObjectNode n){n.put("scopeSha256",MaterialAuthorization.scopeDigest(Json.read(n.toString(),MaterialScopeV1.class)));}
  @Test void cooperativeEightSecondEnvelopeCannotMintAfterOverrun()throws Exception{
    try(var f=new FrozenModelBindingConsumerFixture()){
      f.accepted(input->{int calls=f.consumer.iam.authorityCalls();f.ds.beforeRead=()->{try{Thread.sleep(8050);}catch(InterruptedException e){Thread.currentThread().interrupt();}};denied(()->new FrozenModelBindingVerifier(f.ds).verify(input),"SYS-MODEL-503");assertThat(f.consumer.iam.authorityCalls()).isEqualTo(calls);assertThat(f.ds.borrowed).hasValue(0);return null;});
    }
  }
}
