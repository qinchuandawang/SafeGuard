import request from './request'

export function getManagedModels() {
  return request.get('/admin/models')
}

export function getActiveModel() {
  return request.get('/audio/model/active')
}

export function getAllModels() {
  return request.get('/audio/models')
}
