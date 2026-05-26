import request from './request'

export function getDetectionRecords(params) {
  return request.get('/records/list', { params })
}

export function getDailyStats() {
  return request.get('/records/stats/daily')
}

export function getAudioRecords() {
  return request.get('/audio/records')
}

export function getAudioStatistics() {
  return request.get('/audio/statistics')
}

export function getUsers() {
  return request.get('/admin/users')
}
