package cn.agentplatform.common.authority;
import cn.agentplatform.common.Actor;
import java.time.Instant;
import java.util.*;

public interface ResponseAuthorityClient {
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
