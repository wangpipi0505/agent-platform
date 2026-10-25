import { describe, it, expect } from "vitest";
import { splitGraph, mergeGraph } from "../flow-designer/transform";
import { parseFrames, applyEvent, executingNode, type ExecutingNode } from "../debug-panel/events";
import { createApi } from "../shared/api";
describe("contract boundaries", () => {
  it("layout movement never changes business definition", () => {
    const a = splitGraph({
      cells: [
        {
          id: "s",
          position: { x: 1, y: 2 },
          data: { type: "START", config: {} },
        },
      ],
    });
    const b = splitGraph({
      cells: [
        {
          id: "s",
          position: { x: 8, y: 9 },
          data: { type: "START", config: {} },
        },
      ],
    });
    expect(a.definition).toEqual(b.definition);
    expect(
      (mergeGraph(a.definition, a.layout).cells[0] as any).position,
    ).toEqual({ x: 1, y: 2 });
  });
  it("parses split CRLF multiline frames and keeps partial tail", () => {
    expect(
      parseFrames(
        'id: 3\r\nevent: CHUNK\r\ndata: {"x":\r\ndata: 1}\r\n\r\nid: 4',
      ),
    ).toEqual({
      frames: [{ id: "3", event: "CHUNK", data: '{"x":\n1}' }],
      rest: "id: 4",
    });
  });
  it("deduplicates replay and never rewinds watermark", () => {
    const s = {
      watermark: "4",
      events: [] as any[],
      nodes: {} as Record<string, string>,
    };
    expect(
      applyEvent(s, { seq: "3", type: "NODE_STARTED", data:{nodeId:"a"},rawData:'{"nodeId":"a"}' }),
    ).toEqual(s);
    expect(
      applyEvent(s, { seq: "5", type: "NODE_FINISHED", data:{nodeId:"a"},rawData:'{"nodeId":"a"}' }).nodes.a,
    ).toBe("SUCCEEDED");
  });
  it("fails closed without token and carries memory token", async () => {
    const calls: any[] = [];
    const api = createApi(
      () => "",
      async (...args: any[]) => {
        calls.push(args);
        return new Response("{}");
      },
    );
    await expect(api("/admin/apps")).rejects.toThrow("请先登录");
    expect(calls).toHaveLength(0);
  });

  it("executingNode keeps a single spinner under concurrent runs (UIF-A3)", () => {
    let current: ExecutingNode = null;
    const start = (seq: string, node: string) =>
      (current = executingNode(current, { seq, type: "NODE_STARTED", data:{nodeId:node}, rawData:'{}' }, node));
    const finish = (seq: string, node: string) =>
      (current = executingNode(current, { seq, type: "NODE_FINISHED", data:{nodeId:node}, rawData:'{}' }, node));
    start("5", "a");
    // 乱序/重放的旧 START 不改变当前显示节点
    start("3", "b");
    expect(current).toEqual({ node: "a", seq: "5" });
    start("6", "b");
    expect(current).toEqual({ node: "b", seq: "6" });
    // 其他节点的完成事件不清空 spinner（当前显示的是 b）
    finish("7", "a");
    expect(current).toEqual({ node: "b", seq: "6" });
    // 当前节点自身完成才清空；空闲后接受任意序号（waiting 恢复计数归零场景）
    finish("8", "b");
    expect(current).toBeNull();
    start("2", "c");
    expect(current).toEqual({ node: "c", seq: "2" });
  });
});
