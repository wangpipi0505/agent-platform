/**
 * M5-2：技能发布预检投影（skill-precheck，非 mock 命名——页面不再 import mock-*）。
 *
 * 诚实边界：独立预检端点（`GET /admin/skills/{code}/prechecks`）尚未交付——本模块按
 * 确定性规则从**真实技能行**（GET /admin/skills、GET /admin/skills/{code}）推导 checklist，
 * 页面只渲染 items[].status，不自行计算任何通过/阻断（红线8 语义保持：推导规则集中于此，
 * 页面零判定）。形状与未来真实端点返回体一致，端点交付后整行替换。
 *
 * 分型 checklist（评审八轮 P2-5）：
 * - TOOL 四项：契约完整 → 连接测试 → 选型验收 → （副作用技能）意图字段声明；
 *   非副作用技能意图项免检（required=false），副作用技能 fieldAnchor 直达契约编辑器。
 * - GUIDE 两项：frontmatter 完整（name/description）→ 内容规范（SKILL.md 正文非空）。
 */

export type SkillKind = "TOOL" | "GUIDE";
export type SkillPrecheckStatus = "PASS" | "FAIL" | "PENDING";

export interface SkillPrecheckItem {
  key: string;
  label: string;
  status: SkillPrecheckStatus;
  required: boolean;
  detail: string;
  fieldAnchor?: string;
}

export interface SkillPrecheck {
  /** derived:true=由真实技能行按确定性规则推导（非独立预检端点，诚实标注）。 */
  derived: true;
  /** 兼容字段：真实/推导投影恒不为 demo（页面 demo 徽章在真实模式不渲染）。 */
  demo?: false;
  code: string;
  version: number;
  kind: SkillKind;
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

/** 技能分型推导：kind==='GUIDE' 即指南技能；其余都是有执行契约的工具技能。 */
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

/** TOOL 四项的确定性预检规则（从真实技能行字段推导）。 */
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
 * 预检投影（row=已从真实端点加载的技能行）：Promise 形状与未来真实客户端一致，
 * 端点交付后整行替换为 api 调用，页面渲染逻辑不变。
 */
export function fetchSkillPrecheck(
  row: Record<string, any> | null | undefined,
  version?: number,
): Promise<SkillPrecheck> {
  const kind = skillKindOf(row);
  const items = kind === "GUIDE" ? guideChecklistItems(row ?? {}) : toolChecklistItems(row ?? {});
  const versionNo = Number(
    version ?? row?.currentVersion ?? row?.versions?.[0]?.versionNo ?? 1,
  );
  return Promise.resolve({
    derived: true,
    code: String(row?.code ?? ""),
    version: Number.isInteger(versionNo) && versionNo > 0 ? versionNo : 1,
    kind,
    passed: items.every((item) => !item.required || item.status === "PASS"),
    items,
    checkedAt: new Date().toISOString(),
  });
}
