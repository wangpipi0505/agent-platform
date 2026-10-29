import { describe, expect, it } from "vitest";
import { canReadCapabilityPackages, capabilityPackageClient, declarationOptions, ontologyLink, parseJsonObject } from "../shared/capability-package";
import { createApi } from "../shared/api";

describe("capability directory presentation boundaries", () => {
  it("defaults to hidden without a supported management role", () => {
    expect(canReadCapabilityPackages([])).toBe(false);
    expect(canReadCapabilityPackages(["APPROVER"])).toBe(false);
    for (const role of ["VIEWER", "EDITOR", "ADMIN"]) expect(canReadCapabilityPackages([role])).toBe(true);
  });
  it("requires explicit object input and reads only actual declaration choices", () => {
    expect(() => parseJsonObject("[]")).toThrow();
    expect(() => parseJsonObject("null")).toThrow();
    expect(() => parseJsonObject("invalid")).toThrow();
    expect(parseJsonObject('{"applications":[]}')).toEqual({ applications: [] });
    expect(declarationOptions({ software: { environments: { staging: "declared" } }, interfaces: [{ id: "primary" }] }))
      .toEqual({ environments: ["staging"], routes: ["primary"] });
    expect(declarationOptions({ routeProfiles: [{ id: "selected" }], interfaces: [{ id: "primary" }] }).routes).toEqual(["selected"]);
  });
  it("uses the existing ontology consumption route with the original space and release", () => {
    const link = ontologyLink("http://127.0.0.1:15184/", { mode: "REFERENCE", space: "space/a", release: "r?1" });
    expect(link).toBe("http://127.0.0.1:15184/spaces/space%2Fa/releases?tab=consumption&releaseId=r%3F1");
    expect(ontologyLink("javascript:alert(1)", { mode: "REFERENCE", space: "s", release: "r" })).toBe("");
    expect(ontologyLink("https://user:secret@example.test", { mode: "REFERENCE", space: "s", release: "r" })).toBe("");
    expect(ontologyLink("https://example.test/?token=secret", { mode: "REFERENCE", space: "s", release: "r" })).toBe("");
    expect(ontologyLink("https://example.test", { mode: "NONE" })).toBe("");
  });
  it("calls the real contract paths and propagates rejected API operations", async () => {
    const requests: { path: string; method?: string; body?: string | null }[] = [];
    const client = capabilityPackageClient(createApi(() => "session", async (path, init) => {
      requests.push({ path: String(path), method: init?.method, body: typeof init?.body === "string" ? init.body : null });
      return new Response(JSON.stringify({ code: "OK", data: { packageVersionId: 7, items: [] } }), { status: 200 });
    }));
    await client.list({ ontologySpace: "space/a", ontologyRelease: "r?1" });
    await client.detail(7); await client.register({ kind: "CapabilityPackage" });
    await client.resolve(7, { environment: "staging", routeProfileId: "primary" });
    await client.createApplications(7, { applications: [] });
    expect(requests.map((request) => request.path)).toEqual([
      "/admin/capability-packages?limit=30&ontologySpace=space%2Fa&ontologyRelease=r%3F1",
      "/admin/capability-packages/7", "/admin/capability-packages/import",
      "/admin/capability-packages/7/bindings/resolve", "/admin/capability-packages/7/applications",
    ]);
    expect(JSON.parse(requests[2].body || "")).toEqual({ package: { kind: "CapabilityPackage" } });
    const rejected = capabilityPackageClient(createApi(() => "session", async () => new Response(JSON.stringify({ code: "SYS-APP-4031", message: "denied" }), { status: 403 })));
    await expect(rejected.register({})).rejects.toMatchObject({ status: 403 });
  });
});
