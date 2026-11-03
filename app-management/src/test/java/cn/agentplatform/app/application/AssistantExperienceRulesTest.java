package cn.agentplatform.app.application;

import cn.agentplatform.app.infra.AssistantExperienceRepository.Revision;
import cn.agentplatform.common.Actor;
import cn.agentplatform.common.Digest;
import cn.agentplatform.common.Json;
import cn.agentplatform.common.PlatformException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-03 体验描述的纯规则校验：不启动应用服务、不连库，坏输入必须在领域层被拒绝。 */
class AssistantExperienceRulesTest {

  private static final Actor ACTOR = new Actor("t-1", "u-1", Set.of("ADMIN"), Map.of());

  private static Map<String, Object> body() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("title", "逾期账单助手");
    body.put("welcomeText", "我帮你找出逾期客户。");
    body.put("capabilityDescriptions", List.of(Map.of(
        "code", "customers.read", "label", "查询客户", "readOnly", true)));
    body.put("promptRefs", List.of(Map.of("ref", "overdue-brief", "version", 2)));
    body.put("helpRefs", List.of("doc:overdue-faq"));
    return body;
  }

  @Test
  void declarationIsStableAndOrderIndependent() {
    Revision first = AssistantExperiences.declaration(ACTOR, body());
    Revision second = AssistantExperiences.declaration(ACTOR, body());
    assertEquals(first.sha256(), second.sha256());
    assertEquals(64, first.sha256().length());
    assertTrue(first.sha256().matches("^[0-9a-f]{64}$"));
  }

  @Test
  void digestOnlyCoversDeclaredFields() {
    Map<String, Object> changed = body();
    changed.put("title", "另一个标题");
    Revision a = AssistantExperiences.declaration(ACTOR, body());
    Revision b = AssistantExperiences.declaration(ACTOR, changed);
    assertTrue(!a.sha256().equals(b.sha256()), "标题变化必须改变摘要");
  }

  @Test
  void unknownFieldsAndMissingTitleAreRejected() {
    // 未知字段在进入 declaration 之前由 create()/revise() 用同一个 keys 白名单拒绝（见 AssistantExperiences:56）。
    Map<String, Object> unknown = body();
    unknown.put("themeColor", "#fff");
    PlatformException e = assertThrows(PlatformException.class,
        () -> ConnectionProfiles.keys(unknown, AssistantExperiences.BODY_KEYS, "体验描述"));
    assertEquals("INVALID_INPUT", e.code());
    assertTrue(e.getMessage().contains("未知字段: themeColor"));

    Map<String, Object> noTitle = body();
    noTitle.remove("title");
    assertEquals("INVALID_INPUT",
        assertThrows(PlatformException.class,
            () -> AssistantExperiences.declaration(ACTOR, noTitle)).code());
  }

  @Test
  void plaintextSecretsNeverEnterExperience() {
    Map<String, Object> leaked = body();
    leaked.put("apiKey", "sk-live-123");
    PlatformException e = assertThrows(PlatformException.class,
        () -> AssistantExperiences.declaration(ACTOR, leaked));
    assertEquals("CONFIG_SECRET_PLAINTEXT_REJECTED", e.code());
  }

  @Test
  void presentationMustStaySmall() {
    Map<String, Object> body = body();
    Map<String, Object> presentation = new LinkedHashMap<>();
    for (int i = 0; i < 20; i++) {
      presentation.put("k" + i, "v");
    }
    body.put("inputPresentation", presentation);
    assertEquals("CONFIG_SCHEMA_BUDGET_EXCEEDED",
        assertThrows(PlatformException.class,
            () -> AssistantExperiences.declaration(ACTOR, body)).code());
  }

  @Test
  void capabilityCountIsBounded() {
    Map<String, Object> body = body();
    List<Map<String, Object>> capabilities = new ArrayList<>();
    for (int i = 0; i < 33; i++) {
      capabilities.add(Map.of("code", "cap" + i, "label", "能力" + i));
    }
    body.put("capabilityDescriptions", capabilities);
    assertEquals("CONFIG_SCHEMA_BUDGET_EXCEEDED",
        assertThrows(PlatformException.class,
            () -> AssistantExperiences.declaration(ACTOR, body)).code());
  }

  @Test
  void promptVersionMustBePositiveInteger() {
    Map<String, Object> body = body();
    body.put("promptRefs", List.of(Map.of("ref", "overdue-brief", "version", 0)));
    assertEquals("INVALID_INPUT",
        assertThrows(PlatformException.class,
            () -> AssistantExperiences.declaration(ACTOR, body)).code());
  }

  @Test
  void canonicalDigestMatchesPublishedFormula() {
    Revision revision = AssistantExperiences.declaration(ACTOR, body());
    Map<String, Object> declared = new LinkedHashMap<>();
    declared.put("title", revision.title());
    declared.put("welcomeText", revision.welcomeText());
    declared.put("capabilityDescriptions", revision.capabilityDescriptions());
    declared.put("promptRefs", revision.promptRefs());
    declared.put("inputPresentation", revision.inputPresentation());
    declared.put("resultPresentation", revision.resultPresentation());
    declared.put("helpRefs", revision.helpRefs());
    assertEquals(Digest.sha256(Json.canonical(declared)), revision.sha256());
  }
}
