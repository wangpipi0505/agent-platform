import { describe, expect, it } from "vitest";
import { probeView, serviceAccess } from "../shared/service-access";

describe("service access card", () => {
  it("renders only callable remote definitions with endpoint", () => {
    expect(serviceAccess({ type: "TEMPLATE", endpoint: "http://x" })).toBeNull();
    expect(serviceAccess({ type: "HTTP" })).toBeNull();
    const view = serviceAccess({
      type: "HTTP",
      endpoint: "http://127.0.0.1:8080/api/v1/space/visibility/plan",
      httpMethod: "POST",
      authConfigured: true,
      timeoutMs: 30000,
    });
    expect(view).toMatchObject({
      origin: "http://127.0.0.1:8080",
      path: "/api/v1/space/visibility/plan",
      method: "POST",
      authConfigured: true,
      timeoutMs: 30000,
    });
  });

  it("keeps non-absolute algorithm endpoints visible without fake origin links", () => {
    const view = serviceAccess({ type: "ALGORITHM", endpoint: "local://orbit" });
    expect(view).toMatchObject({ endpoint: "local://orbit", origin: "" });
  });

  it("maps probe results to honest labels", () => {
    expect(probeView(null).label).toBe("尚未探测");
    expect(probeView({ status: "REACHABLE", httpStatus: 204, latencyMs: 8 })).toMatchObject({
      label: "端点可达",
      type: "success",
    });
    expect(probeView({ status: "DEGRADED", httpStatus: 503 }).type).toBe("warning");
    expect(probeView({ status: "UNREACHABLE", reason: "探测超时" })).toMatchObject({
      label: "端点不可达",
      type: "danger",
      detail: "探测超时",
    });
  });
});
