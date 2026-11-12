import { afterEach, expect, it, vi } from 'vitest'
import { createRenderer, h, nextTick } from 'vue'
import ChatConfig from '../admin/src/ChatConfig.vue'
import AppChatPanel from '../admin/src/pages/workspace/AppChatPanel.vue'
const fixture = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('../shared/api', () => ({ useAuth: () => ({ token: 'demo', can: () => true }), useApi: () => fixture.api, createApi: () => fixture.api }))
vi.mock('../shared/drafts', () => ({ readDraft: () => null, writeDraft: () => {} }))
interface Node { type: string; text: string; props: Record<string, unknown>; children: Node[]; parent: Node | null; scrollTo: () => void }
const node = (type: string, text = ''): Node => ({ type, text, props: {}, children: [], parent: null, scrollTo: () => {} })
const renderer = createRenderer<Node, Node>({
  createElement: type => node(type), createText: text => node('text', text), createComment: text => node('comment', text),
  setText: (n, text) => { n.text = text }, setElementText: (n, text) => { n.text = text; n.children = [] },
  patchProp: (n, key, _previous, value) => { n.props[key] = value },
  insert: (n, parent, anchor) => { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1); n.parent = parent; const index = anchor ? parent.children.indexOf(anchor) : -1; parent.children.splice(index < 0 ? parent.children.length : index, 0, n) },
  remove: n => { if (n.parent) n.parent.children.splice(n.parent.children.indexOf(n), 1) }, parentNode: n => n.parent, nextSibling: n => n.parent?.children[n.parent.children.indexOf(n) + 1] ?? null,
})
const all = (root: Node): Node[] => [root, ...root.children.flatMap(all)]
const text = (root: Node): string => root.text + root.children.map(text).join('')
const apps: ReturnType<typeof renderer.createApp>[] = []
const settle = async () => { for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick() } }
const releaseRef = { spaceCode: 'observation-planning', releaseId: 'rel-observation-v1', version: 'v1', contentDigest: 'a'.repeat(64) }
afterEach(() => { apps.forEach(app => app.unmount()); apps.length = 0; fixture.api.mockReset() })
function api() {
  fixture.api.mockImplementation(async (url: string, method?: string, body?: Record<string, unknown>) => {
    if (url.includes('semantic-catalog')) return { releases: [{ name: '观测规划本体', spaceId: 'sp-observation', releaseRef, projection: { definitions: { objects: [{ ref: { name: 'Satellite' } }] } } }], complete: true, errors: [] }
    if (url.endsWith('planning-scenarios')) return { clock: '2026-11-05T09:00:00+08:00', planningDate: '2026-11-06', examples: [{ id: 'ground', title: '地面目标观测', message: '规划青海湖观测' }], tools: [] }
    if (url.endsWith('demo-chat')) return { runId: 'run-test', conversationId: body?.conversationId, reply: '青海湖可用窗口GD-W01', status: 'SUCCEEDED', toolCalls: [], artifacts: [], ontology: null }
    return []
  })
}
it('配置页选择本体并保存完整版本引用，可清除选择', async () => {
  api()
  const saved: unknown[] = []
  const root = node('root')
  const app = renderer.createApp({ render: () => h(ChatConfig, { config: {}, onSave: value => saved.push(value) }) })
  app.config.warnHandler = () => {}; app.mount(root); apps.push(app); await settle()
  const selector = all(root).find(item => item.props['aria-label'] === '选择本体正式版本')
  expect(selector, '配置页必须提供本体选择').toBeDefined()
  const update = selector!.props['onUpdate:modelValue'] as (value: string) => void
  const change = selector!.props.onChange as (value: string) => void
  update('rel-observation-v1'); change('rel-observation-v1'); await settle()
  const save = all(root).find(item => item.type === 'el-button' && text(item).includes('保存配置'))!
  ;(save.props.onClick as () => void)(); await settle()
  expect(saved[0]).toMatchObject({ ontology: { space: 'sp-observation', ...releaseRef } })
  update(''); change(''); await settle(); (save.props.onClick as () => void)(); await settle()
  expect(saved[1]).toMatchObject({ ontology: null })
})
it('对话页点击示例发送具体问法和独立会话', async () => {
  api()
  const root = node('root')
  const app = renderer.createApp({ render: () => h(AppChatPanel, { appCode: 'satellite-planning-assistant' }) })
  app.config.warnHandler = () => {}; app.component('RouterLink', { render: () => null }); app.mount(root); apps.push(app); await settle()
  const example = all(root).find(item => item.type === 'el-button' && text(item).includes('地面目标观测'))
  expect(example, '用户应能直接点击示例问法').toBeDefined()
  ;(example!.props.onClick as () => void)(); await settle()
  const sent = fixture.api.mock.calls.find(call => String(call[0]).endsWith('/demo-chat'))
  expect(sent?.[2]).toMatchObject({ message: '规划青海湖观测', conversationId: expect.any(String) })
  expect(text(root)).toContain('青海湖可用窗口GD-W01')
})
