package cn.agentplatform.common.authority;

/** Cannot be constructed from a caller body, serialized lease, Actor, or expired current authority. */
public final class ResponseMaterialAccess {
    private final StreamAuthorityGuard owner;
    ResponseMaterialAccess(StreamAuthorityGuard owner){this.owner=owner;}
    void require(MaterialScopeV1 scope){owner.requireMaterial(scope,this);}
    boolean belongsTo(StreamAuthorityGuard guard){return owner==guard;}
    public String responseId(){return owner.binding().responseId();}
    public SubjectKey owner(){return owner.accessPermit(this).owner();}
    public ResourceBinding resource(){return owner.accessPermit(this).binding().resource();}
    public String producer(){return owner.accessPermit(this).producer();}
    public String consumer(){return owner.accessPermit(this).consumer();}
    @Override public String toString(){return "ResponseMaterialAccess[response="+responseId()+"]";}
}
