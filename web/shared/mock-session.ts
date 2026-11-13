/** T2-14 mock 契约：会话域——GET /v1/apps/{code}/sessions?mine=true（S2-1 契约，接口稿 §5.1）。仅 demo 装载。 */

export interface MockSessionRow {
  sessionId: string
  appCode: string
  title: string
  lastActiveAt: string
  createdAt: string
  state: string
  origin: "USER" | "SUBAGENT" | "SCHEDULE" | "API" | "TASK_INTERNAL"
  demo: true
}

const seedSessions: MockSessionRow[] = [
  { sessionId: "sess-demo-28", appCode: "satellite-planning-assistant", title: "帮我规划今晚的观测序列，云量控制在", lastActiveAt: "2026-11-13T10:00:00Z", createdAt: "2026-11-13T08:00:00Z", state: "活跃", origin: "USER", demo: true },
  { sessionId: "sess-demo-27", appCode: "satellite-planning-assistant", title: "上次差异报告的第 3 节需要补充对比", lastActiveAt: "2026-11-12T16:20:00Z", createdAt: "2026-11-12T15:00:00Z", state: "终态", origin: "USER", demo: true },
  { sessionId: "sess-demo-internal-1", appCode: "satellite-planning-assistant", title: "TASK_INTERNAL 根会话（不得出现在列表）", lastActiveAt: "2026-11-13T09:00:00Z", createdAt: "2026-11-13T09:00:00Z", state: "终态", origin: "TASK_INTERNAL", demo: true },
]

export const truncateTitle = (firstUserMessage: string, max = 48) =>
  firstUserMessage.length <= max ? firstUserMessage : firstUserMessage.slice(0, max)

/**
 * 本人会话列表：principal 过滤（mock 固定 u-demo）、排除 TASK_INTERNAL（规划根会话经任务访问）、
 * lastActiveAt 排序 + before 游标分页（lastActiveAt 晚于该值的行），返回 nextBefore。
 * 重命名/删除不在本期契约（接口稿 §5.1），列表只读+切换+新建。
 */
export function listMySessions(query: { mine?: boolean; limit?: number; before?: string } = {}):
  { sessions: Omit<MockSessionRow, "origin" | "demo">[]; nextBefore?: string } {
    if (!query.mine) throw new Error("mine=true 首期固定")
    const visible = seedSessions
      .filter((s) => s.origin !== "TASK_INTERNAL")
      .sort((a, b) => b.lastActiveAt.localeCompare(a.lastActiveAt))
    const filtered = query.before ? visible.filter((s) => s.lastActiveAt < query.before!) : visible
    const limit = query.limit ?? 50
    const page = filtered.slice(0, limit)
    const next = filtered.length > page.length ? page.at(-1)?.lastActiveAt : undefined
    return { sessions: page.map(({ origin: _o, demo: _d, ...rest }) => rest), nextBefore: next }
  }
