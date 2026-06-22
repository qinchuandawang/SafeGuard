<template>
  <div class="page">
    <!-- 统计行 -->
    <div class="mini-stats">
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ items.length }}</span>
        <span class="mini-stat-label">知识条目</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ categoryCount }}</span>
        <span class="mini-stat-label">分类数</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ enabledCount }}</span>
        <span class="mini-stat-label">已启用</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ disabledCount }}</span>
        <span class="mini-stat-label">已禁用</span>
      </div>
    </div>

    <!-- 分类分布图 -->
    <div class="glass-card" style="margin-bottom:20px">
      <h4>知识条目分类分布</h4>
      <ChartCard :option="catChartOption" :height="220" />
    </div>

    <!-- 知识条目列表 -->
    <div class="glass-card">
      <div class="page-header">
        <h3>反诈知识条目</h3>
        <div class="header-actions">
          <el-button type="primary" size="small" @click="openCreate">新增条目</el-button>
          <el-upload
            :show-file-list="false"
            :auto-upload="false"
            accept=".txt,.pdf"
            :on-change="handleImportFile"
          >
            <el-button size="small">导入文档</el-button>
          </el-upload>
          <el-input
            v-model="search"
            placeholder="搜索知识条目..."
            :prefix-icon="Search"
            size="small"
            class="search-input"
            clearable
          />
        </div>
      </div>
      <el-table :data="filteredItems" v-loading="loading" class="modern-table" stripe @cell-click="handleCellClick">
        <el-table-column prop="id" label="ID" width="55" sortable />
        <el-table-column prop="category" label="分类" width="100">
          <template #default="{ row }">
            <el-tag
              :style="{ background: categoryColor(row.category), border: 'none', color: '#fff' }"
              size="small"
            >
              {{ row.category }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="question" label="问题" min-width="200" show-overflow-tooltip />
        <el-table-column prop="answer" label="答案" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="answer-text">{{ row.answer }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="80" sortable>
          <template #default="{ row }">
            <div class="priority-cell">
              <el-input-number
                v-if="editingPriority === row.id"
                v-model="row.priority"
                :min="1"
                :max="10"
                size="small"
                controls-position="right"
                @blur="savePriority(row)"
                @keyup.enter="savePriority(row)"
                style="width:76px"
              />
              <el-tag v-else :type="priorityType(row.priority)" size="small" effect="light">
                {{ row.priority }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled === 1 || row.enabled === true"
              size="small"
              @change="(val) => toggleEnabled(row, val)"
              active-color="#22c55e"
              inactive-color="#dfdfdf"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="editingItem?.id ? '编辑知识条目' : '新增知识条目'" width="680px">
      <el-form :model="form" label-width="72px">
        <el-form-item label="分类">
          <el-input v-model="form.category" placeholder="如：诈骗类型 / AI诈骗 / 防骗技巧" />
        </el-form-item>
        <el-form-item label="问题">
          <el-input v-model="form.question" placeholder="输入知识条目标题或问题" />
        </el-form-item>
        <el-form-item label="答案">
          <el-input v-model="form.answer" type="textarea" :rows="7" placeholder="输入完整答案内容" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="多个标签可用逗号分隔" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveItem">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { createKnowledge, deleteKnowledge, getKnowledgeList, importKnowledgeDocument, updateKnowledge } from '../api/knowledge'
import ChartCard from '../components/ChartCard.vue'
import * as echarts from 'echarts'

const items = ref([])
const loading = ref(false)
const search = ref('')
const editingPriority = ref(null)
const dialogVisible = ref(false)
const saving = ref(false)
const editingItem = ref(null)
const form = ref({
  category: '',
  question: '',
  answer: '',
  tags: '',
  priority: 5,
  enabled: 1,
})

const filteredItems = computed(() => {
  if (!search.value) return items.value
  const q = search.value.toLowerCase()
  return items.value.filter(i =>
    (i.question && i.question.toLowerCase().includes(q)) ||
    (i.answer && i.answer.toLowerCase().includes(q)) ||
    (i.category && i.category.toLowerCase().includes(q)) ||
    (i.tags && i.tags.toLowerCase().includes(q))
  )
})

const categoryCount = computed(() => {
  const cats = new Set(items.value.map(i => i.category).filter(Boolean))
  return cats.size
})

const enabledCount = computed(() => items.value.filter(i => i.enabled === 1 || i.enabled === true).length)
const disabledCount = computed(() => items.value.filter(i => i.enabled === 0 || i.enabled === false).length)

const catColorPalette = ['#0ea5e9', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4', '#8b5cf6']

const categoryStats = computed(() => {
  const map = {}
  items.value.forEach(i => {
    if (i.category) map[i.category] = (map[i.category] || 0) + 1
  })
  return Object.entries(map)
    .map(([name, count], i) => ({ name, count, color: catColorPalette[i % catColorPalette.length] }))
    .sort((a, b) => b.count - a.count)
})

const catChartOption = computed(() => ({
  tooltip: {
    trigger: 'axis',
    axisPointer: { type: 'shadow' },
    formatter: (params) => `${params[0].name}: ${params[0].value} 条`
  },
  grid: { top: 10, right: 20, bottom: 24, left: 70 },
  xAxis: {
    type: 'value',
    axisLabel: { color: '#94a3b8', fontSize: 10 },
    splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)' } }
  },
  yAxis: {
    type: 'category',
    data: categoryStats.value.map(c => c.name).reverse(),
    axisLabel: { color: '#64748b', fontSize: 11 }
  },
  series: [{
    type: 'bar',
    data: categoryStats.value.map(c => ({
      value: c.count,
      itemStyle: {
        borderRadius: [0, 4, 4, 0],
        color: {
          type: 'linear', x: 0, y: 0, x2: 1, y2: 0,
          colorStops: [
            { offset: 0, color: '#0ea5e9' },
            { offset: 1, color: '#06b6d4' }
          ]
        }
      }
    })).reverse(),
    barWidth: '55%',
    label: {
      show: true,
      position: 'right',
      color: '#64748b',
      fontSize: 11
    }
  }]
}))

const catColors = {
  '诈骗类型': '#0ea5e9',
  'AI诈骗': '#22c55e',
  '防骗技巧': '#f59e0b',
  '安全防护': '#ef4444',
  '法律法规': '#8b5cf6',
}

function categoryColor(cat) {
  return catColors[cat] || '#0ea5e9'
}

function priorityType(p) {
  if (p >= 9) return 'danger'
  if (p >= 7) return 'warning'
  return 'info'
}

function handleCellClick(row, column) {
  if (column.property === 'priority') {
    editingPriority.value = row.id
  }
}

async function savePriority(row) {
  const id = editingPriority.value
  editingPriority.value = null
  if (!id) return
  try {
    await updateKnowledge(id, { priority: row.priority })
    ElMessage.success('优先级已更新')
  } catch (e) {
    ElMessage.error('更新失败')
  }
}

async function toggleEnabled(row, val) {
  const newVal = val ? 1 : 0
  try {
    await updateKnowledge(row.id, { ...row, enabled: newVal })
    row.enabled = newVal
    ElMessage.success(val ? '已启用' : '已禁用')
  } catch (e) {
    ElMessage.error('更新失败')
    row.enabled = val ? 0 : 1
  }
}

function openCreate() {
  editingItem.value = null
  form.value = { category: '诈骗类型', question: '', answer: '', tags: '', priority: 5, enabled: 1 }
  dialogVisible.value = true
}

function openEdit(row) {
  editingItem.value = row
  form.value = {
    category: row.category || '',
    question: row.question || '',
    answer: row.answer || '',
    tags: row.tags || '',
    priority: row.priority || 5,
    enabled: row.enabled === false ? 0 : (row.enabled ?? 1),
  }
  dialogVisible.value = true
}

async function saveItem() {
  if (!form.value.question || !form.value.answer) {
    ElMessage.warning('请填写问题和答案')
    return
  }
  saving.value = true
  try {
    if (editingItem.value?.id) {
      await updateKnowledge(editingItem.value.id, form.value)
      ElMessage.success('知识条目已更新')
    } else {
      await createKnowledge(form.value)
      ElMessage.success('知识条目已新增')
    }
    dialogVisible.value = false
    await loadItems()
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    saving.value = false
  }
}

async function removeItem(row) {
  try {
    await ElMessageBox.confirm(`确定删除知识条目「${row.question || row.id}」吗？`, '删除确认', { type: 'warning' })
    await deleteKnowledge(row.id)
    ElMessage.success('已删除')
    await loadItems()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function handleImportFile(file) {
  const rawFile = file?.raw
  if (!rawFile) return
  const ext = rawFile.name.split('.').pop()?.toLowerCase()
  if (!['txt', 'pdf'].includes(ext)) {
    ElMessage.warning('仅支持 txt 或 pdf 文件')
    return
  }
  const formData = new FormData()
  formData.append('file', rawFile)
  formData.append('category', '知识文档')
  formData.append('tags', `文档导入,${ext}`)
  formData.append('priority', '6')
  formData.append('enabled', 'true')
  try {
    await importKnowledgeDocument(formData)
    ElMessage.success('文档已导入知识库')
    await loadItems()
  } catch (e) {
    ElMessage.error('文档导入失败')
  }
}

async function loadItems() {
  loading.value = true
  try {
    const itemsData = await getKnowledgeList()
    items.value = itemsData || []
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await loadItems()
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
@keyframes pageIn {
  from { opacity: 0; transform: translateY(12px); }
  to { opacity: 1; transform: translateY(0); }
}
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}
.page-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  margin: 0;
}
.header-actions { display: flex; gap: 12px; align-items: center; }
.glass-card h4 {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  margin: 0 0 16px 0;
}
.search-input { width: 240px; }
.search-input :deep(.el-input__wrapper) { border-radius: 10px; }
.answer-text { font-size: 13px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.status-tag { border-radius: 6px; }
.priority-cell { min-height: 32px; display: flex; align-items: center; cursor: pointer; }

/* Mini stats */
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
.mini-stat-item:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-md);
}
.mini-stat-value {
  font-size: 28px;
  font-weight: 800;
  color: var(--text-primary, #0f172a);
  letter-spacing: -1px;
}
.mini-stat-label {
  font-size: 12px;
  color: var(--text-secondary, #64748b);
}

/* Table */
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
  color: var(--text-primary, #0f172a) !important;
}
.modern-table :deep(.el-table--striped .el-table__body tr.el-table__row--striped td) {
  background: rgba(14,165,233,0.02) !important;
}
@media (max-width: 768px) { .mini-stats { grid-template-columns: repeat(2, 1fr); } }
</style>
