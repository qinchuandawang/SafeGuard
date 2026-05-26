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
        <span class="mini-stat-value">{{ documents.length }}</span>
        <span class="mini-stat-label">已上传文档</span>
      </div>
      <div class="mini-stat-item">
        <span class="mini-stat-value">{{ enabledCount }}</span>
        <span class="mini-stat-label">已启用条目</span>
      </div>
    </div>

    <!-- 分类分布 + 文档状态 -->
    <div class="viz-row">
      <div class="glass-card viz-card">
        <h4>知识条目分类</h4>
        <ChartCard :option="catChartOption" :height="220" />
      </div>
      <div class="glass-card viz-card">
        <h4>文档状态</h4>
        <ChartCard :option="docChartOption" :height="220" />
      </div>
    </div>

    <!-- PDF 上传 RAG -->
    <div class="glass-card" style="margin-bottom:20px">
      <div class="page-header">
        <h3>PDF 文档上传（RAG 知识库）</h3>
        <el-button
          v-if="documents.length"
          :icon="Refresh"
          size="small"
          text
          @click="loadDocuments"
        />
      </div>

      <!-- 上传区域 -->
      <el-upload
        ref="uploadRef"
        :auto-upload="false"
        accept=".pdf"
        :show-file-list="false"
        :on-change="handleFileChange"
        class="upload-area"
      >
        <div class="upload-dropzone">
          <el-icon :size="40" class="upload-icon"><Upload /></el-icon>
          <p class="upload-text">拖拽 PDF 文件到此处，或<em>点击选择文件</em></p>
          <p class="upload-hint">仅支持 PDF 格式，用于更新 RAG 知识库</p>
        </div>
      </el-upload>

      <!-- 上传进度 -->
      <div v-if="uploading" class="upload-progress">
        <el-progress :percentage="uploadPercent" :stroke-width="6" striped />
        <span class="upload-filename">{{ uploadingName }}</span>
      </div>

      <!-- 文档列表 -->
      <div v-if="documents.length" class="doc-list">
        <div class="doc-item" v-for="doc in documents" :key="doc.id">
          <div class="doc-info">
            <el-icon><Document /></el-icon>
            <div class="doc-meta">
              <span class="doc-name">{{ doc.fileName || doc.originalName }}</span>
              <span class="doc-date">{{ doc.createdAt || '' }}</span>
            </div>
          </div>
          <div class="doc-actions">
            <el-tag v-if="doc.status === 'processing'" size="small" type="warning">处理中</el-tag>
            <el-tag v-else-if="doc.status === 'ready'" size="small" type="success">就绪</el-tag>
            <el-tag v-else size="small" type="info">{{ doc.status || '待处理' }}</el-tag>
            <el-button :icon="Delete" size="small" text type="danger" @click="removeDocument(doc.id)" />
          </div>
        </div>
      </div>
      <el-empty v-else-if="!uploading" description="暂无上传文档" :image-size="60" style="padding:20px 0" />
    </div>

    <!-- 知识条目列表 -->
    <div class="glass-card">
      <div class="page-header">
        <h3>反诈知识条目</h3>
        <el-input
          v-model="search"
          placeholder="搜索知识条目..."
          :prefix-icon="Search"
          size="small"
          class="search-input"
          clearable
        />
      </div>
      <el-table :data="filteredItems" v-loading="loading" class="modern-table" stripe>
        <el-table-column prop="id" label="ID" width="60" sortable />
        <el-table-column prop="category" label="分类" width="110">
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
        <el-table-column prop="answer" label="答案" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="answer-text">{{ row.answer }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="tags" label="标签" width="180" show-overflow-tooltip />
        <el-table-column prop="priority" label="优先级" width="80" sortable />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="light" class="status-tag">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Search, Upload, Document, Delete, Refresh } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { silentGet } from '../utils/fallback'
import { uploadKnowledgePdf, deleteKnowledgeDocument } from '../api/knowledge'
import ChartCard from '../components/ChartCard.vue'
import * as echarts from 'echarts'

const items = ref([])
const documents = ref([])
const loading = ref(false)
const search = ref('')
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadingName = ref('')

const filteredItems = computed(() => {
  if (!search.value) return items.value
  const q = search.value.toLowerCase()
  return items.value.filter(i =>
    (i.question && i.question.toLowerCase().includes(q)) ||
    (i.answer && i.answer.toLowerCase().includes(q)) ||
    (i.category && i.category.toLowerCase().includes(q))
  )
})

const categoryCount = computed(() => {
  const cats = new Set(items.value.map(i => i.category).filter(Boolean))
  return cats.size
})

const enabledCount = computed(() => items.value.filter(i => i.enabled).length)

const catColorPalette = ['#0ea5e9', '#22c55e', '#f59e0b', '#ef4444', '#06b6d4']
const categoryStats = computed(() => {
  const map = {}; items.value.forEach(i => { if (i.category) map[i.category] = (map[i.category] || 0) + 1 })
  return Object.entries(map).map(([name, count], i) => ({ name, count, color: catColorPalette[i % catColorPalette.length] }))
})
const catChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  grid: { top: 10, right: 10, bottom: 24, left: 70 },
  xAxis: { type: 'value', axisLabel: { color: '#94a3b8', fontSize: 10 }, splitLine: { lineStyle: { color: 'rgba(0,0,0,0.04)' } } },
  yAxis: { type: 'category', data: categoryStats.value.map(c => c.name).reverse(), axisLabel: { color: '#64748b', fontSize: 11 } },
  series: [{ type: 'bar', data: categoryStats.value.map(c => c.count).reverse(), barWidth: '55%', itemStyle: { borderRadius: [0, 4, 4, 0], color: { type: 'linear', x: 0, y: 0, x2: 1, y2: 0, colorStops: [{ offset: 0, color: '#0ea5e9' }, { offset: 1, color: '#06b6d4' }] } } }],
}))

