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

export function uploadKnowledgePdf(file, onProgress) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/knowledge/upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: onProgress,
  })
}

export function getKnowledgeDocuments() {
  return request.get('/knowledge/documents')
}

export function deleteKnowledgeDocument(id) {
  return request.delete(`/knowledge/documents/${id}`)
}
