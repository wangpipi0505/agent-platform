package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import cn.agentplatform.common.Actor;
import java.util.function.Supplier;
import java.util.Objects;

/** Request-local scope only. Async callers must pass their verified object explicitly. */
public final class AuthorityContext implements AutoCloseable {
    private static final ThreadLocal<AuthorityContext> CURRENT=new ThreadLocal<>();
    private final Thread owner=Thread.currentThread();
    private final AuthorityContext previous;
    private VerifiedAuthority value;
    private final Supplier<VerifiedAuthority> refresh;
    private boolean closed;
    private boolean invalidated;
    private AuthorityContext(VerifiedAuthority value,Supplier<VerifiedAuthority> refresh){this.value=Objects.requireNonNull(value);this.refresh=refresh;previous=CURRENT.get();CURRENT.set(this);}
    public static AuthorityContext bind(VerifiedAuthority value){return new AuthorityContext(value,null);}
    public static VerifiedAuthority current(){
        var value=CURRENT.get();
        if(value==null||value.invalidated)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权上下文");
        return value.value;
    }
    static AuthorityContext bind(VerifiedAuthority value,Supplier<VerifiedAuthority> refresh){return new AuthorityContext(value,Objects.requireNonNull(refresh));}
    public static VerifiedAuthority requireFor(Actor actor,ResourceBinding resource){
        var frame=CURRENT.get();
        if(frame==null||frame.invalidated||frame.refresh==null)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权复核入口");
        var before=frame.value;
        // Failure is terminal for this request frame, even when business code catches the error.
        // A stale Actor/current proof must not remain readable after revocation, expiry or outage.
        frame.invalidated=true;
        // Actor is only an ownership comparison. Never exchange it for a ticket or pass its roles
        // as authority input. Reject a locally assembled wider projection before any network call.
        AuthorityProtocol.within(before.actor(),Objects.requireNonNull(actor));
        AuthorityProtocol.same(before.call().resource(),resource);
        var fresh=frame.refresh.get();
        AuthorityProtocol.same(before.caller(),fresh.caller());AuthorityProtocol.same(before.call(),fresh.call());
        AuthorityProtocol.same(before.delegation(),fresh.delegation());AuthorityProtocol.same(before.operation(),fresh.operation());
        AuthorityProtocol.same(before.authorizationVersion(),fresh.authorizationVersion());
        AuthorityProtocol.within(before.actor(),fresh.actor());
        frame.value=fresh;frame.invalidated=false;return fresh;
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
