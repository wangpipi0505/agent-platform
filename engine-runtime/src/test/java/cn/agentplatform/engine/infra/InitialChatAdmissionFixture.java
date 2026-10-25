package cn.agentplatform.engine.infra;

import cn.agentplatform.common.*;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.api.*;
import cn.agentplatform.engine.application.*;
import cn.agentplatform.engine.domain.RunDispatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping;

/** Component fixture only. App/model remain independent HTTP servers and schema roles. */
public final class InitialChatAdmissionFixture implements AutoCloseable {
    static InitialChatAdmissionFixture current;
    final Path dir=Path.of(System.getProperty("ica.phase"));
    final InitialSenderIntegrationFixture previous;
    final FrozenModelBindingProducerFixture producer;
    final TraceStore trace;
    volatile String executionFault="";
    volatile boolean faultUsed;
    final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    RuntimeClients clients;
    RunScheduler scheduler;
    RunService service;
    ChunkBus chunks;
    PortalRunEventsService events;
    ServletWebServerApplicationContext context;
    volatile long selectedRun;
    volatile String selectedPublic;
    volatile String workerFailure;
    JsonNode publishedSource;
    final List<String> chunksSeen=new CopyOnWriteArrayList<>();

    InitialChatAdmissionFixture()throws Exception {
        previous=new InitialSenderIntegrationFixture();producer=previous.producer;
        trace=new TraceStore(producer.db,new org.springframework.jdbc.datasource.DataSourceTransactionManager(producer.ds),producer.iam.clock()) {
            @Override public Map<String,Object> dispatchInitial(RunDispatch dispatch,String worker,cn.agentplatform.engine.domain.ModelInputManifest manifest,byte[] canonical,HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                var claim=super.dispatchInitial(dispatch,worker,manifest,canonical,proof);
                if("unknown".equals(executionFault)&&!faultUsed){faultUsed=true;recordFault(dispatch,claim,"after-durable-dispatch-before-http");throw new IllegalStateException("INJECTED_DISPATCH_ACK_LOSS");}
                return claim;
            }
            @Override public void commitInitial(RunDispatch dispatch,Map<String,Object> claim,HttpCurrentAuthorityClient.RetainedAuthorization proof) {
                if("response".equals(executionFault)&&!faultUsed){faultUsed=true;recordFault(dispatch,claim,"after-durable-response-before-logical-commit");throw new IllegalStateException("INJECTED_COMMIT_WINDOW");}
                super.commitInitial(dispatch,claim,proof);
            }
        };
    }

    void recordFault(RunDispatch dispatch,Map<String,Object> claim,String window) {
        try{Files.writeString(dir.resolve("recovery-window.json"),Json.write(Map.of("run",dispatch.runId(),"owner",dispatch.owner(),"generation",dispatch.generation(),"node",TraceStore.number(claim,"id"),"attempt",TraceStore.number(claim,"attemptId"),"window",window,"at",Instant.now().toString())));}
        catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
    }
    void publishInputs()throws Exception {
        FrozenModelBindingProducerFixture.f=producer;
        producer.start();
        Files.writeString(dir.resolve("app-authority.json"),Json.write(producer.iam.config("app")));
        Files.writeString(dir.resolve("app-client.secret"),producer.iam.testClientSecret("app"));
        var snapshot=(ObjectNode)producer.snapshot();
        ((ObjectNode)snapshot.path("config")).set("output",Json.tree(Map.of("maxTokens",256)));
        var flow=Json.tree(Map.of("appCode",flowCode(),"type","FLOW","versionNo",1,
                "candidateSha256",Digest.sha256("initial-chat-flow-control"),"config",Map.of(),
                "definition",Map.of("nodes",List.of(Map.of("id","start","type","START"),Map.of("id","end","type","END")),
                        "edges",List.of(Map.of("from","start","to","end"))),"dependencyVersions",Map.of()));
        Files.writeString(dir.resolve("app-seed.json"),Json.write(Map.of("tenant",producer.actor.tenantId(),
                "principal",producer.actor.principalId(),"snapshots",List.of(snapshot,flow))));
        Files.writeString(dir.resolve("engine-material-ready.json"),Json.write(Map.of("pid",ProcessHandle.current().pid(),"ready",true)));
    }

