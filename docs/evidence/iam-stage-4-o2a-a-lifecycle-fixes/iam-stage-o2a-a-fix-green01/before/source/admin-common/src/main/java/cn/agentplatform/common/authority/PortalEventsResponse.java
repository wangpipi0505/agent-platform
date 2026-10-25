package cn.agentplatform.common.authority;

import cn.agentplatform.common.PlatformException;
import java.util.concurrent.TimeUnit;

/** One opaque response, with local-only captures; retained request frames never cross this seam. */
public final class PortalEventsResponse implements AutoCloseable {
    private final HttpCurrentAuthorityClient owner;
    private HttpCurrentAuthorityClient.PortalEventsEvidence evidence;
    private HttpCurrentAuthorityClient.EventsTask<Void> pending;
    private long generation;
    private PlatformException failure;
    private boolean cancellationStarted;

    PortalEventsResponse(HttpCurrentAuthorityClient.PortalEventsEvidence evidence) {
        evidence.consume();
        evidence.remainingNanos();
        this.evidence = evidence;
        this.owner = evidence.owner;
    }

    public Capture currentCapture() {
        synchronized (this) {
            requireCurrent();
            return new Capture(this, evidence, generation);
        }
    }

    /** Local only: safe inside a caller's bounded read transaction, with no implicit online check. */
    public void requireLive(Capture capture) {
        synchronized (this) {
            requireCurrent();
            if (capture == null || capture.holder != this || capture.evidence != evidence
                    || capture.generation != generation || capture.evidence.owner != owner) {
                throw AuthorityProtocol.denied();
            }
        }
    }

    private long requireCurrent() {
        if (failure != null) throw failure;
        try {
            return evidence.remainingNanos();
        } catch (PlatformException expired) {
            // Local observations are terminal even if the wall clock moves back before the timer ticks.
            failure = expired;
            throw expired;
        }
    }

    long remainingNanos(HttpCurrentAuthorityClient expectedOwner) {
        synchronized (this) {
            requireOwner(expectedOwner);
            return requireCurrent();
        }
    }

    private void requireOwner(HttpCurrentAuthorityClient expectedOwner) {
        if (expectedOwner != owner) throw AuthorityProtocol.denied();
    }

    HttpCurrentAuthorityClient.EventsTask<Void> renew(HttpCurrentAuthorityClient expectedOwner) {
        HttpCurrentAuthorityClient.EventsTask<Void> task;
        synchronized (this) {
            requireOwner(expectedOwner);
            requireCurrent();
            if (pending != null) return pending;
            task = owner.renewEvents(this, evidence);
            pending = task;
        }
        // Publish before submission; even an immediate result cannot strand single-flight.
        try {
            owner.eventsLifecycle.accept("renew-published");
            owner.executeEvents(task);
        }
        catch (RuntimeException rejected) {
            task.cancel(false);
            stop(BoundedIamHttp.unavailable());
            throw rejected;
        }
        return task;
    }

    void install(HttpCurrentAuthorityClient.PortalEventsEvidence expected,
            HttpCurrentAuthorityClient.PortalEventsEvidence replacement) {
        synchronized (this) {
            requireCurrent();
            if (evidence != expected || replacement.owner != owner || replacement.slot != expected.slot
                    || replacement.binding != expected.binding || replacement.immutable != expected.immutable) {
                throw AuthorityProtocol.denied();
            }
            replacement.remainingNanos();
            replacement.consume();
            evidence = replacement;
            generation++;
        }
    }

    void renewFinished(HttpCurrentAuthorityClient.EventsTask<?> task) {
        synchronized (this) {
            if (pending == task) pending = null;
        }
    }

    void tick() {
        try {
            long remaining;
            synchronized (this) { remaining = requireCurrent(); }
            if (remaining <= TimeUnit.SECONDS.toNanos(3)) renew(owner);
        } catch (PlatformException stopped) {
            stop(stopped);
        } catch (RuntimeException unavailable) {
            stop(BoundedIamHttp.unavailable());
        }
    }

    void stop(PlatformException reason) {
        HttpCurrentAuthorityClient.EventsTask<?> task;
        HttpCurrentAuthorityClient.EventsSlot slot;
        synchronized (this) {
            if (cancellationStarted) return;
            if (failure == null) failure = reason;
            cancellationStarted = true;
            generation++;
            task = pending;
            slot = evidence.slot;
        }
        // Stop precedes cancellation. Running work owns its physical slot until its actual finally.
        owner.eventsLifecycle.accept("stop-published");
        if (task != null) task.cancel(true);
        owner.cancelEvents(slot);
    }

    @Override
    public void close() {
        stop(AuthorityProtocol.expired());
    }

    public static final class Capture {
        private final PortalEventsResponse holder;
        private final HttpCurrentAuthorityClient.PortalEventsEvidence evidence;
        private final long generation;

        private Capture(PortalEventsResponse holder, HttpCurrentAuthorityClient.PortalEventsEvidence evidence,
                long generation) {
            this.holder = holder;
            this.evidence = evidence;
            this.generation = generation;
        }

        public SubjectKey subject() {
            holder.requireLive(this);
            return AuthorityProtocol.subject(evidence.current.actor());
        }

        public ResourceBinding resource() {
            holder.requireLive(this);
            return evidence.binding.resource();
        }

        public String responseId() {
            holder.requireLive(this);
            return evidence.binding.responseId();
        }

        /** Coverage is not PG provenance. The domain reader still binds and hashes its real rows. */
        public void requireMaterial(MaterialScopeV1 scope) {
            holder.requireLive(this);
            MaterialAuthorization.validate(scope);
            AuthorityProtocol.same(AuthorityProtocol.subject(evidence.current.actor()), scope.owner());
            MaterialAuthorization.cover(scope, evidence.current.actor());
            holder.requireLive(this);
        }
    }
}
