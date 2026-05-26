<template>
  <div class="layout-wrapper">
    <!-- 背景装饰 -->
    <div class="bg-ornament bg-ornament-1" />
    <div class="bg-ornament bg-ornament-2" />

    <el-container class="layout-container">
      <!-- 玻璃态侧边栏 -->
      <el-aside :width="isCollapsed ? '72px' : '260px'" class="glass-sidebar">
        <div class="sidebar-header">
          <div class="logo-wrap">
            <div class="logo-icon">
              <svg viewBox="0 0 32 32" width="28" height="28" fill="none">
                <circle cx="16" cy="16" r="14" stroke="url(#g1)" stroke-width="2.5" />
                <path d="M12 16l3 3 5-6" stroke="url(#g1)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
                <defs><linearGradient id="g1" x1="0" y1="0" x2="32" y2="32"><stop stop-color="#38bdf8" /><stop offset="1" stop-color="#22d3ee" /></linearGradient></defs>
              </svg>
            </div>
            <transition name="fade">
              <div v-if="!isCollapsed" class="logo-text">
                <span class="logo-title">SafeGuard</span>
                <span class="logo-sub">反诈守护系统</span>
              </div>
            </transition>
          </div>
          <el-button :icon="isCollapsed ? 'Expand' : 'Fold'" text class="collapse-btn" @click="isCollapsed = !isCollapsed" />
        </div>

        <el-menu
          :default-active="activeMenu"
          :collapse="isCollapsed"
          :collapse-transition="false"
          class="sidebar-menu"
          router
        >
          <el-menu-item index="/dashboard">
            <el-icon><DataAnalysis /></el-icon>
            <template #title>系统概览</template>
          </el-menu-item>
          <el-menu-item index="/users">
            <el-icon><User /></el-icon>
            <template #title>用户管理</template>
          </el-menu-item>
          <el-menu-item index="/knowledge">
            <el-icon><Reading /></el-icon>
            <template #title>知识库</template>
          </el-menu-item>
          <el-menu-item index="/records">
            <el-icon><Document /></el-icon>
            <template #title>检测记录</template>
          </el-menu-item>
          <el-menu-item index="/models">
            <el-icon><Cpu /></el-icon>
            <template #title>模型管理</template>
          </el-menu-item>
        </el-menu>

        <div class="sidebar-footer" v-if="!isCollapsed">
          <div class="sidebar-user">
            <el-avatar :size="32" class="user-avatar">{{ adminName.charAt(0) }}</el-avatar>
            <div class="user-info">
              <span class="user-name">{{ adminName }}</span>
              <span class="user-role">管理员</span>
            </div>
          </div>
        </div>
      </el-aside>

      <!-- 主区域 -->
      <el-container class="main-area">
        <el-header class="glass-header">
          <div class="header-left">
            <h2 class="page-title">{{ pageTitle }}</h2>
            <el-breadcrumb separator="/">
              <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
              <el-breadcrumb-item v-if="route.path !== '/dashboard'">{{ pageTitle }}</el-breadcrumb-item>
            </el-breadcrumb>
          </div>
          <div class="header-right">
            <el-tooltip content="刷新数据" placement="bottom">
              <el-button :icon="Refresh" circle text @click="refreshCurrent" />
            </el-tooltip>
            <el-tooltip :content="theme === 'dark' ? '浅色模式' : '深色模式'" placement="bottom">
              <el-button :icon="theme === 'dark' ? 'Sunny' : 'Moon'" circle text @click="toggleTheme" />
            </el-tooltip>
            <el-dropdown trigger="click" @command="handleCommand">
              <div class="header-user">
                <el-avatar :size="36" class="user-avatar">{{ adminName.charAt(0) }}</el-avatar>
                <span class="header-username">{{ adminName }}</span>
                <el-icon><ArrowDown /></el-icon>
              </div>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="profile"><el-icon><User /></el-icon>个人信息</el-dropdown-item>
                  <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </el-header>

        <el-main class="content-area">
          <router-view v-slot="{ Component }">
            <transition name="page-fade" mode="out-in">
              <component :is="Component" />
            </transition>
          </router-view>
        </el-main>
      </el-container>
    </el-container>
    <!-- AI 安全助手 -->
    <AiAssistant />
  </div>
</template>

<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Refresh, ArrowDown, SwitchButton } from '@element-plus/icons-vue'
import AiAssistant from './AiAssistant.vue'
import { getUserInfo } from '../api/auth'

const route = useRoute()
const router = useRouter()
const isCollapsed = ref(false)
const theme = ref(localStorage.getItem('admin_theme') || 'light')

const pageTitle = computed(() => {
  const map = {
    '/dashboard': '系统概览',
    '/users': '用户管理',
    '/knowledge': '知识库管理',
    '/records': '检测记录',
    '/models': '模型管理',
    '/profile': '个人信息',
  }
  return map[route.path] || 'SafeGuard'
})

