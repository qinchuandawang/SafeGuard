import request from './request'

export function getManagedModels() {
  return request.get('/admin/models')
}

export function switchManagedModel(category, modelId) {
  return request.post('/admin/models/switch', { category, modelId })
}

export function getActiveModel() {
  return request.get('/audio/model/active')
}

export function getAllModels() {
  return request.get('/audio/models')
}
