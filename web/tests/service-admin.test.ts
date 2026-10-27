import { describe, expect, it } from "vitest";
import {
  commandFromText,
  commandText,
  serviceActionPath,
  serviceStateClass,
  serviceStateLabel,
} from "../admin/src/pages/service-admin";

describe("service management presentation", () => {
  it("uses explicit argv lines and drops blank lines only", () => {
    expect(commandFromText("C:\\Java\\bin\\java.exe\n-jar\n\napp.jar\n")).toEqual([
      "C:\\Java\\bin\\java.exe",
      "-jar",
      "app.jar",
    ]);
    expect(commandText(["C:\\Java\\bin\\java.exe", "-jar", "app.jar"])).toBe(
      "C:\\Java\\bin\\java.exe\n-jar\napp.jar",
    );
  });

  it("labels every lifecycle state without claiming unknown processes are healthy", () => {
    expect(serviceStateLabel("READY")).toBe("运行中");
    expect(serviceStateLabel("UNHEALTHY")).toBe("健康检查失败");
    expect(serviceStateLabel("UNKNOWN")).toBe("未接管");
    expect(serviceStateLabel("SOMETHING_NEW")).toBe("SOMETHING_NEW");
    expect(serviceStateClass("FAILED")).toBe("failed");
    expect(serviceStateClass("READY")).toBe("ready");
  });

  it("sends the service code as the confirmation token and keeps delete on the resource path", () => {
    expect(serviceActionPath("space demo", "stop")).toBe(
      "/admin/services/space%20demo/stop?confirm=space%20demo",
    );
    expect(serviceActionPath("space_demo", "restart")).toBe(
      "/admin/services/space_demo/restart?confirm=space_demo",
    );
    expect(serviceActionPath("space_demo", "delete")).toBe(
      "/admin/services/space_demo?confirm=space_demo",
    );
  });
});
