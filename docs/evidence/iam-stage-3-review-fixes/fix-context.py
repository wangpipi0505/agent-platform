from pathlib import Path
p=Path('admin-common/src/main/java/cn/agentplatform/common/authority/AuthorityContext.java');s=p.read_text(encoding='utf8').replace('ThreadLocal<VerifiedAuthority>','ThreadLocal<AuthorityContext>').replace('    private final VerifiedAuthority previous;','    private final AuthorityContext previous;\n    private final VerifiedAuthority value;').replace('private AuthorityContext(VerifiedAuthority value){previous=CURRENT.get();CURRENT.set(Objects.requireNonNull(value));}','private AuthorityContext(VerifiedAuthority value){this.value=Objects.requireNonNull(value);previous=CURRENT.get();CURRENT.set(this);}').replace('        return value;','        return value.value;').replace('        if(!closed){closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}','''        if(closed)return;
        closed=true;
        // Non-LIFO close retires only this frame; later unwind must skip every already closed ancestor.
        if(CURRENT.get()==this){
            var next=previous;while(next!=null&&next.closed)next=next.previous;
            if(next==null)CURRENT.remove();else CURRENT.set(next);
        }''');p.write_text(s,encoding='utf8')
