package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import java.util.Objects;

/** Request-local scope only. Async callers must pass their verified object explicitly. */
public final class AuthorityContext implements AutoCloseable {
    private static final ThreadLocal<AuthorityContext> CURRENT=new ThreadLocal<>();
    private final Thread owner=Thread.currentThread();
    private final AuthorityContext previous;
    private final VerifiedAuthority value;
    private boolean closed;
    private AuthorityContext(VerifiedAuthority value){this.value=Objects.requireNonNull(value);previous=CURRENT.get();CURRENT.set(this);}
    public static AuthorityContext bind(VerifiedAuthority value){return new AuthorityContext(value);}
    public static VerifiedAuthority current(){
        var value=CURRENT.get();
        if(value==null)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权上下文");
        return value.value;
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
