import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
})

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

function shouldSuppress(config) {
  return config?.headers?.__suppressError === true
}

request.interceptors.response.use(
  (res) => {
    // SSE/流式响应直接返回原始 response，不做业务码包装
    if (res.config?.responseType === 'stream' || res.config?.responseType === 'text') {
      return res
    }
    if (res.data.code === 200) return res.data.data
    if (!shouldSuppress(res.config)) {
      ElMessage.error(res.data.message || '请求失败')
    }
    return Promise.reject(new Error(res.data.message))
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('admin_token')
      router.push('/login')
      ElMessage.error('登录已过期，请重新登录')
    } else if (!shouldSuppress(err.config)) {
      ElMessage.error(err.message || '网络错误')
    }
    return Promise.reject(err)
  },
)

export default request
