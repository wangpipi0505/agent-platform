import { describe, expect, it } from "vitest";
import {
  ENVIRONMENTS,
  actionError,
  activationBlockers,
  activeRelease,
  containsSensitiveKey,
  copyAsDraft,
  diagnoseStages,
  diffRelease,
  parseApplications,
  parseReleases,
  redactedExport,
  releaseImpact,
  validateDraft,
  type ConnectionProfile,
  type EnvironmentBinding,
  type ExperienceView,
  type IntegrationProfileView,
  type ReleaseView,
} from "../admin/src/pages/integration/integration-console";

const digest = "a".repeat(64);

const release = (overrides: Partial<ReleaseView> = {}): ReleaseView => ({
  releaseId: "rel_1",
  appCode: "ops",
  appVersion: 7,
  candidateSha256: digest,
  experienceRev: 3,
  integrationProfileId: "profile_1",
  integrationProfileRev: 2,
  connectionProfileRefs: [{ profileId: "conn_1", profileRev: 4 }],
  activationScope: "production",
  status: "ACTIVE",
  digest,
  createdBy: "admin",
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  skillRefs: [{ code: "erp_query", version: 2 }],
  semanticBindings: [],
  ...overrides,
});

const experience = (status = "APPROVED"): ExperienceView => ({
  appCode: "ops",
  experienceRev: 3,
  status,
  createdBy: "admin",
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  revisions: [
    {
      experienceRev: 3,
      title: "运营助手",
      welcomeText: "你好",
      capabilityDescriptions: [],
      promptRefs: [],
      inputPresentation: {},
      resultPresentation: {},
      helpRefs: [],
      declarationSha256: digest,
      createdBy: "admin",
      createdAt: "2026-11-01T00:00:00Z",
    },
  ],
});

const profile = (status = "APPROVED"): IntegrationProfileView => ({
  profileId: "profile_1",
  appCode: "ops",
  profileRev: 2,
  status,
  createdBy: "admin",
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  revisions: [
    {
      profileRev: 2,
      bizSystemCode: "erp",
      environment: "production",
      mode: "L2",
      allowedOrigins: ["https://erp.example.com"],
      contextSchema: {},
      credentialPolicyRef: "policy:erp",
      experienceRef: { appCode: "ops", experienceRev: 3 },
      declarationSha256: digest,
      createdBy: "admin",
      createdAt: "2026-11-01T00:00:00Z",
    },
  ],
});

const connection = (status = "APPROVED"): ConnectionProfile => ({
  profileId: "conn_1",
  currentRev: 4,
  status,
  createdBy: "admin",
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  revisions: [],
});

const binding = (environment = "production", status = "APPROVED"): EnvironmentBinding => ({
  bindingId: "bind_1",
  currentRev: 1,
  status,
  createdBy: "admin",
  createdAt: "2026-11-01T00:00:00Z",
  updatedAt: "2026-11-01T00:00:00Z",
  revisions: [
    {
      bindingRev: 1,
      environment,
      connectionProfileId: "conn_1",
      connectionProfileRev: 4,
      baseEndpoint: "https://erp.example.com",
      hasSecret: true,
      runtimeRef: {},
      originPolicy: [],
      healthPolicy: {},
      declarationSha256: digest,
      createdBy: "admin",
      createdAt: "2026-11-01T00:00:00Z",
    },
  ],
});

