<template>
  <div class="page">
    <!-- 统计行 -->
    <div class="mini-stats">
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ models.length }}</span>
        <span class="mini-stat-label">模型总数</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ activeCount }}</span>
        <span class="mini-stat-label">活跃模型</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ bestAccuracy }}</span>
        <span class="mini-stat-label">最高准确率</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ modelTypes }}</span>
        <span class="mini-stat-label">模型类型数</span>
      </div>
    </div>

    <!-- 模型类型 + 准确率 -->
    <div class="viz-row">
      <div class="glass-card viz-card">
        <h4>模型类型分布</h4>
        <ChartCard :option="typeChartOption" :height="220" />
      </div>
      <div class="glass-card viz-card">
        <h4>模型准确率</h4>
        <ChartCard :option="accChartOption" :height="220" />
      </div>
    </div>

    <!-- 当前活跃模型 -->
    <div class="glass-card" style="margin-bottom:24px">
      <div class="page-header">
        <h3>当前活跃模型</h3>
        <el-tag type="success" effect="dark" size="small" v-if="activeModel">运行中</el-tag>
      </div>
      <div v-if="activeModel" class="model-active-card">
        <div class="model-header">
          <div class="model-icon-wrap">
            <el-icon :size="28"><Cpu /></el-icon>
          </div>
          <div class="model-info">
            <h4>{{ activeModel.name }}</h4>
            <span class="model-version">v{{ activeModel.modelVersion }}</span>
          </div>
        </div>
        <el-descriptions :column="3" border class="model-descs">
          <el-descriptions-item label="类型" label-class-name="desc-label">
            <el-tag size="small">{{ activeModel.modelType }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="准确率">
            <span class="metric-value good">{{ activeModel.accuracy }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="EER">
            <span class="metric-value warn">{{ activeModel.eer }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="训练数据集" :span="3">
            <span class="dataset-text">{{ activeModel.trainingDataset }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <el-empty v-else description="暂无活跃模型" :image-size="80" />
    </div>

    <!-- 所有模型 -->
    <div class="glass-card">
      <div class="page-header">
        <h3>所有模型</h3>
      </div>
      <el-table :data="models" v-loading="loading" class="modern-table" stripe>
        <el-table-column prop="id" label="ID" width="60" sortable />
        <el-table-column prop="name" label="名称" min-width="180" />
        <el-table-column prop="modelVersion" label="版本" width="110" />
        <el-table-column prop="modelType" label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ row.modelType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="accuracy" label="准确率" width="100">
          <template #default="{ row }">
            <span class="metric-value good">{{ row.accuracy }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="eer" label="EER" width="100">
          <template #default="{ row }">
            <span class="metric-value warn">{{ row.eer }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.isActive ? 'success' : 'info'" size="small" effect="light" class="status-tag">
              {{ row.isActive ? '活跃' : '非活跃' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="trainingDataset" label="数据集" min-width="180" show-overflow-tooltip />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Cpu } from '@element-plus/icons-vue'
import { getManagedModels } from '../api/models'
import ChartCard from '../components/ChartCard.vue'
import * as echarts from 'echarts'

const activeModel = ref(null)
const models = ref([])
const loading = ref(false)

const activeCount = computed(() => models.value.filter(m => m.isActive).length)

const typeColors = ['#0ea5e9', '#06b6d4', '#14b8a6', '#22d3ee']
const typeStats = computed(() => {
  const map = {}; models.value.forEach(m => { if (m.modelType) map[m.modelType] = (map[m.modelType] || 0) + 1 })
  return Object.entries(map).map(([name, count], i) => ({ name, count, color: typeColors[i % typeColors.length] }))
})
const typeChartOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'pie', radius: ['45%', '70%'], avoidLabelOverlap: true,
    label: { show: true, color: '#64748b', fontSize: 12 },
    data: typeStats.value.map(t => ({ value: t.count, name: t.name, itemStyle: { color: t.color } })),
  }],
}))
const accChartOption = computed(() => {
  const list = models.value.slice(0, 5).map(m => {
    const raw = parseFloat(m.accuracy) || 0
    return { name: m.name || '模型', pct: raw <= 1 ? Math.round(raw * 100) : Math.min(raw, 100) }
  })
  return {
    tooltip: { trigger: 'axis' },
    grid: { top: 10, right: 40, bottom: 24, left: 60 },
    xAxis: { type: 'value', max: 100, axisLabel: { color: '#94a3b8', fontSize: 10, formatter: '{value}%' }, splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)' } } },
    yAxis: { type: 'category', data: list.map(m => m.name).reverse(), axisLabel: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'bar', data: list.map(m => m.pct).reverse(), barWidth: '55%',
      itemStyle: { borderRadius: [0, 4, 4, 0], color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [{ offset: 0, color: '#0ea5e9' }, { offset: 1, color: '#06b6d4' }]) },
      label: { show: true, position: 'right', color: '#64748b', fontSize: 11, formatter: '{c}%' },
    }],
  }
})
const bestAccuracy = computed(() => {
  if (!models.value.length) return '-'
  const best = Math.max(...models.value.map(m => {
    const v = parseFloat(m.accuracy) || 0
    return v <= 1 ? v * 100 : v
  }))
  return Math.round(best) + '%'
})
const modelTypes = computed(() => {
  const types = new Set(models.value.map(m => m.modelType).filter(Boolean))
  return types.size
})

onMounted(async () => {
  loading.value = true
  try {
    const data = await getManagedModels()
    activeModel.value = data?.activeModel || null
    models.value = data?.models || []
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

.model-active-card { margin-bottom: 8px; }
.model-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
  padding: 16px 20px;
  background: linear-gradient(135deg, rgba(14,165,233,0.06), rgba(6,182,212,0.04));
  border-radius: 14px;
  border: 1px solid rgba(14,165,233,0.08);
}
.model-icon-wrap {
  width: 56px; height: 56px;
  border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #0ea5e9, #06b6d4);
  color: #fff;
  flex-shrink: 0;
}
.model-info { flex: 1; }
.model-info h4 { font-size: 18px; font-weight: 700; color: var(--text-primary, #1a1a2e); margin-bottom: 4px; }
.model-version { font-size: 12px; color: var(--text-muted, #9ca3af); }

.model-descs :deep(.el-descriptions__cell) { padding: 12px 16px !important; }
.model-descs :deep(.desc-label) { background: rgba(14,165,233,0.04); }

.metric-value { font-weight: 600; font-size: 14px; }
.metric-value.good { color: #22c55e; }
.metric-value.warn { color: #f59e0b; }
.dataset-text { font-size: 13px; color: var(--text-secondary, #6b7280); }
.status-tag { border-radius: 6px; }

/* Vizzes */
.viz-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; animation: pageIn 0.4s ease; }
.viz-card h4 { font-size: 14px; font-weight: 600; color: var(--text-primary, #0f172a); margin-bottom: 16px; }
.stacked-bars { display: flex; flex-direction: column; gap: 10px; }
.stacked-bar-row { display: flex; align-items: center; gap: 8px; }
.stacked-label { width: 56px; font-size: 12px; color: var(--text-secondary, #64748b); flex-shrink: 0; }
.stacked-track { flex: 1; height: 10px; border-radius: 5px; background: rgba(14,165,233,0.06); overflow: hidden; }
.bar-fill-acc { height: 100%; border-radius: 5px; transition: width 0.6s ease; }
.stacked-val { width: 48px; font-size: 11px; color: var(--text-muted, #94a3b8); text-align: right; flex-shrink: 0; }
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
