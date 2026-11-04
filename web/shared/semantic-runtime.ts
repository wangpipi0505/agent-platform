import Ajv2020 from "ajv/dist/2020";
import goalSchemaText from "../../contracts/semantic-runtime-goal-v1.schema.json?raw";
const goalSchema = JSON.parse(goalSchemaText) as { $defs: Record<string, unknown> };

/**
 * SEM-09 业务配置端的语义表单与权威显示规则。
 *
 * 设计不变量：
 * - 表单只从服务端已发布的定义/扩展里选择，不手写稳定 JSON，也不接受授权相关字段
 *   （scope/grant/spaceCode 覆盖/revision 等）；调用方即使传入也会被丢弃。
 * - 完成状态只认服务端事实与回执。模型文本可以解释“想做什么”，但不能把
 *   UNKNOWN/PENDING 改写成成功，也不能补出服务端未给的 evidenceRef。
 * - 权限不足时不返回内部资源标识（对象类型/动作/扩展），只给可展示的原因。
 */

export const SEMANTIC_PROFILE = "read-only@1.0" as const;
export const SEMANTIC_SCHEMA_VERSION = "1.0" as const;

export type SemanticRole = "VIEWER" | "EDITOR" | "APPROVER" | "ADMIN";

export interface SemanticReleaseRef {
  spaceCode: string;
  releaseId: string;
  version: string;
  contentDigest: string;
}

export interface SemanticSkillRef {
  skillCode: string;
  skillVersion: number;
  skillSha256?: string;
}

export interface SemanticFactField {
  field: string;
  kind: "attribute" | "relation";
}

/** 与 OR SemanticDescriptor 白名单一一对应；form 只能产出这些键。 */
export interface SemanticBindingRequest {
  releaseRef: SemanticReleaseRef;
  extensionId: string;
  opCode: string;
  objectType: string;
  skill: SemanticSkillRef;
  profiles: [typeof SEMANTIC_PROFILE];
  factFields: SemanticFactField[];
  ruleRefs?: string[];
}

export interface SemanticExtensionChoice {
  id: string;
  opCode: string;
  profile: string;
  schemaVersion: string;
  status: "ACTIVE" | "RETIRED";
  descriptor: {
    objectType: string;
    factFields: SemanticFactField[];
    ruleRefs?: string[];
  };
}

export interface SemanticSelection {
  releaseRef: SemanticReleaseRef;
  objectTypes: string[];
  actions: string[];
  extensions: SemanticExtensionChoice[];
}

export interface SemanticBindingForm {
  releaseRef: SemanticReleaseRef;
  objectType: string;
  opCode: string;
  extensionId: string;
  skillCode: string;
  skillVersion: number;
  skillSha256?: string;
  factFields: SemanticFactField[];
  ruleRefs?: string[];
}

export class SemanticFormError extends Error {
  constructor(
    readonly field: string,
    message: string,
  ) {
    super(message);
    this.name = "SemanticFormError";
  }
}

const DIGEST = /^[0-9a-f]{64}$/;
const SKILL_CODE = /^[A-Za-z0-9._:-]{1,64}$/;
const OP_CODE = /^[A-Za-z0-9._:-]{1,80}$/;
const MAX_FACT_FIELDS = 32;
const MAX_RULE_REFS = 16;

/** 配置页只对能写配置的角色展示写操作；是否真正允许仍由服务端 Actor 判定。 */
export function canReadSemantics(roles: readonly string[]): boolean {
  return roles.some((role) => ["VIEWER", "EDITOR", "APPROVER", "ADMIN"].includes(role));
}

export function canWriteSemantics(roles: readonly string[]): boolean {
  return roles.some((role) => ["EDITOR", "APPROVER", "ADMIN"].includes(role));
}

