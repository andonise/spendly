import type { EChartsCoreOption } from 'echarts/core';
import { ReportCategoryTotal, TrendPoint } from '../../core/api';
import { categoryLabel } from '../../shared/categories';
import { formatMoney } from '../../shared/money.pipe';
import { shortDayLabel, shortMonthLabel } from './period';

/**
 * Chart options for the dashboard. Single-series charts use one hue (no legend; the card title names the series).
 * The spend/income history uses categorical slots 1 and 2 of the validated reference palette, with a legend.
 */
const SINGLE = { light: '#3a6fd6', dark: '#8fb0f5' };
const SERIES = { light: ['#2a78d6', '#eb6834'], dark: ['#3987e5', '#d95926'] };
const INK = { light: { text: '#44474f', grid: '#e1e2ec', surface: '#ffffff' }, dark: { text: '#c4c6d0', grid: '#3a3d45', surface: '#1b1b1f' } };

type Mode = 'light' | 'dark';

export function colorMode(): Mode {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function'
    && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

/** Horizontal bars, largest on top; only the top 3 carry a direct label. */
export function categoryBars(rows: ReportCategoryTotal[], mode: Mode = colorMode()): EChartsCoreOption {
  const data = [...rows].reverse(); // category axes draw bottom-up
  return {
    animation: false,
    grid: { left: 8, right: 96, top: 8, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'item',
      formatter: (p: { dataIndex: number }) => {
        const r = data[p.dataIndex];
        return `${categoryLabel(r.category)}<br/><b>${formatMoney(r.amountBase)}</b> · ${Math.round(r.share * 100)}% · ${r.count} txn`;
      },
    },
    xAxis: { type: 'value', axisLabel: { show: false }, splitLine: { lineStyle: { color: INK[mode].grid } } },
    yAxis: {
      type: 'category',
      data: data.map((r) => categoryLabel(r.category)),
      axisTick: { show: false },
      axisLine: { show: false },
      axisLabel: { color: INK[mode].text },
    },
    series: [
      {
        type: 'bar',
        data: data.map((r) => Number(r.amountBase)),
        barMaxWidth: 18,
        itemStyle: { color: SINGLE[mode], borderRadius: [0, 4, 4, 0] },
        label: {
          show: true,
          position: 'right',
          color: INK[mode].text,
          formatter: (p: { value: number; dataIndex: number }) => (data.length - p.dataIndex <= 3 ? formatMoney(p.value) : ''),
        },
      },
    ],
  };
}

/** Daily spend as thin columns (one series). */
export function dailySpend(points: TrendPoint[], mode: Mode = colorMode()): EChartsCoreOption {
  return {
    animation: false,
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps: { dataIndex: number }[]) => {
        const p = points[ps[0].dataIndex];
        return `${shortDayLabel(p.from)}<br/>Spent <b>${formatMoney(p.spendBase)}</b>`;
      },
    },
    xAxis: {
      type: 'category',
      data: points.map((p) => shortDayLabel(p.from)),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: INK[mode].grid } },
      axisLabel: { color: INK[mode].text, hideOverlap: true },
    },
    yAxis: {
      type: 'value',
      splitNumber: 3,
      axisLabel: { color: INK[mode].text, formatter: (v: number) => compact(v) },
      splitLine: { lineStyle: { color: INK[mode].grid } },
    },
    series: [
      {
        type: 'bar',
        data: points.map((p) => Number(p.spendBase)),
        barMaxWidth: 14,
        itemStyle: { color: SINGLE[mode], borderRadius: [4, 4, 0, 0] },
      },
    ],
  };
}

/** Six months of spend vs income, grouped columns with a legend (same unit, one axis). */
export function monthlyHistory(points: TrendPoint[], mode: Mode = colorMode()): EChartsCoreOption {
  const [spend, income] = SERIES[mode];
  return {
    animation: false,
    legend: { top: 0, left: 0, itemWidth: 12, itemHeight: 12, textStyle: { color: INK[mode].text } },
    grid: { left: 8, right: 8, top: 36, bottom: 8, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow' },
      formatter: (ps: { dataIndex: number }[]) => {
        const p = points[ps[0].dataIndex];
        const partial = p.coverage === 'PARTIAL' ? '<br/><i>Partial coverage</i>' : '';
        return `${shortMonthLabel(p.from)}<br/>Spent <b>${formatMoney(p.spendBase)}</b><br/>Income <b>${formatMoney(p.incomeBase)}</b>${partial}`;
      },
    },
    xAxis: {
      type: 'category',
      data: points.map((p) => shortMonthLabel(p.from) + (p.coverage === 'PARTIAL' ? '*' : '')),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: INK[mode].grid } },
      axisLabel: { color: INK[mode].text },
    },
    yAxis: {
      type: 'value',
      splitNumber: 3,
      axisLabel: { color: INK[mode].text, formatter: (v: number) => compact(v) },
      splitLine: { lineStyle: { color: INK[mode].grid } },
    },
    series: [
      {
        name: 'Spent',
        type: 'bar',
        data: points.map((p) => Number(p.spendBase)),
        barMaxWidth: 16,
        barGap: '12%',
        itemStyle: { color: spend, borderRadius: [4, 4, 0, 0], borderColor: INK[mode].surface, borderWidth: 1 },
      },
      {
        name: 'Income',
        type: 'bar',
        data: points.map((p) => Number(p.incomeBase)),
        barMaxWidth: 16,
        itemStyle: { color: income, borderRadius: [4, 4, 0, 0], borderColor: INK[mode].surface, borderWidth: 1 },
      },
    ],
  };
}

/** 1250 → "1.3k" for axis ticks (values in tooltips stay exact). */
export function compact(v: number): string {
  return Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(Math.abs(v) >= 10_000 ? 0 : 1)}k` : String(Math.round(v));
}
