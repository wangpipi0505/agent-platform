/**
 * 评测用例断言的唯一前端模型。
 *
 * 契约（monitor/src/main/java/cn/agentplatform/monitor/domain/EvaluationAssertions.java）：
 * - 用例字段 assertion 缺失等价 EXACT（EvaluationAssertions.java:41,49）；
 * - assertion 只能是模式名字符串，或 NUMERIC_TOLERANCE 的 {mode,tolerance} 对象
 *   （EvaluationAssertions.java:179-197,275-289）；
 * - REGEX 的 pattern、SCHEMA 的 schema、CONTAINS 的片段、NUMERIC_TOLERANCE 的期望数值都放在
 *   用例的 expected 里（EvaluationAssertions.java:210-257），assertion 里没有 pattern 字段；
 * - 安全用例（safety=true）只允许 EXACT/SCHEMA（EvaluationAssertions.java:133-141）。
 *
 * 前端据此生成与报告快照相同的规范形状（EvaluationAssertions.java:93-103）：
 * 非数值模式提交模式名字符串，NUMERIC_TOLERANCE 提交 {mode,tolerance}。
 */

export const ASSERTION_MODES = [
  'EXACT',
  'JSON_SUBSET',
  'TEXT_EQUALS',
  'CONTAINS',
  'REGEX',
  'SCHEMA',
  'NUMERIC_TOLERANCE',
] as const;

export type AssertionMode = (typeof ASSERTION_MODES)[number];

/** 安全用例可用模式；与后端 requireValid 的判定一致。 */
export const SAFETY_MODES: readonly AssertionMode[] = ['EXACT', 'SCHEMA'];

/** 与后端拒绝信息同口径，供界面与校验共用。 */
export const SAFETY_HINT = '安全用例只允许严格相等或 Schema 校验';

export type ExpectedKind = 'json' | 'text' | 'pattern' | 'schema' | 'number';

export interface ModeMeta {
  /** 下拉选项里的短说明。 */
  label: string;
  /** 一行摘要，列表/状态提示复用。 */
  summary: string;
  /** 模式相关参数提示（必须覆盖契约语义）。 */
  hint: string;
  expectedLabel: string;
  expectedKind: ExpectedKind;
}

export const MODE_META: Record<AssertionMode, ModeMeta> = {
  EXACT: {
    label: '严格相等',
    summary: '整份输出逐字段相等',
    hint: '整份输出逐字段相等：输出必须与期望 JSON 完全一致，字段顺序无关、字段集合不一致即失败。',
    expectedLabel: '预期完整输出 JSON',
    expectedKind: 'json',
  },
  JSON_SUBSET: {
    label: 'JSON 子集',
    summary: '期望值是输出子集',
    hint: '期望值是输出子集：输出可以多出字段，但期望值里的每个字段和值都必须在输出中匹配。',
    expectedLabel: '期望值 JSON（输出子集）',
    expectedKind: 'json',
  },
  TEXT_EQUALS: {
    label: '文本相等',
    summary: '输出文本与期望文本逐字相等',
    hint: '按文本逐字相等比较；对象/数组会先转为紧凑 JSON 文本再比较。',
    expectedLabel: '期望文本',
    expectedKind: 'text',
  },
  CONTAINS: {
    label: '包含片段',
    summary: '输出文本包含期望片段',
    hint: '输出文本必须包含该片段；空片段会匹配任意输出，因此不能为空。',
    expectedLabel: '包含片段',
    expectedKind: 'text',
  },
  REGEX: {
    label: '正则匹配',
    summary: '整串匹配',
    hint: '整串匹配：只有整个输出文本匹配该模式才算通过；正则走 RE2，不支持 lookaround、原子组或反向引用。',
    expectedLabel: '正则表达式（pattern）',
    expectedKind: 'pattern',
  },
  SCHEMA: {
    label: 'JSON Schema',
    summary: '输出必须通过 JSON Schema 校验',
    hint: '期望值是 JSON Schema 对象，输出必须通过该 Schema 的校验。',
    expectedLabel: 'JSON Schema 对象',
    expectedKind: 'schema',
  },
  NUMERIC_TOLERANCE: {
    label: '数值容差',
    summary: '数值差不超过 tolerance',
    hint: '输出与期望值都必须是数值，绝对差不超过 tolerance 时通过。',
    expectedLabel: '期望数值',
    expectedKind: 'number',
  },
};