    void assemble()throws Exception {
        String app=Json.read(Files.readString(dir.resolve("app-ready.json"))).path("origin").asText();
        String model=Json.read(Files.readString(dir.resolve("model-ready.json"))).path("origin").asText();
        clients=new RuntimeClients(producer.iam.config("engine"),producer.engine,app,true,model);
        scheduler=new RunScheduler(new RunScheduleRepository(trace,true){
            @Override public RunDispatch claimNext(RunDispatch.Queue queue,String owner,Duration lease){
                return selectedRun==0?null:super.claimSpecific(selectedRun,queue,owner,lease);
            }
        },dir.getFileName().toString().startsWith("concurrent-")?2:1,1,100,30000,1000){
            @Override public void created(long id,Admission admission){selectedRun=id;super.created(id,admission);}
            @Override public void bind(java.util.function.LongConsumer work,Runnable renew,Runnable ready){
                super.bind(id->{try{work.accept(id);}catch(RuntimeException failure){workerFailure=failure instanceof PlatformException p?p.code():failure.getClass().getSimpleName();try{Files.writeString(dir.resolve("worker-failure.json"),Json.write(Map.of("type",failure.getClass().getName(),"code",workerFailure,"frames",Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString).toList())));}catch(java.io.IOException e){failure.addSuppressed(e);}throw failure;}},renew,ready);
            }
        };
        chunks=new ChunkBus(){@Override public void publish(long id,String text){
            super.publish(id,text);chunksSeen.add(text);
            try{Files.writeString(dir.resolve("initial-chat-engine-chunk-seen"),Instant.now().toString());}
            catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        }};
        service=new RunService(trace,clients,new FlowDriver(trace,null),null,scheduler,
                previous.authority,producer.runs,producer.iam.clock(),producer.materials,chunks);
        events=new PortalRunEventsService(new RunEventReader(producer.ds),producer.engine,chunks,producer.iam.clock());
        current=this;
        var appContext=new SpringApplication(Receiver.class);appContext.setAdditionalProfiles("initial-chat-component");
        appContext.setDefaultProperties(Map.ofEntries(Map.entry("spring.config.location","optional:classpath:/ica-empty.properties"),
                Map.entry("server.address","127.0.0.1"),Map.entry("server.port",0),Map.entry("spring.main.banner-mode","off"),
                Map.entry("spring.flyway.enabled",false),Map.entry("spring.jmx.enabled",false),Map.entry("management.endpoints.enabled-by-default",false),Map.entry("management.endpoints.web.exposure.exclude","*"),Map.entry("management.endpoints.web.discovery.enabled",false),Map.entry("springdoc.api-docs.enabled",false),Map.entry("springdoc.swagger-ui.enabled",false),Map.entry("logging.level.root","ERROR")));
        context=(ServletWebServerApplicationContext)appContext.run();
    }

    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration @EnableWebSecurity
    static class Receiver {
        @Bean(destroyMethod="") javax.sql.DataSource dataSource(){return current.producer.ds;}
        @Bean RunController runs(){var c=new RunController(current.service,current.trace,current.chunks,null);c.portalEvents(current.events);return c;}
        @Bean ModelInputMaterialController materials(){return new ModelInputMaterialController(current.producer.materials);}
        @Bean RunObservationMetadataController metadata(){return new RunObservationMetadataController(new RunObservationMetadataService(current.producer.db));}
        @Bean PlatformErrors errors(){return new PlatformErrors();}
        @Bean(destroyMethod="close") JwtDecoder decoder(){return new TrustedJwtDecoder(current.producer.iam.config("engine"),current.producer.iam.clock(),true);}
        @Bean ServiceAuthorization authorization(){return new ServiceAuthorization("engine",new ServiceRoutePolicy(),current.producer.engine);}
        @Bean SecurityFilterChain security(HttpSecurity h,JwtDecoder d,ServiceAuthorization a)throws Exception{return SecurityConfiguration.serviceSecurity(h,d,a);}
        @Bean org.springframework.web.servlet.handler.MappedInterceptor guard(ServiceAuthorization a){return a.mappedInterceptor();}
        @Bean RouteCoverageVerifier coverage(List<RequestMappingInfoHandlerMapping> m){return new RouteCoverageVerifier("engine",new ServiceRoutePolicy(),m);}
        @Bean org.springframework.web.servlet.config.annotation.WebMvcConfigurer failureObservation(){return new org.springframework.web.servlet.config.annotation.WebMvcConfigurer(){
            @Override public void extendHandlerExceptionResolvers(List<org.springframework.web.servlet.HandlerExceptionResolver> resolvers){
                resolvers.add(0,(request,response,handler,failure)->{
                    try{if(!Files.exists(current.dir.resolve("receiver-failure.json")))Files.writeString(current.dir.resolve("receiver-failure.json"),Json.write(Map.of("type",failure.getClass().getName(),"code",failure instanceof PlatformException p?p.code():"OTHER","frames",Arrays.stream(failure.getStackTrace()).map(StackTraceElement::toString).toList(),"routeMethod",request.getMethod(),"routeTemplate",String.valueOf(request.getAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)),"routeUri",request.getRequestURI())));}
                    catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}return null;
                });
            }
        };}
    }

    Map<String,Object> request(String code,String requestId)throws Exception {
        return request(code,requestId,Json.tree(Map.of("inputs",Map.of("message","首次消息"))));
    }
    Map<String,Object> request(String code,String requestId,JsonNode supplied)throws Exception {
        var requestBody=(ObjectNode)supplied.deepCopy();requestBody.put("requestId",requestId+"-"+dir.getFileName());
        ResourceBinding root=code.equals(flowCode())?new ResourceBinding("app-version",flowCode()+"@1",Digest.sha256("initial-chat-flow-control")):FrozenModelBindingProducerFixture.ROOT;
        var call=new ServiceCall("engine","proxy.engine.post.RunController.run",root);
        String token=producer.gateway.exchange(producer.iam.portal(300),call).value();
        var result=http.send(HttpRequest.newBuilder(URI.create(origin()+"/admin/apps/"+code+"/runs"))
                .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(requestBody))).build(),HttpResponse.BodyHandlers.ofString());
        var body=Json.read(result.body());
        if(result.statusCode()==200)selectedPublic=body.path("data").path("runId").asText();
        return Map.of("httpStatus",result.statusCode(),"body",body);
    }

    Map<String,Object> observe(String kind)throws Exception {return observe(kind,null,null);}
    Map<String,Object> observe(String kind,String cursor,String fault)throws Exception {
        var binding=producer.runs.load(selectedRun);
        producer.iam.registerObservation(binding.owner(),binding.publicId(),binding.snapshotSha());
        var root=kind.equals("metadata")?RunObservationSelectors.metadata(binding.owner(),binding.publicId()):RunObservationSelectors.status(binding.publicId(),binding.snapshotSha());
        String action=kind.equals("metadata")?RunObservationSelectors.ACTION:kind.equals("events")?RunObservationSelectors.EVENTS_ACTION:RunObservationSelectors.STATUS_ACTION;
        String path=kind.equals("metadata")?"/internal/runs/"+binding.publicId()+"/observation-metadata":"/admin/runs/"+binding.publicId()+(kind.equals("events")?"/events":"");
        String token=producer.gateway.exchange(producer.iam.portal(300),new ServiceCall("engine",action,root)).value();
        var request=HttpRequest.newBuilder(URI.create(origin()+path)).timeout(Duration.ofSeconds(10))
                .header("Authorization","Bearer "+token).header("Accept",kind.equals("events")?"text/event-stream":"application/json");
        if(cursor!=null)request.header("Last-Event-ID",cursor);
        if("narrow".equals(fault))producer.iam.decisionMutation(claims->claims.put("roles",Set.of()));
        if("outage".equals(fault))producer.iam.unavailable(true);
        try {
            var response=http.send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
            return Map.of("httpStatus",response.statusCode(),"body",response.body());
        }finally{producer.iam.unavailable(false);producer.iam.decisionMutation(claims->{});}
    }
    String origin(){return "http://127.0.0.1:"+context.getWebServer().getPort();}
    String flowCode(){return "ica-flow-"+Digest.sha256(dir.toString()).substring(0,10);}
    Map<String,Object> published()throws Exception {
        var call=new ServiceCall("engine","proxy.engine.post.RunController.run",FrozenModelBindingProducerFixture.ROOT);
        var ticket=producer.gateway.exchange(producer.iam.portal(300),call);
        try(var decoder=new TrustedJwtDecoder(producer.iam.config("engine"),producer.iam.clock(),true)){
            var request=new org.springframework.mock.web.MockHttpServletRequest("POST","/admin/apps/control/runs");
            var response=new org.springframework.mock.web.MockHttpServletResponse();
            request.setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/admin/apps/{code}/runs");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(decoder.decode(ticket.value()),List.of()));
            var handler=new org.springframework.web.method.HandlerMethod(new RunController(null,null,null,null),RunController.class.getMethod("run",String.class,JsonNode.class));
            var guard=new ServiceAuthorization("engine",new ServiceRoutePolicy(),producer.engine);
            try {
                guard.preHandle(request,response,handler);
                var proof=AuthorityContext.requireInitialFor(Actor.current(),FrozenModelBindingProducerFixture.ROOT);
                var source=clients.published(FrozenModelBindingProducerFixture.ROOT.id().split("@")[0],proof);
                var frozen=FrozenModels.reference(source,source.path("config").path("modelEntryId").asLong());
                publishedSource=source.deepCopy();
                return Map.of("type",source.path("type").asText(),"versionId",source.path("appVersionId").asLong(),"hasFrozenModel",frozen.path("bindingId").asLong()>0);
            }finally{guard.afterCompletion(request,response,handler,null);org.springframework.security.core.context.SecurityContextHolder.clearContext();org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
        }
    }
    Map<String,Object> restartReceiver()throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(scheduler.activeWorkers()!=0&&System.nanoTime()<end)Thread.sleep(10);
        if(scheduler.activeWorkers()!=0)throw new IllegalStateException("ACTIVE_WORKER_AT_RESTART");
        scheduler.close();context.close();events.close();clients.close();chunks.close();
        assemble();return Map.of("receiverReconstructed",true,"sameJvm",true);
    }
    Map<String,Object> recoverCurrent()throws Exception {
        if(!faultUsed||Files.exists(dir.resolve("expiry-injection.json")))throw new IllegalStateException("RECOVERY_WINDOW_REQUIRED_ONCE");
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(scheduler.activeWorkers()!=0&&System.nanoTime()<end)Thread.sleep(10);
        if(scheduler.activeWorkers()!=0)throw new IllegalStateException("OLD_WORKER_NOT_CLOSED");
        scheduler.close();context.close();events.close();clients.close();chunks.close();
        Files.writeString(dir.resolve("old-owner-closed.json"),Json.write(Map.of("activeWorkers",scheduler.activeWorkers(),"httpClosed",true,"at",Instant.now().toString())));
        var b=producer.runs.load(selectedRun);
        var row=producer.db.queryForMap("select r.id as run_id,r.tenant_id,r.status as run_status,r.dispatch_owner,r.dispatch_until,r.dispatch_generation,n.id as node_id,n.status as node_status,n.lease_owner,n.lease_until,a.id as attempt_id from engine.run r join engine.node_instance n on n.run_id=r.id join engine.attempt a on a.node_instance_id=n.id where r.id=? and r.tenant_id=?",selectedRun,b.owner().tenantId());
        Files.writeString(dir.resolve("expiry-injection-registration.json"),Json.write(Map.of("selection",row,"expectedNodeRows",1,"injectedExpiry",true,"naturalWallTimeExpiry",false,"at",Instant.now().toString())));
        Integer changed=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(producer.ds)).execute(tx->{
            int count=producer.db.update("update engine.node_instance n set lease_until=clock_timestamp()-interval '1 second' where n.id=? and n.run_id=? and n.lease_owner=? and n.status='RUNNING' and exists(select 1 from engine.run r where r.id=n.run_id and r.tenant_id=? and r.status='RUNNING' and r.dispatch_owner is null) and exists(select 1 from engine.attempt a where a.id=? and a.node_instance_id=n.id)",row.get("node_id"),selectedRun,row.get("lease_owner"),b.owner().tenantId(),row.get("attempt_id"));
            if(count!=1){tx.setRollbackOnly();throw new IllegalStateException("EXPIRY_ROW_COUNT");}return count;
        });
        Files.writeString(dir.resolve("expiry-injection.json"),Json.write(Map.of("nodeRows",changed,"runRows",0,"onlyLeaseUntil",true,"injectedExpiry",true,"at",Instant.now().toString())));
        executionFault="";workerFailure=null;assemble();scheduler.poll();return facts();
    }
    Map<String,Object> immutableTamper()throws Exception {
        var b=producer.runs.load(selectedRun);
        var row=producer.db.queryForMap("select m.material_id,m.run_id,m.tenant_id,m.request_sha256,m.manifest_sha256,i.id as invocation_id from engine.model_input_snapshot m join engine.model_invocation i on i.run_id=m.run_id where m.run_id=? and m.tenant_id=?",selectedRun,b.owner().tenantId());
        var record=dir.resolve("immutable-tamper-registration.json");
        if(Files.exists(record))throw new IllegalStateException("ONE_TAMPER_ATTEMPT_ONLY");
        Files.writeString(record,Json.write(Map.of("selection",row,"expectedRows",1,"at",Instant.now().toString())));
        var result=new LinkedHashMap<String,Object>();
        try {
            Integer changed=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(producer.ds)).execute(tx->{
                int count=producer.db.update("update engine.model_input_snapshot set manifest=jsonb_set(manifest,'{profile}','\"unsupported-profile\"'::jsonb) where material_id=? and run_id=? and tenant_id=?",row.get("material_id"),selectedRun,b.owner().tenantId());
                if(count!=1){tx.setRollbackOnly();throw new IllegalStateException("TAMPER_ROW_COUNT");}return count;
            });
            result.put("changed",changed);result.put("storageRejected",false);
        }catch(org.springframework.dao.DataAccessException rejected){
            String state="";for(Throwable cause=rejected;cause!=null;cause=cause.getCause())if(cause instanceof java.sql.SQLException sql)state=sql.getSQLState();
            result.put("changed",0);result.put("storageRejected",true);result.put("sqlState",state);
        }
        result.put("observer",observe("status"));return result;
    }
    // Exercise explicit request fields through the same signed admin HTTP receiver as callers.
    Object admissionBoundary(String action)throws Exception {
        String code=FrozenModelBindingProducerFixture.ROOT.id().split("@")[0];
        if(action.equals("i1-counts")) {
            var counts=new LinkedHashMap<String,Object>();
            counts.put("runs",producer.db.queryForObject("select count(*) from engine.run where tenant_id=? and app_code=?",Integer.class,producer.actor.tenantId(),code));
            for(String table:List.of("run_authority","node_instance","model_invocation"))
                counts.put(table,producer.db.queryForObject("select count(*) from engine."+table+" x join engine.run r on r.id=x.run_id where r.tenant_id=? and r.app_code=?",Integer.class,producer.actor.tenantId(),code));
            counts.put("providerRequests",facts().get("providerRequests"));
            return counts;
        }
        if(action.equals("i1-snapshot")) {
            if(publishedSource==null)throw new IllegalStateException("published positive must precede snapshot check");
            int rejected=0;
            for(String field:List.of("chatSession","chatTurn","sessionHistory","history")) {
                var source=(ObjectNode)publishedSource.deepCopy();source.set(field,Json.tree(List.of()));
                try{cn.agentplatform.engine.domain.InitialChatRules.requireSources(source,Json.tree(Map.of("message","hello")));}
                catch(PlatformException expected){rejected++;}
            }
            return Map.of("rejected",rejected);
        }
        var body=(ObjectNode)Json.tree(Map.of("inputs",Map.of("message","hello")));
        if(action.equals("i1-accept"))return request(code,"i1-accepted",body);
        String[] parts=action.split("-");
        if(parts.length!=4||!Set.of("new","replay","inputs").contains(parts[1])
                ||!Set.of("chatSession","chatTurn","sessionHistory","history").contains(parts[2]))
            throw new IllegalArgumentException("finite boundary action required");
        JsonNode value=switch(parts[3]){
            case "null"->Json.tree(null);
            case "empty"->Json.tree(List.of());
            case "present"->Json.tree(Map.of("marker","unsupported"));
            default->throw new IllegalArgumentException("finite boundary shape required");
        };
        ((ObjectNode)(parts[1].equals("inputs")?body.path("inputs"):body)).set(parts[2],value);
        return request(code,parts[1].equals("replay")?"i1-accepted":action,body);
    }

    Map<String,Object> facts(){
        var result=new LinkedHashMap<String,Object>();result.put("admittedCount",producer.db.queryForObject("select count(*) from engine.run where app_code=? and trigger_type='CHAT'",Integer.class,FrozenModelBindingProducerFixture.ROOT.id().split("@")[0]));result.put("selectedRun",selectedRun);result.put("workerFailure",workerFailure);result.put("chunks",List.copyOf(chunksSeen));
        if(selectedRun>0){result.put("run",producer.db.queryForMap("select public_id,trigger_type,mode,status,event_seq from engine.run where id=?",selectedRun));
            result.put("nodes",producer.db.queryForList("select node_type,node_def_id,step_seq,definition::text from engine.node_instance where run_id=?",selectedRun));
            result.put("attempts",producer.db.queryForObject("select count(*) from engine.attempt a join engine.node_instance n on n.id=a.node_instance_id where n.run_id=?",Integer.class,selectedRun));}
        try{result.put("providerRequests",Files.exists(dir.resolve("provider-events.jsonl"))?Files.readAllLines(dir.resolve("provider-events.jsonl")).size():0);}catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
        result.put("faultUsed",faultUsed);result.put("interactiveAvailable",scheduler.available(cn.agentplatform.engine.domain.RunDispatch.Queue.INTERACTIVE));result.put("activeWorkers",scheduler.activeWorkers());
        if(selectedRun>0){result.put("invocations",producer.db.queryForList("select id,status,sdk_attempts,(response is not null) as response_present from engine.model_invocation where run_id=?",selectedRun));result.put("dispatchGeneration",producer.db.queryForObject("select dispatch_generation from engine.run where id=?",Long.class,selectedRun));}        return result;
    }

    public static void main(String[] args)throws Exception {
        HttpServer control=null;
        try(var owned=new InitialChatAdmissionFixture()) {
            owned.publishInputs();
            long ready=System.nanoTime()+TimeUnit.SECONDS.toNanos(28);
            while((!Files.exists(owned.dir.resolve("app-ready.json"))||!Files.exists(owned.dir.resolve("model-ready.json")))&&System.nanoTime()<ready){if(Files.exists(owned.dir.resolve("initial-chat.stop")))throw new IllegalStateException("BOOTSTRAP_CANCELLED");Thread.sleep(20);}
            owned.assemble();
            control=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            control.createContext("/control",exchange->{
                try {
                    String action=exchange.getRequestURI().getRawQuery();Object result;
                    if(action!=null&&action.startsWith("i1-"))result=owned.admissionBoundary(action);
                    else if("published".equals(action))result=owned.published();
                    else if("chat".equals(action))result=owned.request(FrozenModelBindingProducerFixture.ROOT.id().split("@")[0],"ica-first-request");
                    else if("flow".equals(action))result=owned.request(owned.flowCode(),"ica-flow-control");
                    else if(action!=null&&action.startsWith("reject-")){
                        var body=Json.tree(Map.of("inputs",Map.of("message","首次消息")));
                        String variant=action.substring(7);
                        if(variant.equals("step"))((ObjectNode)body).put("mode","STEP");
                        else if(variant.equals("debug"))((ObjectNode)body).put("mode","DEBUG");
                        else if(variant.equals("session"))((ObjectNode)body).put("sessionId","unsupported-session");
                        else if(variant.equals("conflict"))((ObjectNode)body.path("inputs")).put("message","different");
                        else ((ObjectNode)body.path("inputs")).set(variant,Json.tree(List.of()));
                        result=owned.request(FrozenModelBindingProducerFixture.ROOT.id().split("@")[0],variant.equals("conflict")?"ica-first-request":action,body);
                    }
                    else if(Set.of("metadata","status","events").contains(action))result=owned.observe(action);
                    else if("chat-response-window".equals(action)||"chat-unknown-window".equals(action)){owned.executionFault=action.equals("chat-response-window")?"response":"unknown";result=owned.request(FrozenModelBindingProducerFixture.ROOT.id().split("@")[0],action);}
                    else if("recover-current".equals(action))result=owned.recoverCurrent();
                    else if("restart-receiver".equals(action))result=owned.restartReceiver();
                    else if("immutable-tamper".equals(action))result=owned.immutableTamper();
                    else if("events-after".equals(action))result=owned.observe("events","3",null);
                    else if("events-ahead".equals(action))result=owned.observe("events","999",null);
                    else if("observe-narrow".equals(action))result=owned.observe("status",null,"narrow");
                    else if("observe-outage".equals(action))result=owned.observe("status",null,"outage");
                    else if("private-task".equals(action))result=owned.previous.drive();
                    else if("poll".equals(action)){owned.scheduler.poll();result=owned.facts();}
                    else result=owned.facts();
                    byte[] bytes=Json.write(result).getBytes(StandardCharsets.UTF_8);
                    Files.write(owned.dir.resolve("control-"+(action!=null&&action.matches("[a-zA-Z0-9-]+")?action:"facts")+".json"),bytes);
                    exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }catch(Throwable failure){Files.writeString(owned.dir.resolve("control-failure.txt"),failure.getClass().getName()+Arrays.toString(failure.getStackTrace()));exchange.sendResponseHeaders(500,-1);}
                finally{exchange.close();}
            });control.start();
            Files.writeString(owned.dir.resolve("initial-chat-ready.json"),Json.write(Map.of("origin","http://127.0.0.1:"+control.getAddress().getPort(),"engineOrigin",owned.origin(),"pid",ProcessHandle.current().pid())));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(55);
            while(!Files.exists(owned.dir.resolve("initial-chat.stop"))&&System.nanoTime()<until)Thread.sleep(20);
        }finally{if(control!=null){control.stop(0);((ExecutorService)control.getExecutor()).close();}}
    }

    @Override public void close()throws Exception {
        Throwable failure=null;
        for(AutoCloseable resource:Arrays.asList(context,events,scheduler,chunks,clients,http,previous))if(resource!=null)try{resource.close();}catch(Throwable e){if(failure==null)failure=e;else failure.addSuppressed(e);}
        Files.writeString(dir.resolve("initial-chat-closed.json"),Json.write(Map.of("closed",failure==null,"pid",ProcessHandle.current().pid())));
        if(failure!=null)throw new IllegalStateException("INITIAL_CHAT_FIXTURE_CLOSE_FAILED",failure);
    }
}








