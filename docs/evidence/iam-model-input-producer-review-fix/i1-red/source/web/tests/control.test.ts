import { it, expect } from "vitest";
import { safeMarkdown } from "../shared/content";
import { resumePayload } from "../debug-panel/control";
it("rejects stale pause and uses instance identity for resume", () => {
  expect(() => resumePayload(null, "NEXT_NODE", "r")).toThrow("没有");
  expect(
    resumePayload(
      {
        nodeInstanceId: "ni_1",
        nodeDefId: "n1",
        pauseSeq: 9,
        allowedActions: ["NEXT_NODE"],
      },
      "NEXT_NODE",
      "r",
    ),
  ).toEqual({
    nodeInstanceId: "ni_1",
    pauseSeq: 9,
    action: "NEXT_NODE",
    requestId: "r",
  });
});
it("rejects actions not granted by authoritative snapshot", () => {
  expect(() =>
    resumePayload(
      { nodeInstanceId: "ni", pauseSeq: 1, allowedActions: [] },
      "RUN_TO_END",
      "r",
    ),
  ).toThrow("不允许");
});