export interface AssertionDraft {
  mode: AssertionMode;
  /** 模式对应的期望值输入；REGEX 时是 pattern，SCHEMA 时是 Schema JSON。 */
  expectedText: string;
  /** 仅 NUMERIC_TOLERANCE 使用。 */
  tolerance: string;
}

/** 提交给后端的规范断言值：模式名字符串，或 {mode,tolerance}。 */
export type AssertionValue = Exclude<AssertionMode, 'NUMERIC_TOLERANCE'> | { mode: 'NUMERIC_TOLERANCE'; tolerance: number };

export interface AssertionConfig {
  expected: unknown;
  assertion: AssertionValue;
}

export function isAssertionMode(value: unknown): value is AssertionMode {
  return typeof value === 'string' && (ASSERTION_MODES as readonly string[]).includes(value);
}

export function canUseAssertionMode(mode: AssertionMode, safety: boolean): boolean {
  return !safety || SAFETY_MODES.includes(mode);
}

export function emptyAssertionDraft(): AssertionDraft {
  return { mode: 'EXACT', expectedText: '', tolerance: '' };
}

/** 下拉选项；安全用例下非 EXACT/SCHEMA 选项保留展示但禁用，避免用户以为模式被移除。 */
export function assertionOptions(safety: boolean): Array<{ value: AssertionMode; label: string; disabled: boolean }> {
  return ASSERTION_MODES.map((mode) => ({
    value: mode,
    label: `${mode}（${MODE_META[mode].label}）`,
    disabled: !canUseAssertionMode(mode, safety),
  }));
}

/**
 * 把界面草稿转换为提交值。所有失败都抛可读中文错误（在发请求前拦截），
 * 期望值按模式不同落在 expected，绝不塞进 assertion。
 */
export function buildAssertion(draft: AssertionDraft, safety: boolean): AssertionConfig {
  if (!isAssertionMode(draft.mode)) throw new Error(`未知的断言模式：${String(draft.mode)}`);
  const mode = draft.mode;
  if (!canUseAssertionMode(mode, safety)) {
    throw new Error(`${SAFETY_HINT}；当前为 ${mode}，请改选 EXACT 或 SCHEMA`);
  }
  const expectedText = draft.expectedText ?? '';
  switch (mode) {
    case 'EXACT':
      return { expected: parseExpected(expectedText, '预期值'), assertion: 'EXACT' };
    case 'JSON_SUBSET':
      return { expected: parseExpected(expectedText, '期望值'), assertion: 'JSON_SUBSET' };
    case 'SCHEMA': {
      if (!expectedText.trim()) throw new Error('JSON Schema 不能为空');
      const schema = parseExpected(expectedText, 'JSON Schema');
      if (schema === null || typeof schema !== 'object' || Array.isArray(schema)) {
        throw new Error('Schema 必须是 JSON 对象，例如 {"type":"object"}');
      }
      return { expected: schema, assertion: 'SCHEMA' };
    }
    case 'REGEX': {
      if (!expectedText.trim()) throw new Error('正则表达式不能为空');
      if (expectedText.length > 2000) throw new Error('正则表达式长度不能超过 2000 个字符');
      assertRe2Compatible(expectedText);
      return { expected: expectedText, assertion: 'REGEX' };
    }
    case 'TEXT_EQUALS': {
      if (!expectedText.length) throw new Error('预期文本不能为空');
      return { expected: expectedText, assertion: 'TEXT_EQUALS' };
    }
    case 'CONTAINS': {
      if (!expectedText.trim()) throw new Error('包含片段不能为空（空片段会匹配任意输出）');
      return { expected: expectedText, assertion: 'CONTAINS' };
    }
    case 'NUMERIC_TOLERANCE': {
      const expected = parseNumber(expectedText, '期望数值');
      const toleranceText = (draft.tolerance ?? '').trim();
      if (!toleranceText) throw new Error('容差不能为空');
      const tolerance = parseNumber(toleranceText, '容差');
      if (tolerance < 0) throw new Error('容差必须是非负数值');
      return { expected, assertion: { mode: 'NUMERIC_TOLERANCE', tolerance } };
    }
  }
}

