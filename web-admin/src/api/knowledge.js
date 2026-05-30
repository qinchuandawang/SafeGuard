import request from './request'

export function getKnowledgeList() {
  return request.get('/knowledge')
}

export function createKnowledge(data) {
  return request.post('/knowledge', data)
}

export function updateKnowledge(id, data) {
  return request.put(`/knowledge/${id}`, data)
}

export function deleteKnowledge(id) {
  return request.delete(`/knowledge/${id}`)
}

// 知识库 PDF 上传功能暂未实现后端接口
// uploadKnowledgePdf / getKnowledgeDocuments / deleteKnowledgeDocument 需要先实现后端 Controller 路由
