<template>
  <div class="login-page">
    <!-- 动态背景 -->
    <div class="bg-grid" />
    <div class="bg-orb bg-orb-1" />
    <div class="bg-orb bg-orb-2" />
    <div class="bg-orb bg-orb-3" />

    <div class="login-container">
      <!-- 左侧品牌区域 -->
      <div class="brand-section">
        <div class="brand-content">
          <div class="brand-icon">
            <svg viewBox="0 0 48 48" width="64" height="64" fill="none">
              <circle cx="24" cy="24" r="20" stroke="rgba(14,165,233,0.2)" stroke-width="2" />
              <path d="M18 24l4 4 8-8" stroke="#0ea5e9" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />
              <circle cx="24" cy="24" r="4" fill="rgba(14,165,233,0.06)" />
            </svg>
          </div>
          <h1 class="brand-title">SafeGuard</h1>
          <p class="brand-desc">智能反诈骗检测系统<br/>守护您的数字安全</p>
          <div class="brand-features">
            <div class="feature-item" v-for="f in features" :key="f.label">
              <div class="feature-dot" />
              <span>{{ f.label }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- 右侧登录/注册卡片 -->
      <div class="login-card-wrap">
        <div class="login-card">
          <div class="card-tabs">
            <span :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</span>
            <span :class="{ active: mode === 'register' }" @click="mode = 'register'">注册管理员</span>
          </div>

          <!-- 登录表单 -->
          <template v-if="mode === 'login'">
            <div class="card-header">
              <h2 class="card-title">欢迎回来</h2>
              <p class="card-subtitle">请登录您的管理员账户</p>
            </div>
            <el-form :model="form" @keyup.enter="handleLogin" class="login-form" label-position="top">
              <el-form-item label="用户名">
                <el-input v-model="form.username" placeholder="请输入用户名" size="large" :prefix-icon="User" class="modern-input" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="form.password" type="password" placeholder="请输入密码" size="large" :prefix-icon="Lock" show-password class="modern-input" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="large" class="login-btn" :loading="loading" @click="handleLogin">{{ loading ? '登录中...' : '登 录' }}</el-button>
              </el-form-item>
              <div class="quick-login">
                <el-button size="large" class="quick-btn" :loading="adminQuickLoading" @click="quickAdminLogin">管理员一键登录</el-button>
                <el-button size="large" class="quick-btn user" :loading="userQuickLoading" @click="quickUserLogin">用户一键登录</el-button>
              </div>
            </el-form>
          </template>

          <!-- 注册表单 -->
          <template v-if="mode === 'register'">
            <div class="card-header">
              <h2 class="card-title">创建账户</h2>
              <p class="card-subtitle">注册成为新的系统管理员</p>
            </div>
            <el-form :model="regForm" class="login-form" label-position="top">
              <el-form-item label="用户名">
                <el-input v-model="regForm.username" placeholder="请输入用户名" size="large" :prefix-icon="User" class="modern-input" />
              </el-form-item>
              <el-form-item label="昵称">
                <el-input v-model="regForm.nickname" placeholder="请输入昵称" size="large" class="modern-input" />
              </el-form-item>
              <el-form-item label="密码">
                <el-input v-model="regForm.password" type="password" placeholder="至少6位" size="large" :prefix-icon="Lock" show-password class="modern-input" />
              </el-form-item>
              <el-form-item label="确认密码">
                <el-input v-model="regForm.confirm" type="password" placeholder="再次输入密码" size="large" :prefix-icon="Lock" show-password class="modern-input" />
              </el-form-item>
              <el-form-item>
                <el-button type="primary" size="large" class="login-btn" :loading="regLoading" @click="handleRegister">{{ regLoading ? '注册中...' : '注 册' }}</el-button>
              </el-form-item>
            </el-form>
          </template>

          <div class="card-footer">
            <span class="footer-text">SafeGuard v2.0 · 智能反诈系统</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { adminLogin, adminRegister } from '../api/auth'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const regLoading = ref(false)
const adminQuickLoading = ref(false)
const userQuickLoading = ref(false)
const form = reactive({ username: '', password: '' })
const regForm = reactive({ username: '', nickname: '', password: '', confirm: '' })

const features = [
  { label: 'AI 语音深度伪造检测' },
  { label: '实时反诈骗知识图谱' },
  { label: '多维度风险智能评估' },
]

async function handleLogin() {
  if (!form.username || !form.password) { ElMessage.warning('请输入用户名和密码'); return }
  loading.value = true
  try {
    const data = await adminLogin(form.username, form.password)
    localStorage.setItem('admin_token', data.token)
    localStorage.setItem('admin_name', data.nickname || form.username)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch (e) { /* handled */ }
  finally { loading.value = false }
}

async function quickAdminLogin() {
  adminQuickLoading.value = true
  try {
    const data = await adminLogin('default', 'admin123')
    localStorage.setItem('admin_token', data.token)
    localStorage.setItem('admin_name', data.nickname || '演示管理员')
    ElMessage.success('已登录演示管理员')
    router.push('/dashboard')
  } catch (e) { /* handled */ }
  finally { adminQuickLoading.value = false }
}

async function quickUserLogin() {
  userQuickLoading.value = true
  try {
    const data = await adminLogin('default', 'admin123')
    localStorage.setItem('admin_token', data.token)
    localStorage.setItem('admin_name', '用户演示')
    ElMessage.success('已进入用户演示模式')
    router.push('/dashboard')
  } catch (e) { /* handled */ }
  finally { userQuickLoading.value = false }
}

async function handleRegister() {
  if (!regForm.username || !regForm.nickname || !regForm.password) { ElMessage.warning('请填写完整信息'); return }
  if (regForm.password.length < 6) { ElMessage.warning('密码至少6位'); return }
  if (regForm.password !== regForm.confirm) { ElMessage.warning('两次密码不一致'); return }
  regLoading.value = true
  try {
    await adminRegister(regForm.username, regForm.nickname, regForm.password)
    ElMessage.success('注册成功，请登录')
    mode.value = 'login'
    form.username = regForm.username
    regForm.username = ''; regForm.nickname = ''; regForm.password = ''; regForm.confirm = ''
  } catch (e) { /* handled */ }
  finally { regLoading.value = false }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #f0f9ff 0%, #e0f2fe 50%, #f0fdfa 100%);
  position: relative;
  overflow: hidden;
}

/* 点阵背景 */
.bg-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgba(14,165,233,0.07) 1px, transparent 1px);
  background-size: 28px 28px;
  z-index: 0;
}

