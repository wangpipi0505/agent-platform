import { it, expect } from "vitest";
import { mergeGraph, splitGraph } from "../flow-designer/transform";
it("opens an empty server draft as an empty editor without fabricating nodes", () => {
  expect(mergeGraph({} as any, {}).cells).toEqual([]);
});
it("first explicit START selection establishes entry on an empty draft", () => {
  const d = splitGraph(
    { cells: [{ id: "s", data: { type: "START" }, position: { x: 1, y: 1 } }] },
    {} as any,
  ).definition;
  expect(d.entry).toBe("s");
  expect(d.executionRules.cyclesForbidden).toBe(true);
  expect(d.joins).toEqual([]);
});
