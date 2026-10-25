from pathlib import Path
base=Path('admin-common/src/main/java/cn/agentplatform/common/authority')
(base/'ResponseLease.java').write_text('''package cn.agentplatform.common.authority;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Data equality is not proof of an open capability. Only verified open/check creates claimable leases. */
public final class ResponseLease {
    private final ResponseBinding binding;
    private final String sourceKind,continuationRef,openingDecisionId;
    private final Instant checkedUntil,sourceExpiresAt;
    private final ResponseMaterialPermit completionPermit;
    private final Lifecycle lifecycle;
    private final boolean verified;

    /** Compatibility data shell; copying a permit never grants ownership of a response. */
    public ResponseLease(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,
                         Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit){
        this(binding,sourceKind,continuationRef,openingDecisionId,checkedUntil,sourceExpiresAt,completionPermit,
                completionPermit==null?null:completionPermit.lifecycle(),false);
    }
    private ResponseLease(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,
                          Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit,Lifecycle lifecycle,boolean verified){
        this.binding=binding;this.sourceKind=sourceKind;this.continuationRef=continuationRef;this.openingDecisionId=openingDecisionId;
        this.checkedUntil=checkedUntil;this.sourceExpiresAt=sourceExpiresAt;this.completionPermit=completionPermit;this.lifecycle=lifecycle;this.verified=verified;
    }
    static ResponseLease opened(ResponseBinding binding,String sourceKind,String continuationRef,String openingDecisionId,
                                Instant checkedUntil,Instant sourceExpiresAt,ResponseMaterialPermit completionPermit){
        return new ResponseLease(binding,sourceKind,continuationRef,openingDecisionId,checkedUntil,sourceExpiresAt,completionPermit,
                completionPermit==null?new Lifecycle():completionPermit.lifecycle(),true);
    }
    ResponseLease renewed(Instant checkedUntil){
        requireVerifiedLive();
        return new ResponseLease(binding,sourceKind,continuationRef,openingDecisionId,checkedUntil,sourceExpiresAt,completionPermit,lifecycle,true);
    }
    void requireVerifiedLive(){if(!verified||lifecycle==null)throw AuthorityProtocol.denied();lifecycle.requireLive();}
    void claim(){requireVerifiedLive();lifecycle.claim();}
    void terminate(){lifecycle.terminate();}
    void requireSameLifecycle(ResponseLease other){other.requireVerifiedLive();if(lifecycle!=other.lifecycle)throw AuthorityProtocol.denied();}
    public ResponseBinding binding(){return binding;}
    public String sourceKind(){return sourceKind;}
    public String continuationRef(){return continuationRef;}
    public String openingDecisionId(){return openingDecisionId;}
    public Instant checkedUntil(){return checkedUntil;}
    public Instant sourceExpiresAt(){return sourceExpiresAt;}
    public ResponseMaterialPermit completionPermit(){return completionPermit;}
    @Override public boolean equals(Object value){
        if(this==value)return true;if(!(value instanceof ResponseLease other))return false;
        return Objects.equals(binding,other.binding)&&Objects.equals(sourceKind,other.sourceKind)&&Objects.equals(continuationRef,other.continuationRef)
                &&Objects.equals(openingDecisionId,other.openingDecisionId)&&Objects.equals(checkedUntil,other.checkedUntil)
                &&Objects.equals(sourceExpiresAt,other.sourceExpiresAt)&&Objects.equals(completionPermit,other.completionPermit);
    }
    @Override public int hashCode(){
        int hash=Objects.hashCode(binding);hash=31*hash+Objects.hashCode(sourceKind);hash=31*hash+Objects.hashCode(continuationRef);
        hash=31*hash+Objects.hashCode(openingDecisionId);hash=31*hash+Objects.hashCode(checkedUntil);hash=31*hash+Objects.hashCode(sourceExpiresAt);
        return 31*hash+Objects.hashCode(completionPermit);
    }
    @Override public String toString(){return "ResponseLease[redacted]";}

    /** No registry/cache: one open owns this object, shared only by its verified renewals and pinned permit. */
    static final class Lifecycle {
        private final AtomicInteger state=new AtomicInteger(); // 0 unclaimed, 1 claimed, 2 permanently terminated
        void claim(){if(!state.compareAndSet(0,1))throw AuthorityProtocol.denied();}
        void requireLive(){if(state.get()==2)throw AuthorityProtocol.expired();}
        void terminate(){state.set(2);}
    }
}
''')
p=base/'ResponseMaterialPermit.java';s=p.read_text().replace('    private final String raw,issuer,audience;','    private final ResponseLease.Lifecycle lifecycle=new ResponseLease.Lifecycle();\n    ResponseLease.Lifecycle lifecycle(){return lifecycle;}\n    private final String raw,issuer,audience;').replace('    void verify(){','    void verify(){\n        lifecycle.requireLive();');p.write_text(s)
p=base/'HttpCurrentAuthorityClient.java';s=p.read_text().replace('    @Override public ResponseLease check(ResponseLease lease){','    @Override public ResponseLease check(ResponseLease lease){\n        lease.requireVerifiedLive();').replace('        return new ResponseLease(binding,source,continuation,decision,checked,sourceExpiry,permit);','        return previous==null?ResponseLease.opened(binding,source,continuation,decision,checked,sourceExpiry,permit):previous.renewed(checked);');p.write_text(s)
p=base/'StreamAuthorityGuard.java';s=p.read_text().replace('    private ResponseLease lease;','    private volatile ResponseLease lease;');start=s.index('        this.client=Objects.requireNonNull(client);');end=s.index('\n    }',start)
s=s[:start]+'''        this.watchdog=Objects.requireNonNull(scheduler);
        boolean claimed=false;
        try {
            this.client=Objects.requireNonNull(client);this.lease=Objects.requireNonNull(lease);this.clock=Objects.requireNonNull(clock);this.cancel=Objects.requireNonNull(cancel);
            lease.claim();claimed=true;
            cursor=lease.completionPermit()==null?0:lease.completionPermit().initialCursor();
            watchdog.scheduleAtFixedRate(this::watchdogCheck,1,1,TimeUnit.SECONDS);
        }catch(RuntimeException invalid){
            // A rejected second holder must not stop the legitimate owner. A failed owner cannot be revived.
            if(claimed)lease.terminate();watchdog.shutdownNow();throw invalid;
        }''' + s[end:]