const activeMenu = computed(() => route.path)
const adminName = ref(localStorage.getItem('admin_name') || '管理员')

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('admin_theme', theme.value)
  document.documentElement.setAttribute('data-theme', theme.value)
}

function refreshCurrent() {
  const event = new CustomEvent('refresh-page')
  window.dispatchEvent(event)
}

// 挂载时从 API 刷新管理员昵称，修复 localStorage 缓存乱码
onMounted(async () => {
  try {
    const info = await getUserInfo()
    if (info?.nickname) {
      adminName.value = info.nickname
      localStorage.setItem('admin_name', info.nickname)
    }
  } catch (e) { /* use cached value */ }
})

function handleCommand(cmd) {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    localStorage.removeItem('admin_token')
    localStorage.removeItem('admin_name')
    router.push('/login')
  }
}
</script>

<style>
/* ===== CSS Variables ===== */
:root {
  --sidebar-bg: rgba(255,255,255,0.75);
  --sidebar-border: rgba(255,255,255,0.3);
  --header-bg: rgba(255,255,255,0.6);
  --content-bg: #f0f9ff;
  --text-primary: #0f172a;
  --text-secondary: #64748b;
  --text-muted: #94a3b8;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.04);
  --shadow-md: 0 4px 20px rgba(0,0,0,0.06);
  --shadow-lg: 0 8px 40px rgba(0,0,0,0.08);
  --accent-gradient: linear-gradient(135deg, #0ea5e9, #06b6d4);
  --accent-gradient-2: linear-gradient(135deg, #38bdf8, #0ea5e9);
  --card-bg: rgba(255,255,255,0.8);
  --card-border: rgba(255,255,255,0.6);
  --glass-bg: rgba(255,255,255,0.7);
  --accent-blue: #0ea5e9;
  --accent-cyan: #06b6d4;
  --accent-light: #e0f2fe;
}

/* Modern scrollbar */
::-webkit-scrollbar { width: 6px; height: 6px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: rgba(14,165,233,0.2); border-radius: 3px; }
::-webkit-scrollbar-thumb:hover { background: rgba(14,165,233,0.35); }

/* Smooth entrance for app */
.layout-wrapper { animation: appFadeIn 0.6s ease; }
@keyframes appFadeIn { from { opacity: 0; } to { opacity: 1; } }

[data-theme="dark"] {
  --sidebar-bg: rgba(30,30,50,0.85);
  --sidebar-border: rgba(255,255,255,0.08);
  --header-bg: rgba(30,30,50,0.6);
  --content-bg: #0f0f23;
  --text-primary: #e5e7eb;
  --text-secondary: #9ca3af;
  --shadow-sm: 0 1px 3px rgba(0,0,0,0.2);
  --shadow-md: 0 4px 20px rgba(0,0,0,0.3);
  --shadow-lg: 0 8px 40px rgba(0,0,0,0.4);
  --card-bg: rgba(30,30,50,0.6);
  --card-border: rgba(255,255,255,0.06);
  --glass-bg: rgba(30,30,50,0.5);
}
</style>

<style scoped>
.layout-wrapper {
  min-height: 100vh;
  background: var(--content-bg);
  position: relative;
  overflow: hidden;
}

/* Subtle dot-grid pattern for content area (Google-inspired) */
.layout-wrapper::before {
  content: '';
  position: fixed;
  inset: 0;
  background-image: radial-gradient(rgba(14,165,233,0.08) 1px, transparent 1px);
  background-size: 32px 32px;
  pointer-events: none;
  z-index: 0;
}

/* 背景装饰 */
.bg-ornament {
  position: fixed;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.15;
  pointer-events: none;
  z-index: 0;
}
.bg-ornament-1 {
  width: 600px; height: 600px;
  background: radial-gradient(circle, #0ea5e9, transparent);
  top: -200px; right: -200px;
  animation: float 20s ease-in-out infinite, pulseGlow 4s ease-in-out infinite;
}
.bg-ornament-2 {
  width: 400px; height: 400px;
  background: radial-gradient(circle, #06b6d4, transparent);
  bottom: -100px; left: -100px;
  animation: float 25s ease-in-out infinite reverse, pulseGlow 5s ease-in-out infinite 1s;
}
@keyframes float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  50% { transform: translate(30px, -30px) scale(1.05); }
}
@keyframes pulseGlow {
  0%, 100% { opacity: 0.12; }
  50% { opacity: 0.22; }
}

/* Sidebar accent line */
.glass-sidebar::after {
  content: '';
  position: absolute;
  top: 0;
  right: 0;
  width: 3px;
  height: 100%;
  background: linear-gradient(180deg, transparent, #0ea5e9, #06b6d4, transparent);
  opacity: 0.3;
  pointer-events: none;
}

.layout-container {
  position: relative;
  z-index: 1;
  height: 100vh;
}

/* ===== 侧边栏 ===== */
.glass-sidebar {
  background: var(--sidebar-bg);
  backdrop-filter: blur(20px) saturate(1.4);
  -webkit-backdrop-filter: blur(20px) saturate(1.4);
  border-right: 1px solid var(--sidebar-border);
  display: flex;
  flex-direction: column;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1);
  overflow: hidden;
  box-shadow: 2px 0 20px rgba(0,0,0,0.04);
}

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 20px 16px;
  border-bottom: 1px solid var(--sidebar-border);
  min-height: 72px;
}

.logo-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  overflow: hidden;
}

.logo-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
}

