/**
 * 技能 Schema 的方言提示：只读的前端分析，不替代后端校验，也不参与请求体。
 *
 * 后端接受的可空写法有两种：`type: ["string","null"]` 与 `nullable: true`；两者都是
 * JSON Schema / OpenAPI 方言里合法但容易被写错的形状。这里把它们识别出来给界面提示，
 * 其余问题（远程 $ref、多具体类型联合、type 形状不对）只作为可恢复的警告，绝不阻止保存。
 *
 * 本模块是纯函数（无 Vue、无 IO），测试在 tests/schema-dialect.test.ts。
 */

export interface SchemaProblem {
  /** JSON Pointer 路径；根节点为 ''，属性名按 RFC 6901 转义（`~`→`~0`、`/`→`~1`）。 */
  path: string;
  /** 面向用户的中文提示。 */
  message: string;
}

export interface SchemaAnalysis {
  /** JSON 文本是否可解析；problems 非空不代表不可解析，提示不阻塞提交。 */
  ok: boolean;
  /** 已识别为可空的 Schema 节点路径：type 数组含 "null"，或 nullable: true。 */
  nullable: string[];
  problems: SchemaProblem[];
}

interface Collector {
  nullable: string[];
  seenNullable: Set<string>;
  problems: SchemaProblem[];
  depthExceeded: boolean;
}

/** 只走 JSON Schema 的已知子 Schema 关键字，避免把名为 type/$ref 的业务字段误判成关键字。 */
const SCHEMA_CHILD_KEYS = ['not', 'if', 'then', 'else', 'contains', 'propertyNames'] as const;
const SCHEMA_OR_BOOLEAN_KEYS = ['additionalProperties', 'unevaluatedItems', 'unevaluatedProperties'] as const;
const SCHEMA_LIST_KEYS = ['allOf', 'anyOf', 'oneOf', 'prefixItems'] as const;
const SCHEMA_MAP_KEYS = ['$defs', 'definitions', 'dependentSchemas', 'patternProperties'] as const;

