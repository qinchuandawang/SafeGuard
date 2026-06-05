<template>
  <div class="page">
    <!-- 统计行 -->
    <div class="mini-stats">
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ records.length }}</span>
        <span class="mini-stat-label">检测总数</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ spoofCount }}</span>
        <span class="mini-stat-label">伪造检测</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ highRiskCount }}</span>
        <span class="mini-stat-label">高风险</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ spoofRate }}%</span>
        <span class="mini-stat-label">伪造率</span>
      </div>
    </div>

    <!-- 检测结果 + 风险等级 -->
    <div class="viz-row">
      <div class="glass-card viz-card">
        <h4>检测结果分布</h4>
        <ChartCard :option="resultChartOption" :height="220" />
      </div>
      <div class="glass-card viz-card">
        <h4>风险等级</h4>
        <ChartCard :option="riskChartOption" :height="220" />
      </div>
    </div>

    <div class="glass-card">
      <div class="page-header">
        <h3>音频检测记录</h3>
        <div class="header-actions">
          <el-select v-model="riskFilter" placeholder="风险等级" size="small" clearable style="width:130px">
            <el-option label="高风险" value="high" />
            <el-option label="中风险" value="medium" />
            <el-option label="低风险" value="low" />
          </el-select>
          <el-input
            v-model="search"
            placeholder="搜索文件名..."
            :prefix-icon="Search"
            size="small"
            class="search-input"
            clearable
          />
        </div>
      </div>
      <el-table
        :data="filteredRecords"
        v-loading="loading"
        class="modern-table"
        stripe
        max-height="600"
        :default-sort="{ prop: 'createdAt', order: 'descending' }"
      >
        <el-table-column prop="id" label="ID" width="60" sortable />
        <el-table-column prop="fileName" label="文件名" min-width="160" show-overflow-tooltip />
        <el-table-column prop="detectionResult" label="检测结果" width="110">
          <template #default="{ row }">
            <el-tag
              :type="row.detectionResult === 'spoof' ? 'danger' : 'success'"
              size="small"
              class="result-tag"
            >
              <el-icon style="margin-right:3px;vertical-align:-2px">
                <component :is="row.detectionResult === 'spoof' ? 'WarningFilled' : 'CircleCheck' " />
              </el-icon>
              {{ row.detectionResult === 'spoof' ? '伪造' : '真实' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="spoofProbability" label="伪造概率" width="120" sortable>
          <template #default="{ row }">
            <div class="prob-bar-wrap">
              <el-progress
                :percentage="Math.round((row.spoofProbability || 0) * 100)"
                :color="spoofColor(row.spoofProbability)"
                :stroke-width="8"
                :show-text="false"
                class="prob-bar"
              />
              <span class="prob-text">{{ row.spoofProbability ? (row.spoofProbability * 100).toFixed(1) + '%' : '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="riskLevel" label="风险等级" width="110" sortable>
          <template #default="{ row }">
            <div class="risk-badge" :class="'risk-' + (row.riskLevel || 'low')">
              <span class="risk-dot" />
              {{ riskLabel(row.riskLevel) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80" />
        <el-table-column prop="createdAt" label="检测时间" width="175" sortable />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search, WarningFilled, CircleCheck } from '@element-plus/icons-vue'
import { getAudioRecords } from '../api/records'
import ChartCard from '../components/ChartCard.vue'

const records = ref([])
const loading = ref(false)
const search = ref('')
const riskFilter = ref('')

const spoofCount = computed(() => records.value.filter(r => r.detectionResult === 'spoof').length)
const highRiskCount = computed(() => records.value.filter(r => r.riskLevel === 'high').length)
const mediumRiskCount = computed(() => records.value.filter(r => r.riskLevel === 'medium').length)
const lowRiskCount = computed(() => records.value.filter(r => r.riskLevel === 'low').length)
const spoofRate = computed(() => {
  if (!records.value.length) return 0
  return Math.round((spoofCount.value / records.value.length) * 100)
})
const resultChartOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'pie', radius: ['45%', '70%'], avoidLabelOverlap: true,
    label: { show: true, color: '#64748b', fontSize: 12 },
    data: [
      { value: records.value.length - spoofCount.value, name: '真实', itemStyle: { color: '#22c55e' } },
      { value: spoofCount.value, name: '伪造', itemStyle: { color: '#ef4444' } },
    ],
  }],
}))
const riskChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { top: 10, right: 10, bottom: 24, left: 60 },
  xAxis: { type: 'value', axisLabel: { color: '#94a3b8', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)' } } },
  yAxis: { type: 'category', data: ['低风险', '中风险', '高风险'], axisLabel: { color: '#64748b', fontSize: 11 } },
  series: [{
    type: 'bar', data: [lowRiskCount.value, mediumRiskCount.value, highRiskCount.value],
    barWidth: '55%', itemStyle: { borderRadius: [0, 4, 4, 0] },
    color: ['#22c55e', '#f59e0b', '#ef4444'],
  }],
}))

