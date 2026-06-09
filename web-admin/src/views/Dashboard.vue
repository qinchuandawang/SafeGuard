<template>
  <div class="dashboard">
    <!-- 骨架屏遮罩层（始终覆盖在内容之上） -->
    <div v-if="showSkeleton" class="skeleton-overlay">
      <div class="skeleton-stats-grid">
        <div class="skeleton-card" v-for="i in 4" :key="i">
          <div class="skeleton-shimmer" />
          <div class="skeleton-row">
            <div class="skeleton-icon" />
            <div class="skeleton-lines">
              <div class="skeleton-line w-20" />
              <div class="skeleton-line w-12" />
            </div>
          </div>
        </div>
      </div>
      <div class="skeleton-charts-grid">
        <div class="skeleton-chart" v-for="i in 2" :key="i">
          <div class="skeleton-shimmer" />
          <div class="skeleton-line w-24" style="margin-bottom:16px" />
          <div class="skeleton-block" />
        </div>
      </div>
    </div>

    <!-- 真实内容（始终在 DOM 中，图表容器挂载后即可初始化） -->
    <div class="dashboard-content" :class="{ 'content-ready': statsLoaded }">
      <!-- 统计卡片行 -->
      <div class="stats-grid">
        <div
          class="stat-card"
          v-for="(card, idx) in statCards"
          :key="card.label"
          :style="{ '--card-hue': card.hue }"
          @mouseenter="hoveredCard = idx"
          @mouseleave="hoveredCard = null"
        >
          <div class="stat-card-glow" :class="{ active: hoveredCard === idx }" />
          <div class="stat-card-inner">
            <div class="stat-icon-wrap">
              <el-icon :size="24"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-body">
              <div class="stat-value">{{ card.display }}</div>
              <div class="stat-label">{{ card.label }}</div>
              <div class="stat-change" v-if="card.change !== null">
                <span :class="card.change >= 0 ? 'up' : 'down'">
                  {{ card.change >= 0 ? '+' : '' }}{{ card.change }}%
                </span>
                <span class="change-label">较上月</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 图表行 -->
      <div class="charts-grid">
        <div class="glass-card chart-card">
          <div class="chart-header">
            <h3>检测趋势</h3>
            <el-radio-group v-model="trendRange" size="small" @change="loadCharts">
              <el-radio-button value="7d">近7天</el-radio-button>
              <el-radio-button value="30d">近30天</el-radio-button>
            </el-radio-group>
          </div>
          <div ref="trendChartRef" class="chart-container" />
        </div>
        <div class="glass-card chart-card">
          <div class="chart-header">
            <h3>检测类型分布</h3>
          </div>
          <div ref="pieChartRef" class="chart-container" />
        </div>
      </div>

      <!-- 音频检测服务状态 -->
      <div class="charts-grid" style="grid-template-columns: 1fr;">
        <div class="glass-card">
          <div class="chart-header">
            <h3>音频检测服务状态</h3>
            <el-tag :type="serviceHealthy ? 'success' : 'danger'" size="small" effect="dark">
              {{ serviceHealthy ? '运行正常' : '异常' }}
            </el-tag>
          </div>
          <el-table :data="audioStats" class="modern-table" stripe>
            <el-table-column prop="key" label="指标" width="220">
              <template #default="{ row }">
                <div class="table-key">
                  <span class="key-dot" :style="{ background: row.color }" />
                  {{ row.label }}
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="value" label="数值">
              <template #default="{ row }">
                <span v-if="row.type === 'bool'" :class="row.rawValue ? 'status-ok' : 'status-err'">
                  {{ row.rawValue ? '正常' : '离线' }}
                </span>
                <span v-else class="status-value">{{ row.displayValue }}</span>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, markRaw } from 'vue'
import { User, WarningFilled, Reading, Microphone } from '@element-plus/icons-vue'
import { getOverviewStats } from '../api/auth'
import { getTrendStats, getDistributionStats } from '../api/records'
import * as echarts from 'echarts'

const hoveredCard = ref(null)
const audioStats = ref([])
const serviceHealthy = ref(true)
const trendRange = ref('7d')
const trendChartRef = ref(null)
const pieChartRef = ref(null)
const statsLoaded = ref(false)
const showSkeleton = ref(true)