.logo-text {
  display: flex;
  flex-direction: column;
  white-space: nowrap;
}
.logo-title {
  font-size: 18px;
  font-weight: 800;
  background: var(--accent-gradient);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  letter-spacing: -0.3px;
}
.logo-sub {
  font-size: 11px;
  color: var(--text-secondary);
  letter-spacing: 2px;
  text-transform: uppercase;
  margin-top: 1px;
}

.collapse-btn {
  color: var(--text-secondary);
  flex-shrink: 0;
}
.collapse-btn:hover { color: var(--text-primary); }

.sidebar-menu {
  flex: 1;
  border-right: none !important;
  background: transparent !important;
  padding: 8px;
}
.sidebar-menu .el-menu-item {
  border-radius: 10px;
  margin: 4px 0;
  height: 46px;
  line-height: 46px;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  color: var(--text-secondary);
  position: relative;
  overflow: hidden;
}
.sidebar-menu .el-menu-item::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(14,165,233,0.08), rgba(6,182,212,0.04));
  opacity: 0;
  transition: opacity 0.25s ease;
}
.sidebar-menu .el-menu-item:hover::before { opacity: 1; }
.sidebar-menu .el-menu-item:hover { color: var(--text-primary); }
.sidebar-menu .el-menu-item.is-active {
  background: var(--accent-gradient);
  color: #fff;
  box-shadow: 0 4px 15px rgba(14,165,233,0.35);
}
.sidebar-menu .el-menu-item.is-active::before { opacity: 0; }
.sidebar-menu .el-menu-item.is-active .el-icon { color: #fff; }
.sidebar-menu .el-menu-item .el-icon { color: var(--text-secondary); position: relative; z-index: 1; }
.sidebar-menu .el-menu-item.is-active:hover { color: #fff; }
.sidebar-menu .el-menu-item.is-active:hover::before { opacity: 0; }

.sidebar-menu .el-menu-item :deep(.el-icon) { position: relative; z-index: 1; }
.sidebar-menu .el-menu-item :deep(template) { position: relative; z-index: 1; }

.sidebar-footer {
  padding: 12px 16px;
  border-top: 1px solid var(--sidebar-border);
}

.sidebar-user {
  display: flex;
  align-items: center;
  gap: 10px;
}
.user-avatar {
  flex-shrink: 0;
  background: var(--accent-gradient);
  color: #fff;
  font-weight: 600;
}
.user-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.user-name { font-size: 13px; font-weight: 600; color: var(--text-primary); white-space: nowrap; }
.user-role { font-size: 11px; color: var(--text-muted); }

/* ===== 主区域 ===== */
.main-area {
  display: flex;
  flex-direction: column;
  background: transparent;
}

.glass-header {
  background: var(--header-bg);
  backdrop-filter: blur(16px) saturate(1.3);
  -webkit-backdrop-filter: blur(16px) saturate(1.3);
  border-bottom: 1px solid var(--sidebar-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 72px !important;
  padding: 0 32px;
  position: sticky;
  top: 0;
  z-index: 10;
}
.glass-header::after {
  content: '';
  position: absolute;
  bottom: -1px;
  left: 0;
  width: 100%;
  height: 2px;
  background: linear-gradient(90deg, transparent, #0ea5e9, #06b6d4, transparent);
  opacity: 0.25;
  pointer-events: none;
}

.header-left {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.page-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  letter-spacing: -0.3px;
}
.header-left :deep(.el-breadcrumb) { font-size: 12px; }
.header-left :deep(.el-breadcrumb__inner) { color: var(--text-muted) !important; }

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.header-right :deep(.el-button) { color: var(--text-secondary); }
.header-right :deep(.el-button:hover) { color: var(--text-primary); }

.header-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 10px;
  transition: background 0.2s;
}
.header-user:hover { background: rgba(14,165,233,0.08); }
.header-username { font-size: 13px; font-weight: 500; color: var(--text-primary); }

.content-area {
  background: transparent !important;
  padding: 24px 32px;
  overflow-y: auto;
}

/* 页面切换动画 - 纯淡入淡出，避免布局抖动 */
.page-fade-enter-active { transition: opacity 0.15s ease; }
.page-fade-leave-active { transition: opacity 0.1s ease; }
.page-fade-enter-from { opacity: 0; }
.page-fade-leave-to { opacity: 0; }

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s ease; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
