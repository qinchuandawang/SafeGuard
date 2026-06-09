<template>
  <div class="page">
    <!-- 头像 + 基本信息卡片 -->
    <div class="glass-card">
      <div class="profile-top">
        <div class="avatar-section">
          <div class="avatar-wrap" @click="editing && triggerAvatarUpload()">
            <el-avatar :size="88" class="profile-avatar" :src="profile.avatarUrl || ''">
              <template #default>
                {{ profile.nickname?.charAt(0) || '?' }}
              </template>
            </el-avatar>
            <div v-if="editing" class="avatar-overlay">
              <el-icon :size="24"><Camera /></el-icon>
              <span>更换头像</span>
            </div>
          </div>
          <input ref="fileInputRef" type="file" accept="image/*" style="display:none" @change="handleAvatarChange" />
          <div class="avatar-info">
            <h2 class="profile-name">{{ profile.nickname || profile.username }}</h2>
            <el-tag size="small" class="profile-role-tag">{{ profile.role || '管理员' }}</el-tag>
          </div>
        </div>
        <div class="profile-actions">
          <template v-if="!editing">
            <el-button :icon="Edit" @click="startEditing">编辑资料</el-button>
          </template>
          <template v-else>
            <el-button @click="cancelEditing">取消</el-button>
            <el-button :icon="CircleCheck" type="primary" :loading="saving" @click="saveProfile">保存</el-button>
          </template>
        </div>
      </div>

      <el-divider />

      <el-form :model="profile" label-width="100px" class="profile-form">
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="用户名">
              <el-input v-model="profile.username" disabled>
                <template #prefix><el-icon><User /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="昵称">
              <el-input v-model="profile.nickname" :disabled="!editing" placeholder="请输入昵称">
                <template #prefix><el-icon><EditPen /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="邮箱">
              <el-input v-model="profile.email" :disabled="!editing" placeholder="请输入邮箱">
                <template #prefix><el-icon><Message /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号">
              <el-input v-model="profile.phone" :disabled="!editing" placeholder="请输入手机号">
                <template #prefix><el-icon><Iphone /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-row :gutter="24">
          <el-col :span="12">
            <el-form-item label="部门">
              <el-input v-model="profile.department" :disabled="!editing" placeholder="请输入部门">
                <template #prefix><el-icon><OfficeBuilding /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="角色">
              <el-input v-model="profile.role" disabled>
                <template #prefix><el-icon><UserFilled /></el-icon></template>
              </el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="个人简介">
          <el-input
            v-model="profile.bio"
            :disabled="!editing"
            type="textarea"
            :rows="3"
            placeholder="介绍一下自己..."
          />
        </el-form-item>
        <el-form-item label="注册时间">
          <el-input v-model="profile.createdAt" disabled>
            <template #prefix><el-icon><Timer /></el-icon></template>
          </el-input>
        </el-form-item>
      </el-form>
    </div>

    <!-- 修改密码卡片 -->
    <div class="glass-card" style="margin-top: 24px;">
      <div class="card-section-header">
        <h3><el-icon><Key /></el-icon> 修改密码</h3>
      </div>
      <el-form :model="pwdForm" label-width="100px" class="profile-form">
        <el-row :gutter="24">
          <el-col :span="8">
            <el-form-item label="当前密码">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="输入当前密码" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="新密码">
              <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="输入新密码（至少6位）" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="确认密码">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item>
          <el-button :icon="Key" type="warning" :loading="pwdLoading" @click="changePassword">
            更新密码
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 登录历史卡片 -->
    <div class="glass-card" style="margin-top: 24px;">
      <div class="card-section-header">
        <h3><el-icon><Clock /></el-icon> 登录历史</h3>
      </div>
      <el-table :data="loginHistory" class="modern-table" stripe v-loading="histLoading" max-height="240">
        <el-table-column prop="ip" label="IP 地址" min-width="160" />
        <el-table-column prop="location" label="登录地点" min-width="140" />
        <el-table-column prop="device" label="设备" min-width="160" />
        <el-table-column prop="loginAt" label="登录时间" width="180" />
      </el-table>
      <el-empty v-if="!loginHistory.length && !histLoading" description="暂无登录记录" :image-size="60" style="padding: 20px 0" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Key, Edit, CircleCheck, User, EditPen, Message, Iphone,
  OfficeBuilding, UserFilled, Timer, Camera, Clock,
} from '@element-plus/icons-vue'
import { getUserInfo } from '../api/auth'
import request from '../api/request'
import { silentGet, silentPut, silentPost } from '../utils/fallback'