let trendChart = null
let pieChart = null
let animFrames = {}

const statCards = ref([
  { label: '用户总数', value: 0, display: 0, icon: 'User', hue: 230, change: null },
  { label: '检测总数', value: 0, display: 0, icon: 'WarningFilled', hue: 340, change: null },
  { label: '知识条目', value: 0, display: 0, icon: 'Reading', hue: 170, change: null },
  { label: '音频检测', value: 0, display: 0, icon: 'Microphone', hue: 290, change: null },
])

function animateValue(index) {
  const card = statCards.value[index]
  if (!card) return 0
  const from = 0
  const to = card.value
  const duration = 1500
  const start = performance.now()
  if (animFrames[index]) cancelAnimationFrame(animFrames[index])
  function frame(now) {
    const t = Math.min((now - start) / duration, 1)
    const ease = 1 - Math.pow(1 - t, 3)
    card.display = Math.round(from + (to - from) * ease)
    if (t < 1) animFrames[index] = requestAnimationFrame(frame)
    else card.display = to
  }
  animFrames[index] = requestAnimationFrame(frame)
}

onMounted(async () => {
  // 图表容器已在 DOM 中，立即初始化图表
  await nextTick()
  initTrendChart()
  initPieChart()

  // 加载统计数据（安全调用 + 200ms 保底：避免骨架屏闪烁）
  await Promise.all([
    loadStats(),
    new Promise(r => setTimeout(r, 200)),
  ])

  // 数据就绪，隐藏骨架
  statsLoaded.value = true
  showSkeleton.value = false
  window.addEventListener('refresh-page', refreshHandler)
})

onUnmounted(() => {
  window.removeEventListener('refresh-page', refreshHandler)
  Object.values(animFrames).forEach(f => cancelAnimationFrame(f))
  trendChart?.dispose()
  pieChart?.dispose()
})

function refreshHandler() { loadStats(); loadCharts() }

async function loadStats() {
  let data
  try { data = await getOverviewStats() } catch (e) { return }
  const newCards = [
    { label: '用户总数', value: data.userCount ?? 0, display: 0, icon: 'User', hue: 230, change: data.userChange ?? null },
    { label: '检测总数', value: data.detectionCount ?? 0, display: 0, icon: 'WarningFilled', hue: 340, change: data.detectionChange ?? null },
    { label: '知识条目', value: data.knowledgeCount ?? 0, display: 0, icon: 'Reading', hue: 170, change: data.knowledgeChange ?? null },
    { label: '音频检测', value: data.audioRecordCount ?? 0, display: 0, icon: 'Microphone', hue: 290, change: data.audioChange ?? null },
  ]
  statCards.value = newCards
  newCards.forEach((_, i) => animateValue(i))
  if (data.audioStats) {
    const labels = {
      totalDetections: '总检测数',
      spoofCount: '伪造检测数',
      bonafideCount: '真实检测数',
      serviceStatus: '服务状态',
      modelLoaded: '模型加载',
    }
    const colors = ['#0ea5e9', '#ef4444', '#22c55e', '#06b6d4', '#f59e0b']
    audioStats.value = Object.entries(data.audioStats).map(([k, v], i) => ({
      key: k,
      label: labels[k] || k,
      rawValue: v,
      displayValue: typeof v === 'boolean' ? (v ? '正常' : '离线') : String(v ?? '-'),
      type: typeof v === 'boolean' ? 'bool' : 'string',
      color: colors[i % colors.length],
    }))
    const statusEntry = audioStats.value.find(s => s.key === 'serviceStatus')
    if (statusEntry) serviceHealthy.value = statusEntry.rawValue === true
  }
}

async function loadCharts() {
  await nextTick()
  initTrendChart()
  initPieChart()
}

