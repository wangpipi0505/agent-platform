export type Definition = {
  definitionVersion: string;
  trigger: string;
  entry: string;
  inputs: any[];
  outputs: any[];
  nodes: any[];
  edges: any[];
  [key: string]: any;
};
export function splitGraph(graph: any, base?: Definition) {
  // Layout is deliberately excluded from semantic data: dragging cannot invalidate candidate digests.
  const nodes = graph.cells
    .filter((c: any) => !c.source)
    .map((c: any) => ({ ...c.data, id: c.id }));
  const edges = graph.cells
    .filter((c: any) => c.source)
    .map((c: any) => ({
      ...c.data,
      from: typeof c.source === "string" ? c.source : c.source.cell,
      to: typeof c.target === "string" ? c.target : c.target.cell,
    }));
  return {
    definition: {
      definitionVersion: "1.0",
      trigger: "TASK",
      inputs: [],
      outputs: [],
      joins: [],
      executionRules: {
        parallel: false,
        maxParallel: 1,
        cyclesForbidden: true,
      },
      ...base,
      entry:
        base?.entry && nodes.some((n: any) => n.id === base.entry)
          ? base.entry
          : nodes.find((n: any) => n.type === "START")?.id || "",
      nodes,
      edges,
    },
    layout: Object.fromEntries(
      graph.cells
        .filter((c: any) => !c.source)
        .map((c: any) => [c.id, c.position || { x: 0, y: 0 }]),
    ),
  };
}
export function mergeGraph(
  definition: Definition,
  layout: Record<string, any> = {},
) {
  return {
    cells: [
      ...(definition.nodes || []).map((n: any, i: number) => ({
        id: n.id,
        shape: "rect",
        width: 164,
        height: 64,
        label: n.name || n.type,
        attrs: {
          body: {
            stroke: "var(--st-wait)",
            strokeWidth: 2,
            fill: "var(--surface)",
            rx: 6,
            ry: 6,
          },
          label: { fill: "var(--ink)", fontSize: 13 },
        },
        position: layout[n.id] || { x: 80 + i * 210, y: 100 },
        data: n,
        ports: {
          groups: {
            in: { position: "left", attrs: { circle: { r: 5, magnet: true } } },
            out: {
              position: "right",
              attrs: { circle: { r: 5, magnet: true } },
            },
          },
          items: [
            { id: "in", group: "in" },
            { id: "out", group: "out" },
          ],
        },
      })),
      ...(definition.edges || []).map((e: any, i: number) => ({
        id: "edge_" + i,
        shape: "edge",
        source: { cell: e.from, port: "out" },
        target: { cell: e.to, port: "in" },
        data: e,
      })),
    ],
  };
}
