package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import java.util.Objects;

/** Request-local scope only. Async callers must pass their verified object explicitly. */
public final class AuthorityContext implements AutoCloseable {
    private static final ThreadLocal<VerifiedAuthority> CURRENT=new ThreadLocal<>();
    private final Thread owner=Thread.currentThread();
    private final VerifiedAuthority previous;
    private boolean closed;
    private AuthorityContext(VerifiedAuthority value){previous=CURRENT.get();CURRENT.set(Objects.requireNonNull(value));}
    public static AuthorityContext bind(VerifiedAuthority value){return new AuthorityContext(value);}
    public static VerifiedAuthority current(){
        var value=CURRENT.get();
        if(value==null)throw new PlatformException(403,"AUTHORITY_CONTEXT_MISSING","缺少当前授权上下文");
        return value;
    }
    @Override public void close(){
        if(Thread.currentThread()!=owner)throw new IllegalStateException("AUTHORITY_CONTEXT_THREAD_MISMATCH");
        if(!closed){closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