function requireText(value: unknown, field: string, pattern?: RegExp, max = 128): string {
  const text = typeof value === "string" ? value.trim() : "";
  if (!text || text.length > max || (pattern && !pattern.test(text)))
    throw new SemanticFormError(field, `${field} 不是有效的已发布取值`);
  return text;
}

function requireDigest(value: unknown, field: string): string {
  const text = typeof value === "string" ? value.trim() : "";
  if (!DIGEST.test(text)) throw new SemanticFormError(field, `${field} 必须为 64 位小写十六进制摘要`);
  return text;
}

export function validateReleaseRef(input: SemanticReleaseRef): SemanticReleaseRef {
  return {
    spaceCode: requireText(input?.spaceCode, "releaseRef.spaceCode", /^[A-Za-z0-9._-]{1,64}$/, 64),
    releaseId: requireText(input?.releaseId, "releaseRef.releaseId"),
    version: requireText(input?.version, "releaseRef.version", /^v[1-9][0-9]*$/, 64),
    contentDigest: requireDigest(input?.contentDigest, "releaseRef.contentDigest"),
  };
}

function normalizeFacts(fields: readonly SemanticFactField[] | undefined): SemanticFactField[] {
  const seen = new Set<string>();
  const facts: SemanticFactField[] = [];
  for (const item of fields ?? []) {
    const field = requireText(item?.field, "factFields.field", /^[A-Za-z_][A-Za-z0-9_-]{0,63}$/, 64);
    if (item.kind !== "attribute" && item.kind !== "relation")
      throw new SemanticFormError("factFields.kind", "事实字段类型只能是 attribute 或 relation");
    const key = `${item.kind}:${field}`;
    if (seen.has(key)) continue;
    seen.add(key);
    facts.push({ field, kind: item.kind });
  }
  if (facts.length > MAX_FACT_FIELDS)
    throw new SemanticFormError("factFields", `事实字段最多 ${MAX_FACT_FIELDS} 项`);
  return facts;
}

/**
 * 由“服务端已发布的选择集”构造绑定请求。
 * - extension 必须 ACTIVE、同 opCode、profile 为 read-only@1.0；
 * - objectType/opCode 必须出现在选择集中，不允许自由输入；
 * - 任何授权/来源字段都不在返回体内（调用方传入的 scope/grant 等直接被忽略）。
 */
export function buildSemanticBindingRequest(
  form: SemanticBindingForm,
  selection: SemanticSelection,
): SemanticBindingRequest {
  const releaseRef = validateReleaseRef(form?.releaseRef);
  if (JSON.stringify(releaseRef) !== JSON.stringify(validateReleaseRef(selection?.releaseRef)))
    throw new SemanticFormError("releaseRef", "绑定必须引用当前选择的固定发布版本");

  const objectType = requireText(form.objectType, "objectType", /^[A-Za-z_][A-Za-z0-9_]{0,63}$/, 64);
  if (!selection.objectTypes.includes(objectType))
    throw new SemanticFormError("objectType", "对象类型不在当前发布的可用定义中");
  const opCode = requireText(form.opCode, "opCode", OP_CODE, 80);
  if (!selection.actions.includes(opCode))
    throw new SemanticFormError("opCode", "动作不在当前发布且已授权的操作中");

  const extension = selection.extensions.find((item) => item.id === form.extensionId);
  if (!extension || extension.status !== "ACTIVE" || extension.profile !== SEMANTIC_PROFILE)
    throw new SemanticFormError("extensionId", "语义扩展未激活或 profile 不匹配");
  if (extension.opCode !== opCode)
    throw new SemanticFormError("extensionId", "语义扩展与所选动作不匹配");

  const skill: SemanticSkillRef = {
    skillCode: requireText(form.skillCode, "skill.skillCode", SKILL_CODE, 64),
    skillVersion: Number(form.skillVersion),
  };
  if (!Number.isInteger(skill.skillVersion) || skill.skillVersion < 1)
    throw new SemanticFormError("skill.skillVersion", "技能版本必须为正整数");
  if (form.skillSha256 !== undefined && form.skillSha256 !== "")
    skill.skillSha256 = requireDigest(form.skillSha256, "skill.skillSha256");

  const facts = normalizeFacts(form.factFields?.length ? form.factFields : extension.descriptor.factFields);
  if (!facts.length) throw new SemanticFormError("factFields", "至少选择一个可投影事实字段");
  const ruleRefs = [...new Set((form.ruleRefs ?? extension.descriptor.ruleRefs ?? []).map((id) => String(id)))];
  if (ruleRefs.length > MAX_RULE_REFS)
    throw new SemanticFormError("ruleRefs", `规则引用最多 ${MAX_RULE_REFS} 项`);
  if (ruleRefs.some((id) => !/^[A-Za-z0-9:._/-]{1,128}$/.test(id)))
    throw new SemanticFormError("ruleRefs", "规则引用含非法字符");

  const request: SemanticBindingRequest = {
    releaseRef,
    extensionId: extension.id,
    opCode,
    objectType,
    skill,
    profiles: [SEMANTIC_PROFILE],
    factFields: facts,
  };
  if (ruleRefs.length) request.ruleRefs = ruleRefs;
  return request;
}

