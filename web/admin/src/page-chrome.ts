import { reactive } from "vue";

/** Header text owned by the active page, rendered by AppShell. */
export const pageChrome = reactive({
  title: "智能体管理",
  eyebrow: "平台管理",
  subtitle: "连接业务能力，持续验证每一次运行。",
});

/**
 * UI-02：AppShell 顶栏为本页操作提供的固定挂载点 id。
 * PageHeader 检测到该元素时切换 shell 模式：actions 插槽以 Teleport 迁入顶栏，
 * 标题/副题写入 pageChrome 由顶栏展示；路由离开随组件卸载自动清理。
 */
export const PAGE_ACTIONS_TARGET = "appshell-page-actions";
