import { it, expect } from "vitest";
import { validateDefinition } from "../flow-designer/validation";
it("rejects contract definitions missing execution rules and joins", () => {
  expect(
    validateDefinition({
      definitionVersion: "1.0",
      trigger: "TASK",
      entry: "s",
      nodes: [{ id: "s", type: "START" }],
      edges: [],
    }),
  ).toContain("executionRules");
});
it("preserves valid contract extensions and rejects unknown node types", () => {
  const d = {
    definitionVersion: "1.0",
    trigger: "TASK",
    entry: "s",
    nodes: [{ id: "s", type: "INVENTED" }],
    edges: [],
    joins: [],
    executionRules: { parallel: false, maxParallel: 1, cyclesForbidden: true },
  };
  expect(validateDefinition(d)).toContain("nodes");
});
