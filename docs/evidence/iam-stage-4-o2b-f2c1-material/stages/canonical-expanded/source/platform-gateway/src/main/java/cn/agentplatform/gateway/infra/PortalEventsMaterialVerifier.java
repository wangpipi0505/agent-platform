package cn.agentplatform.gateway.infra;

import cn.agentplatform.common.Actor;
import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.core.util.BufferRecyclers;
import java.io.*;
import java.math.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.function.Supplier;

/** Real same-session verification. A synchronous borrow never authorizes future publication. */
final class PortalEventsMaterialVerifier {
    static final long MATERIAL_BYTES = 83886080L;
    private static final int MAX_SCOPES=4096,SLOTS=8192;
    private static final Set<String> ROOT_KINDS=Set.of("run-inputs","run-snapshot","run-version-refs");
    private static final List<String> KINDS=List.of("run-inputs","run-snapshot","run-version-refs","run-state","run-budget","run-output","run-error","node-definition","node-state","node-output","node-error","attempt-state","attempt-request","attempt-checkpoint","attempt-error","run-event");
    private static final Set<String> ATTRS=Set.of("region","dataLabels","bizSystemCode","userId"),ENTITLEMENTS=Set.of("region","bizSystemCode","userId");
    private final PortalRunEventsHttp.Session.MaterialContext context;
    private final PortalEventsPacketDecoder.UntrustedPacket packet;
    private final Index index;
    private final Persistent persistent=new Persistent();
    private Map<String,Object> facts;
    private Actor initial;
    private Target state,event;
    private int[] roots;
    private GatewayPortalEventsResponse.Capture capture;
    private Object payload;
    private String stateDigest,eventDigest;
    PortalEventsMaterialVerifier(PortalRunEventsHttp.Session.MaterialContext context){this.context=context;packet=context.packet();index=new Index(context::live);}
    void consume(PortalRunEventsHttp.Session.WindowConsumer consumer)throws IOException{
        context.live();if(packet.kind()==1){hello();return;}header(false);
        if(packet.kind()==3)return; // CAPACITY/EOF convey no run facts or terminal success.
        scan(1,null);relations();scan(2,null);targets();
        boolean callbackStarted=false;
        for(int attempt=0;attempt<3;attempt++){
            capture=context.capture();var sf=new Floor(initial,persistent);var ef=event==null?null:new Floor(initial,persistent);stateDigest=null;eventDigest=null;
            state.rootSnapshotLabels=null;state.rootVersionLabels=null;
            try{
                scan(3,(row,scope,slot)->{
                    checkScope(row,scope,slot);drain();try{context.require(capture,scope);}finally{drain();}
                    if(contains(roots,slot)){sf.labels(scope.requiredDataLabels());if(ef!=null)ef.labels(scope.requiredDataLabels());}
                    if(state.parents.get(slot))sf.parent(scope);if(event!=null&&event.parents.get(slot))ef.parent(scope);
                    if(slot==state.slot)stateDigest=scope.material().payloadSha256();if(event!=null&&slot==event.slot)eventDigest=scope.material().payloadSha256();
                });
                sf.coveredBy(state);if(ef!=null)ef.coveredBy(event);checkBusiness();context.requireLive(capture);
                if(context.superseded(capture))continue;
                var window=new VerifiedWindow(this,capture);
                try{callbackStarted=true;consumer.accept(window);window.live();}finally{window.close();}
                return;
            }catch(PlatformException failure){if(!callbackStarted&&attempt<2&&context.superseded(capture))continue;throw failure;}
            finally{sf.clear();if(ef!=null)ef.clear();persistent.resetFloors();payload=null;}
        }
        throw denied("generation retries");
    }
    private void hello()throws IOException{
        header(true);Map<String,Object> hello;
        try(var p=parser(packet.metadata(),false)){hello=p.object(Shape.HELLO);p.end();}
        String signed=text(hello,"receipt");if(signed.length()>4096)throw capacity("receipt");for(int i=0;i<signed.length();i++)if(signed.charAt(i)>127)throw denied("receipt ASCII");
        context.acceptReceipt(signed); // Same exact signed string, common G consumes its sole attempt.
        String[] pieces=signed.split("\\.",-1);if(pieces.length!=3)throw denied("receipt shape");byte[] decoded;
        try{decoded=Base64.getUrlDecoder().decode(pieces[1]);if(!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(pieces[1]))throw denied("receipt base64");}catch(IllegalArgumentException e){throw denied("receipt base64");}
        Map<String,Object> claims;try(var p=parser(new ByteArrayInputStream(decoded),false)){claims=p.object(Shape.RECEIPT);p.end();}
        var current=context.capture();if(!text(claims,"parent_response_id").equals(current.responseId())||!text(claims,"child_response_id").equals(packet.header().childResponseId()))throw denied("receipt association");
        context.bindChild(packet.header().childResponseId());
    }
    private void header(boolean hello){var h=packet.header();var c=context.capture();
        if(!context.run().equals(h.runId())||!context.accepted().equals(h.resourceSha256())||context.cursor()!=h.initialCursor()||context.cursor()!=h.afterSeq()||!c.subject().equals(context.subject())||!c.resource().equals(context.snapshot())||c.initialCursor()!=context.cursor())throw denied("header original Q");
        if(!hello&&!h.childResponseId().equals(context.childId()))throw denied("header child");
    }
    @FunctionalInterface private interface ScopeVisitor{void accept(Map<String,Object> row,MaterialScopeV1 scope,int slot)throws IOException;}
    private void scan(int pass,ScopeVisitor visitor)throws IOException{
        try(var p=parser(packet.metadata(),false)){
            p.expect(JsonToken.START_OBJECT);var seen=new HashSet<String>();var small=new LinkedHashMap<String,Object>();
            while(p.next()!=JsonToken.END_OBJECT){p.expectCurrent(JsonToken.FIELD_NAME);String key=p.name();if(!WINDOW.contains(key)||!seen.add(key))throw denied("window keys");p.next();
                if(key.equals("scopes")){p.expectCurrent(JsonToken.START_ARRAY);int count=0;
                    while(p.next()!=JsonToken.END_ARRAY){if(++count>MAX_SCOPES)throw capacity("scope count");int start=p.tokens;p.localStrings=0;p.localStart=p.tokens;p.local=true;
                        Map<String,Object> row=p.objectCurrent(Shape.ROW);p.local=false;
                        if((long)p.tokens-start+64>8192||cost(row)>1048576)throw capacity("one scope model");
                        var raw=object(row,"scope");long rev=positive(row,"revision");String hash=hash(raw,"scopeSha256"),kind=text(object(raw,"material"),"kind");int kindId=KINDS.indexOf(kind);if(kindId<0)throw denied("material kind");
                        long loc=kind.startsWith("run-")&&!kind.equals("run-event")?0:locator(text(object(raw,"material"),"id"));
                        if(pass==1)index.add(hash,rev,kindId,loc);else{
                            int slot=index.find(hash);if(slot<0||index.revisions[slot]!=rev||index.kinds[slot]!=kindId||index.locators[slot]!=loc)throw denied("immutable index");
                            if(pass==2){if(slot==state.slot)state.read(raw,index,persistent);if(event!=null&&slot==event.slot)event.read(raw,index,persistent);}else visitor.accept(row,scope(raw),slot);
                        }
                    }
                    if(pass!=1&&count!=index.count)throw denied("scope count changed");
                }else{
                    int start=p.tokens;p.localStrings=0;p.localStart=p.tokens;p.local=key.equals("initialCeiling");Object value=p.value(field(Shape.WINDOW,key));
                    if(p.local&&((long)p.tokens-start+64>8192||cost(value)>1048576))throw capacity("initial model");p.local=false;small.put(key,value);
                }
            }
            if(!seen.equals(WINDOW))throw denied("window missing fields");p.end();if(pass==1){facts=small;persistent.value(facts);}
        }catch(PlatformException e){throw e;}catch(IOException|RuntimeException e){throw denied("metadata JSON");}
    }
    private void relations(){
        if(!object(facts,"owner").equals(subject(context.subject())))throw denied("owner original Q");
        if(!hash(facts,"acceptedSnapshotSha").equals(context.accepted())||!hash(facts,"initialCeilingSha256").equals(context.initial()))throw denied("Q hashes");
        var a=object(facts,"initialCeiling");initial=new Actor(text(a,"tenantId"),text(a,"principalId"),strings(a,"roles"),attributes(a,"attributes"));
        if(!new SubjectKey(initial.tenantId(),initial.principalId()).equals(context.subject())||!common(()->MaterialAuthorization.ceilingSha256(initial)).equals(context.initial()))throw denied("initial ceiling");
        var prov=object(facts,"provenance");var root=object(prov,"executionRoot");
        if(!context.root().equals(new ResourceBinding(text(root,"kind"),text(root,"id"),hash(root,"versionDigest")))||positive(prov,"appVersionId")!=context.versionId()||positive(prov,"appVersionNo")!=context.versionNo())throw denied("provenance original Q");
        var rs=object(facts,"rootScopes");roots=new int[3];int n=0;
        for(String kind:List.of("run-inputs","run-snapshot","run-version-refs")){int slot=index.find(hash(rs,kind));if(slot<0||index.kinds[slot]!=KINDS.indexOf(kind)||contains(roots,n,slot))throw denied("roots");roots[n++]=slot;}
        int ss=index.find(hash(facts,"stateScope"));if(ss<0||index.kinds[ss]!=KINDS.indexOf("run-state"))throw denied("state scope");state=new Target(ss);
        boolean has=(Boolean)facts.get("hasEvent");if(has){int es=index.find(hash(facts,"eventScope"));if(es<0||index.kinds[es]!=KINDS.indexOf("run-event"))throw denied("event scope");event=new Target(es);}else if(facts.get("eventScope")!=null||facts.get("eventIdentity")!=null)throw denied("event absence");
        var sr=object(facts,"state");var w=object(facts,"window");long db=positive(facts,"runDbId"),mark=nonnegative(w,"watermark");
        if(positive(sr,"id")!=db||!text(sr,"public_id").equals(context.run())||nonnegative(sr,"event_seq")!=mark||!text(sr,"status").equals(text(w,"status"))||nonnegative(w,"afterSeq")!=context.cursor())throw denied("state/window identity");
        if(w.get("oldest")!=null&&positive(w,"oldest")>mark)throw denied("oldest");utc(sr.get("started_at"));utc(sr.get("ended_at"));
        if(has){var id=object(facts,"eventIdentity");long seq=positive(id,"seq");if(positive(id,"run_id")!=db||seq<=context.cursor()||seq>mark||!Objects.equals(packet.header().eventSeq(),seq)||!Objects.equals(packet.header().eventType(),text(id,"type"))||index.locators[event.slot]!=positive(id,"id"))throw denied("event identity");utc(id.get("created_at"));}
    }
    private void targets(){var union=new BitSet(MAX_SCOPES);for(int r:roots)union.set(r);union.set(state.slot);union.or(state.parents);if(event!=null){union.set(event.slot);union.or(event.parents);}if(union.cardinality()!=index.count)throw denied("exact direct parent union");persistent.floorBase();}
    private void checkScope(Map<String,Object> row,MaterialScopeV1 s,int slot){
        if(!s.owner().equals(context.subject())||!s.initialCeilingSha256().equals(context.initial())||!s.material().producer().equals("engine")||!common(()->MaterialAuthorization.scopeDigest(s)).equals(s.scopeSha256()))throw denied("scope digest/owner");
        String kind=s.material().kind();long rev=positive(row,"revision");
        if(ROOT_KINDS.contains(kind)){
            var prov=object(facts,"provenance");
            if(!contains(roots,slot)||!s.material().id().equals(context.run())||!s.material().version().equals(context.accepted())||!s.sourceDecisionId().equals(text(prov,"admissionDecisionId"))||!s.sourceAuthorizationVersion().equals(text(prov,"authorizationVersion"))||!s.requiredRoles().equals(initial.roles())||!s.requiredEntitlements().equals(initialEntitlements())||!s.requiredDataLabels().containsAll(initial.attributes().getOrDefault("dataLabels",Set.of())))throw denied("root provenance/floor");
            if(kind.equals("run-version-refs")){if(!s.parentScopeSha256().equals(List.of(index.hash(roots[1]))))throw denied("root parent");persistent.value(s.requiredDataLabels());state.rootVersionLabels=s.requiredDataLabels();}
            else{if(!s.parentScopeSha256().isEmpty())throw denied("root parent");if(kind.equals("run-snapshot")){if(!s.material().payloadSha256().equals(context.accepted()))throw denied("snapshot payload");persistent.value(s.requiredDataLabels());state.rootSnapshotLabels=s.requiredDataLabels();}}
        }else{
            if(!s.material().version().equals(Long.toString(rev))||s.parentScopeSha256().isEmpty())throw denied("dynamic revision/parents");
            if(kind.startsWith("run-")&&!kind.equals("run-event")&&!s.material().id().equals(context.run()))throw denied("run locator");
        }
        // Included parents retain their complete parent arrays in the digest; no recursive closure is inferred.
        if(slot==state.slot||event!=null&&slot==event.slot)for(String parent:s.parentScopeSha256()){int p=index.find(parent);if(p<0||index.revisions[p]>=rev)throw denied("target parent revision");}
    }
    private void checkBusiness()throws IOException{
        if(state.rootSnapshotLabels==null||state.rootVersionLabels==null||!state.rootVersionLabels.containsAll(state.rootSnapshotLabels))throw denied("version root labels");
        if(!digest(object(facts,"state")).equals(stateDigest))throw denied("state business SHA");
        if(event!=null){try(var p=parser(packet.payload(),true)){p.scanPayload();}try(var p=parser(packet.payload(),true)){payload=p.payloadValue();p.end();}
            var identity=new LinkedHashMap<>(object(facts,"eventIdentity"));identity.put("payload",payload);if(!digest(identity).equals(eventDigest))throw denied("event business SHA");}
    }
    private Map<String,Set<String>> initialEntitlements(){var m=new TreeMap<String,Set<String>>();initial.attributes().forEach((k,v)->{if(!k.equals("dataLabels"))m.put(k,v);});return m;}
    private static void utc(Object x){if(x==null)return;String s=(String)x;try{if(!s.endsWith("Z"))throw denied("UTC text");java.time.Instant.parse(s);}catch(java.time.format.DateTimeParseException e){throw denied("UTC text");}}
    static final class VerifiedWindow{
        private volatile PortalEventsMaterialVerifier verifier;
        private final GatewayPortalEventsResponse.Capture capture;
        private VerifiedWindow(PortalEventsMaterialVerifier v,GatewayPortalEventsResponse.Capture c){verifier=v;capture=c;}
        private PortalEventsMaterialVerifier live(){var v=verifier;if(v==null)throw denied("window borrow closed");v.context.live();v.context.requireLive(capture);if(v.context.superseded(capture))throw denied("window generation");return v;}
        String run(){return live().context.run();}
        String status(){return text(object(live().facts,"window"),"status");}
        long watermark(){return nonnegative(object(live().facts,"window"),"watermark");}
        Long oldest(){return (Long)object(live().facts,"window").get("oldest");}
        Long eventSeq(){var v=live();return v.event==null?null:positive(object(v.facts,"eventIdentity"),"seq");}
        String eventType(){var v=live();return v.event==null?null:text(object(v.facts,"eventIdentity"),"type");}
        String resetReason(){var v=live();long after=v.context.cursor(),mark=watermark();Long first=oldest();return after>mark?"CURSOR_AHEAD":(first==null?after<mark:after<first-1)?"EXPIRED":null;}
        boolean drained(){return eventSeq()==null&&resetReason()==null&&live().context.cursor()>=watermark()&&Set.of("SUCCEEDED","FAILED","CANCELLED").contains(status());}
        void writePayload(JsonGenerator generator)throws IOException{var v=live();if(v.event==null)throw denied("no payload");write(generator,v.payload);live();}
        private void close(){verifier=null;}
    }
    private static final class Index{
        final byte[] hashes=new byte[MAX_SCOPES*32],kinds=new byte[MAX_SCOPES];final long[] revisions=new long[MAX_SCOPES],locators=new long[MAX_SCOPES];
        final int[] slots=new int[SLOTS],revisionSlots=new int[SLOTS];final Runnable live;int count;
        Index(Runnable live){this.live=live;}
        int start(byte[] b){int h=0;for(byte x:b)h=31*h+Byte.toUnsignedInt(x);return h&(SLOTS-1);}
        int find(String hash){byte[] b=HexFormat.of().parseHex(hash);int p=start(b);for(int i=0;i<SLOTS;i++,p=(p+1)&(SLOTS-1)){if((i&63)==0)live.run();int s=slots[p]-1;if(s<0)return -1;boolean equal=true;for(int k=0;k<32;k++)if(hashes[s*32+k]!=b[k]){equal=false;break;}if(equal)return s;}throw capacity("index probes");}
        void add(String hash,long rev,int kind,long locator){if(count==MAX_SCOPES)throw capacity("index count");byte[] b=HexFormat.of().parseHex(hash);int p=start(b);
            for(int i=0;i<SLOTS;i++,p=(p+1)&(SLOTS-1)){if((i&63)==0)live.run();if(slots[p]==0){slots[p]=count+1;break;}int s=slots[p]-1;boolean equal=true;for(int k=0;k<32;k++)if(hashes[s*32+k]!=b[k]){equal=false;break;}if(equal)throw denied("duplicate scope hash");}
            int rp=(int)(rev^(rev>>>32))&(SLOTS-1);for(int i=0;revisionSlots[rp]!=0;i++,rp=(rp+1)&(SLOTS-1)){if((i&63)==0)live.run();if(i>=SLOTS)throw capacity("revision probes");if(revisions[revisionSlots[rp]-1]==rev)throw denied("duplicate revision");}
            revisionSlots[rp]=count+1;System.arraycopy(b,0,hashes,count*32,32);revisions[count]=rev;kinds[count]=(byte)kind;locators[count]=locator;count++;
        }
        String hash(int slot){return HexFormat.of().formatHex(hashes,slot*32,slot*32+32);}
    }
    private static final class Target{
        final int slot;final BitSet parents=new BitSet(MAX_SCOPES);Set<String> roles,labels,rootSnapshotLabels,rootVersionLabels;Map<String,Set<String>> entitlements;
        Target(int slot){this.slot=slot;}
        void read(Map<String,Object> raw,Index index,Persistent persistent){roles=strings(raw,"requiredRoles");labels=strings(raw,"requiredDataLabels");entitlements=attributes(raw,"requiredEntitlements");persistent.value(roles);persistent.value(labels);persistent.value(entitlements);
            for(String hash:list(raw,"parentScopeSha256")){int p=index.find(hash);if(p<0||index.revisions[p]>=index.revisions[slot]||parents.get(p))throw denied("direct parent");parents.set(p);}}
    }
    private static final class Persistent{
        int entries,baseEntries;long chars,baseChars;
        void string(String x){if(++entries>16384||(chars=Math.addExact(chars,x.length()))>1048576)throw capacity("persistent facts");}
        void value(Object x){if(x instanceof String s)string(s);else if(x instanceof Map<?,?> m)m.forEach((k,v)->{string((String)k);value(v);});else if(x instanceof Iterable<?> i)i.forEach(this::value);}
        void floorBase(){baseEntries=entries;baseChars=chars;}void resetFloors(){entries=baseEntries;chars=baseChars;}
    }
    private static final class Floor{
        final Set<String> roles=new TreeSet<>(),labels=new TreeSet<>();final Map<String,Set<String>> entitlements=new TreeMap<>();final Persistent persistent;
        Floor(Actor initial,Persistent p){persistent=p;add(roles,initial.roles());initial.attributes().forEach((k,v)->{if(k.equals("dataLabels"))add(labels,v);else attr(k,v);});}
        void add(Set<String> target,Set<String> source){for(String s:source)if(!target.contains(s)){persistent.string(s);target.add(s);}}
        void attr(String k,Set<String> values){var s=entitlements.get(k);if(s==null){persistent.string(k);s=new TreeSet<>();entitlements.put(k,s);}add(s,values);}
        void labels(Set<String> s){add(labels,s);}void parent(MaterialScopeV1 s){add(roles,s.requiredRoles());labels(s.requiredDataLabels());s.requiredEntitlements().forEach(this::attr);}
        void coveredBy(Target t){if(!t.roles.containsAll(roles)||!t.labels.containsAll(labels))throw denied("target floor");entitlements.forEach((k,v)->{if(!t.entitlements.getOrDefault(k,Set.of()).containsAll(v))throw denied("target floor");});}
        void clear(){roles.clear();labels.clear();entitlements.clear();}
    }
    private static MaterialScopeV1 scope(Map<String,Object> m){var r=object(m,"material");var o=object(m,"owner");return new MaterialScopeV1(1,new MaterialRef(text(r,"producer"),text(r,"kind"),text(r,"id"),text(r,"version"),hash(r,"payloadSha256")),new SubjectKey(text(o,"tenantId"),text(o,"principalId")),text(m,"sourceDecisionId"),text(m,"sourceAuthorizationVersion"),hash(m,"initialCeilingSha256"),strings(m,"requiredRoles"),attributes(m,"requiredEntitlements"),strings(m,"requiredDataLabels"),list(m,"parentScopeSha256"),hash(m,"scopeSha256"));}
    private static String common(Supplier<String> work){drain();try{return work.get();}catch(RuntimeException e){throw denied("common material");}finally{drain();}}
    private static void drain(){var r=BufferRecyclers.getBufferRecycler();for(int i=0;i<4;i++){r.allocByteBuffer(i);r.allocCharBuffer(i);}}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Map<String,Object> m,String key){Object v=m.get(key);if(!(v instanceof Map))throw denied("object "+key);return (Map<String,Object>)v;}
    private static String text(Map<String,Object> m,String key){Object v=m.get(key);if(!(v instanceof String s)||s.isBlank())throw denied("text "+key);return s;}
    private static String hash(Map<String,Object> m,String key){String s=text(m,key);if(s.length()!=64)throw denied("hash");for(int i=0;i<64;i++){char c=s.charAt(i);if(!(c>='0'&&c<='9'||c>='a'&&c<='f'))throw denied("hash");}return s;}
    private static long positive(Map<String,Object> m,String key){long n=nonnegative(m,key);if(n==0)throw denied("positive "+key);return n;}
    private static long nonnegative(Map<String,Object> m,String key){Object v=m.get(key);if(!(v instanceof Long n)||n<0)throw denied("integer "+key);return n;}
    @SuppressWarnings("unchecked") private static List<String> list(Map<String,Object> m,String key){return (List<String>)m.get(key);}
    private static Set<String> strings(Map<String,Object> m,String key){return new TreeSet<>(list(m,key));}
    private static Map<String,Set<String>> attributes(Map<String,Object> m,String key){var out=new TreeMap<String,Set<String>>();object(m,key).forEach((k,v)->{ @SuppressWarnings("unchecked") var values=(List<String>)v;out.put(k,new TreeSet<>(values));});return out;}
    private static Map<String,Object> subject(SubjectKey s){return Map.of("tenantId",s.tenantId(),"principalId",s.principalId());}
    private static boolean contains(int[] a,int v){return contains(a,a.length,v);}private static boolean contains(int[] a,int n,int v){for(int i=0;i<n;i++)if(a[i]==v)return true;return false;}
    private static long locator(String s){try{long n=Long.parseLong(s);if(n<1||!Long.toString(n).equals(s))throw denied("locator");return n;}catch(NumberFormatException e){throw denied("locator");}}
    private static PlatformException denied(String detail){return new PlatformException(403,"EVENT_MATERIAL_DENIED",detail);}
    private static PlatformException capacity(String detail){return new PlatformException(413,"EVENT_MATERIAL_CAPACITY",detail);}
    private static final Set<String> WINDOW=Set.of("runDbId","owner","acceptedSnapshotSha","initialCeiling","initialCeilingSha256","provenance","rootScopes","stateScope","eventScope","scopes","state","eventIdentity","window","hasEvent");
    private enum Shape{WINDOW,OWNER,INITIAL,ATTR,ENTITLEMENT,PROVENANCE,ROOT,ROOTS,ROW,SCOPE,MATERIAL,STATE,IDENTITY,CURSOR,HELLO,RECEIPT}
    private enum Scalar{TEXT,STRING,NULLABLE_STRING,HASH,NULLABLE_HASH,POSITIVE,NONNEGATIVE,NULLABLE_POSITIVE,ONE,BOOLEAN,STRINGS,HASHES,NULLABLE_IDENTITY}
    private static final Map<Shape,Map<String,Object>> SCHEMA=Map.ofEntries(
        Map.entry(Shape.WINDOW,shape("runDbId",Scalar.POSITIVE,"owner",Shape.OWNER,"acceptedSnapshotSha",Scalar.HASH,"initialCeiling",Shape.INITIAL,"initialCeilingSha256",Scalar.HASH,"provenance",Shape.PROVENANCE,"rootScopes",Shape.ROOTS,"stateScope",Scalar.HASH,"eventScope",Scalar.NULLABLE_HASH,"state",Shape.STATE,"eventIdentity",Scalar.NULLABLE_IDENTITY,"window",Shape.CURSOR,"hasEvent",Scalar.BOOLEAN)),
        Map.entry(Shape.OWNER,shape("tenantId",Scalar.TEXT,"principalId",Scalar.TEXT)),
        Map.entry(Shape.INITIAL,shape("tenantId",Scalar.TEXT,"principalId",Scalar.TEXT,"roles",Scalar.STRINGS,"attributes",Shape.ATTR)),
        Map.entry(Shape.PROVENANCE,shape("admissionDecisionId",Scalar.TEXT,"appVersionId",Scalar.POSITIVE,"appVersionNo",Scalar.POSITIVE,"authorizationVersion",Scalar.TEXT,"executionRoot",Shape.ROOT,"grantRef",Scalar.TEXT,"operationId",Scalar.TEXT,"originAuthenticationId",Scalar.TEXT)),
        Map.entry(Shape.ROOT,shape("kind",Scalar.TEXT,"id",Scalar.TEXT,"versionDigest",Scalar.HASH)),
        Map.entry(Shape.ROOTS,shape("run-inputs",Scalar.HASH,"run-snapshot",Scalar.HASH,"run-version-refs",Scalar.HASH)),
        Map.entry(Shape.ROW,shape("revision",Scalar.POSITIVE,"scope",Shape.SCOPE)),
        Map.entry(Shape.SCOPE,shape("schemaVersion",Scalar.ONE,"material",Shape.MATERIAL,"owner",Shape.OWNER,"sourceDecisionId",Scalar.TEXT,"sourceAuthorizationVersion",Scalar.TEXT,"initialCeilingSha256",Scalar.HASH,"requiredRoles",Scalar.STRINGS,"requiredEntitlements",Shape.ENTITLEMENT,"requiredDataLabels",Scalar.STRINGS,"parentScopeSha256",Scalar.HASHES,"scopeSha256",Scalar.HASH)),
        Map.entry(Shape.MATERIAL,shape("producer",Scalar.TEXT,"kind",Scalar.TEXT,"id",Scalar.TEXT,"version",Scalar.TEXT,"payloadSha256",Scalar.HASH)),
        Map.entry(Shape.STATE,shape("id",Scalar.POSITIVE,"public_id",Scalar.TEXT,"app_code",Scalar.STRING,"status",Scalar.STRING,"event_seq",Scalar.NONNEGATIVE,"stop_reason",Scalar.NULLABLE_STRING,"started_at",Scalar.NULLABLE_STRING,"ended_at",Scalar.NULLABLE_STRING,"mode",Scalar.STRING,"trigger_type",Scalar.STRING,"output_ref",Scalar.NULLABLE_STRING)),
        Map.entry(Shape.IDENTITY,shape("created_at",Scalar.STRING,"id",Scalar.POSITIVE,"run_id",Scalar.POSITIVE,"seq",Scalar.POSITIVE,"type",Scalar.STRING)),
        Map.entry(Shape.CURSOR,shape("afterSeq",Scalar.NONNEGATIVE,"oldest",Scalar.NULLABLE_POSITIVE,"status",Scalar.STRING,"watermark",Scalar.NONNEGATIVE)),
        Map.entry(Shape.HELLO,shape("receipt",Scalar.TEXT)),
        Map.entry(Shape.RECEIPT,shape("iss",Scalar.TEXT,"aud",Scalar.TEXT,"token_use",Scalar.TEXT,"iat",Scalar.NONNEGATIVE,"nbf",Scalar.NONNEGATIVE,"exp",Scalar.NONNEGATIVE,"jti",Scalar.TEXT,"association_sha256",Scalar.HASH,"parent_response_id",Scalar.TEXT,"child_response_id",Scalar.TEXT,"child_immutable_sha256",Scalar.HASH,"engine_opening_decision_id",Scalar.TEXT,"engine_admission_nonce",Scalar.TEXT,"engine_admission_request_sha256",Scalar.HASH))
    );
    private static Map<String,Object> shape(Object... a){var m=new HashMap<String,Object>();for(int i=0;i<a.length;i+=2)m.put((String)a[i],a[i+1]);return Map.copyOf(m);}
    private static Object field(Shape s,String k){if(s==Shape.ATTR||s==Shape.ENTITLEMENT){if(!(s==Shape.ATTR?ATTRS:ENTITLEMENTS).contains(k))throw denied("attribute key");return Scalar.STRINGS;}Object v=SCHEMA.get(s).get(k);if(v==null)throw denied("unknown "+s+"."+k);return v;}
    private Parser parser(InputStream in,boolean payload)throws IOException{return new Parser(in,payload,context::live);}
    private static JsonFactory factory(boolean payload){return JsonFactory.builder().disable(JsonFactory.Feature.CANONICALIZE_FIELD_NAMES).disable(JsonFactory.Feature.INTERN_FIELD_NAMES).disable(JsonFactory.Feature.USE_THREAD_LOCAL_FOR_BUFFER_RECYCLING).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(payload?64:80).maxTokenCount(payload?32768:65536).maxStringLength(payload?262144:65536).maxNameLength(payload?262144:256).maxNumberLength(1000).maxDocumentLength(8388608).build()).build();}
    private static final class Parser implements AutoCloseable{
        final JsonParser p;final Reader reader;final boolean payload;final Runnable live;int tokens,localStart;long names,localStrings;boolean local;
        Parser(InputStream in,boolean payload,Runnable live)throws IOException{this.payload=payload;this.live=live;reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));p=factory(payload).createParser(reader);}
        JsonToken next()throws IOException{
            JsonToken t;
            try{t=p.nextToken();}catch(com.fasterxml.jackson.core.exc.StreamConstraintsException e){throw capacity("parser constraint");}
            if(t==null)return null;if(++tokens>(payload?32768:65536))throw capacity("tokens");if(local&&tokens-localStart+64>8192)throw capacity("scope tokens");if((tokens&255)==0)live.run();
            if(t==JsonToken.FIELD_NAME){String s=p.currentName();unicode(s);if((names=Math.addExact(names,s.length()))>(payload?262144:524288))throw capacity("names");count(s);}
            else if(t==JsonToken.VALUE_STRING){try{p.finishToken();}catch(com.fasterxml.jackson.core.exc.StreamConstraintsException e){throw capacity("scalar");}String s=p.getText();if(s.length()>(payload?262144:65536))throw capacity("scalar");unicode(s);count(s);}
            else if(t.isNumeric())numberCost(p.getText());return t;
        }
        void count(String s){if(local&&(localStrings=Math.addExact(localStrings,s.length()))>131072)throw capacity("scope strings");}
        String name()throws IOException{return p.currentName();}
        void expect(JsonToken t)throws IOException{if(next()!=t)throw denied("JSON type");}
        void expectCurrent(JsonToken t){if(p.currentToken()!=t)throw denied("JSON type");}
        void end()throws IOException{if(next()!=null)throw denied("trailing JSON");}
        Map<String,Object> object(Shape s)throws IOException{next();return objectCurrent(s);}
        Map<String,Object> objectCurrent(Shape s)throws IOException{
            expectCurrent(JsonToken.START_OBJECT);var m=new LinkedHashMap<String,Object>();
            while(next()!=JsonToken.END_OBJECT){expectCurrent(JsonToken.FIELD_NAME);String key=name();Object schema=field(s,key);next();if(m.containsKey(key))throw denied("duplicate key");m.put(key,value(schema));}
            if(s!=Shape.ATTR&&s!=Shape.ENTITLEMENT&&!m.keySet().equals(SCHEMA.get(s).keySet()))throw denied("missing "+s);return m;
        }
        Object value(Object schema)throws IOException{
            if(schema instanceof Shape s)return objectCurrent(s);Scalar s=(Scalar)schema;JsonToken t=p.currentToken();
            if(t==JsonToken.VALUE_NULL&&(s==Scalar.NULLABLE_STRING||s==Scalar.NULLABLE_HASH||s==Scalar.NULLABLE_POSITIVE||s==Scalar.NULLABLE_IDENTITY))return null;
            if(s==Scalar.NULLABLE_IDENTITY)return objectCurrent(Shape.IDENTITY);
            if(s==Scalar.STRINGS||s==Scalar.HASHES){expectCurrent(JsonToken.START_ARRAY);var a=new ArrayList<String>();var seen=new TreeSet<String>();while(next()!=JsonToken.END_ARRAY){expectCurrent(JsonToken.VALUE_STRING);String v=p.getText();if(v.isBlank()||!seen.add(v))throw denied("duplicate/empty array value");if(s==Scalar.HASHES){hash(Map.of("v",v),"v");if(a.size()==4096)throw capacity("parents");}a.add(v);}return a;}
            if(s==Scalar.BOOLEAN){if(t!=JsonToken.VALUE_TRUE&&t!=JsonToken.VALUE_FALSE)throw denied("boolean");return t==JsonToken.VALUE_TRUE;}
            if(s==Scalar.POSITIVE||s==Scalar.NONNEGATIVE||s==Scalar.NULLABLE_POSITIVE||s==Scalar.ONE){expectCurrent(JsonToken.VALUE_NUMBER_INT);long n;try{n=p.getLongValue();}catch(Exception e){throw denied("long range");}if(n<0||(s!=Scalar.NONNEGATIVE&&n==0)||s==Scalar.ONE&&n!=1)throw denied("integer range");return n;}
            expectCurrent(JsonToken.VALUE_STRING);String v=p.getText();if(s==Scalar.TEXT&&v.isBlank())throw denied("blank");if(s==Scalar.HASH||s==Scalar.NULLABLE_HASH)hash(Map.of("v",v),"v");return v;
        }
        void scanPayload()throws IOException{if(next()==null)throw denied("payload missing");walk(false);end();}
        Object payloadValue()throws IOException{if(next()==null)throw denied("payload missing");return walk(true);}
        Object walk(boolean keep)throws IOException{
            JsonToken t=p.currentToken();
            if(t==JsonToken.START_OBJECT){Map<String,Object> m=keep?new LinkedHashMap<>():null;while(next()!=JsonToken.END_OBJECT){expectCurrent(JsonToken.FIELD_NAME);String key=name();next();Object x=walk(keep);if(keep)m.put(key,x);}return m;}
            if(t==JsonToken.START_ARRAY){List<Object> a=keep?new ArrayList<>():null;while(next()!=JsonToken.END_ARRAY){Object x=walk(keep);if(keep)a.add(x);}return a;}
            if(t==JsonToken.VALUE_STRING)return keep?p.getText():null;if(t==JsonToken.VALUE_NULL)return null;
            if(t==JsonToken.VALUE_TRUE||t==JsonToken.VALUE_FALSE)return keep?t==JsonToken.VALUE_TRUE:null;
            if(t==JsonToken.VALUE_NUMBER_INT||t==JsonToken.VALUE_NUMBER_FLOAT){String n=p.getText();numberCost(n);return keep?(t==JsonToken.VALUE_NUMBER_INT?new BigInteger(n):new BigDecimal(n)):null;}
            throw denied("payload type");
        }
        public void close()throws IOException{p.close();reader.close();}
    }
    private static void unicode(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i==s.length()||!Character.isLowSurrogate(s.charAt(i)))throw denied("surrogate");}else if(Character.isLowSurrogate(c))throw denied("surrogate");}}
    // Lexical exponent is bounded before any BigDecimal construction or integer expansion.
    private static long numberCost(String s){
        if(s.length()>1000)throw capacity("number lexeme");long digits=0,fraction=0,exponent=0;boolean decimal=false;int i=s.charAt(0)=='-'?1:0;
        for(;i<s.length();i++){char c=s.charAt(i);if(c=='e'||c=='E'){i++;break;}if(c=='.'){decimal=true;continue;}digits++;if(decimal)fraction++;}
        if(i<s.length()){boolean neg=false;if(s.charAt(i)=='+'||s.charAt(i)=='-')neg=s.charAt(i++)=='-';for(;i<s.length();i++){if(exponent>Integer.MAX_VALUE+1000L)throw capacity("number exponent");exponent=exponent*10+s.charAt(i)-'0';}if(neg)exponent=-exponent;}
        long scale=fraction-exponent,n=scale<=0?digits-scale+2:digits+24;if(scale<Integer.MIN_VALUE||scale>Integer.MAX_VALUE||n>65536)throw capacity("number expansion");return n;
    }
    private static long cost(Object value){long n;if(value instanceof Map<?,?> m){n=2;for(var e:m.entrySet())n=add(n,6L*((String)e.getKey()).length()+4+cost(e.getValue()));return n;}if(value instanceof Iterable<?> a){n=2;for(Object x:a)n=add(n,1+cost(x));return n;}if(value instanceof String s)return 6L*s.length()+2;if(value instanceof Number x){if(x instanceof BigDecimal d)return d.scale()<=0?(long)d.precision()-d.scale()+2:(long)d.precision()+24;return x.toString().length()+2;}return 5;}
    private static long add(long a,long b){try{return Math.addExact(a,b);}catch(ArithmeticException e){throw capacity("canonical overflow");}}
    private static String digest(Object value)throws IOException{
        if(cost(value)>8388608)throw capacity("canonical bytes");MessageDigest md;try{md=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        var sink=new OutputStream(){long count;public void write(int b){if(++count>8388608)throw capacity("canonical bytes");md.update((byte)b);}public void write(byte[] b,int off,int len){if((count+=len)>8388608)throw capacity("canonical bytes");md.update(b,off,len);}};
        try(var generator=factory(true).createGenerator(sink)){write(generator,value);}return HexFormat.of().formatHex(md.digest());
    }
    private static void write(JsonGenerator g,Object v)throws IOException{
        if(v instanceof Map<?,?> m){g.writeStartObject();String[] keys=m.keySet().toArray(String[]::new);Arrays.sort(keys);for(String key:keys){g.writeFieldName(key);write(g,m.get(key));}g.writeEndObject();}
        else if(v instanceof Iterable<?> a){g.writeStartArray();for(Object x:a)write(g,x);g.writeEndArray();}
        else if(v instanceof String s)g.writeString(s);else if(v==null)g.writeNull();else if(v instanceof Boolean b)g.writeBoolean(b);
        else if(v instanceof Number n){BigDecimal d=n instanceof BigDecimal b?b:new BigDecimal(n.toString());long size=d.scale()<=0?(long)d.precision()-d.scale()+2:(long)d.precision()+24;if(size>65536)throw capacity("number expansion");d=d.stripTrailingZeros();if(d.signum()==0)g.writeNumber(BigInteger.ZERO);else if(d.scale()<=0)g.writeNumber(d.toBigIntegerExact());else g.writeNumber(d);}
        else throw denied("canonical type");
    }
    /** Pure business hash operation cannot manufacture any response authority or window. */
    static String canonicalSha(byte[] input)throws IOException{
        if(input.length>262144)throw capacity("payload bytes");
        try(var p=new Parser(new ByteArrayInputStream(input),true,()->{})){p.scanPayload();}
        try(var p=new Parser(new ByteArrayInputStream(input),true,()->{})){Object value=p.payloadValue();p.end();return digest(value);}
    }
}
