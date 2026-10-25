package cn.agentplatform.common.authority;

/** Opaque G view over the existing bounded holder. No authority or engine delegate escapes. */
public final class GatewayPortalEventsResponse implements AutoCloseable {
    private final HttpCurrentAuthorityClient owner;
    private final PortalEventsResponse holder;
    private final HttpCurrentAuthorityClient.GatewayEventsParent parent;
    GatewayPortalEventsResponse(HttpCurrentAuthorityClient.GatewayEventsParent parent){
        parent.consume();this.parent=parent;owner=parent.owner;holder=parent.holder;
    }
    HttpCurrentAuthorityClient.GatewayEventsParent requireParent(HttpCurrentAuthorityClient expected){
        if(owner!=expected)throw AuthorityProtocol.denied();holder.currentCapture();return parent;
    }
    public Capture currentCapture(){return new Capture(this,holder.currentCapture());}
    public void requireLive(Capture capture){
        if(capture==null||capture.owner!=this)throw AuthorityProtocol.denied();holder.requireLive(capture.capture);
    }
    public boolean isSuperseded(Capture capture){
        holder.currentCapture();
        if(capture==null||capture.owner!=this)throw AuthorityProtocol.denied();return holder.isSuperseded(capture.capture);
    }
    public void acceptChildReceipt(String signedReceipt){owner.acceptGatewayChildReceipt(this,signedReceipt);}
    @Override public void close(){holder.close();}
    public static final class Capture {
        private final GatewayPortalEventsResponse owner;
        private final PortalEventsResponse.Capture capture;
        private Capture(GatewayPortalEventsResponse owner,PortalEventsResponse.Capture capture){this.owner=owner;this.capture=capture;}
        public SubjectKey subject(){owner.requireLive(this);return capture.subject();}
        public ResourceBinding resource(){owner.requireLive(this);return capture.resource();}
        public String responseId(){owner.requireLive(this);return capture.responseId();}
        public long initialCursor(){owner.requireLive(this);return capture.initialCursor();}
        public long remainingNanos(){owner.requireLive(this);return capture.remainingNanos();}
        public void requireMaterial(MaterialScopeV1 material){owner.requireLive(this);capture.requireMaterial(material);owner.requireLive(this);}
    }
}
