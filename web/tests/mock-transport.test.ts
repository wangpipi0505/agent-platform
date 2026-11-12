import { describe, expect, it } from 'vitest'
import { mockTransport } from '../shared/mock-transport'

describe('mock transport demo state', () => {
  it('allows the dev-token POST so the demo can sign in', async () => {
    const response = await mockTransport('/auth/dev-token', { method: 'POST' })
    const body = await response.json()
    expect(response.status).toBe(200)
    expect(body.data.accessToken).toBe('mock-token-demo')
    expect(body.data.user.tenantId).toBe('t-demo')
    expect(body.data.expiresIn).toBeGreaterThan(0)
  })

  it('persists demo business writes and reads back the created application', async () => {
    const response = await mockTransport('/admin/apps', {
      method: 'POST',
      body: JSON.stringify({ code: 'roundtrip-demo-app', name: 'demo' }),
    })
    const body = await response.json()
    expect(response.status).toBe(200)
    expect(body.code).toBe('OK')
    const detail = await (await mockTransport('/admin/apps/roundtrip-demo-app')).json()
    expect(detail.data).toMatchObject({ code: 'roundtrip-demo-app', name: 'demo', status: 'DRAFT' })
  })

  it('keeps read routes available', async () => {
    const response = await mockTransport('/admin/apps', { method: 'GET' })
    const body = await response.json()
    expect(response.status).toBe(200)
    expect(Array.isArray(body.data)).toBe(true)
    expect(body.data.length).toBeGreaterThan(0)
  })
})