s=s.replace('                // The holder defends','                lease.requireSameLifecycle(next);\n                // The holder defends').replace('if(!stopped.compareAndSet(false,true))return;failure=reason;watchdog.shutdownNow();','if(!stopped.compareAndSet(false,true))return;failure=reason;lease.terminate();watchdog.shutdownNow();');p.write_text(s)
p=base/'AuthorityContext.java';s=p.read_text().replace('ThreadLocal<VerifiedAuthority>','ThreadLocal<AuthorityContext>').replace('    private final VerifiedAuthority previous;','    private final AuthorityContext previous;\n    private final VerifiedAuthority value;').replace('private AuthorityContext(VerifiedAuthority value){previous=CURRENT.get();CURRENT.set(Objects.requireNonNull(value));}','private AuthorityContext(VerifiedAuthority value){this.value=Objects.requireNonNull(value);previous=CURRENT.get();CURRENT.set(this);}').replace('        return value;','        return value.value;').replace('        if(!closed){closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}','''        if(closed)return;
        closed=true;
        // Non-LIFO close retires only this frame; later unwind must skip every already closed ancestor.
        if(CURRENT.get()==this){
            var next=previous;while(next!=null&&next.closed)next=next.previous;
            if(next==null)CURRENT.remove();else CURRENT.set(next);
        }''');p.write_text(s)