const filteredRecords = computed(() => {
  let r = records.value
  if (riskFilter.value) r = r.filter(x => x.riskLevel === riskFilter.value)
  if (search.value) {
    const q = search.value.toLowerCase()
    r = r.filter(x => x.fileName && x.fileName.toLowerCase().includes(q))
  }
  return r
})

function spoofColor(prob) {
  if (!prob) return '#22c55e'
  if (prob > 0.7) return '#ef4444'
  if (prob > 0.4) return '#f59e0b'
  return '#22c55e'
}

function riskLabel(level) {
  const m = { high: '高风险', medium: '中风险', low: '低风险' }
  return m[level] || level || '-'
}

onMounted(async () => {
  loading.value = true
  try {
    records.value = (await getAudioRecords()) || []
  } catch (e) { console.error(e) }
  finally { loading.value = false }
})
</script>

<style scoped>
.page { max-width: 1400px; }
.glass-card {
  background: var(--card-bg, rgba(255,255,255,0.7));
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 20px;
  padding: 24px;
  animation: pageIn 0.4s ease;
}
@keyframes pageIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-header h3 { font-size: 16px; font-weight: 600; color: var(--text-primary, #1a1a2e); }
.header-actions { display: flex; gap: 12px; align-items: center; }
.search-input { width: 200px; }
.search-input :deep(.el-input__wrapper) { border-radius: 10px; }

.result-tag { border-radius: 6px; font-weight: 500; border: none; }

.prob-bar-wrap { display: flex; align-items: center; gap: 8px; }
.prob-bar { flex: 1; }
.prob-text { font-size: 12px; color: var(--text-secondary, #6b7280); white-space: nowrap; min-width: 48px; text-align: right; }

.risk-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 12px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
}
.risk-dot { width: 6px; height: 6px; border-radius: 50%; }
.risk-high { background: rgba(239,68,68,0.1); color: #ef4444; }
.risk-high .risk-dot { background: #ef4444; }
.risk-medium { background: rgba(245,158,11,0.1); color: #f59e0b; }
.risk-medium .risk-dot { background: #f59e0b; }
.risk-low { background: rgba(34,197,94,0.1); color: #22c55e; }
.risk-low .risk-dot { background: #22c55e; }

/* Vizzes */
.viz-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; animation: pageIn 0.4s ease; }
.viz-card h4 { font-size: 14px; font-weight: 600; color: var(--text-primary, #0f172a); margin-bottom: 16px; }
.stacked-bars { display: flex; flex-direction: column; gap: 10px; }
.stacked-bar-row { display: flex; align-items: center; gap: 8px; }
.stacked-label { width: 56px; font-size: 12px; color: var(--text-secondary, #64748b); flex-shrink: 0; }
.stacked-track { flex: 1; height: 10px; border-radius: 5px; background: rgba(14,165,233,0.06); overflow: hidden; }
.stacked-fill { height: 100%; border-radius: 5px; transition: width 0.6s ease; }
.stacked-val { width: 24px; font-size: 11px; color: var(--text-muted, #94a3b8); text-align: right; flex-shrink: 0; }
.donut-wrap { display: flex; align-items: center; gap: 24px; }
.donut { width: 100px; height: 100px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.donut-hole { width: 70px; height: 70px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.donut-value { font-size: 20px; font-weight: 800; color: var(--text-primary, #0f172a); line-height: 1; }
.donut-label { font-size: 10px; color: var(--text-muted, #94a3b8); margin-top: 2px; }
.donut-legend { display: flex; flex-direction: column; gap: 8px; }
.legend-item { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-secondary, #64748b); }
.legend-item .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
@media (max-width: 900px) { .viz-row { grid-template-columns: 1fr; } }

/* Mini stats row */
.mini-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
  animation: pageIn 0.4s ease;
}
.mini-stat-item {
  background: var(--card-bg, rgba(255,255,255,0.7));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 16px;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  transition: transform 0.2s ease, box-shadow 0.2s ease;
}
.mini-stat-item:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.mini-stat-value { font-size: 28px; font-weight: 800; color: var(--text-primary, #0f172a); letter-spacing: -1px; }
.mini-stat-label { font-size: 12px; color: var(--text-secondary, #64748b); }
@media (max-width: 768px) { .mini-stats { grid-template-columns: repeat(2, 1fr); } }

.modern-table :deep(.el-table) {
  background: transparent !important;
  --el-table-border-color: transparent !important;
}
.modern-table :deep(.el-table th) {
  background: rgba(14,165,233,0.04) !important;
  color: var(--text-secondary, #64748b) !important;
  font-weight: 500 !important;
  font-size: 12px !important;
}
.modern-table :deep(.el-table tr) { background: transparent !important; }
.modern-table :deep(.el-table td) {
  border-bottom: 1px solid rgba(255,255,255,0.04) !important;
  color: var(--text-primary, #1a1a2e) !important;
}
.modern-table :deep(.el-table--striped .el-table__body tr.el-table__row--striped td) {
  background: rgba(14,165,233,0.02) !important;
}
</style>
