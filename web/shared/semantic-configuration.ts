import type {
  DefinitionProjectionLike,
  GoalDeclaration,
  SemanticSelection,
  SemanticExtensionChoice,
  SemanticBindingRequest
} from './semantic-runtime';
export type JsonRecord = Record<string, unknown>;
export interface SkillChoice {
  code: string;
  version: number;
  sha256: string;
  inputSchema: unknown;
  outputSchema: unknown;
  readonly: boolean;
  sideEffect: boolean;
}
export interface CatalogRelease {
  releaseRef: SemanticSelection['releaseRef'];
  semanticExtensions: {
    extensionId: string;
    opCode: string;
    profile: string;
    schemaVersion: string;
    extensionSha256: string;
    descriptor: SemanticExtensionChoice['descriptor'];
  }[];
  operations: {
    opCode: string;
    status: string;
  }[];
  projection: DefinitionProjectionLike;
}
export interface SemanticCatalog {
  releases: CatalogRelease[];
  errors: {
    releaseId: string;
    code: string;
    message: string;
  }[];
  complete: boolean;
}
export interface SemanticApp {
  code: string;
  draftRev: number;
  config: JsonRecord;
}
export const object = (value: unknown): JsonRecord => value !== null && typeof value === 'object' && !Array.isArray(value) ? value as JsonRecord : {};
export function releaseSelection(release: CatalogRelease): SemanticSelection {
  return {
    releaseRef: release.releaseRef,
    objectTypes: release.projection.definitions.objects.map(item => item.ref.name),
    actions: release.operations.filter(item => item.status === 'ACTIVE').map(item => item.opCode),
    extensions: release.semanticExtensions.filter(item => !!item.descriptor?.objectType).map(item => ({
      id: item.extensionId,
      opCode: item.opCode,
      profile: item.profile,
      schemaVersion: item.schemaVersion,
      status: 'ACTIVE',
      descriptor: item.descriptor
    }))
  };
}
/** 仅声明的有限字段路径；数组用第0行模板，禁止自由表达式作为默认配置入口。 */
export function schemaPaths(schema: unknown, prefix = '$', depth = 0): string[] {
  if (depth > 6)
    return [];
  const value = object(schema);
  const properties = object(value.properties);
  const paths = Object.entries(properties).flatMap(([key, item]) => schemaPaths(item, `${prefix}.${key}`, depth + 1));
  if (paths.length)
    return paths;
  if (value.type === 'array')
    return schemaPaths(value.items, `${prefix}[0]`, depth + 1);
  return prefix === '$' ? [] : [prefix];
}
export function bindingWrite(appCode: string, request: SemanticBindingRequest, release: CatalogRelease, skill: SkillChoice, requestId: string) {
  const extension = release.semanticExtensions.find(item => item.extensionId === request.extensionId && item.opCode === request.opCode);
  if (!extension || extension.descriptor.objectType !== request.objectType || !skill.readonly || skill.sideEffect
    || skill.code !== request.skill.skillCode || skill.version !== request.skill.skillVersion)
    throw Error('请选择当前正式扩展与只读技能版本');
  // 目录只接受引用白名单；factFields/objectType属于扩展正文，不能越过目录协议塞入请求。
  return {
    bindingId: `sb-${crypto.randomUUID().replaceAll('-', '')}`,
    appCode,
    requestId,
    releaseRef: release.releaseRef,
    extensionId: extension.extensionId,
    extensionDigest: extension.extensionSha256,
    opCode: extension.opCode,
    skill: {
      skillCode: skill.code,
      skillVersion: skill.version,
      skillSha256: skill.sha256
    },
    profiles: ['read-only@1.0']
  };
}
export function mergeGoal(config: JsonRecord, goal: GoalDeclaration): JsonRecord {
  const goals = Array.isArray(config.goals) ? config.goals : [];
  return {
    ...config,
    goals: [...goals.filter(item => object(item).goalId !== goal.goalId), goal]
  };
}
export function addSkillRefs(config: JsonRecord, skills: readonly SkillChoice[]): JsonRecord {
  const refs = Array.isArray(config.skillRefs) ? config.skillRefs.map(object) : [];
  for (const skill of skills) {
    const previous = refs.find(item => item.code === skill.code);
    if (previous && (previous.version !== skill.version || previous.enabled === false))
      throw Error('技能已有其他版本或已停用，请先在应用配置中确认');
    if (!previous)
      refs.push({
        code: skill.code,
        version: skill.version
      });
  }
  return {
    ...config,
    skillRefs: refs
  };
}
