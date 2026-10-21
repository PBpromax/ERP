<template>
  <div class="page" v-loading="loading">
    <div class="dash-header">
      <div>
        <div class="dash-greeting">工作台</div>
        <div class="dash-date">{{ todayStr }}</div>
      </div>
      <el-popover placement="bottom-end" :width="420" trigger="click" popper-class="material-warning-popover">
        <template #reference>
          <el-button class="material-warning-trigger" :class="{ 'has-error': materialWarningsError, 'is-normal': !materialWarnings.length && !materialWarningsError }" plain>
            <el-icon><Bell /></el-icon>
            <span>{{ materialWarnings.length ? `原料待采购 ${materialWarnings.length} 项` : materialWarningsError ? '原料预警读取失败' : materialWarningsLoaded ? '暂无原材料低于预警值' : '正在读取原料预警…' }}</span>
          </el-button>
        </template>
        <div class="material-warning-heading">
          <strong>原材料采购提醒</strong>
          <el-button link type="primary" :loading="materialWarningsLoading" @click="refreshMaterialWarnings">刷新</el-button>
        </div>
        <div v-if="materialWarningsError" class="material-warning-error">读取失败，{{ materialWarnings.length ? '当前显示上次结果，请刷新重试。' : '请刷新重试。' }}</div>
        <div class="material-warning-list">
          <div v-for="row in materialWarnings" :key="row.id" class="material-warning-item">
            <div class="material-warning-name">
              <el-button v-if="user.hasMenu('materials')" link type="primary" @click="router.push({ path: '/materials', query: { materialId: row.id } })">{{ row.name }}</el-button>
              <strong v-else>{{ row.name }}</strong>
              <span>{{ row.category }}</span>
            </div>
            <div class="material-warning-quantities">
              <span>当前 <strong>{{ quantity(row.currentQty) }} {{ row.unit }}</strong></span>
              <span>预警值 {{ quantity(row.safetyStock) }} {{ row.unit }}</span>
            </div>
          </div>
        </div>
        <div v-if="!materialWarnings.length && !materialWarningsError" class="material-warning-resolved">{{ materialWarningsLoaded ? '暂无原材料低于预警值' : '正在读取原料预警…' }}</div>
      </el-popover>
    </div>

    <div class="dash-grid-main">
      <div class="summary-stack">
        <div
          v-for="card in summaryCards"
          :key="card.label"
          class="mcard mcard--summary"
          :class="`mcard--${card.type}`"
        >
          <div class="mcard-top">
            <div class="mcard-label">{{ card.label }}</div>
            <div class="mcard-icon" :class="`mcard-icon--${card.type}`">
              <el-icon><component :is="card.icon" /></el-icon>
            </div>
          </div>
          <div class="mcard-value">{{ card.value }}</div>
          <div class="mcard-footer">
            <div class="mcard-sub">{{ card.sub }}</div>
          </div>
        </div>
      </div>
      <div class="page-card">
        <div class="section-header section-header--trend">
          <div class="trend-heading">
            <span class="section-title">月度销售趋势</span>
            <span class="trend-period">{{ trendPeriodLabel }}</span>
          </div>
        </div>
        <hr class="card-sep">
        <div ref="chartRef" class="chart-area" role="img" aria-label="全年月度销售趋势"></div>
      </div>
    </div>

    <!-- Metrics -->
    <div class="metrics-grid">
      <div
        v-for="card in cards"
        :key="card.label"
        class="mcard"
        :class="[`mcard--${card.type}`, { 'mcard--total': card.emphasis }]"
      >
        <div class="mcard-top">
          <div class="mcard-label">{{ card.label }}</div>
          <div class="mcard-icon" :class="`mcard-icon--${card.type}`">
            <el-icon><component :is="card.icon" /></el-icon>
          </div>
        </div>
        <div class="mcard-value">{{ card.value }}</div>
        <div class="mcard-footer">
          <div class="mcard-sub">{{ card.sub }}</div>
        </div>
      </div>
    </div>

    <div class="ranking-grid">
      <section class="page-card ranking-panel ranking-panel--debt">
        <div class="ranking-heading">
          <div>
            <div class="section-title">欠款客户公司排行</div>
            <div class="ranking-period">全部历史账款，已扣除预收款</div>
          </div>
          <div class="ranking-total">TOP {{ customerDebtRanking.length }}</div>
        </div>
        <div v-if="customerDebtRanking.length" class="ranking-table" role="table" aria-label="欠款客户公司排行">
          <div class="ranking-row ranking-row--head" role="row">
            <span role="columnheader">排名</span>
            <div class="ranking-column-head" role="columnheader"><span>客户公司</span><span>剩余欠款</span></div>
          </div>
          <div v-for="(row, index) in customerDebtRanking" :key="`${row.customerName}-${index}`" class="ranking-row" role="row">
            <span class="ranking-index" :class="{ 'is-leading': index < 3 }" role="cell">{{ index + 1 }}</span>
            <div class="ranking-customer" role="cell">
              <div class="ranking-name-line">
                <span class="ranking-name" :title="row.customerName">{{ row.customerName }}</span>
                <span class="ranking-orders">{{ Number(row.unpaidCount || 0).toLocaleString('zh-CN') }} 笔未结</span>
                <span class="ranking-amount">{{ formatMoney(row.amount) }}</span>
              </div>
              <span class="ranking-bar" aria-hidden="true"><span :style="{ width: debtRankingBarWidth(row) }"></span></span>
            </div>
          </div>
        </div>
        <el-empty v-else description="暂无欠款客户" :image-size="72" />
      </section>

      <section class="page-card ranking-panel ranking-panel--sales">
        <div class="ranking-heading">
          <div>
            <div class="section-title">客户销售额排行</div>
            <div class="ranking-period">{{ customerRankingYear }}年有效销售订单</div>
          </div>
          <div class="ranking-total">TOP {{ customerRanking.length }}</div>
        </div>
        <div v-if="customerRanking.length" class="ranking-table" role="table" aria-label="客户销售额排行">
          <div class="ranking-row ranking-row--head" role="row">
            <span role="columnheader">排名</span>
            <div class="ranking-column-head" role="columnheader"><span>客户公司</span><span>销售额</span></div>
          </div>
          <div v-for="(row, index) in customerRanking" :key="`${row.customerName}-${index}`" class="ranking-row" role="row">
            <span class="ranking-index" :class="{ 'is-leading': index < 3 }" role="cell">{{ index + 1 }}</span>
            <div class="ranking-customer" role="cell">
              <div class="ranking-name-line">
                <span class="ranking-name" :title="row.customerName">{{ row.customerName }}</span>
                <span class="ranking-orders">{{ Number(row.orderCount || 0).toLocaleString('zh-CN') }} 笔</span>
                <span class="ranking-amount">{{ formatMoney(row.amount) }}</span>
              </div>
              <span class="ranking-bar" aria-hidden="true"><span :style="{ width: rankingBarWidth(row) }"></span></span>
            </div>
          </div>
        </div>
        <el-empty v-else description="本年暂无销售订单" :image-size="72" />
      </section>
    </div>

  </div>
