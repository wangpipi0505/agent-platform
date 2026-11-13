// F4-4：响应式断点体系+亮色切换（L4 收尾卡）。
// 静态锁定前端稿 §7.4 验收断言 4-7 中可静态化的部分与 §7.2 断点表：
//   断言2（1280 无横滚）→ 无横向滚动 min-width 残留 + 断点体系（≥1440/1280/<768）在壳层接线；
//   断言4（亮色切换后对比度达标）→ html.light 语义段+EP 亮色映射覆盖、主按钮实底接
//     --btn-primary-bg（真实对比度数值与 SUS≥70 走查为人工口径，不进测试）；
//   断言5/6 由 F1-F4 各页测试与 tokens 工具类承载（tabular-nums/EmptyState），不重复；
//   断言7（禁新增三级滚动容器）由 workspace-shell 等页级测试承载；
//   附加红线：全前端源码 clamp( 清零、样式块裸色值清零（只用 tokens 变量）。
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

const root = fileURLToPath(new URL("..", import.meta.url));
const read = (name: string) => readFileSync(join(root, name), "utf8");

/** 枚举目录下全部文件（递归）。 */
function walk(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(p));
    else out.push(p);
  }
  return out;
}
const SRC_DIRS = ["admin/src", "webapp/src", "shared", "assistant.js/src"];
const sourceFiles = SRC_DIRS.flatMap((d) => walk(join(root, d)));
/** 去注释与 var() 引用（含回退值）后的源文本：clamp/min-width/裸色值检查的统一口径。 */
function scanView(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/\/\/[^\n]*/g, "")
    .replace(/var\(--[^)]*\)/g, "")
    .replace(/@media[^{]*\{/g, ""); // 媒体查询前导（min-width 断点本身合法，非布局残留）
}

describe("F4-4 断点体系（§7.2：≥1440 全量 / 1280 侧栏折叠 / <768 抽屉）", () => {
  it("admin ≤1280 侧栏自动折叠为图标列：matchMedia+icon-mode 类，组头图标+菜单项首字代字", () => {
    const shell = read("admin/src/AppShell.vue");
    expect(shell).toContain('matchMedia("(max-width: 1280px)")');
    expect(shell).toContain("'icon-mode'");
    // 复用 F1-2 折叠语义：组头只留图标（tooltip=分组名由既有 title 承载），项取 data-short
    expect(shell).toContain(".nav.icon-mode .nav-group-head .glabel");
    expect(shell).toContain("content: attr(data-short)");
    expect(shell).toContain(":data-short=\"item.label.slice(0, 1)\"");
    // tokens.css ≤1280 CSS 兜底（nav 收窄 4.5rem）与断点对齐
    const tokens = read("shared/tokens.css");
    expect(tokens).toContain("@media (max-width: 1280px)");
    expect(tokens).not.toContain("@media (max-width: 1200px)");
  });

  it("admin <768 侧栏收抽屉（webapp 同款：matchMedia+translateX+scrim）", () => {
    const shell = read("admin/src/AppShell.vue");
    expect(shell).toContain('matchMedia("(max-width: 768px)")');
    expect(shell).toContain("@media (max-width: 768px)");
    expect(shell).toContain("transform: translateX(-100%)");
    expect(shell).toContain("transform: translateX(0)");
    expect(shell).toContain('class="scrim"');
    expect(shell).toContain("drawer-trigger");
    // 图标列与抽屉互斥：icon-mode 仅在非抽屉形态挂
    expect(shell).toContain("'icon-mode': iconMode && !mobile");
  });

  it("webapp <768 抽屉机制仍在（F4 批次先例回归锚）", () => {
    const app = read("webapp/src/App.vue");
    expect(app).toContain("@media (max-width: 768px)");
    expect(app).toContain("transform: translateX(-100%)");
    expect(app).toContain('matchMedia("(max-width: 768px)")');
    expect(app).toContain('class="scrim"');
  });

  it("壳层无横向滚动 min-width 残留：全源码无 ≥60rem/≥700px 的 min-width（85.375rem 不回归）", () => {
    for (const file of sourceFiles) {
      const view = scanView(readFileSync(file, "utf8"));
      const widths = [...view.matchAll(/min-width:\s*(\d+(?:\.\d+)?)(rem|px)/g)];
      for (const [, value, unit] of widths) {
        const px = parseFloat(value) * (unit === "rem" ? 16 : 1);
        expect(px, `${file} min-width:${value}${unit}`).toBeLessThan(700);
      }
      expect(view).not.toContain("85.375");
    }
    // 壳层本体仍是 100dvh grid，无横向强扩
    const tokens = read("shared/tokens.css");
    expect(tokens).toMatch(/\.shell \{[^}]*height: 100dvh[^}]*\}/);
  });
});

