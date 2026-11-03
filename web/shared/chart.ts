import { init, use } from "echarts/core";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
export { init };

/**
 * UI-04：ECharts 轴/图例/tooltip 基础配色从语义令牌解析（暗夜流光主题下
 * 与画布/表面同源）。使用方只声明系列数据与颜色，坐标轴、图例、悬浮层
 * 不再各自写死色值；非浏览器环境回退暗色基线，SSR/测试可安全调用。
 */
export function chartBaseTheme(): Record<string, unknown> {
  const token = (name: string, fallback: string) => {
    if (typeof window === "undefined") return fallback;
    const value = getComputedStyle(document.documentElement)
      .getPropertyValue(name)
      .trim();
    return value || fallback;
  };
  const ink = token("--ink", "#e6edf7");
  const muted = token("--muted", "#8ea0b8");
  const line = token("--line", "#28374f");
  return {
    textStyle: { color: ink },
    legend: { textStyle: { color: muted } },
    categoryAxis: {
      axisLine: { lineStyle: { color: line } },
      axisTick: { lineStyle: { color: line } },
      axisLabel: { color: muted },
    },
    valueAxis: {
      axisLabel: { color: muted },
      splitLine: { lineStyle: { color: line } },
    },
    tooltip: {
      backgroundColor: token("--surface-2", "#162032"),
      borderColor: line,
      textStyle: { color: ink },
    },
  };
}