export type ServerOutcomeState = "SUCCEEDED" | "FAILED" | "PENDING" | "UNKNOWN" | "NOT_RUN";
export type EvidenceKind = "call" | "artifact" | "business-record";

export interface EvidenceRef {
  evidenceKind: EvidenceKind;
  callId?: string;
  runId?: string;
  artifactId?: number;
  digest?: string;
  recordSource?: string;
  recordId?: string;
}

export interface ServerOutcome {
  state: ServerOutcomeState;
  evidence?: readonly EvidenceRef[];
  message?: string;
  callId?: string;
}

export interface AuthoritativeView {
  state: ServerOutcomeState | "UNVERIFIED";
  authoritative: boolean;
  evidence: readonly EvidenceRef[];
  /** 服务端事实说明；没有回执时说明缺什么，而不是复述模型文本。 */
  note: string;
  /** 模型声称成功但未被任何服务端回执支持时置 true，页面必须显式提示。 */
  modelTextIgnored: boolean;
}

function evidenceComplete(ref: EvidenceRef): boolean {
  switch (ref.evidenceKind) {
    case "call":
      return typeof ref.callId === "string" && ref.callId.length > 0;
    case "artifact":
      return Number.isInteger(ref.artifactId) && (ref.artifactId as number) >= 1;
    case "business-record":
      return !!ref.recordSource && !!ref.recordId;
    default:
      return false;
  }
}

/**
 * 只有服务端事实与合格回执能决定“完成”。
 * 模型文本永远不参与该判定；它的作用仅是让人理解意图。
 */
export function authoritativeView(
  server: ServerOutcome | null | undefined,
  modelText?: string,
): AuthoritativeView {
  const modelClaim = typeof modelText === "string" && modelText.trim().length > 0;
  if (!server)
    return {
      state: "UNVERIFIED",
      authoritative: false,
      evidence: [],
      note: "未取得服务端执行回执；模型文本不作为完成依据",
      modelTextIgnored: modelClaim,
    };
  const evidence = (server.evidence ?? []).filter(evidenceComplete);
  if (server.state === "SUCCEEDED" && evidence.length === 0)
    return {
      state: "UNVERIFIED",
      authoritative: true,
      evidence: [],
      note: "服务端报告成功但没有可核验回执；不能视为已完成",
      modelTextIgnored: modelClaim,
    };
  const note =
    server.state === "PENDING"
      ? server.message?.trim() || "服务端仍在等待业务侧结果"
      : server.state === "UNKNOWN"
        ? server.message?.trim() || "服务端无法判定当前结果；请按批准的重查方式核对"
        : server.message?.trim() || "";
  return {
    state: server.state,
    authoritative: true,
    evidence,
    note,
    modelTextIgnored: modelClaim && server.state !== "SUCCEEDED",
  };
}