function parseExpected(text: string, label: string): unknown {
  if (!text.trim()) throw new Error(`${label}不能为空`);
  try {
    return JSON.parse(text);
  } catch {
    throw new Error(`${label}必须是合法 JSON，请检查引号、逗号与括号`);
  }
}

function parseNumber(text: string, label: string): number {
  const value = text.trim();
  if (!value) throw new Error(`${label}不能为空`);
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`${label}必须是有限数值`);
  return parsed;
}

/** RE2（后端 EvaluationAssertions.java:226-230）不支持这些结构；提前给出可读错误。 */
function assertRe2Compatible(pattern: string): void {
  const unsupported = /\(\?=|\(\?!|\(\?<=|\(\?<!|\(\?>|\\[1-9]|\\k</;
  if (unsupported.test(pattern)) {
    throw new Error('RE2 不支持 lookaround（前瞻/后顾）、原子组或反向引用，请改写正则表达式');
  }
}

/** 与后端 parseMode 同口径的只读解析：缺失=EXACT，无法识别=INVALID。 */
export function assertionMode(assertion: unknown): AssertionMode | 'INVALID' {
  if (assertion === null || assertion === undefined) return 'EXACT';
  let rawMode: unknown = assertion;
  if (typeof assertion === 'object' && !Array.isArray(assertion)) rawMode = (assertion as { mode?: unknown }).mode;
  if (typeof rawMode !== 'string') return 'INVALID';
  const upper = rawMode.trim().toUpperCase();
  return (ASSERTION_MODES as readonly string[]).includes(upper) ? (upper as AssertionMode) : 'INVALID';
}

export function assertionTolerance(assertion: unknown): number | undefined {
  if (assertion === null || typeof assertion !== 'object' || Array.isArray(assertion)) return undefined;
  const tolerance = (assertion as { tolerance?: unknown }).tolerance;
  return typeof tolerance === 'number' && Number.isFinite(tolerance) ? tolerance : undefined;
}

export function assertionLabel(assertion: unknown): string {
  const mode = assertionMode(assertion);
  if (mode === 'INVALID') return 'INVALID（无法识别的断言配置）';
  if (mode === 'NUMERIC_TOLERANCE') return `NUMERIC_TOLERANCE（容差 ${assertionTolerance(assertion) ?? '缺失'}）`;
  return mode;
}

/** 列表/报告只读展示：真实解析结果，缺省明确标注为 EXACT。 */
export function describeAssertion(assertion: unknown): string {
  const mode = assertionMode(assertion);
  if (mode === 'INVALID') return 'INVALID（无法识别的断言配置）';
  return `${assertionLabel(assertion)} · ${MODE_META[mode].summary}`;
}

/** 比较提交值与服务器回读值是否同一断言（缺失与 EXACT 等价）。 */
export function assertionKey(assertion: unknown): string {
  const mode = assertionMode(assertion);
  if (mode === 'INVALID') return `INVALID:${JSON.stringify(assertion)}`;
  if (mode === 'NUMERIC_TOLERANCE') return `NUMERIC_TOLERANCE:${assertionTolerance(assertion) ?? 'missing'}`;
  return mode;
}
