import { describe, expect, it } from 'vitest'
import Ajv2020 from 'ajv/dist/2020'
import planningText from '../shared/satellite-planning.seed.json?raw'
import { parameterTemplate, toolContract } from '../shared/mock-tool-catalog'

describe('mock tool parameter contracts', () => {
  it('gives each planning operation its own fields and useful valid planning examples', () => {
    const seed = JSON.parse(planningText)
    const ground = toolContract('planning-ground-plan', {})
    const space = toolContract('planning-space-plan', {})
    expect(ground.parameterTemplate).toMatchObject({ targetId: 'GT-QH-01', observerSatelliteIds: [3150], cloudLimit: 30 })
    expect(space.parameterTemplate).toMatchObject({ targetId: 8890, observerSatelliteIds: [2068, 845] })
    const ajv = new Ajv2020({ strict: false, validateFormats: false })
    for (const contract of [ground, space]) expect(ajv.compile(contract.inputSchema)(contract.parameterTemplate)).toBe(true)
    for (const tool of seed.tools) {
      const contract = toolContract(tool.code, tool.inputSchema)
      expect(Object.keys(contract.inputSchema.properties as object).length).toBeLessThan(12)
      expect(() => ajv.compile(contract.inputSchema)).not.toThrow()
    }
    expect(Object.keys(toolContract('planning-task-status', {}).inputSchema.properties as object)).toEqual(['taskId'])
    expect(toolContract('planning-downlink-validate', {}).parameterTemplate).toEqual({ planId: '' })
    expect(toolContract('space-visibility-plan', {}).parameterTemplate).toEqual(space.parameterTemplate)
    expect(toolContract('telemetry_fetch', {}).parameterTemplate).toHaveProperty('parameterCodes', ['BUS_VOLTAGE', 'BATTERY_TEMPERATURE'])
    expect(ajv.compile(ground.inputSchema)({ ...ground.parameterTemplate, cloudLimit: 101 })).toBe(false)
    expect(ajv.compile(space.inputSchema)({ ...space.parameterTemplate, observerSatelliteIds: [] })).toBe(false)
  })

  it('takes ontology references from context and leaves caller authority out of business inputs', () => {
    const releaseRef = { spaceCode: 'user-governance', releaseId: 'rel-v2', version: 'v2', contentDigest: 'abc' }
    expect(toolContract('planning-ontology-facts', {}, { ontology: { releaseRef } }).parameterTemplate.releaseRef).toEqual(releaseRef)
    for (const code of ['um-identity-resolve', 'um-permissions-pull']) expect(toolContract(code, {}).parameterTemplate).toEqual({})
    const request = toolContract('um-request-prepare', {})
    expect(request.parameterTemplate).toMatchObject({ username: 'chen.xin', departmentId: 'dept-a', roleIds: ['ROLE_ANALYST'] })
    expect(request.inputSchema.properties).not.toHaveProperty('permissions')
    expect(request.inputSchema.properties).not.toHaveProperty('actorId')
    expect(Object.keys(toolContract('planning-file-parse', {}).inputSchema.properties as object)).toEqual(['artifactId'])
  })

  it('preserves an unrecognised exact schema and builds isolated nested defaults/examples', () => {
    const schema = { type: 'object', properties: { choices: { type: 'array', items: { type: 'integer' }, default: [2] }, settings: { type: 'object', properties: { limit: { type: 'integer', examples: [7] } } }, query: { type: 'string' } }, required: ['query'] }
    const contract = toolContract('custom-search', schema)
    expect(contract.inputSchema).toEqual(schema)
    expect(contract.parameterTemplate).toEqual({ choices: [2], settings: { limit: 7 }, query: '' })
    ;(contract.parameterTemplate.choices as number[]).push(3)
    expect(parameterTemplate(schema).choices).toEqual([2])
  })
})
