/**
 * 静默请求工具：用于"快速失败 + 静默降级"场景。
 * 出错或超时不触发全局错误弹窗，返回 null。
 * Profile.vue 等页面用它加载非关键数据。
 */
import axios from 'axios'

const SHORT_TIMEOUT = 3000 // 快速失败，不卡 UI

const silent = axios.create({ baseURL: '/api', timeout: SHORT_TIMEOUT })

silent.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

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
