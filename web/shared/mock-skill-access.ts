type Row = Record<string, any>
export interface MockSkillAccessInput {
  calls: readonly Row[]
  skills: readonly Row[]
  skillVersions?: Record<string, readonly Row[]>
  skillRefs?: readonly Row[]
  actor?: Row
}

/** actor is supplied from the trusted demo business store, never from a tool's input. */
export function validateMockSkillCalls({ calls, skills, skillVersions, skillRefs, actor }: MockSkillAccessInput): string[] {
  if (!calls.length) return []
  if (!actor || actor.status !== 'ACTIVE') return ['原业务调用身份不存在或已停用，不能调用技能']
  const roles: string[] = Array.isArray(actor.roles) ? actor.roles : []
  const errors: string[] = []
  for (const call of calls) {
    const code = String(call.skillCode ?? call.code ?? '')
    const requestedVersion = call.version ?? call.versionNo ?? call.skillVersion
    const ref = (skillRefs ?? []).find(item => item.code === code && (requestedVersion === undefined || Number(item.version ?? item.versionNo) === Number(requestedVersion)))
    const versionNo = Number(ref?.version ?? ref?.versionNo)
    if (!code || !ref || !Number.isInteger(versionNo) || versionNo < 1) {
      errors.push(`技能 ${code || '（未指定）'} 未绑定到应用的精确版本`)
      continue
    }
    const skill = skills.find(item => item.code === code)
    if (!skill || ['DISABLED', 'ARCHIVED'].includes(skill.status)) {
      errors.push(`技能 ${code} 不存在或已停用`)
      continue
    }
    const initialized = skillVersions && Object.hasOwn(skillVersions, code)
    const version = initialized
      ? skillVersions![code].find(item => Number(item.versionNo ?? item.version) === versionNo)
      : Number(skill.currentVersion ?? skill.version) === versionNo ? skill : undefined
    if (!version || ['DISABLED', 'ARCHIVED'].includes(version.status)) {
      errors.push(`技能 ${code} 的绑定版本 ${versionNo} 不存在或已停用`)
      continue
    }
    // Current ACL edits take effect immediately, while the execution contract stays version-pinned.
    const policy = skill.accessPolicy ?? version.accessPolicy ?? version.definition?.accessPolicy
    if (policy && Object.hasOwn(policy, 'invokeRoles') && (!Array.isArray(policy.invokeRoles) || !policy.invokeRoles.some((role: string) => roles.includes(role))))
      errors.push(`业务身份 ${actor.name ?? actor.id} 没有调用技能 ${code}@${versionNo} 的角色权限`)
  }
  return [...new Set(errors)]
}
