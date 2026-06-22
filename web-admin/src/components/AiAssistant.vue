<template>
  <div class="ai-assistant">
    <!-- 浮动按钮 -->
    <el-button class="ai-fab" :icon="ChatDotRound" circle @click="open = !open" />

    <!-- 聊天面板 -->
    <transition name="ai-slide">
      <div v-if="open" class="ai-panel">
        <div class="ai-panel-header">
          <div class="ai-header-left">
            <el-icon :size="18" style="color:#0ea5e9"><MagicStick /></el-icon>
            <span>AI 安全助手</span>
          </div>
          <el-button :icon="Close" text size="small" @click="open = false" />
        </div>
        <div class="ai-messages" ref="msgRef">
          <div v-for="(msg, i) in messages" :key="i" :class="'msg-' + msg.role">
            <div class="msg-avatar">{{ msg.role === 'ai' ? 'AI' : '我' }}</div>
            <div class="msg-bubble">{{ msg.text }}</div>
          </div>
          <div v-if="thinking" class="msg-ai">
            <div class="msg-avatar">AI</div>
            <div class="msg-bubble thinking-dots"><span>.</span><span>.</span><span>.</span></div>
          </div>
        </div>
        <div class="ai-input-row">
          <el-input v-model="input" placeholder="输入问题..." size="small" @keyup.enter="send" />
          <el-button :icon="Promotion" size="small" type="primary" @click="send" />
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { ChatDotRound, MagicStick, Close, Promotion } from '@element-plus/icons-vue'
import request from '../api/request'

const open = ref(false)
const input = ref('')
const thinking = ref(false)
const msgRef = ref(null)
const messages = ref([
  { role: 'ai', text: '你好！我是 SafeGuard AI 安全助手，可以帮你：\n• 分析检测结果\n• 查询诈骗案例\n• 提供安全建议\n• 解读模型数据' },
])

function scrollDown() {
  msgRef.value?.scrollTo({ top: msgRef.value.scrollHeight, behavior: 'smooth' })
}

async function send() {
  const text = input.value.trim()
  if (!text || thinking.value) return
  messages.value.push({ role: 'user', text })
  input.value = ''
  thinking.value = true
  await nextTick()
  scrollDown()

  // 占位气泡，普通 JSON 接口比浏览器端 SSE 解析更稳定
  const aiIndex = messages.value.length
  messages.value.push({ role: 'ai', text: '' })

  const resp = await request({
    url: '/llm/analyze',
    method: 'post',
    data: { text },
    headers: { __suppressError: true },
  }).catch(err => {
    messages.value[aiIndex].text = 'AI 服务暂时不可用，请检查后端是否已加载 LLM_API_KEY。'
    thinking.value = false
    return null
  })
  if (!resp) {
    return
  }

  messages.value[aiIndex].text = typeof resp === 'string' && resp.trim()
    ? resp
    : 'AI 服务暂时不可用，请稍后重试。'
  thinking.value = false
  await nextTick()
  scrollDown()
}
</script>

<style scoped>
.ai-assistant { position: fixed; bottom: 24px; right: 24px; z-index: 9999; }
.ai-fab { width: 52px; height: 52px; font-size: 22px; box-shadow: 0 4px 20px rgba(14,165,233,0.3); border: none; background: linear-gradient(135deg, #0ea5e9, #06b6d4); color: #fff; }
.ai-fab:hover { transform: scale(1.05); color: #fff; }

.ai-panel {
  position: absolute; bottom: 64px; right: 0;
  width: 360px; height: 480px;
  background: #fff; border-radius: 16px;
  box-shadow: 0 8px 40px rgba(0,0,0,0.1);
  display: flex; flex-direction: column;
  overflow: hidden;
}
.ai-panel-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; border-bottom: 1px solid #f1f5f9;
  background: linear-gradient(135deg, #f0f9ff, #ecfeff);
}
.ai-header-left { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: #0f172a; }

.ai-messages { flex: 1; overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
.msg-ai { display: flex; gap: 8px; align-items: flex-start; }
.msg-user { display: flex; gap: 8px; align-items: flex-start; flex-direction: row-reverse; }
.msg-avatar {
  width: 28px; height: 28px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 10px; font-weight: 700;
}
.msg-ai .msg-avatar { background: linear-gradient(135deg, #0ea5e9, #06b6d4); color: #fff; }
.msg-user .msg-avatar { background: #f1f5f9; color: #64748b; }
.msg-bubble {
  max-width: 80%; padding: 10px 14px; border-radius: 12px;
  font-size: 13px; line-height: 1.6; white-space: pre-line;
}
.msg-ai .msg-bubble { background: #f8fafc; color: #0f172a; border-top-left-radius: 4px; }
.msg-user .msg-bubble { background: #0ea5e9; color: #fff; border-top-right-radius: 4px; }

.thinking-dots { display: flex; gap: 2px; padding: 12px 16px !important; }
.thinking-dots span { animation: dotPulse 1.2s infinite; font-size: 20px; line-height: 0; }
.thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
.thinking-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotPulse { 0%,60%,100% { opacity: 0.3; } 30% { opacity: 1; } }

.ai-input-row { display: flex; gap: 8px; padding: 12px 16px; border-top: 1px solid #f1f5f9; }
.ai-input-row .el-input { flex: 1; }

.ai-slide-enter-active { transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1); }
.ai-slide-leave-active { transition: all 0.15s cubic-bezier(0.4, 0, 0.2, 1); }
.ai-slide-enter-from { opacity: 0; transform: translateY(12px) scale(0.96); }
.ai-slide-leave-to { opacity: 0; transform: translateY(8px) scale(0.96); }
</style>