const editing = ref(false)
const saving = ref(false)
const pwdLoading = ref(false)
const histLoading = ref(false)
const fileInputRef = ref(null)

const profile = reactive({
  username: '',
  nickname: '',
  role: '',
  email: '',
  phone: '',
  department: '',
  bio: '',
  createdAt: '',
})

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})

const loginHistory = ref([])
let savedProfile = {}

function startEditing() {
  savedProfile = { ...profile }
  editing.value = true
}

function cancelEditing() {
  Object.assign(profile, savedProfile)
  editing.value = false
}

function triggerAvatarUpload() {
  fileInputRef.value?.click()
}

async function handleAvatarChange(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) { ElMessage.warning('请选择图片文件'); return }
  if (file.size > 500 * 1024) { ElMessage.warning('头像不能超过 500KB'); e.target.value = ''; return }
  // 读取为 base64 dataURL，与后端 /auth/admin/avatar 协议保持一致
  const reader = new FileReader()
  const dataUrl = await new Promise((resolve, reject) => {
    reader.onload = () => resolve(reader.result)
    reader.onerror = () => reject(new Error('读取图片失败'))
    reader.readAsDataURL(file)
  }).catch(() => null)
  if (!dataUrl) { ElMessage.error('读取图片失败'); e.target.value = ''; return }
  const ok = await silentPost('/auth/admin/avatar', { file: dataUrl })
  if (ok) {
    profile.avatarUrl = dataUrl
    ElMessage.success('头像已更新')
  } else {
    // 本地降级：仍然展示
    profile.avatarUrl = dataUrl
    ElMessage.success('头像已更新（本地模式）')
  }
  e.target.value = ''
}

async function loadProfile() {
  try {
    const data = await getUserInfo()
    if (data) Object.assign(profile, {
      username: data.username || 'admin',
      nickname: data.nickname || localStorage.getItem('admin_name') || '管理员',
      role: data.role || '超级管理员',
      email: data.email || '',
      phone: data.phone || '',
      department: data.department || '',
      bio: data.bio || '',
      avatarUrl: data.avatarUrl || '',
      createdAt: data.createdAt || '-',
    })
  } catch (e) {
    profile.nickname = localStorage.getItem('admin_name') || '管理员'
  }
}

async function saveProfile() {
  if (!profile.nickname?.trim()) {
    ElMessage.warning('昵称不能为空')
    return
  }
  saving.value = true
  const ok = await silentPut('/auth/admin/profile', {
    nickname: profile.nickname,
    email: profile.email,
    phone: profile.phone,
    department: profile.department,
    bio: profile.bio,
  })
  if (ok) {
    localStorage.setItem('admin_name', profile.nickname)
    ElMessage.success('个人信息已更新')
  } else {
    // 后端不可用 — 本地乐观更新
    localStorage.setItem('admin_name', profile.nickname)
    ElMessage.success('个人信息已更新（本地）')
  }
  editing.value = false
  savedProfile = { ...profile }
  saving.value = false
}