/** 任意 scheme 的绝对引用或协议相对引用；文档内引用（#/...）不匹配。 */
const REMOTE_REF = /^(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?\/\//;

/** 递归上限：粘贴巨大嵌套文档时不把界面撑爆。 */
const MAX_DEPTH = 200;

const EMPTY_HINT = 'JSON 解析失败：内容为空';

export function analyzeSchema(text: string): SchemaAnalysis {
  const source = text.trim();
  if (!source) return failure(EMPTY_HINT);

  let parsed: unknown;
  try {
    parsed = JSON.parse(source);
  } catch (error) {
    return failure(`JSON 解析失败：${errorMessage(error)}`);
  }

  const out: Collector = { nullable: [], seenNullable: new Set(), problems: [], depthExceeded: false };
  if (!isRecord(parsed)) {
    out.problems.push({ path: '', message: '根节点必须是 JSON 对象，例如 {"type":"object"}' });
  } else {
    walkSchema(parsed, '', 0, out);
  }
  return { ok: true, nullable: out.nullable, problems: out.problems };
}

function failure(message: string): SchemaAnalysis {
  return { ok: false, nullable: [], problems: [{ path: '', message }] };
}

function walkSchema(schema: Record<string, unknown>, path: string, depth: number, out: Collector): void {
  if (depth > MAX_DEPTH) {
    if (!out.depthExceeded) {
      out.depthExceeded = true;
      out.problems.push({ path, message: `嵌套超过 ${MAX_DEPTH} 层，仅分析已到达的部分` });
    }
    return;
  }

  const type = schema.type;
  const typePath = childPath(path, 'type');
  const typeNames: string[] = [];
  let typeReadable = true;
  if (type !== undefined) {
    if (typeof type === 'string') {
      typeNames.push(type);
    } else if (Array.isArray(type)) {
      if (type.length === 0) {
        out.problems.push({ path: typePath, message: 'type 数组不能为空；可写 "object" 或 ["object","null"]' });
      }
      const bad = type.find((member) => typeof member !== 'string');
      if (bad !== undefined) {
        typeReadable = false;
        out.problems.push({ path: typePath, message: `type 数组只能包含字符串（发现 ${describeValue(bad)}）` });
      }
      // 即使数组里有非法成员，也保留可读的字符串成员，让可空识别仍然可用。
      for (const member of type) {
        if (typeof member === 'string' && !typeNames.includes(member)) typeNames.push(member);
      }
    } else {
      typeReadable = false;
      out.problems.push({ path: typePath, message: `type 必须是字符串或字符串数组（发现 ${describeValue(type)}）` });
    }
  }

  const concrete = typeNames.filter((name) => name !== 'null');
  if (typeReadable && concrete.length > 1) {
    out.problems.push({
      path: typePath,
      message: `多具体类型联合（${concrete.join(', ')}）：后端只接受单一类型，可空请写 ["类型","null"] 或 nullable:true`,
    });
  }

  let nullableHere = typeNames.includes('null');
  const nullableKeyword = schema.nullable;
  if (nullableKeyword !== undefined) {
    if (typeof nullableKeyword === 'boolean') nullableHere = nullableHere || nullableKeyword;
    else out.problems.push({ path: childPath(path, 'nullable'), message: `nullable 必须是 true/false（发现 ${describeValue(nullableKeyword)}）` });
  }
  if (nullableHere && !out.seenNullable.has(path)) {
    out.seenNullable.add(path);
    out.nullable.push(path);
  }

  const ref = schema.$ref;
  if (typeof ref === 'string') {
    if (REMOTE_REF.test(ref)) {
      out.problems.push({ path: childPath(path, '$ref'), message: `远程引用不受支持：${ref}；后端只解析文档内引用（#/...）` });
    }
  } else if (ref !== undefined) {
    out.problems.push({ path: childPath(path, '$ref'), message: `$ref 必须是字符串（发现 ${describeValue(ref)}）` });
  }

  walkProperties(schema.properties, path, depth, out);
  walkItems(schema.items, path, depth, out);
  for (const key of SCHEMA_CHILD_KEYS) walkChild(schema[key], childPath(path, key), key, depth, out);
  for (const key of SCHEMA_OR_BOOLEAN_KEYS) walkChild(schema[key], childPath(path, key), key, depth, out, true);
  for (const key of SCHEMA_LIST_KEYS) {
    const list = schema[key];
    if (list === undefined) continue;
    if (!Array.isArray(list)) {
      out.problems.push({ path: childPath(path, key), message: `${key} 必须是 Schema 数组` });
      continue;
    }
    list.forEach((member, index) => {
      const memberPath = childPath(childPath(path, key), String(index));
      if (isRecord(member)) walkSchema(member, memberPath, depth + 1, out);
      else out.problems.push({ path: memberPath, message: 'Schema 必须是 JSON 对象' });
    });
  }
  for (const key of SCHEMA_MAP_KEYS) {
    const map = schema[key];
    if (map === undefined) continue;
    if (!isRecord(map)) {
      out.problems.push({ path: childPath(path, key), message: `${key} 必须是对象（名称到 Schema 的映射）` });
      continue;
    }
    for (const [name, member] of Object.entries(map)) {
      const memberPath = childPath(childPath(path, key), name);
      if (isRecord(member)) walkSchema(member, memberPath, depth + 1, out);
      else out.problems.push({ path: memberPath, message: 'Schema 必须是 JSON 对象' });
    }
  }
}

function walkProperties(properties: unknown, path: string, depth: number, out: Collector): void {
  if (properties === undefined) return;
  if (!isRecord(properties)) {
    out.problems.push({ path: childPath(path, 'properties'), message: 'properties 必须是对象（属性名到 Schema 的映射）' });
    return;
  }
  for (const [name, member] of Object.entries(properties)) {
    const memberPath = childPath(childPath(path, 'properties'), name);
    if (isRecord(member)) walkSchema(member, memberPath, depth + 1, out);
    else out.problems.push({ path: memberPath, message: '属性 Schema 必须是 JSON 对象' });
  }
}

function walkItems(items: unknown, path: string, depth: number, out: Collector): void {
  if (items === undefined) return;
  if (Array.isArray(items)) {
    // draft-04 的元组写法：items 是 Schema 数组。
    items.forEach((member, index) => {
      const memberPath = childPath(childPath(path, 'items'), String(index));
      if (isRecord(member)) walkSchema(member, memberPath, depth + 1, out);
      else out.problems.push({ path: memberPath, message: 'Schema 必须是 JSON 对象' });
    });
    return;
  }
  if (isRecord(items)) {
    walkSchema(items, childPath(path, 'items'), depth + 1, out);
    return;
  }
  out.problems.push({ path: childPath(path, 'items'), message: 'items 必须是 Schema 对象' });
}

function walkChild(
  child: unknown,
  path: string,
  keyword: string,
  depth: number,
  out: Collector,
  allowBoolean = false,
): void {
  if (child === undefined) return;
  if (allowBoolean && typeof child === 'boolean') return;
  if (isRecord(child)) {
    walkSchema(child, path, depth + 1, out);
    return;
  }
  out.problems.push({ path, message: `${keyword} 必须是 Schema 对象` });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** JSON Pointer：先转义 token，再拼接；根路径为 ''。 */
function childPath(base: string, token: string): string {
  return `${base}/${token.replace(/~/g, '~0').replace(/\//g, '~1')}`;
}

function describeValue(value: unknown): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return '数组';
  if (typeof value === 'string') return '字符串';
  if (typeof value === 'number') return '数字';
  if (typeof value === 'boolean') return '布尔值';
  if (typeof value === 'object') return '对象';
  return typeof value;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
