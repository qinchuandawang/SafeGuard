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

export function importKnowledgeDocument(formData) {
  return request.post('/knowledge/import', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}
