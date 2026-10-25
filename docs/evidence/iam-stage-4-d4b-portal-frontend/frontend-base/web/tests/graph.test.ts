import { it, expect } from "vitest";
import { mergeGraph, splitGraph } from "../flow-designer/transform";
it("editing graph preserves graph-level join and execution contract", () => {
  const d = {
    definitionVersion: "1.0",
    trigger: "TASK",
    entry: "s",
    inputs: [],
    outputs: [],
    nodes: [{ id: "s", type: "START" }],
    edges: [],
    joins: [],
    executionRules: { parallel: true, maxParallel: 4, cyclesForbidden: true },
  };
  expect(splitGraph(mergeGraph(d, {}), d).definition).toEqual(d);
});
