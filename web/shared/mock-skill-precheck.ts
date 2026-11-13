/**
 * F2-2 技能发布预检 mock 数据源（仅 demo，不经 mock-transport 装载；先例=F1-2 mock-confirmation-counts.ts）。
 *
 * 形状 = 未来真实端点 `GET /admin/skills/{code}/prechecks?version={n}` 返回体（方案 §5.5
 * 「各项展示服务端预检结果，非前端自算」）：真实端点就绪后 SkillDetailPage 把
 * fetchMockSkillPrecheck 换成 api 客户端即可，页面渲染逻辑不变。
 *
 * demo:true = 非服务端数据（mock 资产惯例，见 mock-task.ts MockTaskSeed）。
 * 「服务端预检结果」在演示态由本模块按确定性规则推导（等价服务端读库预检），
 * 页面只渲染 items[].status，不自行计算任何通过/阻断。
 *
 * 分型 checklist（评审八轮 P2-5）：
 * - TOOL  四项：契约完整 → 连接测试 → 选型验收 → （副作用技能）意图字段声明；
 *   非副作用技能意图项免检（required=false，不阻断发布），副作用技能该项
 *   fieldAnchor='businessIntentInput'，前端点击直达契约编辑器对应字段（锚点滚动）。
 * - GUIDE 两项：frontmatter 完整（name/description）→ 内容规范（SKILL.md 正文非空）；
 *   无连接测试/选型验收/意图字段项（GUIDE 无执行契约）。
 */

export type SkillKind = "TOOL" | "GUIDE";
export type SkillPrecheckStatus = "PASS" | "FAIL" | "PENDING";

export interface SkillPrecheckItem {
  /** 稳定键：contract/connection/acceptance/intent/frontmatter/content */
  key: string;
  label: string;
  status: SkillPrecheckStatus;
  /** false=免检项（如非副作用技能的意图声明），不阻断发布 */
  required: boolean;
  /** 服务端给出的结论说明（原样展示，不由前端改写） */
  detail: string;
  /** 阻断项可定位字段：点击 checklist 项滚动到契约编辑器该字段（方案 §5.2） */
  fieldAnchor?: string;
}

export interface SkillPrecheck {
  /** demo:true=非服务端数据 */
  demo: true;
  code: string;
  version: number;
  kind: SkillKind;
  /** 所有必检项全部 PASS 才为 true；前端据此激活发布按钮 */
  passed: boolean;
  items: SkillPrecheckItem[];
  checkedAt: string;
}

/** 列表 kind 筛选选项（方案 §5.5：类型筛选 TOOL/GUIDE） */
export const SKILL_KIND_FILTERS: { value: SkillKind | ""; label: string }[] = [
  { value: "", label: "全部类型" },
  { value: "TOOL", label: "工具 TOOL" },
  { value: "GUIDE", label: "指南 GUIDE" },
];

/**
 * 技能分型推导：kind==='GUIDE' 即指南技能；其余（HTTP/MCP/ALGORITHM/PROCESS/TOOL）
 * 都是有执行契约的工具技能。存量行没有 kind 字段时按 TOOL 处理。
 */
export function skillKindOf(row?: { kind?: unknown } | null): SkillKind {
  return String(row?.kind ?? "").toUpperCase() === "GUIDE" ? "GUIDE" : "TOOL";
}

/** 列表客户端 kind 过滤：''=不过滤；TOOL=非 GUIDE；GUIDE=仅 GUIDE。 */
export function matchesKindFilter(
  row: { kind?: unknown },
  filter: SkillKind | "",
): boolean {
  if (!filter) return true;
  return skillKindOf(row) === filter;
}

function hasSchema(value: unknown) {
  return !!value && typeof value === "object";
}

/** TOOL 四项的确定性服务端预检规则（demo 语义见文件头）。 */
function toolChecklistItems(row: Record<string, any>): SkillPrecheckItem[] {
  const sideEffect = row?.execution?.sideEffect === true || row?.sideEffect === true;
  const intent = String(row?.businessIntentInput ?? "").trim();
  return [
    {
      key: "contract",
      label: "契约完整",
      status: hasSchema(row?.inputSchema) && hasSchema(row?.outputSchema) ? "PASS" : "FAIL",
      required: true,
      detail:
        hasSchema(row?.inputSchema) && hasSchema(row?.outputSchema)
          ? "输入/输出 Schema 与执行属性齐备"
          : "缺少输入或输出 Schema，请补全契约",
    },
    {
      key: "connection",
      label: "连接测试",
      status: String(row?.endpoint ?? "").trim() ? "PASS" : "PENDING",
      required: true,
      detail: String(row?.endpoint ?? "").trim()
        ? "最近一次受控连通记录有效"
        : "尚无受控连通记录，请在本页发起受控测试",
    },
    {
      key: "acceptance",
      label: "选型验收",
      status: row?.status === "PUBLISHED" ? "PASS" : "PENDING",
      required: true,
      detail:
        row?.status === "PUBLISHED"
          ? "当前版本选型验收报告已通过"
          : "当前版本没有通过的选型验收报告，请运行选型验收后重试",
    },
    sideEffect
      ? {
          key: "intent",
          label: "意图字段声明",
          status: intent ? "PASS" : "FAIL",
          required: true,
          detail: intent
            ? "副作用业务意图已声明（businessIntentInput）"
            : "副作用技能必须声明业务意图字段 businessIntentInput",
          fieldAnchor: "businessIntentInput",
        }
      : {
          key: "intent",
          label: "意图字段声明",
          status: "PASS",
          required: false,
          detail: "无副作用技能，免检",
        },
  ];
}

/** GUIDE 两项：无连接测试/选型验收/意图字段项。 */
function guideChecklistItems(row: Record<string, any>): SkillPrecheckItem[] {
  const named = Boolean(String(row?.name ?? "").trim()) && Boolean(String(row?.description ?? "").trim());
  const content = String(row?.content ?? "").trim();
  return [
    {
      key: "frontmatter",
      label: "frontmatter 完整",
      status: named ? "PASS" : "FAIL",
      required: true,
      detail: named ? "name/description 齐备" : "frontmatter 缺少 name 或 description",
    },
    {
      key: "content",
      label: "内容规范",
      status: content ? "PASS" : "PENDING",
      required: true,
      detail: content ? "SKILL.md 正文非空" : "SKILL.md 正文为空，请补写内容",
    },
  ];
}

/**
 * Promise 形状与未来真实客户端一致（便于整行替换为 api 调用）。
 * row 为详情页已加载的技能行——真实端点由服务端读库，演示态传入已加载行推导。
 */
export function fetchMockSkillPrecheck(
  row: Record<string, any> | null | undefined,
  version?: number,
): Promise<SkillPrecheck> {
  const kind = skillKindOf(row);
  const items = kind === "GUIDE" ? guideChecklistItems(row ?? {}) : toolChecklistItems(row ?? {});
  const versionNo = Number(
    version ?? row?.currentVersion ?? row?.versions?.[0]?.versionNo ?? 1,
  );
  return Promise.resolve({
    demo: true,
    code: String(row?.code ?? ""),
    version: Number.isInteger(versionNo) && versionNo > 0 ? versionNo : 1,
    kind,
    passed: items.every((item) => !item.required || item.status === "PASS"),
    items,
    checkedAt: new Date().toISOString(),
  });
}
