import request from './request'

export function adminLogin(username, password) {
  return request.post('/auth/admin/login', { username, password })
}

export function getUserInfo() {
  return request.get('/auth/userinfo')
}

export function getOverviewStats() {
  return request.get('/admin/stats/overview')
}
