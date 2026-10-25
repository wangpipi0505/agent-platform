package cn.agentplatform.engine.application;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.authority.*;
import cn.agentplatform.engine.infra.RunAuthorityStore;
import java.time.*;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit local continuation, not wired into scheduler/driver admission yet. */
public final class RunAuthority {
    private final RunAuthorityStore store;
    private final HttpCurrentAuthorityClient client;
    private final Clock clock;
    private final Duration backoff;
    public RunAuthority(RunAuthorityStore store,HttpCurrentAuthorityClient client,Clock clock,Duration backoff){
        this.store=Objects.requireNonNull(store);this.client=Objects.requireNonNull(client);this.clock=Objects.requireNonNull(clock);
        if(backoff==null||backoff.isNegative()||backoff.isZero()||backoff.compareTo(Duration.ofMinutes(1))>0)throw new IllegalArgumentException("BOUNDED_RETRY_REQUIRED");this.backoff=backoff;
    }
    public VerifiedAuthority current(long runId,ServiceCall call){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("CURRENT_NETWORK_REQUIRES_NO_TRANSACTION");
        if(!ServiceRoutePolicy.localRunAdvance("engine",call,"delegated","DELEGATED","PORTAL"))throw RunAuthorityStore.denied();
        var binding=store.load(runId);if(!binding.root().equals(call.resource()))throw RunAuthorityStore.denied();
        if(binding.blockedReason()!=null&&!"AUTHORITY_UNAVAILABLE".equals(binding.blockedReason()))throw RunAuthorityStore.denied();
        if(binding.retryNotBefore()!=null&&binding.retryNotBefore().isAfter(clock.instant()))throw unavailable();
        try{
            if(!binding.delegation().expiresAt().isAfter(clock.instant()))throw new PlatformException(403,"AUTHORITY_EXPIRED","原运行委托已到期");
            // Both online operations are outside every PG transaction and have the same exact tuple.
            var ticket=client.resume(binding.delegation(),call);
            var proof=client.authorizeRetained(ticket.value(),call);proof.requireVerified();RunAuthorityStore.strict(binding,proof);
            for(var scope:binding.scopes())MaterialAuthorization.require(scope,proof.current(),clock);
            if(!store.recordCheck(binding,proof,null,backoff))throw new StaleCheck();
            // Spring's real commit has returned. Its historical observation cannot be rolled back,
            // but a proof expiring during commit must never be delivered as advance authority.
            proof.requireVerified();
            return proof.current();
        }catch(PlatformException e){
            if(e.status()!=403&&e.status()!=503)throw e;
            String reason=e.status()==503?"AUTHORITY_UNAVAILABLE":"AUTHORITY_EXPIRED".equals(e.code())?"AUTHORITY_EXPIRED":"AUTHORITY_DENIED";
            // CAS zero is an obsolete observation, never permission to overwrite newer blocking state.
            store.recordCheck(binding,null,reason,backoff);throw e;
        }catch(StaleCheck e){throw RunAuthorityStore.denied();}
    }
    private static final class StaleCheck extends RuntimeException {}
    private static PlatformException unavailable(){return new PlatformException(503,"AUTHORITY_UNAVAILABLE","授权服务暂不可用，请稍后重试");}
}
