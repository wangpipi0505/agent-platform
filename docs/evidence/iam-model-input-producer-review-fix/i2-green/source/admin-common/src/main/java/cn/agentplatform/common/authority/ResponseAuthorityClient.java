package cn.agentplatform.common.authority;
public interface ResponseAuthorityClient {
    default GatewayPortalEventsResponse openGatewayPortalEvents(HttpCurrentAuthorityClient.GatewayEventsOpening opening,
            ResourceBinding runSnapshot,Runnable cancel){throw BoundedIamHttp.unavailable();}
    default PortalEventsResponse openPortalEventsChild(HttpCurrentAuthorityClient.RetainedAuthorization initial,
            long effectiveCursor,Runnable cancel){throw BoundedIamHttp.unavailable();}
    default void checkGatewayPortalEvents(GatewayPortalEventsResponse response){throw BoundedIamHttp.unavailable();}
    default String portalEventsChildReceipt(PortalEventsResponse response){throw BoundedIamHttp.unavailable();}
    ResponseLease open(VerifiedAuthority authority, ResponseBinding binding);

    ResponseLease check(ResponseLease lease);

    /** A sibling profile: older adapters remain closed until they implement its signed current. */
    default PortalEventsResponse openPortalEvents(HttpCurrentAuthorityClient.RetainedAuthorization initial,
            PortalEventsBinding binding, Runnable cancel) {
        throw BoundedIamHttp.unavailable();
    }

    default void checkPortalEvents(PortalEventsResponse response) {
        throw BoundedIamHttp.unavailable();
    }
}