describe("integration console release flow", () => {
  it("copies only the active release of the selected environment", () => {
    const production = release();
    const draft = copyAsDraft(production, "ops", "production");
    expect(draft.appVersion).toBe(7);
    expect(draft.connectionProfileRefs).toEqual([{ profileId: "conn_1", profileRev: 4 }]);
    expect(() => copyAsDraft(release({ activationScope: "staging" }), "ops", "production")).toThrow(/不能把 staging/);
    expect(() => copyAsDraft(null, "ops", "production")).toThrow(/激活/);
  });

  it("reports incomplete drafts instead of silently saving them", () => {
    const draft = copyAsDraft(release(), "ops", "production");
    expect(validateDraft(draft)).toEqual([]);
    expect(validateDraft({ ...draft, appVersion: 0, connectionProfileRefs: [{ profileId: "", profileRev: 0 }] })).toEqual(
      expect.arrayContaining(["智能体版本必须是正整数", "连接引用必须包含档案与正整数修订"]),
    );
  });

  it("diffs a draft against the active revision", () => {
    const base = release();
    const draft = { ...copyAsDraft(base, "ops", "production"), experienceRev: 4 };
    const fields = diffRelease(base, draft);
    expect(fields.map((field) => field.field)).toEqual(["experienceRev"]);
    expect(fields[0].before).toBe("3");
    expect(fields[0].after).toBe("4");
    expect(diffRelease(null, draft)).toEqual([]);
  });

  it("blocks activation until release, experience, profile and connections are approved", () => {
    const blocked = activationBlockers({
      release: release({ status: "DRAFT" }),
      environment: "production",
      experience: experience("DRAFT"),
      profile: profile("DRAFT"),
      connections: [connection("DRAFT")],
    });
    expect(blocked.join("\n")).toMatch(/APPROVED/);
    expect(blocked.join("\n")).toMatch(/连接档案 conn_1/);

    const environmentMismatch = activationBlockers({
      release: release({ activationScope: "staging" }),
      environment: "production",
      experience: experience(),
      profile: profile(),
      connections: [connection()],
    });
    expect(environmentMismatch.join("\n")).toMatch(/staging/);

    const ready = activationBlockers({
      release: release({ status: "APPROVED" }),
      environment: "production",
      experience: experience(),
      profile: profile(),
      connections: [connection()],
    });
    expect(ready).toEqual([]);
  });

  it("exports only logical references and validation digests", () => {
    const payload = redactedExport(release(), "production");
    expect(containsSensitiveKey(payload)).toBe(false);
    const text = JSON.stringify(payload);
    expect(text).not.toMatch(/secretRef|token|createdBy|baseEndpoint|password/i);
    expect(text).toContain("rel_1");
    expect(text).toContain(digest);
  });

  it("explains impact for activate and disable", () => {
    expect(releaseImpact("activate").join("\n")).toMatch(/不回溯改写/);
    expect(releaseImpact("disable").join("\n")).toMatch(/CONFIG_RELEASE_NOT_ACTIVE/);
  });

  it("maps 409 conflicts to an actionable message", () => {
    expect(actionError({ status: 409, code: "CONFIG_REV_CONFLICT" })).toMatch(/新的 expectedRev/);
    expect(actionError({ code: "CONFIG_RELEASE_NOT_ACTIVE" })).toMatch(/激活的配置/);
  });

  it("marks unavailable stages as UNKNOWN rather than fabricating probes", () => {
    const stages = diagnoseStages({
      environment: "production",
      releases: [release()],
      profiles: [profile()],
      connections: [connection()],
      bindings: [binding()],
      credential: {
        id: 1,
        appCode: "ops",
        bizSystemCode: "erp",
        status: "ACTIVE",
        allowedOrigins: ["https://erp.example.com"],
        principalIds: ["alice"],
        issuedAt: "2026-11-01T00:00:00Z",
      },
    });
    const identity = stages.find((stage) => stage.key === "identity");
    expect(identity?.status).toBe("UNKNOWN");
    expect(identity?.evidence).toMatch(/无只读/);
    expect(stages.find((stage) => stage.key === "ticket")?.status).toBe("OK");
    expect(stages.find((stage) => stage.key === "version")?.status).toBe("OK");
    expect(stages.find((stage) => stage.key === "binding")?.status).toBe("OK");
  });

  it("flags a missing credential and a missing binding without calling anything", () => {
    const stages = diagnoseStages({
      environment: "staging",
      releases: [release()],
      profiles: [profile()],
      connections: [connection()],
      bindings: [binding()],
      credential: null,
    });
    expect(stages.find((stage) => stage.key === "ticket")?.status).toBe("BLOCKED");
    expect(stages.find((stage) => stage.key === "binding")?.status).toBe("WARN");
    expect(stages.find((stage) => stage.key === "version")?.status).toBe("BLOCKED");
  });

  it("picks the active release only for the requested environment", () => {
    const releases = [release(), release({ releaseId: "rel_2", activationScope: "staging", status: "ACTIVE" })];
    expect(activeRelease(releases, "production")?.releaseId).toBe("rel_1");
    expect(activeRelease(releases, "staging")?.releaseId).toBe("rel_2");
    expect(activeRelease([], "production")).toBeNull();
    expect(ENVIRONMENTS).toContain("production");
  });

  it("accepts array and items payloads but rejects unknown shapes", () => {
    const row = release();
    expect(parseReleases([row])).toHaveLength(1);
    expect(parseReleases({ items: [row] })).toHaveLength(1);
    expect(() => parseReleases({ rows: [] })).toThrow(/列表格式/);
    expect(parseApplications({ items: [{ code: "ops", name: "运营" }] })).toEqual([{ code: "ops", name: "运营" }]);
  });
});