function initTrendChart() {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = markRaw(echarts.init(trendChartRef.value, null, { renderer: 'canvas' }))

  const is7d = trendRange.value === '7d'
  const days = is7d ? 7 : 30

  // 先用占位结构渲染（空数据），随后异步填充
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['可疑检测', '安全检测'], textStyle: { color: '#94a3b8' }, bottom: 0 },
    grid: { top: 20, right: 20, bottom: 40, left: 50 },
    xAxis: { type: 'category', data: [], axisLabel: { color: '#94a3b8', fontSize: 11 }, axisLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)', type: 'dashed' } }, axisLabel: { color: '#94a3b8', fontSize: 11 } },
    series: [
      { name: '可疑检测', type: 'bar', data: [], itemStyle: { borderRadius: [4, 4, 0, 0], color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#ef4444' }, { offset: 1, color: 'rgba(239,68,68,0.15)' }]) }, barWidth: '30%' },
      { name: '安全检测', type: 'bar', data: [], itemStyle: { borderRadius: [4, 4, 0, 0], color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#22c55e' }, { offset: 1, color: 'rgba(34,197,94,0.15)' }]) }, barWidth: '30%' },
    ],
  })
  trendChart.resize()

  getTrendStats(days).then(data => {
    const dates = data?.dates || []
    const suspicious = data?.suspicious || []
    const safe = data?.safe || []
    trendChart.setOption({
      xAxis: { data: dates },
      series: [
        { name: '可疑检测', data: suspicious },
        { name: '安全检测', data: safe },
      ],
    })
  }).catch(() => { /* 静默失败，图表保持空状态 */ })
}

function initPieChart() {
  if (!pieChartRef.value) return
  if (!pieChart) pieChart = markRaw(echarts.init(pieChartRef.value, null, { renderer: 'canvas' }))

  pieChart.setOption({
    tooltip: { trigger: 'item' },
    series: [{
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '45%'],
      avoidLabelOverlap: true,
      itemStyle: { borderRadius: 6, borderColor: 'transparent', borderWidth: 3 },
      label: { show: true, color: '#94a3b8', fontSize: 12 },
      data: [],
    }],
  })
  pieChart.resize()

  getDistributionStats().then(data => {
    // data.byResult 形如 [{result:'safe',count:10,percentage:50.0}, ...]
    const rows = data?.byResult || []
    const colorMap = {
      safe: '#22c55e',
      suspicious: '#f59e0b',
      dangerous: '#ef4444',
      failed: '#94a3b8',
      unknown: '#06b6d4',
    }
    const nameMap = {
      safe: '安全',
      suspicious: '可疑',
      dangerous: '危险',
      failed: '失败',
      unknown: '未知',
    }
    const pieData = rows.map(r => ({
      name: nameMap[r.result] || r.result,
      value: r.count || 0,
      itemStyle: { color: colorMap[r.result] || '#0ea5e9' },
    }))
    if (pieData.length === 0) {
      pieData.push({ name: '暂无数据', value: 0, itemStyle: { color: '#cbd5e1' } })
    }
    pieChart.setOption({ series: [{ data: pieData }] })
  }).catch(() => { /* 静默失败 */ })
}
</script>

<style scoped>
.dashboard { max-width: 1400px; position: relative; }

/* ===== 骨架屏遮罩 ===== */
.skeleton-overlay {
  position: absolute; inset: 0; z-index: 10;
  pointer-events: none;
}
.skeleton-stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 24px;
}
.skeleton-charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}
.skeleton-card {
  position: relative; overflow: hidden;
  background: var(--card-bg, rgba(255,255,255,0.7));
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 20px; padding: 24px;
}
.skeleton-chart {
  position: relative; overflow: hidden;
  background: var(--card-bg, rgba(255,255,255,0.7));
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 20px; padding: 24px; height: 380px;
}
.skeleton-shimmer {
  position: absolute; inset: 0;
  background: linear-gradient(90deg, transparent, rgba(14,165,233,0.04), transparent);
  animation: shimmer 1.5s ease-in-out infinite;
}
@keyframes shimmer {
  0% { transform: translateX(-100%); }
  100% { transform: translateX(100%); }
}
.skeleton-row { display: flex; align-items: center; gap: 16px; }
.skeleton-icon {
  width: 48px; height: 48px; border-radius: 16px;
  background: rgba(14,165,233,0.08); flex-shrink: 0;
}
.skeleton-lines { flex: 1; display: flex; flex-direction: column; gap: 8px; }
.skeleton-line { height: 14px; border-radius: 7px; background: rgba(14,165,233,0.06); }
.skeleton-line.w-20 { width: 60%; }
.skeleton-line.w-12 { width: 40%; }
.skeleton-line.w-24 { width: 80px; height: 12px; }
.skeleton-block { width: 100%; height: 240px; border-radius: 12px; background: rgba(14,165,233,0.04); }