</template>

<script setup lang="ts">
import { formatMoney as money } from '@/format/money'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Bell, Box, Money, Tickets, TrendCharts, Van, Wallet } from '@element-plus/icons-vue'
import { init, graphic, use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import http from '@/api/http'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { materialApi, quantity, type MaterialRow } from '@/material/api'

const router = useRouter()
const user = useUserStore()
const materialWarnings = ref<MaterialRow[]>([])
const materialWarningsLoading = ref(false)
const materialWarningsError = ref(false)
const materialWarningsLoaded = ref(false)
let materialWarningTimer: ReturnType<typeof setInterval> | null = null
let materialWarningsPending = false

async function refreshMaterialWarnings() {
  if (disposed || document.hidden) return
  if (materialWarningsLoading.value) { materialWarningsPending = true; return }
  materialWarningsLoading.value = true
  try {
    const rows = await materialApi.warnings()
    if (!disposed) { materialWarnings.value = rows; materialWarningsError.value = false; materialWarningsLoaded.value = true }
  } catch { if (!disposed) materialWarningsError.value = true }
  finally {
    materialWarningsLoading.value = false
    if (materialWarningsPending && !disposed) { materialWarningsPending = false; void refreshMaterialWarnings() }
  }
}

use([LineChart, DataZoomComponent, GridComponent, TooltipComponent, CanvasRenderer])

const data = ref<any>({})
const chartRef = ref<HTMLDivElement>()
let trendResizeObserver: ResizeObserver | null = null
const loading = ref(true)
let chartInstance: ReturnType<typeof init> | null = null
let collectionRefreshTimer: ReturnType<typeof setInterval> | null = null
let collectionRefreshRunning = false
let collectionRefreshPending = false
let disposed = false
const trendMonths = computed<string[]>(() => data.value.trend?.months || [])
const trendSales = computed<number[]>(() => data.value.trend?.sales || [])
const trendVisibleStart = ref(0)
const trendVisibleEnd = ref(0)
const customerRanking = computed<any[]>(() => data.value.customerRanking || [])
const customerDebtRanking = computed<any[]>(() => data.value.customerDebtRanking || [])
const customerRankingYear = computed(() => data.value.customerRankingYear || new Date().getFullYear())
const customerRankingMax = computed(() => Math.max(0, ...customerRanking.value.map(row => Number(row.amount || 0))))
const customerDebtRankingMax = computed(() => Math.max(0, ...customerDebtRanking.value.map(row => Number(row.amount || 0))))
const trendPeriodLabel = computed(() => {
  const months = trendMonths.value
  if (!months.length) return ''
  const start = months[Math.min(trendVisibleStart.value, months.length - 1)]
  const end = months[Math.min(trendVisibleEnd.value, months.length - 1)]
  return `${formatTrendMonth(start)} - ${formatTrendMonth(end)}`
})
const todayStr = computed(() => {
  const d = new Date()
  return d.toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' })
})
const summaryCards = computed(() => {
  const summary = data.value.summary || {}
  return [
    { label: '销售总金额', value: formatMoney(summary.salesTotal), sub: '今年有效销售订单金额', type: 'blue', icon: TrendCharts },
    { label: '已送货金额', value: formatMoney(summary.deliveredTotal), sub: '今年有效订单已送货金额', type: 'green', icon: Van }
  ]
})
const cards = computed(() => {
  const summary = data.value.summary || {}
  return [
    { label: '已送货并对账金额', value: formatMoney(summary.deliveredReconciledTotal), sub: '今年订单已送货且已对账金额，含开票、现汇等结算方式', type: 'teal', icon: Tickets },
    { label: '已送货未对账金额', value: formatMoney(summary.deliveredUnreconciledTotal), sub: '全部客户实际待对账金额，含往年记录，与对账单页同步', type: 'orange', icon: Box },
    { label: '收款登记未回款金额', value: formatMoney(summary.collectionUnpaidTotal), sub: '收款登记全部公司当前欠款合计 · 自动同步', type: 'red', icon: Wallet },
    { label: '今年累计回款', value: formatMoney(summary.yearReceivedTotal), sub: `${summary.collectionYear || new Date().getFullYear()}年全部公司收款，含货物抵款、账款调减及预收款`, type: 'green', icon: Money },
    { label: '未送货金额', value: formatMoney(summary.undeliveredTotal), sub: '按订单逐笔计算未送货余额', type: 'amber', icon: Van },
    { label: '现在剩余外欠金额（合计欠款总金额）', value: formatMoney(summary.currentOutstandingTotal), sub: '收款登记未回款金额 ＋ 已送货未对账金额 · 自动同步', type: 'blue', icon: Wallet, emphasis: true }
  ]
})

function formatTrendMonth(value: string) {
  const [year, month] = value.split('-')
  return `${year}年${Number(month)}月`
}

function formatMoney(value: any) {
  return `¥${money(value)}`
}

function rankingBarWidth(row: any) {
  if (!customerRankingMax.value) return '0%'
  return `${Math.max(4, Number(row.amount || 0) / customerRankingMax.value * 100)}%`
}

function debtRankingBarWidth(row: any) {
  if (!customerDebtRankingMax.value) return '0%'
  return `${Math.max(4, Number(row.amount || 0) / customerDebtRankingMax.value * 100)}%`
}

async function load() {
  loading.value = true
  try {
    const [dashboard, collections] = await Promise.all([
      http.get('/reports/dashboard'),
      http.get('/reports/collection-summary')
    ])
    if (disposed) return
    data.value = { ...dashboard, summary: { ...(dashboard as any).summary, ...collections } }
    await nextTick()
    renderChart()
  } finally {
    loading.value = false
  }
}

async function refreshCollections() {
  if (disposed || document.hidden) return
  if (collectionRefreshRunning) {
    collectionRefreshPending = true
    return
  }
  collectionRefreshRunning = true
  try {
    const summary = await http.get('/reports/collection-summary', { skipErrorToast: true })
    if (!disposed) data.value = { ...data.value, summary: { ...data.value.summary, ...summary } }
  } catch {
    // Retain the last confirmed balance; the next refresh retries automatically.
  } finally {
    collectionRefreshRunning = false
    if (collectionRefreshPending && !disposed) {
      collectionRefreshPending = false
      void refreshCollections()
    }
  }
}

function renderChart() {
  if (!chartRef.value) return
  chartInstance ||= init(chartRef.value)
  const lastIndex = Math.max(0, trendMonths.value.length - 1)
  const firstVisibleIndex = Math.max(0, trendMonths.value.length - 12)
  trendVisibleStart.value = firstVisibleIndex
  trendVisibleEnd.value = lastIndex
  // Use the complete history so scrolling never changes the vertical scale.
  const peakSales = Math.max(0, ...trendSales.value.map(value => Number(value) || 0))
  const rawInterval = Math.max(10000, peakSales / 6)
  const intervalMagnitude = 10 ** Math.floor(Math.log10(rawInterval))
  const intervalStep = [1, 2, 5, 10].find(step => step * intervalMagnitude >= rawInterval) || 10
  const salesAxisInterval = intervalStep * intervalMagnitude
  const salesAxisMax = Math.max(salesAxisInterval, Math.ceil(peakSales / salesAxisInterval) * salesAxisInterval)
  const yAxis = {
    type: 'value' as const,
    min: 0,
    max: salesAxisMax,
    interval: salesAxisInterval,
    axisLabel: {
      color: '#667085',
      fontSize: 12,
      formatter: (v: number) => v >= 10000 ? `${(v / 10000).toFixed(0)}万` : v.toLocaleString()
    },
    splitLine: { lineStyle: { color: '#f1f4f8' } }
  }
  chartInstance.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: any) => money(value),
      confine: true,
      backgroundColor: '#fff',
      borderColor: '#e6e9ef',
      borderWidth: 1,
      textStyle: { color: '#1d2433', fontSize: 13 }
    },
    grid: { left: 58, right: 18, top: 14, bottom: 48 },
    dataZoom: [
      {
        type: 'inside',
        startValue: firstVisibleIndex,
        endValue: lastIndex,
        minValueSpan: Math.min(11, lastIndex),
        maxValueSpan: Math.min(11, lastIndex),
        zoomLock: true
      },
      {
        type: 'slider',
        startValue: firstVisibleIndex,
        endValue: lastIndex,
        minValueSpan: Math.min(11, lastIndex),
        maxValueSpan: Math.min(11, lastIndex),
        zoomLock: true,
        brushSelect: false,
        showDataShadow: false,
        height: 8,
        bottom: 8,
        borderRadius: 4,
        borderColor: 'transparent',
        backgroundColor: '#f1f3f4',
        fillerColor: '#bdc1c6',
        handleIcon: 'circle',
        handleSize: '100%',
        handleStyle: { color: '#bdc1c6', borderWidth: 0 },
        moveHandleSize: 0,
        showDetail: false,
        emphasis: {
          handleStyle: { color: '#9aa0a6', borderWidth: 0 }
        }
      }
    ],
    xAxis: {
      type: 'category',
      data: trendMonths.value,
      axisLine: { lineStyle: { color: '#e6e9ef' } },
      axisTick: { show: false },
      axisLabel: {
        color: '#667085',
        fontSize: 11,
        interval: 0,
        formatter: (value: string) => {
          const [year, month] = value.split('-')
          return Number(month) === 1 ? `${year}\n1月` : `${Number(month)}月`
        }
      }
    },
    yAxis,
    series: [{
      name: '销售额',
      type: 'line',
      smooth: true,
      symbol: 'circle',
      symbolSize: 6,
      lineStyle: { color: '#2563eb', width: 2 },
      itemStyle: { color: '#2563eb', borderColor: '#fff', borderWidth: 2 },
      areaStyle: {
        color: new graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: 'rgba(37,99,235,0.14)' },
          { offset: 1, color: 'rgba(37,99,235,0.01)' }
        ])
      },
      data: trendSales.value
    }]
  }, true)
  chartInstance.off('datazoom', updateTrendVisibleRange)
  chartInstance.on('datazoom', updateTrendVisibleRange)
}