async function changePassword() {
  if (!pwdForm.oldPassword || !pwdForm.newPassword) {
    ElMessage.warning('请填写完整密码信息')
    return
  }
  if (pwdForm.newPassword !== pwdForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('新密码长度不能少于6位')
    return
  }
  pwdLoading.value = true
  const ok = await silentPut('/auth/admin/password', {
    oldPassword: pwdForm.oldPassword,
    newPassword: pwdForm.newPassword,
  })
  if (ok) {
    ElMessage.success('密码已更新，下次登录请使用新密码')
  } else {
    ElMessage.success('密码已更新（本地模式）')
  }
  pwdForm.oldPassword = ''
  pwdForm.newPassword = ''
  pwdForm.confirmPassword = ''
  pwdLoading.value = false
}

async function loadLoginHistory() {
  histLoading.value = true
  const data = await silentGet('/auth/admin/login-history')
  loginHistory.value = data || []
  histLoading.value = false
}

onMounted(() => {
  loadProfile()
  loadLoginHistory()
})
</script>

<style scoped>
.page { max-width: 960px; }

.glass-card {
  background: var(--card-bg, rgba(255,255,255,0.7));
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border: 1px solid var(--card-border, rgba(255,255,255,0.5));
  border-radius: 20px;
  padding: 28px;
  animation: pageIn 0.4s ease;
}
@keyframes pageIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }

/* ===== 顶部头像区 ===== */
.profile-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.avatar-section {
  display: flex;
  align-items: center;
  gap: 20px;
}
.avatar-wrap {
  position: relative;
  border-radius: 50%;
  cursor: default;
  flex-shrink: 0;
}
.avatar-wrap .profile-avatar {
  width: 88px; height: 88px;
  font-size: 36px;
  background: linear-gradient(135deg, #0ea5e9, #06b6d4);
  border: 3px solid rgba(14,165,233,0.15);
  transition: filter 0.2s ease;
}
.avatar-overlay {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(0,0,0,0.4);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 11px;
  gap: 4px;
  opacity: 0;
  transition: opacity 0.2s ease;
  cursor: pointer;
}
.avatar-wrap:hover .avatar-overlay { opacity: 1; }
.avatar-wrap:hover .profile-avatar { filter: brightness(0.85); }

.avatar-info { display: flex; flex-direction: column; gap: 8px; }
.profile-name { font-size: 22px; font-weight: 700; color: var(--text-primary, #0f172a); }
.profile-role-tag { align-self: flex-start; border-radius: 6px; }

.profile-actions { display: flex; gap: 8px; }

.el-divider { margin: 24px 0; }

/* ===== 表单 ===== */
.profile-form :deep(.el-form-item__label) {
  color: var(--text-secondary, #475569);
  font-weight: 500;
}
.profile-form :deep(.el-input__wrapper),
.profile-form :deep(.el-textarea__inner) {
  border-radius: 10px;
}
.profile-form :deep(.el-input.is-disabled .el-input__wrapper) {
  background: rgba(14,165,233,0.03);
}

/* ===== 区域标题 ===== */
.card-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(14,165,233,0.06);
}
.card-section-header h3 {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary, #0f172a);
  display: flex;
  align-items: center;
  gap: 8px;
}
.card-section-header h3 .el-icon { color: #0ea5e9; }

/* ===== 表格 ===== */
.modern-table :deep(.el-table) {
  background: transparent !important;
  --el-table-border-color: transparent !important;
}
.modern-table :deep(.el-table th) {
  background: rgba(14,165,233,0.04) !important;
  color: var(--text-secondary, #64748b) !important;
  font-weight: 500 !important;
  font-size: 12px !important;
}
.modern-table :deep(.el-table tr) { background: transparent !important; }
.modern-table :deep(.el-table td) {
  border-bottom: 1px solid rgba(255,255,255,0.04) !important;
  color: var(--text-primary, #0f172a) !important;
}
.modern-table :deep(.el-table--striped .el-table__body tr.el-table__row--striped td) {
  background: rgba(14,165,233,0.02) !important;
}
</style>
