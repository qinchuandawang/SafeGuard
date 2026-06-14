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

// Dashboard 趋势图：按日期+结果分组的真实数据
export function getTrendStats(days = 7) {
  return request.get('/admin/stats/trend', { params: { days } })
}

// Dashboard 饼图：按结果统计的占比
export function getDistributionStats() {
  return request.get('/admin/stats/distribution')
}

// 用户管理：近 N 天每日新增用户数
export function getUserDailyStats(days = 7) {
  return request.get('/admin/users/daily-stats', { params: { days } })
}
