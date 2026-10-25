package cn.agentplatform.common.authority;

import cn.agentplatform.common.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * One finite review run against frozen classes: lifecycle restoration and holder reuse.
 * No production endpoint or credential is used.
 */
public final class Task3ReviewProbe {
    public static void main(String[] args) throws Exception {
        try (var f = new LoopbackAuthority();
             var gateway = f.client("gateway");
             var engine = f.client("engine")) {
            var call = LoopbackAuthority.call("gateway", "engine");
            var current = engine.authorize(gateway.exchange(f.integration(), call).value(), call);

            // Separate disposable worker: an incorrectly retained ThreadLocal cannot leak to other probes.
            try (var executor = Executors.newSingleThreadExecutor()) {
                boolean leaked = executor.submit(() -> {
                    var outer = AuthorityContext.bind(current);
                    var inner = AuthorityContext.bind(current);
                    outer.close();
                    inner.close();
                    try { return AuthorityContext.current() == current; }
                    catch (PlatformException denied) { return false; }
                }).get(2, TimeUnit.SECONDS);
                System.out.println("context_present_after_both_scopes_closed=" + leaked);
                if (!leaked) throw new AssertionError("Expected reviewed implementation's non-LIFO restoration");
            }

            var binding = ResponseAuthorityTest.binding(current, f, "review-original");
            var lease = engine.open(current, binding);
            var material = ResponseAuthorityTest.material(current, lease, 1);
            var first = new StreamAuthorityGuard(engine, lease, f.clock, () -> {});
            first.markResponseAccepted();
            first.beforeMaterialWrite(material, first.materialAccess());
            first.close();
            try (var reconstructed = new StreamAuthorityGuard(engine, lease, f.clock, () -> {})) {
                reconstructed.markResponseAccepted();
                var access = reconstructed.materialAccess();
                f.clock.advance(21);
                f.revoked = true;
                reconstructed.beforeMaterialWrite(material, access);
                System.out.println("same_permit_event_replayed_after_original_holder_close=true");
                System.out.println("reconstructed_holder_drains_after_expiry_and_revocation=" + reconstructed.draining());
                if (!reconstructed.draining()) throw new AssertionError("Probe must reach drain");
            }
            System.out.println("business_sends=" + f.businessSends.get());
            if (f.businessSends.get() != 0) throw new AssertionError("Unexpected business send");
        }
    }
}
