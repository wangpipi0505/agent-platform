package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;

/**
 * One response owns one holder and its cancellation callback. Callback must cancel blocking readers.
 * Call before every chunk/final; watchdog never performs a business call or writes a business payload.
 */
public final class StreamAuthorityGuard implements AutoCloseable {
    private final ResponseAuthorityClient client;
    private final Clock clock;
    private final Runnable cancel;
    private final ScheduledExecutorService watchdog;
    private ResponseLease lease;
    private boolean stopped,accepted,draining;
    private PlatformException failure;
    private long cursor;
    public StreamAuthorityGuard(ResponseAuthorityClient client,ResponseLease lease,Clock clock,Runnable cancel){
        this(client,lease,clock,cancel,Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"response-authority-watchdog");t.setDaemon(true);return t;}));
    }
    StreamAuthorityGuard(ResponseAuthorityClient client,ResponseLease lease,Clock clock,Runnable cancel,ScheduledExecutorService scheduler){
        this.client=Objects.requireNonNull(client);this.lease=Objects.requireNonNull(lease);this.clock=Objects.requireNonNull(clock);this.cancel=Objects.requireNonNull(cancel);
        this.watchdog=scheduler;cursor=lease.completionPermit()==null?0:lease.completionPermit().initialCursor();
        watchdog.scheduleAtFixedRate(this::watchdogCheck,1,1,TimeUnit.SECONDS);
    }
    /** Trusted connector calls only after actual first dispatch, or after legal SSE acceptance. */
    public synchronized void markResponseAccepted(){beforeWrite();accepted=true;}
    public synchronized void beforeWrite(){
        if(stopped)throw failure==null?AuthorityProtocol.expired():failure;
        try {
            if(!lease.binding().hardDeadline().isAfter(clock.instant()))throw AuthorityProtocol.expired();
            if(draining){lease.completionPermit().verify();return;}
            if(!lease.sourceExpiresAt().isAfter(clock.instant()))throw AuthorityProtocol.expired();
            if(!lease.checkedUntil().isAfter(clock.instant())){
                var next=client.check(lease);
                // The holder defends immutability even if a different adapter implementation is supplied.
                AuthorityProtocol.same(lease.binding(),next.binding());AuthorityProtocol.same(lease.sourceKind(),next.sourceKind());
                AuthorityProtocol.same(lease.sourceExpiresAt(),next.sourceExpiresAt());AuthorityProtocol.same(lease.openingDecisionId(),next.openingDecisionId());
                AuthorityProtocol.same(lease.continuationRef(),next.continuationRef());AuthorityProtocol.same(lease.completionPermit(),next.completionPermit());
                if(!next.checkedUntil().isAfter(clock.instant())||next.checkedUntil().isAfter(clock.instant().plusSeconds(5)))throw AuthorityProtocol.denied();
                lease=next;
            }
        }catch(PlatformException denied){
            if(accepted && "INTEGRATION".equals(lease.sourceKind()) && lease.completionPermit()!=null){
                try{lease.completionPermit().verify();draining=true;return;}catch(PlatformException invalid){stop(invalid);throw invalid;}
            }
            stop(denied);throw denied;
        }catch(RuntimeException invalid){
            var denied=BoundedIamHttp.unavailable();stop(denied);throw denied;
        }
    }
    public void watchdogCheck(){try{beforeWrite();}catch(PlatformException ignored){}}
    public synchronized ResponseMaterialAccess materialAccess(){beforeWrite();if(!accepted||lease.completionPermit()==null)throw AuthorityProtocol.denied();return new ResponseMaterialAccess(this);}
    synchronized void requireMaterial(MaterialScopeV1 scope,ResponseMaterialAccess access){
        beforeWrite();if(!access.belongsTo(this)||!accepted||lease.completionPermit()==null)throw AuthorityProtocol.denied();
        lease.completionPermit().require(scope,cursor);
    }
    public synchronized void beforeMaterialWrite(ResponseMaterial material,ResponseMaterialAccess access){
        requireMaterial(material.scope(),access);AuthorityProtocol.same(binding().responseId(),material.responseId());MaterialAuthorization.payload(material);
        if("sse".equals(binding().kind()))cursor=Long.parseLong(material.scope().material().version().substring(material.scope().material().version().lastIndexOf(':')+1));
    }
    public synchronized boolean draining(){return draining;}
    public synchronized boolean stopped(){return stopped;}
    public synchronized ResponseBinding binding(){return lease.binding();}
    public boolean watchdogShutdown(){return watchdog.isShutdown();}
    private void stop(PlatformException reason){
        if(stopped)return;stopped=true;failure=reason;watchdog.shutdownNow();
        try{cancel.run();}catch(RuntimeException ignored){/* stopped remains true even if resource cleanup fails */}
    }
    @Override public synchronized void close(){stop(AuthorityProtocol.expired());}
}
