<template>
  <div class="page">
    <!-- 统计行 -->
    <div class="mini-stats">
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ users.length }}</span>
        <span class="mini-stat-label">用户总数</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ adminCount }}</span>
        <span class="mini-stat-label">管理员</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ users.length - adminCount }}</span>
        <span class="mini-stat-label">普通用户</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ recentCount }}</span>
        <span class="mini-stat-label">近7天新增</span>
      </div>
    </div>

    <!-- 角色分布 + 活跃趋势 -->
    <div class="viz-row">
      <div class="glass-card viz-card">
        <h4>角色分布</h4>
        <ChartCard :option="roleChartOption" :height="220" />
      </div>
      <div class="glass-card viz-card">
        <h4>近7天新增</h4>
        <ChartCard :option="activityChartOption" :height="220" />
      </div>
    </div>

    <div class="glass-card">
      <div class="page-header">
        <h3>用户列表</h3>
        <el-input
          v-model="search"
          placeholder="搜索昵称..."
          :prefix-icon="Search"
          size="small"
          class="search-input"
          clearable
        />
      </div>
      <el-table :data="filteredUsers" v-loading="loading" class="modern-table" stripe>
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="OpenID" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="openid-cell">{{ maskOpenid(row.openid) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="150">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="28" class="cell-avatar">{{ (row.nickname || '?').charAt(0) }}</el-avatar>
              <span>{{ row.nickname || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="role" label="角色" width="100">
          <template #default="{ row }">
            <el-tag
              :type="row.role === 'admin' ? 'danger' : 'info'"
              size="small"
              effect="light"
              class="role-tag"
            >
              {{ row.role === 'admin' ? '管理员' : row.role || '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginAt" label="最后登录" width="180" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { getUsers, getUserDailyStats } from '../api/records'
import ChartCard from '../components/ChartCard.vue'
import * as echarts from 'echarts'

const users = ref([])
const loading = ref(false)
const search = ref('')
const dailyDates = ref([])
const dailyCounts = ref([])

const adminCount = computed(() => users.value.filter(u => u.role === 'admin').length)
const recentCount = computed(() => {
  if (dailyCounts.value.length > 0) {
    return dailyCounts.value.reduce((sum, n) => sum + n, 0)
  }
  const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000)
  return users.value.filter(u => u.createdAt && new Date(u.createdAt) >= weekAgo).length
})
const roleChartOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'pie', radius: ['45%', '70%'], avoidLabelOverlap: true,
    label: { show: true, color: '#64748b', fontSize: 12 },
    data: [
      { value: adminCount.value, name: '管理员', itemStyle: { color: '#0ea5e9' } },
      { value: users.value.length - adminCount.value, name: '普通用户', itemStyle: { color: '#22d3ee' } },
    ],
  }],
}))
const activityChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { top: 10, right: 10, bottom: 24, left: 36 },
  xAxis: { type: 'category', data: dailyDates.value, axisLabel: { color: '#94a3b8', fontSize: 10 } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)' } }, axisLabel: { color: '#94a3b8', fontSize: 10 } },
  series: [{
    type: 'bar',
    data: dailyCounts.value,
    barWidth: '40%',
    itemStyle: {
      borderRadius: [4, 4, 0, 0],
      color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: '#0ea5e9' }, { offset: 1, color: 'rgba(14,165,233,0.15)' }]),
    },
  }],
}))

function maskOpenid(openid) {
  if (!openid) return '-'
  if (openid.length <= 8) return openid
  return openid.slice(0, 4) + '…' + openid.slice(-4)
}

const filteredUsers = computed(() => {
  if (!search.value) return users.value
  const q = search.value.toLowerCase()
  return users.value.filter(u =>
    (u.nickname && u.nickname.toLowerCase().includes(q))
  )
})

onMounted(async () => {
  loading.value = true
  try {
    const [userList, daily] = await Promise.allSettled([getUsers(), getUserDailyStats(7)])
    if (userList.status === 'fulfilled') users.value = userList.value || []
    if (daily.status === 'fulfilled') {
      dailyDates.value = daily.value?.dates || []
      dailyCounts.value = daily.value?.counts || []
    }
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
.search-input { width: 240px; }
.search-input :deep(.el-input__wrapper) { border-radius: 10px; }

.user-cell { display: flex; align-items: center; gap: 10px; }
.cell-avatar { background: linear-gradient(135deg, #0ea5e9, #06b6d4); color: #fff; font-weight: 600; flex-shrink: 0; }
.role-tag { border-radius: 6px; font-weight: 500; }
.openid-cell { font-family: 'SF Mono', Consolas, monospace; font-size: 12px; color: var(--text-secondary, #64748b); letter-spacing: 0.5px; }

/* ===== 可视化 ===== */
.viz-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; animation: pageIn 0.4s ease; }
.viz-card h4 { font-size: 14px; font-weight: 600; color: var(--text-primary, #0f172a); margin-bottom: 16px; }
.donut-wrap { display: flex; align-items: center; gap: 24px; }
.donut { width: 100px; height: 100px; border-radius: 50%; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.donut-hole { width: 70px; height: 70px; border-radius: 50%; background: #fff; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.donut-value { font-size: 20px; font-weight: 800; color: var(--text-primary, #0f172a); line-height: 1; }
.donut-label { font-size: 10px; color: var(--text-muted, #94a3b8); margin-top: 2px; }
.donut-legend { display: flex; flex-direction: column; gap: 8px; }
.legend-item { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--text-secondary, #64748b); }
.legend-item .dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.bar-chart { display: flex; flex-direction: column; gap: 8px; }
.bar-item { display: flex; align-items: center; gap: 8px; }
.bar-label { width: 32px; font-size: 11px; color: var(--text-muted, #94a3b8); text-align: right; flex-shrink: 0; }
.bar-track { flex: 1; height: 8px; border-radius: 4px; background: rgba(14,165,233,0.06); overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; background: linear-gradient(90deg, #0ea5e9, #22d3ee); transition: width 0.6s ease; }
.bar-val { width: 24px; font-size: 11px; color: var(--text-secondary, #64748b); text-align: right; flex-shrink: 0; }
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