/* ===== 真实内容 ===== */
.dashboard-content {
  opacity: 0.3;
  transition: opacity 0.4s ease;
}
.dashboard-content.content-ready {
  opacity: 1;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 20px;
  margin-bottom: 24px;
}

.stat-card {
  position: relative;
  border-radius: 20px;
  overflow: hidden;
  background: var(--card-bg, rgba(255,255,255,0.7));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  cursor: default;
  transition: transform 0.3s cubic-bezier(0.4, 0, 0.2, 1), box-shadow 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}
.stat-card:hover {
  transform: translateY(-4px);
  box-shadow: var(--shadow-lg, 0 12px 48px rgba(0,0,0,0.1));
}

.stat-card-glow {
  position: absolute;
  top: -60%;
  right: -40%;
  width: 100%;
  height: 100%;
  background: radial-gradient(circle, hsla(var(--card-hue, 230), 80%, 65%, 0.12), transparent 70%);
  opacity: 0;
  transition: opacity 0.5s ease;
  pointer-events: none;
}
.stat-card-glow.active { opacity: 1; }

.stat-card-inner {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 24px;
  position: relative;
  z-index: 1;
}

.stat-icon-wrap {
  width: 48px;
  height: 48px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: hsla(var(--card-hue, 230), 80%, 60%, 0.12);
  color: hsla(var(--card-hue, 230), 80%, 55%, 1);
  flex-shrink: 0;
}

.stat-body { flex: 1; min-width: 0; }
.stat-value {
  font-size: 30px;
  font-weight: 800;
  color: var(--text-primary, #0f172a);
  letter-spacing: -1px;
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: var(--text-secondary, #64748b);
  margin-top: 4px;
}
.stat-change { margin-top: 8px; font-size: 12px; }
.stat-change .up { color: #22c55e; font-weight: 600; }
.stat-change .down { color: #ef4444; font-weight: 600; }
.change-label { color: var(--text-muted, #94a3b8); margin-left: 4px; }

.charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-bottom: 24px;
}
.charts-grid:last-child { grid-template-columns: 1fr; }

.glass-card {
  background: var(--card-bg, rgba(255,255,255,0.7));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 20px;
  padding: 24px;
  overflow: hidden;
  transition: box-shadow 0.3s ease, transform 0.3s ease;
}
.glass-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md, 0 4px 20px rgba(0,0,0,0.06));
}

.chart-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.chart-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #0f172a);
}
.chart-container { width: 100%; height: 300px; }

/* Table overrides */
.modern-table :deep(.el-table) {
  background: transparent !important;
  --el-table-border-color: transparent !important;
}
.modern-table :deep(.el-table th) {
  background: rgba(14,165,233,0.04) !important;
  color: var(--text-secondary, #64748b) !important;
  font-weight: 500 !important;
  font-size: 12px !important;
  border-bottom: none !important;
}
.modern-table :deep(.el-table tr) { background: transparent !important; }
.modern-table :deep(.el-table td) {
  border-bottom: 1px solid rgba(255,255,255,0.04) !important;
  color: var(--text-primary, #0f172a) !important;
}
.modern-table :deep(.el-table--striped .el-table__body tr.el-table__row--striped td) {
  background: rgba(14,165,233,0.02) !important;
}

.table-key { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.key-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-ok { color: #22c55e; font-weight: 600; font-size: 13px; }
.status-err { color: #ef4444; font-weight: 600; font-size: 13px; }
.status-value { font-size: 13px; }

@media (max-width: 1200px) {
  .stats-grid, .skeleton-stats-grid { grid-template-columns: repeat(2, 1fr); }
  .charts-grid, .skeleton-charts-grid { grid-template-columns: 1fr; }
}
@media (max-width: 768px) {
  .stats-grid, .skeleton-stats-grid { grid-template-columns: 1fr; }
}
</style>
