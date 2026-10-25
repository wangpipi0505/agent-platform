package cn.agentplatform.common.authority;

/** Default-closed declaration scaffold for the independently signed PORTAL response profile. */
public final class PortalEventsResponse implements AutoCloseable {
    private PortalEventsResponse() {
        throw BoundedIamHttp.unavailable();
    }

    public Capture currentCapture() {
        throw BoundedIamHttp.unavailable();
    }

    public void requireLive(Capture capture) {
        throw BoundedIamHttp.unavailable();
    }

    @Override
    public void close() {
    }

    public static final class Capture {
        private Capture() {
            throw BoundedIamHttp.unavailable();
        }

        public SubjectKey subject() {
            throw BoundedIamHttp.unavailable();
        }

        public ResourceBinding resource() {
            throw BoundedIamHttp.unavailable();
        }

        public String responseId() {
            throw BoundedIamHttp.unavailable();
        }

        public void requireMaterial(MaterialScopeV1 scope) {
            throw BoundedIamHttp.unavailable();
        }
    }
}