function updateTrendVisibleRange(event: any) {
  const zoom = event?.batch?.[0] || event || {}
  const lastIndex = Math.max(0, trendMonths.value.length - 1)
  const startPercent = Number(zoom.start ?? 0)
  const endPercent = Number(zoom.end ?? 100)
  trendVisibleStart.value = Math.round(lastIndex * startPercent / 100)
  trendVisibleEnd.value = Math.round(lastIndex * endPercent / 100)
}

function resizeChart() {
  chartInstance?.resize()
}

onMounted(() => {
  trendResizeObserver = new ResizeObserver(resizeChart)
  if (chartRef.value) trendResizeObserver.observe(chartRef.value)
  load()
  void refreshMaterialWarnings()
  window.addEventListener('oms:data-changed', refreshMaterialWarnings)
  window.addEventListener('focus', refreshMaterialWarnings)
  document.addEventListener('visibilitychange', refreshMaterialWarnings)
  materialWarningTimer = setInterval(refreshMaterialWarnings, 15000)
  window.addEventListener('oms:data-changed', refreshCollections)
  window.addEventListener('focus', refreshCollections)
  document.addEventListener('visibilitychange', refreshCollections)
  // Other employees' changes are picked up while the homepage remains open.
  collectionRefreshTimer = setInterval(refreshCollections, 15000)
})

