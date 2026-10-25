import { init, use } from "echarts/core";
import { BarChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
export { init };
