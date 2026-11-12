import { afterEach, expect, it, vi } from 'vitest'
import { createRenderer, h, nextTick } from 'vue'
import AppChatPanel from '../admin/src/pages/workspace/AppChatPanel.vue'

const fixture = vi.hoisted(() => ({ api: vi.fn() }))
vi.mock('../shared/api', () => ({ useAuth: () => ({ token: 'demo' }), useApi: () => fixture.api }))
vi.mock('../shared/content', () => ({ safeMarkdown: (value: string) => value }))
interface Node { type: string; text: string; props: Record<string, any>; children: Node[]; parent: Node | null; scrollTo: () => void }
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
afterEach(() => { apps.forEach(app => app.unmount()); apps.length = 0; fixture.api.mockReset() })

const tool = (version = 2) => ({
  code: 'observation', name: '观测窗口查询', version, description: '查询可用观测窗口',
  inputSchema: { type: 'object', additionalProperties: false, required: ['targetId'], properties: version === 1 ? { targetId: { type: 'string', minLength: 1 } } : {
    targetId: { type: 'string', minLength: 1 }, count: { type: 'integer', minimum: 1 }, dryRun: { type: 'boolean' },
    mode: { type: 'string', enum: ['GROUND', 'SPACE'] }, targets: { type: 'array', items: { type: 'string' } }, constraints: { type: 'object' },
  } },
  parameterTemplate: version === 1 ? { targetId: 'OLD-1' } : { targetId: 'QH-01', count: 2, dryRun: false, mode: 'GROUND', targets: ['QH-01'], constraints: { maxCloudPercent: 30 } },
})
function mount(mode: 'draft' | 'published' = 'draft') {
  fixture.api.mockImplementation(async (url: string, _method?: string, body?: any) => {
    if (url.endsWith('/versions')) return [{ versionNo: 2, status: 'PUBLISHED' }, { versionNo: 1, status: 'SUPERSEDED' }]
    if (url.includes('/available-tools')) return url.endsWith('versionNo=1') ? [tool(1)] : [tool(), { code: 'report', name: '报告生成', version: 1, inputSchema: { type: 'object', additionalProperties: false, required: ['title'], properties: { title: { type: 'string', minLength: 1 } } }, parameterTemplate: {} }]
    if (url.endsWith('/demo-chat')) return { runId: 'run-tool', reply: '已完成', status: 'SUCCEEDED', parameters: body.parameters }
    return []
  })
  const root = node('root')
  const app = renderer.createApp({ render: () => h(AppChatPanel, { appCode: 'demo-agent', mode, currentVersionNo: 2 }) })
  app.config.warnHandler = () => {}; app.component('RouterLink', { render: () => null }); app.mount(root); apps.push(app)
  return root
}
const field = (root: Node, label: string) => all(root).find(item => item.props['aria-label'] === label)!
const click = (root: Node, label: string) => all(root).find(item => item.type === 'el-button' && text(item) === label)!.props.onClick()
const sent = () => fixture.api.mock.calls.filter(call => String(call[0]).endsWith('/demo-chat'))

it('按技能精确版本加载模板，直接执行提交类型化参数，切换后校验必填且不影响自然语言参数', async () => {
  const root = mount(); await settle()
  const select = field(root, '选择可用技能/工具')
  expect(select, '应提供可搜索的技能下拉').toBeDefined()
  expect(select.props.filterable).toBeDefined()
  expect(field(root, '所选技能的附件与运行参数')).toBeUndefined()
  select.props['onUpdate:modelValue']('observation@2'); await settle()
  expect(field(root, '所选技能的附件与运行参数')).toBeDefined()
  expect(text(root)).toContain('运行参数 · 观测窗口查询')
  expect(text(root)).not.toContain('目标CSV模板')
  expect(field(root, 'targetId').props.modelValue).toBe('QH-01')
  field(root, 'targetId').props['onUpdate:modelValue']('CUSTOM-1')
  click(root, '执行技能'); await settle()
  expect(sent()[0]?.[2]).toMatchObject({ message: '执行观测窗口查询', toolSelection: { code: 'observation', version: 2 }, parameters: { ...tool().parameterTemplate, targetId: 'CUSTOM-1' } })
  expect(field(root, 'targetId').props.modelValue).toBe('CUSTOM-1')
  click(root, '重新载入模板'); await settle()
  expect(field(root, 'targetId').props.modelValue).toBe('QH-01')
  field(root, '选择可用技能/工具').props['onUpdate:modelValue']('report@1'); await settle()
  expect(field(root, 'targetId')).toBeUndefined()
  click(root, '执行技能'); await settle()
  expect(sent()).toHaveLength(1)
  expect(text(root)).toContain('title')
  field(root, 'title').props['onUpdate:modelValue']('报告A')
  click(root, '执行技能'); await settle()
  expect(sent()[1]?.[2]).toMatchObject({ toolSelection: { code: 'report', version: 1 }, parameters: { title: '报告A' } })
  field(root, '输入问题').props['onUpdate:modelValue']('你好')
  click(root, '发送'); await settle()
  expect(sent()[2]?.[2]).toMatchObject({ message: '你好', parameters: {} })
  expect(sent()[2]?.[2]).not.toHaveProperty('toolSelection')
})

it('切换发布版本重新读取绑定目录并清空旧技能和参数', async () => {
  const root = mount('published'); await settle()
  expect(fixture.api.mock.calls.some(call => String(call[0]).includes('/available-tools?conversationId=') && String(call[0]).endsWith('versionNo=2'))).toBe(true)
  field(root, '选择可用技能/工具').props['onUpdate:modelValue']('observation@2'); await settle()
  field(root, '运行版本').props['onUpdate:modelValue'](1); await settle()
  expect(fixture.api.mock.calls.some(call => String(call[0]).includes('/available-tools?conversationId=') && String(call[0]).endsWith('versionNo=1'))).toBe(true)
  expect(field(root, '选择可用技能/工具').props['model-value']).toBe('')
  expect(field(root, 'targetId')).toBeUndefined()
  field(root, '选择可用技能/工具').props['onUpdate:modelValue']('observation@1'); await settle()
  expect(field(root, 'count')).toBeUndefined()
  click(root, '执行技能'); await settle()
  expect(sent()[0]?.[2]).toMatchObject({ versionNo: 1, toolSelection: { code: 'observation', version: 1 }, parameters: { targetId: 'OLD-1' } })
})
