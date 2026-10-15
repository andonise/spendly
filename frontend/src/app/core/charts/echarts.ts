/**
 * Tree-shaken ECharts build: only the pieces the app uses. Loaded lazily by
 * provideEchartsCore (app.config.ts), so it stays out of the initial bundle.
 */
import { BarChart, LineChart, PieChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import * as echarts from 'echarts/core';
import { CanvasRenderer, SVGRenderer } from 'echarts/renderers';

echarts.use([PieChart, BarChart, LineChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer, SVGRenderer]);

export { echarts };