/* 装饰性浅色光晕 */
.bg-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(100px);
  pointer-events: none;
  z-index: 0;
}
.bg-orb-1 {
  width: 500px; height: 500px;
  background: radial-gradient(circle, rgba(14,165,233,0.10), transparent);
  top: -200px; left: -100px;
  animation: orbFloat 25s ease-in-out infinite;
}
.bg-orb-2 {
  width: 400px; height: 400px;
  background: radial-gradient(circle, rgba(6,182,212,0.08), transparent);
  bottom: -150px; right: -100px;
  animation: orbFloat 30s ease-in-out infinite reverse;
}
.bg-orb-3 {
  width: 300px; height: 300px;
  background: radial-gradient(circle, rgba(56,189,248,0.06), transparent);
  top: 50%; left: 50%;
  transform: translate(-50%, -50%);
  animation: orbFloat 20s ease-in-out infinite 5s;
}
@keyframes orbFloat {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(40px, -40px) scale(1.1); }
  66% { transform: translate(-20px, 30px) scale(0.95); }
}

.login-container {
  display: flex;
  width: 920px;
  max-width: 90vw;
  min-height: 560px;
  position: relative;
  z-index: 1;
  background: #fff;
  border-radius: 28px;
  box-shadow: 0 8px 40px rgba(14,165,233,0.08), 0 2px 12px rgba(0,0,0,0.04);
  overflow: hidden;
}

