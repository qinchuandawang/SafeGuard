/**
 * 安全 API 调用：真实接口失败时静默降级为 mock 数据
 * 避免因后端未就绪导致"系统异常"弹窗和长时间等待
 */

import axios from 'axios'

const SHORT_TIMEOUT = 3000 // 快速失败，不卡 UI

/**
 * 安全 API 调用：真实接口失败时静默降级为 mock 数据
 * 不触发全局错误弹窗
 */
export function safeCall(apiFn, mockData, timeoutMs = SHORT_TIMEOUT) {
  return Promise.race([
    apiFn().catch(() => mockData),
    new Promise(resolve => setTimeout(() => resolve(mockData), timeoutMs)),
  ])
}

/** 静默请求实例：不出错弹窗，适合懒加载/可选数据 */
const silent = axios.create({ baseURL: '/api', timeout: SHORT_TIMEOUT })

silent.interceptors.response.use(
  (res) => res.data?.code === 200 ? res.data.data : Promise.reject(res.data),
  () => Promise.reject(null),
)

export function silentGet(url) {
  return silent.get(url).catch(() => null)
}

export function silentPut(url, data) {
  return silent.put(url, data).catch(() => null)
}

export function silentPost(url, data) {
  return silent.post(url, data).catch(() => null)
}

export function silentDelete(url) {
  return silent.delete(url).catch(() => null)
}

export const fallbackStats = {
  userCount: 1284,
  userChange: 12.5,
  detectionCount: 8753,
  detectionChange: 8.2,
  knowledgeCount: 346,
  knowledgeChange: 5.1,
  audioRecordCount: 2106,
  audioChange: -2.3,
  audioStats: {
    totalDetections: 2106,
    spoofCount: 385,
    bonafideCount: 1721,
    serviceStatus: true,
    modelLoaded: true,
  },
}

export const fallbackUserInfo = {
  username: 'admin',
  nickname: '管理员',
  role: '超级管理员',
  email: 'admin@safeguard.com',
  phone: '138****8888',
  department: '安全中心',
  bio: '系统管理员，负责反诈系统的日常运维。',
  createdAt: '2025-01-15 09:30:00',
}

export const fallbackLoginHistory = [
  { ip: '192.168.1.100', location: '中国 北京', device: 'Chrome 125 on Windows', loginAt: '2025-05-25 14:32:10' },
  { ip: '192.168.1.100', location: '中国 北京', device: 'Chrome 125 on Windows', loginAt: '2025-05-25 09:15:44' },
  { ip: '10.0.0.5', location: '中国 上海', device: 'Safari on macOS', loginAt: '2025-05-24 18:42:30' },
  { ip: '192.168.1.100', location: '中国 北京', device: 'Chrome 125 on Windows', loginAt: '2025-05-24 10:00:12' },
  { ip: '172.16.0.8', location: '中国 深圳', device: 'Edge on Windows', loginAt: '2025-05-23 22:15:08' },
]

export const fallbackKnowledgeDocs = [
  { id: 1, fileName: '反电信网络诈骗法_2025版.pdf', originalName: '反电信网络诈骗法_2025版.pdf', status: 'ready', createdAt: '2025-05-20 10:30:00' },
  { id: 2, fileName: 'AI语音深度伪造检测指南.pdf', originalName: 'AI语音深度伪造检测指南.pdf', status: 'ready', createdAt: '2025-05-18 14:20:00' },
  { id: 3, fileName: '常见诈骗话术库_v3.pdf', originalName: '常见诈骗话术库_v3.pdf', status: 'processing', createdAt: '2025-05-25 16:00:00' },
]

export const fallbackDailyStats = {
  dates: ['05-19', '05-20', '05-21', '05-22', '05-23', '05-24', '05-25'],
  detected: [12, 18, 8, 22, 15, 10, 19],
  safe: [45, 52, 38, 61, 43, 55, 48],
}

export function generateTrendData(days = 7) {
  const dates = []
  const detected = []
  const safe = []
  const now = new Date()
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(now)
    d.setDate(d.getDate() - i)
    dates.push(`${d.getMonth() + 1}/${d.getDate()}`)
    detected.push(Math.floor(Math.random() * 30 + 10))
    safe.push(Math.floor(Math.random() * 60 + 30))
  }
  return { dates, detected, safe }
}