describe("F4-4 亮色切换（§7.3/§7.4 断言4）", () => {
  const tokens = read("shared/tokens.css");
  const lightBlock = tokens.slice(tokens.indexOf("html.light {"));

  it("html.light 语义段覆盖 §7.3 亮色值列（品牌/中性/语义状态/阴影）", () => {
    for (const line of [
      "--brand: #0284c7",
      "--btn-primary-bg: #075985",
      "--bg-canvas: #f5f7fa",
      "--bg-surface-1: #ffffff",
      "--bg-surface-2: #eef1f6",
      "--bg-surface-3: #e3e8ef",
      "--border-subtle: #dfe3ea",
      "--border-strong: #c3cad6",
      "--text-primary: #101828",
      "--text-secondary: #475467",
      "--text-tertiary: #667085",
      "--text-disabled: #98a2b3",
      "--st-ok: #15803d",
      "--st-fail: #dc2626",
      "--st-run: #0284c7",
      "--st-suspend: #b45309",
    ])
      expect(lightBlock).toContain(line);
    expect(lightBlock).toContain("color-scheme: light");
    // 旧名映射仍在（存量页面亮色下跟随切换）
    expect(lightBlock).toContain("--canvas: var(--bg-canvas)");
    expect(lightBlock).toContain("--ink: var(--text-primary)");
  });

  it("EP 组件亮色映射已补齐（F1-1 遗留缺口）：主色/表面/文本/边框/填充接通亮色词表", () => {
    for (const line of [
      "--el-color-primary: var(--brand)",
      "--el-bg-color: var(--bg-surface-1)",
      "--el-bg-color-page: var(--bg-canvas)",
      "--el-text-color-primary: var(--text-primary)",
      "--el-text-color-secondary: var(--text-secondary)",
      "--el-text-color-disabled: var(--text-disabled)",
      "--el-border-color: var(--border-strong)",
      "--el-fill-color: var(--bg-surface-2)",
      "--el-mask-color: rgba(16, 24, 40, 0.45)",
    ])
      expect(lightBlock).toContain(line);
  });

  it("断言4 主按钮白字对比度口径：亮色实底换 --btn-primary-bg+#075985，白字前景走 --on-brand", () => {
    const solid = tokens.slice(tokens.indexOf("html.light .el-button--primary"));
    expect(solid).toContain("--el-button-bg-color: var(--btn-primary-bg)");
    expect(solid).toContain("--el-button-text-color: var(--on-brand)");
    // 分段选择器选中项同口径
    expect(lightBlock).toContain("--el-radio-button-checked-bg-color: var(--btn-primary-bg)");
    // 危险实底 token 化（两主题同值 #b91c1c 系），DangerConfirm 不再硬编码
    expect(tokens).toContain("--danger-solid: #b91c1c");
    const danger = read("shared/components/DangerConfirm.vue");
    expect(danger).toContain("var(--danger-solid)");
    expect(danger).not.toContain("#b91c1c;");
  });

  it("切换开关：shared/theme.ts 三态偏好持久 localStorage+初始跟随系统；双端顶栏有开关且入口引导", () => {
    const theme = read("shared/theme.ts");
    expect(theme).toContain('THEME_KEY = "theme"');
    expect(theme).toContain('"(prefers-color-scheme: dark)"');
    expect(theme).toContain("toggleTheme");
    // dark/light 互斥挂类
    expect(theme).toContain('root.classList.toggle("dark", resolved === "dark")');
    expect(theme).toContain('root.classList.toggle("light", resolved === "light")');
    // 手动覆盖持久；auto 不留偏好键（跟随系统）
    expect(theme).toContain("setItem(THEME_KEY, next)");
    expect(theme).toContain("removeItem(THEME_KEY)");
    for (const entry of ["admin/src/main.ts", "webapp/src/main.ts"]) {
      const main = read(entry);
      expect(main).toContain("initTheme(document)");
      // 不再无条件挂 dark（否则永远压过亮色切换）
      expect(main).not.toContain('classList.add("dark")');
    }
    for (const shellFile of ["admin/src/AppShell.vue", "webapp/src/App.vue"]) {
      const shell = read(shellFile);
      expect(shell).toContain('data-testid="theme-toggle"');
      expect(shell).toContain("@click=\"toggleTheme\"");
    }
  });
});

describe("F4-4 红线（只用 tokens 变量 / clamp 清零）", () => {
  it("全前端源码 clamp( 清零（注释口径除外）", () => {
    for (const file of sourceFiles) {
      expect(scanView(readFileSync(file, "utf8")), file).not.toContain("clamp(");
    }
  });

  it("样式块裸色值清零：admin/webapp/shared 全部 .vue style 段无 #hex 与 rgb()/rgba()", () => {
    const vueFiles = sourceFiles.filter((f) => f.endsWith(".vue"));
    expect(vueFiles.length).toBeGreaterThan(30);
    for (const file of vueFiles) {
      const style = readFileSync(file, "utf8")
        .split("<style")
        .slice(1)
        .join("<style");
      if (!style) continue;
      expect(
        scanView(style).match(/#[0-9a-fA-F]{3,8}\b|rgba?\(/),
        `${file} style 段出现裸色值`,
      ).toBeNull();
    }
  });
});