const docReady = computed(() => documents.value.filter(d => d.status === 'ready').length)
const docProcessing = computed(() => documents.value.filter(d => d.status !== 'ready').length)
const docChartOption = computed(() => ({
  tooltip: { trigger: 'item' },
  series: [{
    type: 'pie', radius: ['45%', '70%'], avoidLabelOverlap: true,
    label: { show: true, color: '#64748b', fontSize: 12 },
    data: [
      { value: docReady.value, name: '就绪', itemStyle: { color: '#22c55e' } },
      { value: docProcessing.value, name: '处理中', itemStyle: { color: '#f59e0b' } },
    ],
  }],
}))

const catColors = {
  '诈骗类型': '#0ea5e9',
  '防范措施': '#22c55e',
  '法律法规': '#f59e0b',
  '典型案例': '#ef4444',
}
function categoryColor(cat) { return catColors[cat] || '#0ea5e9' }

async function handleFileChange(file) {
  if (file.raw.type !== 'application/pdf') {
    ElMessage.warning('仅支持 PDF 文件')
    return
  }
  uploading.value = true
  uploadPercent.value = 0
  uploadingName.value = file.name
  try {
    await uploadKnowledgePdf(file.raw, (e) => {
      uploadPercent.value = Math.round((e.loaded / e.total) * 100)
    })
    ElMessage.success('PDF 上传成功，正在处理中')
    await loadDocuments()
  } catch (e) {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
    uploadPercent.value = 0
    uploadingName.value = ''
  }
}

async function removeDocument(id) {
  try {
    await ElMessageBox.confirm('确定删除此文档吗？', '确认', { type: 'warning' })
    await deleteKnowledgeDocument(id)
    ElMessage.success('文档已删除')
    documents.value = documents.value.filter(d => d.id !== id)
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

async function loadDocuments() {
  const docs = await silentGet('/knowledge/documents')
  documents.value = docs || []
}

onMounted(async () => {
  loading.value = true
  const [itemsData, docsData] = await Promise.all([
    silentGet('/knowledge'),
    silentGet('/knowledge/documents'),
  ])
  items.value = itemsData || []
  documents.value = docsData || []
  loading.value = false
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
.page-header h3 { font-size: 16px; font-weight: 600; color: var(--text-primary, #0f172a); }
.search-input { width: 240px; }
.search-input :deep(.el-input__wrapper) { border-radius: 10px; }
.answer-text { font-size: 13px; line-height: 1.5; }
.status-tag { border-radius: 6px; }

/* Vizzes */
.viz-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 20px; animation: pageIn 0.4s ease; }
.viz-card h4 { font-size: 14px; font-weight: 600; color: var(--text-primary, #0f172a); margin-bottom: 16px; }
.stacked-bars { display: flex; flex-direction: column; gap: 10px; }
.stacked-bar-row { display: flex; align-items: center; gap: 8px; }
.stacked-label { width: 70px; font-size: 12px; color: var(--text-secondary, #64748b); flex-shrink: 0; }
.stacked-track { flex: 1; height: 10px; border-radius: 5px; background: rgba(14,165,233,0.06); overflow: hidden; }
.stacked-fill { height: 100%; border-radius: 5px; transition: width 0.6s ease; }
.stacked-val { width: 30px; font-size: 11px; color: var(--text-muted, #94a3b8); text-align: right; flex-shrink: 0; }
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

/* Upload area */
.upload-area { margin-bottom: 16px; }
.upload-dropzone {
  border: 2px dashed rgba(14,165,233,0.2);
  border-radius: 16px;
  padding: 36px 24px;
  text-align: center;
  cursor: pointer;
  transition: all 0.25s ease;
  background: rgba(14,165,233,0.02);
}
.upload-dropzone:hover {
  border-color: #0ea5e9;
  background: rgba(14,165,233,0.04);
}
.upload-icon { color: #0ea5e9; margin-bottom: 12px; }
.upload-text { font-size: 14px; color: var(--text-secondary, #475569); margin-bottom: 6px; }
.upload-text em { color: #0ea5e9; font-style: normal; font-weight: 500; }
.upload-hint { font-size: 12px; color: var(--text-muted, #94a3b8); }

.upload-progress {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  background: rgba(14,165,233,0.04);
  border-radius: 10px;
  margin-bottom: 16px;
}
.upload-progress :deep(.el-progress) { flex: 1; }
.upload-filename { font-size: 12px; color: var(--text-secondary); white-space: nowrap; max-width: 200px; overflow: hidden; text-overflow: ellipsis; }

/* Document list */
.doc-list { display: flex; flex-direction: column; gap: 6px; }
.doc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-radius: 10px;
  background: rgba(14,165,233,0.02);
  border: 1px solid rgba(14,165,233,0.04);
  transition: background 0.2s ease;
}
.doc-item:hover { background: rgba(14,165,233,0.04); }
.doc-info { display: flex; align-items: center; gap: 12px; }
.doc-info .el-icon { color: #0ea5e9; font-size: 20px; }
.doc-meta { display: flex; flex-direction: column; gap: 2px; }
.doc-name { font-size: 13px; font-weight: 500; color: var(--text-primary, #0f172a); }
.doc-date { font-size: 11px; color: var(--text-muted, #94a3b8); }
.doc-actions { display: flex; align-items: center; gap: 8px; }

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
