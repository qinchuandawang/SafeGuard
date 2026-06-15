// pages/simulate/simulate.js - 模拟诈骗体验（真实 API，无 mock 回退）

const app = getApp();
const { simulateAPI } = require('../../utils/request');

Page({
  data: {
    scripts: [],
    selectedScript: null,
    currentScript: null,
    messages: [],
    inputText: '',
    scrollTop: 0,
    isTyping: false,
    quickReplies: [],
    streamingContent: '',
    isStreaming: false,
    streamingMessageId: null,
    showStartTip: false,
    showSummary: false,
    responseCount: 0,
    suspicionScore: 50,
    analysis: '',
    tips: [],
    turnCount: 0,
    maxTurns: 10,
    maxMessages: 100,
    inputFocused: false,
    loadingError: null,
  },

  onLoad() {
    this._destroyed = false;
    this.loadScripts();
  },

  onShow() {
    if (this.data.selectedScript && !this.data.isTyping) {
      this.setData({ inputFocused: true });
    }
  },

  onHide() { this._destroyed = true; this.stopStreaming(); },
  onUnload() { this._destroyed = true; this.stopStreaming(); },

  async loadScripts() {
    try {
      const res = await simulateAPI.getScripts();
      const scripts = (Array.isArray(res) ? res : (res?.data || res?.scripts || this.getDefaultScripts())).map(s => ({
        id: s.id || s.scriptId,
        name: s.name,
        type: s.id || 'relative',
        icon: 'user',
        color: s.color || '#667eea',
        difficulty: s.difficulty || 'medium',
        difficultyText: s.difficultyText || '中等难度',
        description: s.description || '',
        tags: s.tags || [],
      }));
      this.setData({ scripts: scripts.length > 0 ? scripts : this.getDefaultScripts() });
    } catch (err) {
      console.debug('剧本接口不可用，使用本地默认剧本:', err && (err.errMsg || err.message || err));
      this.setData({ scripts: this.getDefaultScripts() });
    }
  },

  getDefaultScripts() {
    return [
      { id: 'police', name: '冒充公检法', type: 'police', icon: 'user', color: '#ef4444', difficulty: 'high', difficultyText: '高难度', description: '诈骗者冒充警察、检察官等，声称您涉嫌犯罪，要求转账到安全账户', tags: ['高发诈骗', '冒充公检法', '转账诈骗'] },
      { id: 'relative', name: '冒充熟人诈骗', type: 'relative', icon: 'user', color: '#f59e0b', difficulty: 'medium', difficultyText: '中等难度', description: '诈骗者冒充您的亲友，以紧急情况为由请求汇款', tags: ['高发诈骗', '熟人诈骗', '紧急汇款'] },
      { id: 'investment', name: '投资理财诈骗', type: 'investment', icon: 'chart', color: '#10b981', difficulty: 'medium', difficultyText: '中等难度', description: '以高收益投资机会为诱饵，诱导您投入资金', tags: ['投资诈骗', '高收益陷阱', '非法集资'] },
      { id: 'online', name: '网购客服诈骗', type: 'online', icon: 'shop', color: '#3b82f6', difficulty: 'low', difficultyText: '入门难度', description: '冒充电商客服，以退款、补偿为由骗取信息或钱财', tags: ['网购诈骗', '客服诈骗', '信息盗取'] },
    ];
  },

  onBack() {
    if (this.data.selectedScript) { this.showExitConfirm(); }
    else { const p = getCurrentPages(); p.length > 1 ? wx.navigateBack() : wx.switchTab({ url: '/pages/index/index' }); }
  },

  showExitConfirm() {
    wx.showModal({
      title: '退出模拟', content: '确定要退出当前模拟吗？退出后本次对话记录将不会保存。', confirmText: '确定退出', cancelText: '继续模拟',
      success: (res) => { if (res.confirm) this.resetSimulation(); },
    });
  },

  selectScript(e) {
    const { id } = e.currentTarget.dataset;
    const script = this.data.scripts.find(s => s.id === id);
    if (script) this.setData({ currentScript: script, showStartTip: true });
  },

  onTipVisibleChange(e) {
    // e 为 TDesign popup visible-change 事件：{ detail: { visible: boolean } }
    const visible = e?.detail?.visible === true;
    this.setData({ showStartTip: visible });
  },

  showScriptInfo() {
    const { currentScript } = this.data;
    if (!currentScript) return;
    wx.showModal({ title: currentScript.name, content: currentScript.description + '\n\n难度：' + currentScript.difficultyText + '\n标签：' + (currentScript.tags || []).join('、'), showCancel: false });
  },

  async startSimulation() {
    this.setData({
      showStartTip: false, selectedScript: true, messages: [],
      responseCount: 0, turnCount: 0, suspicionScore: 50, quickReplies: [],
      loadingError: null,
    });

    try {
      const response = await simulateAPI.startSimulation(this.data.currentScript.id);
      const content = typeof response === 'string' ? response : (response.content || response.message || response.data || '模拟开始');
      await this._processAIMessage({ content });
    } catch (err) {
      console.error('开始模拟失败:', err);
      this.setData({ loadingError: '无法连接到AI服务，请检查网络后重试' });
    }
  },

  async _processAIMessage(response) {
    const aiMessageId = Date.now();
    const defaultQuickReplies = ['你是谁？', '怎么回事？', '说详细点', '我要核实你身份'];
    const safeResponse = response || {};
    const quickReplies = Array.isArray(safeResponse.quickReplies) && safeResponse.quickReplies.length > 0 ? safeResponse.quickReplies : defaultQuickReplies;
    const aiContent = typeof safeResponse.content === 'string' ? safeResponse.content : '';

    this.setData({ isTyping: true, quickReplies });

    const newMessages = [...this.data.messages, { id: aiMessageId, role: 'ai', content: '', time: this.formatTime(new Date()) }];
    this.setData({ messages: newMessages });
    this.scrollToBottom();

    await this.tryStream(aiMessageId, { ...safeResponse, content: aiContent });
  },

  async sendMessage() {
    const { inputText, messages, turnCount, isTyping } = this.data;
    if (!inputText.trim() || isTyping) return;

    const sanitized = inputText.trim()
      .replace(/[<>]/g, '')
      .substring(0, 2000);
    if (!sanitized) return;

    // 限制消息数量防止内存溢出
    const maxMsg = this.data.maxMessages;
    const msgsToKeep = messages.length >= maxMsg
      ? messages.slice(messages.length - maxMsg + 1)
      : messages;

    const userMessage = { id: Date.now(), role: 'user', content: sanitized, time: this.formatTime(new Date()) };
    this.setData({ messages: [...msgsToKeep, userMessage], inputText: '', responseCount: this.data.responseCount + 1, isTyping: true, quickReplies: [], loadingError: null });
    this.scrollToBottom();

    try {
      const response = await simulateAPI.continueConversation(
        this.data.currentScript.id,
        userMessage.content,
        messages.map(m => ({ role: m.role, content: m.content }))
      );

      const aiContent = typeof response === 'string' ? response : (response.content || response.message || '');
      await this._processAIMessage({ content: aiContent, quickReplies: response?.quickReplies });

      // 自增轮次计数（P0 fix: E1）
      const newTurnCount = this.data.turnCount + 1;
      this.setData({ turnCount: newTurnCount });

      if (response && typeof response !== 'string' && response.suspicionDelta) {
        const newScore = Math.max(0, Math.min(100, this.data.suspicionScore + response.suspicionDelta));
        this.setData({ suspicionScore: newScore });
      }
      if (response && typeof response !== 'string' && response.finished) {
        setTimeout(() => this.showEndSummary(response), 800);
      } else if (newTurnCount >= this.data.maxTurns) {
        // 达到最大轮次，自动结束模拟
        const endData = { analysis: '', tips: [] };
        this.showEndSummary(endData);
      }
    } catch (err) {
      console.error('发送消息失败:', err);
      this.setData({ isTyping: false, loadingError: '消息发送失败，请重试' });
    }
  },

  tryStream(messageId, response) {
    if (this._sseRequestTask) { this._sseRequestTask.abort(); this._sseRequestTask = null; }

    return new Promise((resolve) => {
      this._streamResolve = resolve;

      if (response.streamUrl) {
        const requestTask = wx.request({
          url: response.streamUrl, method: 'GET', enableChunked: true,
          success: () => {}, fail: () => {
            if (response.content) this.streamContent(messageId, response.content);
            else { this.finishStreaming(messageId, ''); }
          },
        });
        this._sseRequestTask = requestTask;
        this.enableSSEStreaming(messageId, requestTask);
        return;
      }

      if (response.content) {
        this.streamContent(messageId, response.content);
      } else {
        this.finishStreaming(messageId, '');
      }
    });
  },

  enableSSEStreaming(messageId, requestTask) {
    let fullContent = '';
    let buffer = '';
    requestTask.onChunkReceived((res) => {
      if (!this.data.isStreaming || this.data.streamingMessageId !== messageId) return;
      try {
        const text = typeof TextDecoder !== 'undefined'
            ? new TextDecoder('utf-8').decode(new Uint8Array(res.data))
            : String.fromCharCode.apply(null, new Uint8Array(res.data));
        buffer += text;
        const parts = buffer.split('\n\n');
        buffer = parts.pop() || '';
        for (const part of parts) {
          const lines = part.split('\n');
          for (const line of lines) {
            if (line.startsWith('data: ')) {
              const payload = line.slice(6).trim();
              if (payload === '[DONE]') { this.finishStreaming(messageId, fullContent); return; }
              try {
                const parsed = JSON.parse(payload);
                if (parsed.content) { fullContent += parsed.content; this.updateStreamingMessage(messageId, fullContent); }
              } catch (_) { fullContent += payload; this.updateStreamingMessage(messageId, fullContent); }
            }
          }
        }
      } catch (err) { console.warn('[SSE] parse error:', err); }
    });
  },

  updateStreamingMessage(messageId, content) {
    const messages = this.data.messages.map(msg => msg.id === messageId ? { ...msg, content } : msg);
    this.setData({ messages, streamingContent: content });
    this.scrollToBottom();
  },

  finishStreaming(messageId, fullContent) {
    this.updateStreamingMessage(messageId, fullContent);
    this.setData({ isTyping: false, streamingMessageId: null, streamingContent: '', inputFocused: true });
    this._sseRequestTask = null;

    // 解析 streaming Promise，使 _processAIMessage 继续执行
    if (this._streamResolve) {
      this._streamResolve();
      this._streamResolve = null;
    }
  },

  async streamContent(messageId, fullContent) {
    this.setData({ isStreaming: true, streamingMessageId: messageId, streamingContent: '' });
    for (let i = 0; i <= fullContent.length; i++) {
      if (this._destroyed || !this.data.isStreaming || this.data.streamingMessageId !== messageId) break;
      this.updateStreamingMessage(messageId, fullContent.slice(0, i));
      const char = fullContent[i - 1];
      let delay = 30;
      if (char && /[一-龥]/.test(char)) delay = 40;
      else if (char && /[.,!?;:，。！？；：]/.test(char)) delay = 120;
      else if (char === '\n') delay = 80;
      await this.sleep(delay);
    }
    if (!this._destroyed) {
      this.finishStreaming(messageId, fullContent);
    }
  },

  stopStreaming() {
    if (this._sseRequestTask) { this._sseRequestTask.abort(); this._sseRequestTask = null; }
    if (this.data.isStreaming && this.data.streamingMessageId) {
      const messages = this.data.messages.map(msg => msg.id === this.data.streamingMessageId ? { ...msg, content: this.data.streamingContent, isLoading: false } : msg);
      this.setData({ messages, isTyping: false, isStreaming: false, streamingMessageId: null });
    }
    if (this._streamResolve) { this._streamResolve(); this._streamResolve = null; }
  },

  sleep(ms) { return new Promise(resolve => setTimeout(resolve, ms)); },

  sendQuickReply(e) {
    this.setData({ inputText: e.currentTarget.dataset.text });
    this.sendMessage();
  },

  onInput(e) { this.setData({ inputText: e.detail.value }); },

  scrollToBottom() {
    setTimeout(() => this.setData({ scrollTop: this.data.messages.length * 1000 + 1 }), 50);
  },

  formatTime(date) {
    return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0');
  },

  showEndSummary(response) {
    const { suspicionScore, currentScript, messages } = this.data;
    if (response?.analysis) {
      this.setData({ showSummary: true, analysis: response.analysis, tips: response.tips || [] });
      return;
    }
    this._fetchEndAnalysisFromBackend(currentScript?.id, messages, suspicionScore);
  },

  async _fetchEndAnalysisFromBackend(scriptId, messages, suspicionScore) {
    try {
      const result = await simulateAPI.endSimulation(scriptId || 'relative', messages.map(m => ({ role: m.role, content: m.content })));
      this.setData({ showSummary: true, analysis: result.analysis || '', tips: result.tips || [] });
    } catch (err) {
      console.warn('获取AI分析失败:', err);
      app.showWarning('获取分析报告失败');
      this.setData({ showSummary: true, analysis: '无法获取AI分析报告，请检查网络连接。', tips: ['遇到可疑情况请拨打96110'] });
    }
  },

  onSummaryVisibleChange(e) {
    const visible = e?.detail?.visible === true;
    this.setData({ showSummary: visible });
  },

  async restartSimulation() {
    this.setData({ showSummary: false });
    await this.sleep(300);
    await this.startSimulation();
  },

  backToList() {
    try { this.stopStreaming(); } catch (e) { console.warn('停止流式输出失败:', e); }
    this.resetSimulation();
  },

  resetSimulation() {
    this.stopStreaming();
    this.setData({
      selectedScript: null, currentScript: null, messages: [], quickReplies: [],
      showStartTip: false, showSummary: false, responseCount: 0, suspicionScore: 50,
      turnCount: 0, inputText: '', isTyping: false, isStreaming: false,
      streamingMessageId: null, streamingContent: '', loadingError: null,
    });
  },

  loadMore() {},

  openQA() {
    const app = getApp();
    app.globalData.openQA = true;
    wx.switchTab({ url: '/pages/knowledge/knowledge' });
  },
});
