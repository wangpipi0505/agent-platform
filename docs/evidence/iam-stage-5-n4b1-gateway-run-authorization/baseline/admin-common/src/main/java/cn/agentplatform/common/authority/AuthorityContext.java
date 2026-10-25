package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Actor;
import java.util.function.Supplier;
import java.util.Objects;
import cn.agentplatform.common.authority.HttpCurrentAuthorityClient.RetainedAuthorization;

/** Request-local scope only. Async callers must pass their verified object explicitly. */
public final class AuthorityContext implements AutoCloseable {
    private static final ThreadLocal<AuthorityContext> CURRENT=new ThreadLocal<>();
    private final Thread owner=Thread.currentThread();
    private final AuthorityContext previous;
    private RetainedAuthorization mapping;
    private final Supplier<RetainedAuthorization> refresh;
    private boolean closed;
    private boolean invalidated;
    private AuthorityContext(RetainedAuthorization mapping,Supplier<RetainedAuthorization> refresh){this.mapping=Objects.requireNonNull(mapping);this.refresh=refresh;previous=CURRENT.get();CURRENT.set(this);}
    public static AuthorityContext bind(VerifiedAuthority value){return new AuthorityContext(RetainedAuthorization.currentOnly(value),null);}
    public static VerifiedAuthority current(){
        var frame=frame();
        try{if(frame.mapping.hasInitialProof())frame.mapping.requireVerified();return frame.mapping.current();}
        catch(RuntimeException e){frame.invalidated=true;throw e;}
    }
    static AuthorityContext bind(VerifiedAuthority value,Supplier<VerifiedAuthority> refresh){
        Objects.requireNonNull(refresh);return bindRetained(RetainedAuthorization.currentOnly(value),()->RetainedAuthorization.currentOnly(refresh.get()));
    }
    static AuthorityContext bindRetained(RetainedAuthorization mapping,Supplier<RetainedAuthorization> refresh){
        return new AuthorityContext(mapping,Objects.requireNonNull(refresh));
    }
    public static VerifiedAuthority requireFor(Actor actor,ResourceBinding resource){
        return refresh(actor,resource,false).current();
    }
    public static RetainedAuthorization requireInitialFor(Actor actor,ResourceBinding resource){
        return refresh(actor,resource,true);
    }
    /** Local-only transaction seam. A refreshed, closed or cross-thread capture is never usable. */
    public static void requireBound(RetainedAuthorization expected){
        var frame=frame();frame.invalidated=true;
        if(frame.mapping!=expected)throw AuthorityProtocol.denied();
        expected.requireVerified();frame.invalidated=false;
    }
    private static AuthorityContext frame(){
        var frame=CURRENT.get();
        if(frame==null||frame.closed||frame.invalidated)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权上下文");
        return frame;
    }
    private static RetainedAuthorization refresh(Actor actor,ResourceBinding resource,boolean initialRequired){
        var frame=frame();var beforeMapping=frame.mapping;var before=beforeMapping.current();
        // Failure is terminal for this request frame, even when business code catches the error.
        // A stale Actor/current proof must not remain readable after revocation, expiry or outage.
        frame.invalidated=true;
        if(frame.refresh==null)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权复核入口");
        if(initialRequired||beforeMapping.hasInitialProof())beforeMapping.requireVerified();
        // Actor is only an ownership comparison. Never exchange it for a ticket or pass its roles
        // as authority input. Reject a locally assembled wider projection before any network call.
        AuthorityProtocol.within(before.actor(),Objects.requireNonNull(actor));
        AuthorityProtocol.same(before.call().resource(),resource);
        var freshMapping=Objects.requireNonNull(frame.refresh.get());var fresh=freshMapping.current();
        AuthorityProtocol.same(before.caller(),fresh.caller());AuthorityProtocol.same(before.call(),fresh.call());
        AuthorityProtocol.same(before.delegation(),fresh.delegation());AuthorityProtocol.same(before.operation(),fresh.operation());
        AuthorityProtocol.same(before.authorizationVersion(),fresh.authorizationVersion());
        AuthorityProtocol.within(before.actor(),fresh.actor());
        if(initialRequired||beforeMapping.hasInitialProof()){
            freshMapping.requireVerified();
            AuthorityProtocol.same(beforeMapping.requireInitialCeiling(),freshMapping.requireInitialCeiling());
            AuthorityProtocol.same(beforeMapping.requireOriginAuthenticationId(),freshMapping.requireOriginAuthenticationId());
        }
        frame.mapping=freshMapping;frame.invalidated=false;return freshMapping;
    }
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("AUTHORITY_CONTEXT_THREAD_MISMATCH");
        if(closed)return;
        closed=true;
        // Non-LIFO close retires only this frame; later unwind must skip every already closed ancestor.
        if(CURRENT.get()==this){
            var next=previous;while(next!=null&&next.closed)next=next.previous;
            if(next==null)CURRENT.remove();else CURRENT.set(next);
        }
    }
}