/* ===== 左侧品牌 ===== */
.brand-section {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 48px;
  background: linear-gradient(135deg, #f0f9ff 0%, #ecfeff 100%);
  border-right: 1px solid rgba(14,165,233,0.06);
}

.brand-content {
  text-align: center;
  max-width: 300px;
}
.brand-icon { margin-bottom: 24px; }
.brand-title {
  font-size: 34px;
  font-weight: 800;
  background: linear-gradient(135deg, #0ea5e9, #06b6d4);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  margin-bottom: 12px;
  letter-spacing: -1px;
}
.brand-desc {
  font-size: 14px;
  color: #64748b;
  line-height: 1.7;
  margin-bottom: 36px;
}
.brand-features {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.feature-item {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #475569;
  font-size: 13px;
  animation: fadeInUp 0.5s ease both;
}
.feature-item:nth-child(1) { animation-delay: 0.2s; }
.feature-item:nth-child(2) { animation-delay: 0.3s; }
.feature-item:nth-child(3) { animation-delay: 0.4s; }
.feature-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0ea5e9, #06b6d4);
  flex-shrink: 0;
}

/* ===== 登录卡片 ===== */
.login-card-wrap {
  width: 440px;
  display: flex;
  align-items: center;
}

.login-card {
  width: 100%;
  padding: 52px 44px;
  background: #fff;
  animation: cardSlideUp 0.6s ease;
}
@keyframes cardSlideUp { from { opacity: 0; transform: translateY(20px); } to { opacity: 1; transform: translateY(0); } }

.brand-content { animation: fadeInUp 0.7s ease 0.1s both; }
@keyframes fadeInUp { from { opacity: 0; transform: translateY(15px); } to { opacity: 1; transform: translateY(0); } }

/* Tabs */
.card-tabs { display: flex; gap: 0; margin-bottom: 28px; border-bottom: 2px solid #f1f5f9; padding-bottom: 0; }
.card-tabs span { flex: 1; text-align: center; padding: 10px 0; font-size: 14px; font-weight: 500; color: #94a3b8; cursor: pointer; transition: all 0.2s ease; position: relative; }
.card-tabs span::after { content: ''; position: absolute; bottom: -2px; left: 25%; width: 50%; height: 2px; background: #0ea5e9; border-radius: 2px; transform: scaleX(0); transition: transform 0.2s ease; }
.card-tabs span.active { color: #0ea5e9; font-weight: 600; }
.card-tabs span.active::after { transform: scaleX(1); }
.card-tabs span:hover { color: #0ea5e9; }

.card-header { margin-bottom: 24px; }
.card-title {
  font-size: 22px;
  font-weight: 700;
  color: #0f172a;
  margin-bottom: 6px;
  letter-spacing: -0.3px;
}
.card-subtitle { font-size: 13px; color: #94a3b8; }

.login-form :deep(.el-form-item) { animation: formItemIn 0.5s ease both; }
.login-form :deep(.el-form-item:nth-child(1)) { animation-delay: 0.15s; }
.login-form :deep(.el-form-item:nth-child(2)) { animation-delay: 0.25s; }
.login-form :deep(.el-form-item:nth-child(3)) { animation-delay: 0.35s; }
@keyframes formItemIn { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: translateY(0); } }

.login-form :deep(.el-form-item__label) {
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  padding-bottom: 6px;
}

.modern-input :deep(.el-input__wrapper) {
  background: #f8fafc;
  border: 1.5px solid #e2e8f0;
  box-shadow: none;
  border-radius: 12px;
  padding: 4px 16px;
  transition: all 0.25s ease;
}
.modern-input :deep(.el-input__wrapper:hover) {
  border-color: #7dd3fc;
  background: #f8fafc;
}
.modern-input :deep(.el-input__wrapper.is-focus) {
  border-color: #0ea5e9;
  background: #f0f9ff;
  box-shadow: 0 0 0 3px rgba(14,165,233,0.08);
}
.modern-input :deep(.el-input__inner) {
  color: #0f172a;
  height: 44px;
}
.modern-input :deep(.el-input__prefix-inner) {
  color: #94a3b8;
}

.login-btn {
  width: 100%;
  height: 48px;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  background: linear-gradient(135deg, #0ea5e9, #06b6d4);
  transition: all 0.25s ease;
  margin-top: 8px;
}
.login-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 25px rgba(14,165,233,0.3);
}
.login-btn:active { transform: translateY(0); }

.quick-login {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 12px;
}
.quick-btn {
  height: 44px;
  margin: 0;
  border-radius: 12px;
  border-color: #bae6fd;
  background: #f0f9ff;
  color: #0369a1;
  font-weight: 600;
}
.quick-btn:hover {
  border-color: #38bdf8;
  background: #e0f2fe;
  color: #075985;
}
.quick-btn.user {
  border-color: #cbd5e1;
  background: #f8fafc;
  color: #475569;
}
.quick-btn.user:hover {
  border-color: #94a3b8;
  background: #f1f5f9;
  color: #334155;
}

.card-footer {
  margin-top: 32px;
  text-align: center;
  padding-top: 24px;
  border-top: 1px solid #f1f5f9;
}
.footer-text {
  font-size: 12px;
  color: #94a3b8;
}

@media (max-width: 768px) {
  .login-container { flex-direction: column; max-width: 440px; }
  .brand-section { border-radius: 28px 28px 0 0; border-right: none; border-bottom: 1px solid rgba(14,165,233,0.06); padding: 32px; }
  .login-card-wrap { width: 100%; }
  .login-card { padding: 36px 28px; }
  .quick-login { grid-template-columns: 1fr; }
}
</style>
