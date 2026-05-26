import request from './request'

export function getActiveModel() {
  return request.get('/audio/model/active')
}

export function getAllModels() {
  return request.get('/audio/models')
}
