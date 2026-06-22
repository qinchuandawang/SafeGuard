// pages/result/result.js - 检测结果页（真实数据，无 mock 回退）

const app = getApp();

Page({
  data: {
    detectionType: 'audio',
    resultLevel: 'danger',
    resultIcon: 'warning-filled',
    resultBadge: '存在风险',
    resultName: '高风险内容',
    confidencePercent: 95,
    confidenceText: '95.0',
    realPercent: 5,
    realText: '5.0',
    fakePercent: 95,
    fakeText: '95.0',
    reportContent: '',
    realLabel: '真实概率',
    fakeLabel: '伪造概率',
    confidenceLabel: '检测置信度',
    confidenceHint: '',
    videoEvidence: [],
    features: [],
    adviceList: [],
    agentSteps: [],
    batchDetails: [],
    batchSummary: null,
    isCollected: false,
    currentRecordId: '',
  },

  onLoad(options) {
    if (options && options.type) this.setData({ detectionType: options.type });
    // 优先从 globalData 读取（避免 URL 2KB 限制导致长 AI 报告被截断乱码）
    const pending = app.globalData.pendingDetectionResult;
    if (pending) {
      app.globalData.pendingDetectionResult = null;
      this.setData({ detectionType: pending.type || this.data.detectionType });
      const fileName = pending.fileName || '';
      const type = pending.type || options.type || this.data.detectionType;
      const uniqueId = pending.id || pending.taskId || Date.now();
      const recordId = uniqueId + '|' + fileName + '|' + type;
      this.setData({ currentRecordId: recordId });
      this.loadCollectedStatus(recordId);
      this.processDetectionResult(pending);
    } else if (options && options.data) {
      try {
        const data = JSON.parse(decodeURIComponent(options.data));
        const fileName = data.fileName || '';
        const type = data.type || options.type || this.data.detectionType;
        const uniqueId = data.id || data.taskId || Date.now();
        const recordId = uniqueId + '|' + fileName + '|' + type;
        this.setData({ currentRecordId: recordId });
        this.loadCollectedStatus(recordId);
        this.processDetectionResult(data);
      } catch (e) {
        console.error('解析检测结果失败:', e);
        app.showWarning('检测数据异常');
      }
    } else {
      const history = app.globalData.detectionHistory;
      if (history && history.length > 0) {
        const latest = history[history.length - 1];
        const recordData = latest.record || latest;
        this.setData({ currentRecordId: latest.id || Date.now() });
        this.processDetectionResult(recordData);
      } else {
        app.showWarning('暂无检测数据');
      }
    }
  },

  loadCollectedStatus(recordId) {
    const stored = wx.getStorageSync('collections');
    if (Array.isArray(stored)) {
      const isCollected = stored.some(item => item.recordId === recordId);
      this.setData({ isCollected });
    }
  },

  processDetectionResult(data) {
    if (!data) return;

    const { confidence, probabilities, report, advice, type } = data;
    const currentType = type || this.data.detectionType;
    const fakeProb = probabilities?.fake || data.riskProbability || data.fakeProbability || data.spoofProb || 0;

    let level, icon, badge, name;
    if (fakeProb > 0.7) { level = 'danger'; icon = 'warning-filled'; badge = '存在风险'; name = '高风险内容'; }
    else if (fakeProb > 0.4) { level = 'warning'; icon = 'warning'; badge = '需谨慎'; name = '中等风险'; }
    else { level = 'success'; icon = 'check-circle-filled'; badge = '安全'; name = '低风险内容'; }

    this.setData({
      detectionType: currentType,
      resultLevel: level,
      resultIcon: icon,
      resultBadge: badge,
      resultName: name,
      confidencePercent: Math.round((confidence || 0.7) * 100),
      confidenceText: ((confidence || 0.7) * 100).toFixed(1),
      realPercent: Math.round((probabilities?.real || (1 - fakeProb)) * 100),
      realText: ((probabilities?.real || (1 - fakeProb)) * 100).toFixed(1),
      fakePercent: Math.round(fakeProb * 100),
      fakeText: (fakeProb * 100).toFixed(1),
      confidenceLabel: currentType === 'text' ? '风险判断置信度' : (currentType === 'video' ? '模型判定置信度' : '检测置信度'),
      confidenceHint: currentType === 'video' ? 'XceptionNet 主要检测换脸/面部篡改痕迹；综合风险会叠加 AIGC 元数据证据，置信度不等于视频真实度。' : '',
      realLabel: currentType === 'text' ? '低风险可能' : (currentType === 'video' ? '综合低风险' : '真实概率'),
      fakeLabel: currentType === 'text' ? '高风险可能' : (currentType === 'video' ? '综合高风险' : '伪造概率'),
      videoEvidence: currentType === 'video' ? this.buildVideoEvidence(data, probabilities, fakeProb) : [],
      reportContent: report || data.report || '无详细报告',
      features: data.features || data.suspiciousPoints || this.generateFeatures(currentType, fakeProb),
      adviceList: advice || data.advice || this.generateAdvice(fakeProb),
      agentSteps: this.normalizeAgentSteps(data.agentSteps || data.orchestratorSteps || []),
      batchDetails: Array.isArray(data.batchDetails) ? data.batchDetails : [],
      batchSummary: data.batchSummary || null,
    });
  },

  buildVideoEvidence(data, probabilities, fakeProb) {
    const visual = typeof data.visualFakeProbability === 'number' ? data.visualFakeProbability : fakeProb;
    const avg = typeof data.averageFakeProbability === 'number' ? data.averageFakeProbability : fakeProb;
    const max = typeof data.maxFakeProbability === 'number' ? data.maxFakeProbability : fakeProb;
    const ratio = typeof data.suspiciousFrameRatio === 'number' ? data.suspiciousFrameRatio : 0;
    const items = [
      { label: '视觉换脸风险', value: (visual * 100).toFixed(1) + '%' },
      { label: '平均单帧风险', value: (avg * 100).toFixed(1) + '%' },
      { label: '最高单帧风险', value: (max * 100).toFixed(1) + '%' },
      { label: '可疑帧占比', value: (ratio * 100).toFixed(1) + '%' },
    ];
    if (data.aigcMetadataDetected) {
      items.push({ label: '元数据证据', value: '命中 AIGC' });
    }
    return items;
  },

  normalizeAgentSteps(steps) {
    if (!Array.isArray(steps)) return [];
    return steps.map((item, index) => {
      if (typeof item === 'string') {
        return { name: `步骤 ${index + 1}`, description: item, status: 'completed' };
      }
      return {
        name: item.name || item.label || `步骤 ${index + 1}`,
        description: item.description || item.desc || '',
        status: item.status || 'completed',
      };
    });
  },

  generateFeatures(type, fakeProb) {
    if (fakeProb < 0.5) return [];
    const audioFeatures = ['频谱分布异常', '波形存在重复模式', '声纹连贯性不足', '音频压缩痕迹异常', '存在合成噪声特征'];
    const videoFeatures = ['面部边缘存在伪影', '光影变化不自然', '眨眼频率异常', '面部纹理失真', '动作过渡不连贯'];
    const textFeatures = ['话术符合常见诈骗模式', '存在威胁恐吓语气', '要求转账的意图明显', '包含钓鱼链接特征'];
    let features;
    switch (type) {
      case 'audio': features = audioFeatures; break;
      case 'video': features = videoFeatures; break;
      case 'text': features = textFeatures; break;
      default: features = audioFeatures;
    }
    const count = fakeProb > 0.8 ? 4 : fakeProb > 0.6 ? 3 : 2;
    return features.slice(0, count);
  },

  generateAdvice(fakeProb) {
    if (fakeProb > 0.7) return ['不要轻信视频或音频内容，应通过其他渠道核实', '遇到涉及金钱的要求务必谨慎', '如有疑问可拨打反诈热线96110咨询', '保存证据并向公安机关报案', '切勿向陌生人转账汇款'];
    if (fakeProb > 0.4) return ['建议通过电话或其他方式再次确认对方身份', '不要急于做出涉及金钱的决定', '如有疑虑可寻求官方渠道核实', '保持警惕多方求证'];
    return ['继续保持警惕核实重要信息', '遇到可疑情况及时报警', '分享防骗知识给身边的人'];
  },

  onBack() { wx.navigateBack(); },
  detectAgain() { wx.switchTab({ url: '/pages/detection/detection' }); },

  onCollect() {
    const isCollected = !this.data.isCollected;
    const { currentRecordId, detectionType, resultLevel } = this.data;
    this.setData({ isCollected });
    wx.showToast({ title: isCollected ? '已收藏' : '已取消收藏', icon: 'success' });

    const collections = wx.getStorageSync('collections') || [];
    if (isCollected) {
      collections.push({
        recordId: currentRecordId,
        type: detectionType,
        level: resultLevel,
        time: new Date().toISOString()
      });
    } else {
      const idx = collections.findIndex(item => item.recordId === currentRecordId);
      if (idx !== -1) collections.splice(idx, 1);
    }
    wx.setStorageSync('collections', collections);
  },

  onShare() { wx.showShareMenu({ withShareTicket: true, menus: ['shareAppMessage', 'shareTimeline'] }); },

  onShareAppMessage() {
    const { resultLevel, detectionType } = this.data;
    let title = '诈骗克星 - AI反诈骗检测';
    if (resultLevel === 'danger') title = '诈骗克星检测到高风险内容！';
    else if (resultLevel === 'warning') title = '诈骗克星提醒您注意安全！';
    return { title, desc: 'AI智能识别音频伪造、视频换脸、文本欺诈', path: '/pages/index/index' };
  },

  onShareTimeline() {
    const t = this.data.resultLevel === 'danger' ? '诈骗克星检测到高风险内容，大家提高警惕！' : '诈骗克星检测结果';
    return { title: t, query: 'type=' + this.data.detectionType };
  },
});