export interface GoalOutcomeLike {
  goalId: string;
  outcomeState: "ACHIEVED" | "NOT_ACHIEVED" | "PENDING" | "FAILED" | "UNKNOWN";
  readbackRef?: EvidenceRef | null;
  nextStep?: string;
  detail?: Record<string, unknown>;
}

export interface GoalRow {
  goalId: string;
  outcomeState: GoalOutcomeLike["outcomeState"] | "UNKNOWN";
  label: string;
  nextStep: string;
  issues: string[];
  evidence: readonly EvidenceRef[];
}

const GOAL_LABEL: Record<string, string> = {
  ACHIEVED: "已达成（有业务回执）",
  NOT_ACHIEVED: "未达成",
  PENDING: "等待业务侧结果",
  FAILED: "执行失败",
  UNKNOWN: "无法判定",
};

/** 目标卡片同样只认回执：ACHIEVED 缺 readbackRef 时降级为 UNKNOWN 并给出可定位问题。 */
export function goalOutcomeRows(goals: readonly GoalOutcomeLike[] | undefined): GoalRow[] {
  return (goals ?? []).map((goal) => {
    const issues: string[] = [];
    let state = goal.outcomeState;
    const evidence = goal.readbackRef ? [goal.readbackRef] : [];
    if (state === "ACHIEVED" && (!goal.readbackRef || !evidenceComplete(goal.readbackRef))) {
      issues.push("ACHIEVED 缺少可核验的 call 回执");
      state = "UNKNOWN";
    }
    if ((state === "PENDING" || state === "UNKNOWN") && !String(goal.nextStep ?? "").trim())
      issues.push("等待/未知状态缺少下一步说明");
    return {
      goalId: goal.goalId,
      outcomeState: state,
      label: GOAL_LABEL[state] ?? state,
      nextStep: String(goal.nextStep ?? "").trim(),
      issues,
      evidence,
    };
  });
}

export interface DefinitionProjectionLike {
  definitions: {
    objects: { ref: { id: string; name: string } }[];
    actions: { ref: { id: string; name: string }; code: string }[];
    rules: { ref: { id: string; name: string } }[];
  };
  emptyResult?: boolean;
  truncated?: boolean;
  truncation?: { reason: string; limit: number };
}

export interface SemanticAccess {
  canRead: boolean;
  allowedActions?: readonly string[];
  allowedObjectTypes?: readonly string[];
}

export interface VisibleProjection {
  visible: boolean;
  reason: string;
  objects: DefinitionProjectionLike["definitions"]["objects"];
  actions: DefinitionProjectionLike["definitions"]["actions"];
  rules: DefinitionProjectionLike["definitions"]["rules"];
  attention: string[];
}

/**
 * 权限不足时不返回任何内部资源标识；有权限时也只展示已授权动作及其可达对象。
 * 截断/空结果必须显式提示，不能把不完整定义当成“没有这个对象”。
 */
export function filterProjection(
  projection: DefinitionProjectionLike | null | undefined,
  access: SemanticAccess,
): VisibleProjection {
  if (!access.canRead)
    return { visible: false, reason: "当前账号无权读取该空间的本体定义", objects: [], actions: [], rules: [], attention: [] };
  if (!projection)
    return { visible: true, reason: "尚未加载正式定义", objects: [], actions: [], rules: [], attention: [] };
  const actions = projection.definitions.actions.filter(
    (action) => !access.allowedActions || access.allowedActions.includes(action.code),
  );
  const objects = projection.definitions.objects.filter(
    (object) => !access.allowedObjectTypes || access.allowedObjectTypes.includes(object.ref.name),
  );
  const attention: string[] = [];
  if (projection.emptyResult) attention.push("当前发布没有可用定义；请确认版本与查询范围");
  if (projection.truncated)
    attention.push(`定义已按 ${projection.truncation?.reason ?? "预算"} 截断（上限 ${projection.truncation?.limit ?? "?"}）`);
  if (access.allowedActions && actions.length === 0)
    attention.push("没有你已获授权的动作；请联系管理员而非扩大表单权限");
  return { visible: true, reason: "", objects, actions, rules: projection.definitions.rules, attention };
}

