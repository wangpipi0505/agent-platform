import type { paths } from "./generated/api";
// Literal and template routes are checked against live OpenAPI; no endpoint is invented for code generation.
export const apiRoutes = {
  apps: "/admin/apps",
  skills: "/admin/skills",
  providers: "/admin/model-providers",
  entries: "/admin/model-entries",
  templates: "/admin/prompt-templates",
  runs: "/admin/runs",
  projection: "/admin/{kind}",
  evaluations: "/admin/evaluations",
} satisfies Record<string, keyof paths>;
export const projectionRoute = (kind: "approvals" | "statistics" | "audit") =>
  apiRoutes.projection.replace("{kind}", kind);
export type PlatformPaths = paths;
