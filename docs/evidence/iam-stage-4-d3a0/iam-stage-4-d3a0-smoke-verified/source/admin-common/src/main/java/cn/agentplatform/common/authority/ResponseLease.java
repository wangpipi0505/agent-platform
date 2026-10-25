package cn.agentplatform.common.authority;

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
