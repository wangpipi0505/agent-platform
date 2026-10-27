import { describe, expect, it } from 'vitest'
import { mockTransport } from '../shared/mock-transport'

describe('mock transport write guard', () => {
  it('allows the dev-token POST so the demo can sign in', async () => {
    const response = await mockTransport('/auth/dev-token', { method: 'POST' })
    const body = await response.json()
    expect(response.status).toBe(200)
    expect(body.data.accessToken).toBe('mock-token-demo')
    expect(body.data.user.tenantId).toBe('t-demo')
    expect(body.data.expiresIn).toBeGreaterThan(0)
  })

  it('rejects business writes instead of pretending they were saved', async () => {
    const response = await mockTransport('/admin/apps', {
      method: 'POST',
      body: JSON.stringify({ name: 'demo' }),
    })
    const body = await response.json()
    expect(response.status).toBe(405)
    expect(body.code).toBe('MOCK_WRITE_DISABLED')
  })

  it('keeps read routes available', async () => {
    const response = await mockTransport('/admin/apps', { method: 'GET' })
    const body = await response.json()
    expect(response.status).toBe(200)
    expect(Array.isArray(body.data)).toBe(true)
    expect(body.data.length).toBeGreaterThan(0)
  })
})