/** 表单/发布/运行共用同一goal@1.0声明Schema；对象引用与参数另作一致性核对。 */
export interface GoalOperation {
  skillCode: string;
  arguments: Record<string, unknown>;
  objectIdPath: string;
}
export interface GoalCriterionForm {
  goalId: string;
  target: { objectType: string; objectId: string };
  skillCode: string;
  readbackArguments: Record<string, unknown>;
  objectIdPath: string;
  resultObjectIdPath: string;
  action?: GoalOperation;
  predicatePath: string;
  operator: "equals" | "nonEmpty" | "exists";
  expectedValue?: string | number | boolean | null;
  nextStep?: string;
}
export interface GoalDeclaration {
  goalId: string;
  criterion: {
    target: GoalCriterionForm["target"];
    readback: GoalOperation & { resultObjectIdPath: string };
    action?: GoalOperation;
    predicate: { path: string; operator: GoalCriterionForm["operator"]; value?: GoalCriterionForm["expectedValue"] };
  };
  nextStep?: string;
}
const validateGoal = new Ajv2020({ allErrors: true, strict: false }).compile({
  $defs: goalSchema.$defs,
  $ref: "#/$defs/declaration",
});

function goalValueAt(root: unknown, path: string): unknown {
  let value = root;
  for (const part of path.slice(2).split(".")) {
    for (const token of part.match(/[A-Za-z_][A-Za-z0-9_]*|\[\d+\]/g) ?? []) {
      if (value === null || typeof value !== "object") return undefined;
      const key = token.startsWith("[") ? token.slice(1, -1) : token;
      value = (value as Record<string, unknown>)[key];
    }
  }
  return value;
}

export function buildGoalCriterion(form: GoalCriterionForm): GoalDeclaration {
  const goal: GoalDeclaration = {
    goalId: form.goalId,
    criterion: {
      target: form.target,
      readback: { skillCode: form.skillCode, arguments: form.readbackArguments,
        objectIdPath: form.objectIdPath, resultObjectIdPath: form.resultObjectIdPath },
      ...(form.action ? { action: form.action } : {}),
      predicate: { path: form.predicatePath, operator: form.operator,
        ...(form.operator === "equals" && form.expectedValue !== undefined ? { value: form.expectedValue } : {}) },
    },
    ...(form.nextStep?.trim() ? { nextStep: form.nextStep.trim() } : {}),
  };
  if (!validateGoal(goal)) {
    const error = validateGoal.errors?.[0];
    throw new SemanticFormError(error?.instancePath ?? "goal", "目标配置不符合goal@1.0：" + (error?.message ?? "字段不合法"));
  }
  for (const operation of [goal.criterion.readback, ...(goal.criterion.action ? [goal.criterion.action] : [])]) {
    if (goalValueAt(operation.arguments, operation.objectIdPath) !== goal.criterion.target.objectId)
      throw new SemanticFormError("arguments", "请求参数的对象标识必须与目标对象一致");
  }
  // 返回独立JSON快照，表单后续编辑不会改写已提交的冻结声明。
  try {
    return JSON.parse(JSON.stringify(goal, (_key, value: unknown) => {
      if (["undefined", "function", "symbol", "bigint"].includes(typeof value)
        || (typeof value === "number" && !Number.isFinite(value)))
        throw new Error("non-JSON value");
      return value;
    })) as GoalDeclaration;
  } catch { throw new SemanticFormError("goal", "目标配置只能包含有限数值及JSON数据"); }
}
