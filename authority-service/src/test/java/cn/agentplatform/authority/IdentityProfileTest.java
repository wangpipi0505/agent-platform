package cn.agentplatform.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.agentplatform.authority.identity.IdentityMapping;
import cn.agentplatform.authority.identity.IdentityProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 身份源档案的配置期校验：协议是有限枚举、映射只能是点分路径、tenant/environment 是硬上限。
 *
 * <p>这些用例不启动 Spring——它们要证明的是"错误配置在启动绑定阶段就失败"，而不是等到首个请求
 * 才在字段缺失处静默降级。INT-04 明确禁止任意脚本鉴权，所以表达式字符必须被配置期拒绝。
 */
class IdentityProfileTest {

    private static IdentityProperties profile(
            boolean enabled, String protocol, Map<String, String> mapping, String environment) {
        return new IdentityProperties(
                enabled,
                enabled ? "http://127.0.0.1:19000" : null,
                enabled ? "env:IDENTITY_KEY" : null,
                "/system/apikeys/identity/self",
                "local-v12",
                "https://authority.test",
                "agent-platform-portal",
                "gateway",
                "platform.access",
                "agent-platform",
                2000,
                2000,
                600,
                Set.of("SERVICE", "GATEWAY_AUTH"),
                protocol,
                mapping,
                "0",
                environment);
    }

    @Test
    void reservedProtocolIsRejectedAtConfigurationTime() {
        assertThatThrownBy(() -> profile(true, "OIDC_USERINFO", Map.of(), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTHORITY_IDENTITY_INVALID")
                .hasMessageContaining("OIDC_USERINFO");
    }

    @Test
    void environmentMustBeADeploymentSlug() {
        assertThatThrownBy(() -> profile(true, "USER_MGMT", Map.of(), "Prod_1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment");
        assertThat(profile(true, "USER_MGMT", Map.of(), "prod-2").environment()).isEqualTo("prod-2");
    }

    @Test
    void mappingRejectsUnknownTargetsAndExpressions() {
        assertThatThrownBy(() -> profile(true, "USER_MGMT", Map.of("nickname", "user.nick"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未知的映射目标");
        assertThatThrownBy(() -> profile(true, "USER_MGMT", Map.of("userName", "$.user.name"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("点分属性路径");
        assertThatThrownBy(() -> profile(true, "USER_MGMT", Map.of("userName", "a.b.c.d"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void kebabCaseTargetsAreCanonicalizedSoYamlAndJavaAgree() {
        IdentityProperties properties =
                profile(
                        true,
                        "USER_MGMT",
                        Map.of("user-name", "profile.login", "dept-id", "org.id"),
                        "prod");
        assertThat(properties.mapping())
                .containsEntry("userName", "profile.login")
                .containsEntry("deptId", "org.id");
        assertThat(properties.profileSummary().get("mapping")).asString().contains("userName");
        assertThat(properties.profileSummary().toString()).as("档案摘要不得回 apiKeyRef 指向的值")
                .doesNotContain("env:IDENTITY_KEY");
    }

    @Test
    void resolveFollowsDottedPathsAndExpandsOneListLevel() {
        Map<String, Object> identity =
                Map.of(
                        "profile", Map.of("login", "zhaoliu"),
                        "org", Map.of("id", 9, "name", "研发一部"),
                        "grants", List.of(Map.of("code", "ops"), Map.of("code", "SRE")));
        assertThat(IdentityMapping.resolve(identity, "profile.login")).isEqualTo("zhaoliu");
        assertThat(IdentityMapping.resolve(identity, "org.id")).isEqualTo(9);
        assertThat(IdentityMapping.resolve(identity, "grants[].code"))
                .isEqualTo(List.of("ops", "SRE"));
    }

    @Test
    void disabledProfileKeepsDefaultsAndDoesNotRequireUrl() {
        IdentityProperties disabled = profile(false, "USER_MGMT", null, null);
        assertThat(disabled.mapping()).containsEntry("userName", "userName");
        assertThat(disabled.enabled()).isFalse();
    }

    @Test
    void defaultMappingIsUpgradedToConfiguredPaths() {
        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("status", "profile.state");
        IdentityProperties properties = profile(true, "USER_MGMT", configured, null);
        assertThat(properties.mapping())
                .containsEntry("status", "profile.state")
                .containsEntry("userName", "userName")
                .containsEntry("roleKey", "roleKey");
    }
}