onBeforeUnmount(() => {
  disposed = true
  if (materialWarningTimer) clearInterval(materialWarningTimer)
  window.removeEventListener('oms:data-changed', refreshMaterialWarnings)
  window.removeEventListener('focus', refreshMaterialWarnings)
  document.removeEventListener('visibilitychange', refreshMaterialWarnings)
  if (collectionRefreshTimer) clearInterval(collectionRefreshTimer)
  window.removeEventListener('oms:data-changed', refreshCollections)
  window.removeEventListener('focus', refreshCollections)
  document.removeEventListener('visibilitychange', refreshCollections)
  trendResizeObserver?.disconnect()
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<style scoped>
.material-warning-trigger { height:34px; padding:0 13px; border-color:#f3d2bd; background:#fff7f0; color:#bf6334; font-size:13px; border-radius:8px; }
.material-warning-trigger:hover,.material-warning-trigger:focus { color:#a84d23; background:#fff0e4; border-color:#eabb9a; }
.material-warning-trigger.is-normal { color:#64748b; background:#f8fafc; border-color:#e2e8f0; }
.material-warning-trigger.is-normal:hover,.material-warning-trigger.is-normal:focus { color:#475569; background:#f1f5f9; border-color:#cbd5e1; }
.material-warning-trigger :deep(.el-icon) { margin-right:7px; font-size:15px; }
.material-warning-trigger.has-error { color:#b58130; }
.material-warning-heading { display:flex; align-items:center; justify-content:space-between; margin-bottom:10px; color:var(--oms-heading); }
.material-warning-list { max-height:320px; overflow-y:auto; }
.material-warning-item { padding:12px 0; border-top:1px solid var(--oms-border); }
.material-warning-name { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.material-warning-name .el-button { min-width:0; white-space:normal; text-align:left; line-height:20px; }
.material-warning-name > span { flex-shrink:0; font-size:12px; color:var(--oms-muted); }
.material-warning-quantities { display:flex; align-items:center; gap:20px; margin-top:6px; font-size:12px; color:var(--oms-sub); }
.material-warning-quantities strong { color:#d85849; font-weight:600; }
.material-warning-error { color:#b58130; font-size:12px; margin:8px 0; }
.material-warning-resolved { color:var(--oms-muted); font-size:13px; padding:10px 0; }
.dash-header { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-bottom:22px; }

.dash-greeting {
  color: var(--oms-heading);
  font-size: 20px;
  font-weight: 760;
  letter-spacing: 0;
}

.dash-date {
  color: var(--oms-sub);
  font-size: 13px;
  margin-top: 3px;
}

/* ─── Metrics ───────────────────────────────── */
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 14px;
}

.mcard {
  background: #fff;
  border: 1px solid var(--oms-border);
  border-radius: 8px;
  padding: 18px 20px 16px;
  box-shadow: var(--oms-shadow);
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  min-height: 132px;
}

.mcard-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.mcard-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--oms-sub);
}

.mcard-icon {
  width: 32px;
  height: 32px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mcard-icon svg { width: 16px; height: 16px; }

.mcard-icon--blue  { background: var(--oms-primary-soft); color: var(--oms-primary); }
.mcard-icon--green { background: #dcfce7; color: #16a34a; }
.mcard-icon--amber { background: #fef3c7; color: #d97706; }
.mcard-icon--orange { background: #ffedd5; color: #ea580c; }
.mcard-icon--teal { background: #ccfbf1; color: #0f766e; }
.mcard-icon--red { background: #fee2e2; color: #dc2626; }

.mcard--blue  { border-left: 3px solid var(--oms-primary); padding-left: 17px; }
.mcard--green { border-left: 3px solid #16a34a; padding-left: 17px; }
.mcard--amber { border-left: 3px solid #d97706; padding-left: 17px; }
.mcard--orange { border-left: 3px solid #ea580c; padding-left: 17px; }
.mcard--teal { border-left: 3px solid #0f766e; padding-left: 17px; }
.mcard--red { border-left: 3px solid #dc2626; padding-left: 17px; }

.mcard--total {
  background: #f8fafc;
  border-color: #cbd5e1;
}

.summary-stack {
  display: grid;
  grid-template-rows: repeat(2, minmax(0, 1fr));
  gap: 14px;
  min-width: 0;
}

.mcard--summary {
  min-height: 0;
  justify-content: space-between;
}

.mcard-value {
  font-size: 24px;
  font-weight: 760;
  letter-spacing: 0;
  color: var(--oms-heading);
  font-variant-numeric: tabular-nums;
  line-height: 1;
  white-space: nowrap;
}

.mcard-footer {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px;
}

.mcard-sub {
  font-size: 12px;
  color: var(--oms-muted);
  line-height: 1.45;
  min-width: 0;
}

/* ─── Layout grids ──────────────────────────── */
.dash-grid-main {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(0, 2fr);
  gap: 14px;
  margin-bottom: 14px;
}

.chart-area {
  height: 220px;
  min-width: 0;
  width: 100%;
}

/* ─── Section headers ───────────────────────── */
.section-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.section-header--trend {
  justify-content: space-between;
  min-height: 32px;
}

.trend-heading {
  display: flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
}

.trend-period {
  color: var(--oms-muted);
  font-size: 11px;
  white-space: nowrap;
}

.section-title {
  color: var(--oms-heading);
  font-size: 13.5px;
  font-weight: 700;
}

.card-sep {
  border: none;
  border-top: 1px solid var(--oms-border);
  margin: 0 -18px 12px;
}

.ranking-grid {
  display: grid;
  gap: 14px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.ranking-panel { min-width: 0; padding: 0; overflow: hidden; }
.ranking-panel--sales { order: -1; }
.ranking-heading {
  align-items: center;
  border-bottom: 1px solid var(--oms-border);
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px 14px;
}
.ranking-period { color: var(--oms-muted); font-size: 12px; margin-top: 4px; }
.ranking-total { color: var(--oms-sub); font-size: 12px; font-weight: 700; }
.ranking-table { width: 100%; }
.ranking-row {
  align-items: center;
  border-bottom: 1px solid #eef1f5;
  display: grid;
  gap: 14px;
  grid-template-columns: 48px minmax(0, 1fr);
  min-height: 58px;
  padding: 8px 18px;
}
.ranking-row:last-child { border-bottom: 0; }
.ranking-row--head { background: #f8fafc; color: var(--oms-sub); font-size: 12px; font-weight: 700; min-height: 40px; }
.ranking-index {
  align-items: center;
  background: #eef2f6;
  border-radius: 6px;
  color: #475467;
  display: inline-flex;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  font-weight: 700;
  height: 26px;
  justify-content: center;
  width: 30px;
}
.ranking-index.is-leading { background: #eff5ff; color: var(--oms-primary); }
.ranking-column-head { display: grid; gap: 14px; grid-template-columns: minmax(0, 1fr) auto; max-width: 760px; }
.ranking-customer { display: grid; gap: 7px; max-width: 760px; min-width: 0; }
.ranking-name-line { align-items: center; display: grid; gap: 10px; grid-template-columns: minmax(0, 1fr) auto auto; min-width: 0; }
.ranking-name { color: var(--oms-heading); font-weight: 650; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ranking-bar { background: #eef2f6; display: block; height: 4px; overflow: hidden; width: min(360px, 100%); }
.ranking-bar > span { background: #2563eb; display: block; height: 100%; }
.ranking-panel--debt .ranking-index.is-leading { background: #fff1f2; color: #dc2626; }
.ranking-panel--debt .ranking-bar > span { background: #dc2626; }
.ranking-orders { color: var(--oms-sub); flex: 0 0 auto; font-size: 12px; font-variant-numeric: tabular-nums; }
.ranking-amount { color: var(--oms-heading); font-variant-numeric: tabular-nums; font-weight: 700; text-align: right; white-space: nowrap; }

@media (max-width: 1280px) {
  .metrics-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 960px) {
  .dash-grid-main {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .summary-stack {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    grid-template-rows: none;
    gap: 10px;
  }

  .ranking-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .metrics-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }

  .trend-heading {
    align-items: flex-start;
    flex-direction: column;
    gap: 2px;
  }

  .trend-period {
    white-space: normal;
  }

  .chart-area {
    height: 200px;
  }

  .summary-stack {
    grid-template-columns: 1fr;
  }

  .ranking-row { gap: 8px; grid-template-columns: 36px minmax(0, 1fr); padding-left: 12px; padding-right: 12px; }
  .ranking-orders { display: none; }
  .ranking-heading { padding-left: 12px; padding-right: 12px; }
}
</style>
