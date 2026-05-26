// pages/detection/detection.js - 内容检测（真实 API 调用，无 mock 回退）

const app = getApp();
const { detectionAPI, recordAPI } = require('../../utils/request');
const auth = require('../../utils/auth');
const TaskWatcher = require('../../utils/taskWatcher');

const MAX_AUDIO_SIZE = 10 * 1024 * 1024; // 10MB
const MAX_VIDEO_SIZE = 50 * 1024 * 1024; // 50MB

Page({
  data: {
    currentType: 'audio',
    audioFile: null,
    videoFile: null,
    textContent: '',
    isDetecting: false,
    canDetect: false,
    showResult: false,
    detectionResult: null,
    resultLevel: 'warning',
    resultIcon: 'info-circle',
    resultIconColor: '#f59e0b',
    resultName: '检测中...',
    resultDesc: '',
    showAgentProcess: false,
    showKnowledgeSources: false,
    examples: [
      '您好，我是公安局民警，您涉嫌一起洗钱案件，请配合调查并将资金转入安全账户',
      '恭喜您中奖了！请先支付手续费领取奖金',
      '您的银行账户存在异常，请点击链接核实身份',
      '我是你领导，现在需要转账到这个账户，紧急用',
      '您的快递因地址不详被扣留，请联系客服处理',
    ],
    uploadError: null,
  },

  onLoad(options) {
    if (options.type) this.setData({ currentType: options.type });
    this.updateCanDetect();
  },

  onUnload() {
    if (this._taskWatcher) {
      this._taskWatcher.destroy();
      this._taskWatcher = null;
    }
  },

  updateCanDetect() {
    const { currentType, audioFile, videoFile, textContent, isDetecting } = this.data;
    let can = false;
    switch (currentType) {
      case 'audio': can = !!audioFile && !isDetecting; break;
      case 'video': can = !!videoFile && !isDetecting; break;
      case 'text': can = textContent.trim().length >= 10 && !isDetecting; break;
      case 'multi': can = ((!!audioFile || !!videoFile) || textContent.trim().length >= 10) && !isDetecting; break;
    }
    this.setData({ canDetect: can });
  },

  onTabChange(e) {
    this.setData({ currentType: e.detail.value });
    this.updateCanDetect();
  },

  onBack() { wx.navigateBack(); },

  async chooseAudio() {
    if (this.data.audioFile) { this.removeAudio(); return; }
    try {
      const res = await wx.chooseMessageFile({ count: 1, type: 'audio' });
      if (res && res.tempFiles && res.tempFiles.length > 0) {
        const file = res.tempFiles[0];
        const name = file.name || '';
        const ext = name.split('.').pop().toLowerCase();
        const allowedAudioExts = ['mp3', 'wav', 'aac', 'm4a', 'ogg', 'wma', 'flac', 'amr', 'opus'];
        if (ext && !allowedAudioExts.includes(ext)) {
          app.showWarning('不支持的音频格式，请选择mp3/wav/aac/m4a/ogg格式');
          return;
        }
        if (file.size > MAX_AUDIO_SIZE) {
          app.showWarning('音频文件不能超过10MB');
          return;
        }
        this.setData({
          audioFile: { path: file.path, name: name, size: this.formatFileSize(file.size) },
          uploadError: null,
        });
        this.updateCanDetect();
      }
    } catch (err) {
      if (err.errMsg && !err.errMsg.includes('cancel')) {
        app.showWarning('选择音频失败');
      }
    }
  },

  removeAudio() {
    wx.showModal({
      title: '提示', content: '确定要移除该音频吗？',
      success: (res) => {
        if (res.confirm) {
          this.setData({ audioFile: null });
          this.updateCanDetect();
        }
      },
    });
  },

  async chooseVideo() {
    if (this.data.videoFile) { this.removeVideo(); return; }
    try {
      const res = await wx.chooseVideo({ sourceType: ['album', 'camera'], maxDuration: 60, camera: 'back' });
      if (res) {
        const filePath = res.tempFilePath || '';
        const ext = filePath.split('.').pop().toLowerCase();
        const allowedVideoExts = ['mp4', 'avi', 'mov', 'mkv', 'flv', 'wmv', 'webm', 'mpeg'];
        if (ext && !allowedVideoExts.includes(ext)) {
          app.showWarning('不支持的视频格式，请选择mp4/avi/mov/mkv格式');
          return;
        }
        if (res.size > MAX_VIDEO_SIZE) { app.showWarning('视频文件不能超过50MB'); return; }
        this.setData({
          videoFile: { path: res.tempFilePath, name: res.tempFilePath.split('/').pop(), size: this.formatFileSize(res.size), duration: Math.ceil(res.duration) },
          uploadError: null,
        });
        this.updateCanDetect();
      }
    } catch (err) {
      if (err.errMsg && !err.errMsg.includes('cancel')) app.showWarning('选择视频失败');
    }
  },

  removeVideo() {
    wx.showModal({
      title: '提示', content: '确定要移除该视频吗？',
      success: (res) => {
        if (res.confirm) { this.setData({ videoFile: null }); this.updateCanDetect(); }
      },
    });
  },

  onTextChange(e) {
    this.setData({ textContent: e.detail.value });
    this.updateCanDetect();
  },

  useExample(e) {
    this.setData({ textContent: e.currentTarget.dataset.text });
    this.updateCanDetect();
  },

  formatFileSize(size) {
    if (size < 1024) return size + ' B';
    if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
    return (size / (1024 * 1024)).toFixed(1) + ' MB';
  },

  async startDetection() {
    if (!this.data.canDetect) return;
    const { currentType, audioFile, videoFile, textContent } = this.data;

    if (currentType === 'audio' && !audioFile) { app.showWarning('请先上传音频文件'); return; }
    if (currentType === 'video' && !videoFile) { app.showWarning('请先上传视频文件'); return; }
    if (currentType === 'text' && !textContent.trim()) { app.showWarning('请输入要检测的文本'); return; }

    this.setData({ isDetecting: true, canDetect: false, uploadError: null });

    try {
      let result;
      switch (currentType) {
        case 'audio':
          result = await detectionAPI.detectAudio(audioFile.path);
          break;
        case 'video':
          result = await detectionAPI.detectVideo(videoFile.path);
          if (result && result.taskId && result.status === 'processing') {
            this.watchVideoTask(result.taskId);
            return;
          }
          break;
        case 'text':
          result = await detectionAPI.detectText(textContent);
          break;
        case 'multi':
          // 综合检测：将音频/视频/文本的检测结果聚合为文本，交由 LLM 综合研判（非原始多模态融合）
          result = await detectionAPI.detectMulti(audioFile?.path, videoFile?.path, textContent);
          break;
      }
      this.handleDetectionResult(result);
    } catch (err) {
      console.error('检测失败:', err);
      app.showError('检测失败，请检查服务是否可用');
      this.setData({ isDetecting: false });
      this.updateCanDetect();
    }
  },

  async saveDetectionResult(result) {
    try {
      const userInfo = auth.getUserInfo();
      if (userInfo && userInfo.userId && result) {
        const fakeProb = result.probabilities?.fake || result.spoofProb || 0;
        const recordData = {
          taskId: Date.now() + '',
          userId: userInfo.userId,
          detectionType: this.data.currentType,
          fileName: this.data.audioFile?.name || this.data.videoFile?.name || '',
          status: 'completed',
          result: fakeProb > 0.7 ? 'dangerous' : fakeProb > 0.4 ? 'suspicious' : 'safe',
          riskScore: Math.round(fakeProb * 100),
          spoofProbability: fakeProb,
          analysisDetail: result.report || JSON.stringify(result),
          processingTimeMs: result.latencyMs || 0,
        };
        await recordAPI.save(recordData);
      }
    } catch (e) {
      console.warn('保存检测结果到后端失败:', e);
    }
  },

  watchVideoTask(taskId) {
    if (this._taskWatcher) this._taskWatcher.destroy();
    const watcher = new TaskWatcher();
    this._taskWatcher = watcher;
    watcher.watch(taskId, {
      onProgress: (progress, data) => {
        this.setData({ resultName: '分析中...', resultDesc: data?.message || ('正在分析' + progress + '%...') });
      },
      onDone: (result) => {
        this.setData({ isDetecting: false });
        this.updateCanDetect();
        this.handleDetectionResult(result);
      },
      onError: (err) => {
        this.setData({ isDetecting: false });
        this.updateCanDetect();
        console.error('视频检测任务失败:', err);
        app.showError('视频检测失败，请重试');
      },
    });
  },

  handleDetectionResult(result) {
    if (!result) {
      app.showError('检测服务返回为空');
      this.setData({ isDetecting: false });
      this.updateCanDetect();
      return;
    }

    const type = this.data.currentType;
    const confidence = result.confidence || 0.7;
    const probabilities = result.probabilities || {
      real: 1 - (result.spoofProb || 0.5),
      fake: result.spoofProb || 0.5,
    };

    this.saveDetectionResult(result);

    let level, icon, iconColor, name, desc;
    const fakeProb = probabilities.fake || 0;

    if (fakeProb > 0.7) {
      level = 'danger'; icon = 'warning-filled'; iconColor = '#ef4444';
      name = '高风险'; desc = '检测到明显的AI伪造特征';
    } else if (fakeProb > 0.4) {
      level = 'warning'; icon = 'warning'; iconColor = '#f59e0b';
      name = '中等风险'; desc = '存在可疑特征，需谨慎对待';
    } else {
      level = 'success'; icon = 'check-circle-filled'; iconColor = '#10b981';
      name = '低风险'; desc = '未检测到明显的伪造痕迹';
    }

    app.addDetectionRecord({
      type,
      result,
      resultLevel: level,
      fileName: this.data.audioFile?.name || this.data.videoFile?.name || '',
      report: result.report || '',
      riskScore: Math.round(fakeProb * 100),
      recordData: true,
    });

    this.setData({
      showResult: true,
      detectionResult: {
        ...result,
        type,
        confidence,
        probabilities,
        confidenceText: (confidence * 100).toFixed(1),
        realText: (probabilities.real * 100).toFixed(1),
        fakeText: (fakeProb * 100).toFixed(1),
      },
      resultLevel: level,
      resultIcon: icon,
      resultIconColor: iconColor,
      resultName: name,
      resultDesc: desc,
      isDetecting: false,
    });
    this.updateCanDetect();
  },

  onResultVisibleChange(e) {
    this.setData({ showResult: e.detail.visible });
  },

  closeResult() { this.setData({ showResult: false }); },

  toggleAgentProcess() {
    this.setData({ showAgentProcess: !this.data.showAgentProcess });
  },

  toggleKnowledgeSources() {
    this.setData({ showKnowledgeSources: !this.data.showKnowledgeSources });
  },

  viewFullReport() {
    const { detectionResult, currentType } = this.data;
    wx.navigateTo({
      url: '/pages/result/result?type=' + currentType + '&data=' + encodeURIComponent(JSON.stringify(detectionResult)),
    });
  },

  openQA() {
    const app = getApp();
    app.globalData.openQA = true;
    wx.switchTab({ url: '/pages/knowledge/knowledge' });
  },
});
