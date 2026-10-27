package cn.agentplatform.common;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.*;

/** PLAT-12：subject 快照的不可变、拷贝语义与映射键确定性（guard/审计/出站共用同一份）。 */
public class ActorSubjectSnapshotTest {
    @Test
    void snapshotIsImmutableAndIsolatedFromCallerOwnedCollections() {
        Set<String> roles = new HashSet<>(List.of("EDITOR"));
        Map<String, Set<String>> attributes = new HashMap<>();
        attributes.put("region", new HashSet<>(List.of("A")));
        Actor actor = new Actor("tenant-a", "alice", roles, attributes);

        // 构造后再改动调用方自持有的集合，不得漏进 Actor 或其快照
        roles.add("INJECTED");
        attributes.put("dept", Set.of("103"));

        SubjectSnapshot snapshot = actor.subject();
        assertThat(snapshot.tenantId()).isEqualTo("tenant-a");
        assertThat(snapshot.principalId()).isEqualTo("alice");
        assertThat(snapshot.roles()).containsExactly("EDITOR");
        assertThat(snapshot.attributes()).containsOnlyKeys("region");
        assertThat(snapshot.attributes().get("region")).containsExactly("A");
        assertThat(snapshot.roles()).isEqualTo(actor.roles());
        assertThat(snapshot.attributes()).isEqualTo(actor.attributes());

        // 快照自身的集合与条目不可变
        assertThatThrownBy(() -> snapshot.roles().add("X"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.attributes().put("x", Set.of("y")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.attributes().get("region").add("B"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void snapshotMatchesVerifiedJwtClaims() {
        Jwt jwt =
                Jwt.withTokenValue("verified-by-decoder")
                        .header("alg", "HS256")
                        .subject("bob")
                        .claim("tenant_id", "tenant-b")
                        .claim("roles", List.of("APPROVER", "EDITOR"))
                        .claim("attributes", Map.of("dept", List.of("103", "205")))
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(60))
                        .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        try {
            SubjectSnapshot snapshot = Actor.current().subject();
            assertThat(snapshot.tenantId()).isEqualTo("tenant-b");
            assertThat(snapshot.principalId()).isEqualTo("bob");
            assertThat(snapshot.roles()).containsExactlyInAnyOrder("APPROVER", "EDITOR");
            assertThat(snapshot.attributes().get("dept")).containsExactlyInAnyOrder("103", "205");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void mappingKeyIsDeterministicAndDiscriminatesContent() {
        var a = new SubjectSnapshot("t", "u", Set.of("R2", "R1"), Map.of("dept", Set.of("103", "205")));
        var b =
                new SubjectSnapshot(
                        "t",
                        "u",
                        new HashSet<>(List.of("R1", "R2")),
                        new HashMap<>(Map.of("dept", new HashSet<>(List.of("205", "103")))));
        // 集合迭代顺序不同、内容相同 → 必须得到同一映射键，否则同一主体会漂移出多个键
        assertThat(b.mappingKey()).isEqualTo(a.mappingKey()).hasSize(64);
        var changed = new SubjectSnapshot("t", "u", Set.of("R1"), Map.of("dept", Set.of("103")));
        assertThat(changed.mappingKey()).isNotEqualTo(a.mappingKey());
        var otherPrincipal =
                new SubjectSnapshot("t", "v", Set.of("R1", "R2"), Map.of("dept", Set.of("103", "205")));
        assertThat(otherPrincipal.mappingKey()).isNotEqualTo(a.mappingKey());
    }

    @Test
    void snapshotRejectsBlankIdentityLikeActor() {
        assertThatThrownBy(() -> new SubjectSnapshot(" ", "u", Set.of(), Map.of()))
                .isInstanceOf(PlatformException.class);
        assertThatThrownBy(() -> new SubjectSnapshot("t", null, Set.of(), Map.of()))
                .isInstanceOf(PlatformException.class);
    }
}
