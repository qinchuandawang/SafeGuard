// pages/detection/detection.js - 内容检测（真实 API 调用，无 mock 回退）

const app = getApp();
const { detectionAPI } = require('../../utils/request');
const TaskWatcher = require('../../utils/taskWatcher');

const MAX_AUDIO_SIZE = 20 * 1024 * 1024; // 20MB，与后端保持一致
const MAX_VIDEO_SIZE = 100 * 1024 * 1024; // 100MB，与后端保持一致
const MAX_TEXT_DOC_SIZE = 10 * 1024 * 1024; // 10MB
const ALLOWED_AUDIO_EXTS = ['mp3', 'wav', 'm4a', 'ogg', 'flac'];
const ALLOWED_VIDEO_EXTS = ['mp4', 'avi', 'mov', 'mkv', 'webm'];
const ALLOWED_TEXT_DOC_EXTS = ['txt', 'pdf', 'docx'];

Page({
  data: {
    currentType: 'audio',
    audioFile: null,
    audioFiles: [],
    videoFile: null,
    textDocument: null,
    textContent: '',
    isDetecting: false,
    canDetect: false,
    workflowStage: 'idle',
    workflowProgress: 0,
    workflowStatusText: '待输入',
    agentPanel: {
      title: '多模态 Agent 工作台',
      desc: '等待输入内容后，自动调度对应检测链路。',
      statusClass: 'idle',
      channelValue: '0/3',
      channelLabel: '待接入',
      ragValue: '待机',
      ragLabel: '知识增强',
      aiValue: '待机',
      aiLabel: '综合研判',
      liveText: '',
      steps: [
        { index: 1, label: '输入', state: 'active' },
        { index: 2, label: '分析', state: '' },
        { index: 3, label: '建议', state: '' },
      ],
    },
    showResult: false,
    detectionResult: null,
    resultLevel: 'warning',
    resultIcon: 'info-circle',
    resultIconColor: '#f59e0b',
    resultName: '检测中...',
    resultDesc: '',
    showAgentProcess: false,
    showKnowledgeSources: false,
    processLogs: [],      // 处理日志列表
    processProgress: 0,   // 整体进度 0-100
    processStage: '',     // 当前阶段名称
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
    const { currentType, audioFile, audioFiles, videoFile, textDocument, textContent, isDetecting } = this.data;
    let can = false;
    switch (currentType) {
      case 'audio': can = ((audioFiles && audioFiles.length > 0) || !!audioFile) && !isDetecting; break;
      case 'video': can = !!videoFile && !isDetecting; break;
      case 'text': can = (textContent.trim().length >= 1 || !!textDocument) && !isDetecting; break;
      case 'multi': can = ((!!audioFile || !!videoFile || !!textDocument) || textContent.trim().length >= 1) && !isDetecting; break;
    }
    this.setData({ canDetect: can });
    this.updateAgentPanel();
  },

  setWorkflow(stage, statusText, progress, extra = {}) {
    const data = {
      ...extra,
    };
    if (stage) data.workflowStage = stage;
    if (statusText) data.workflowStatusText = statusText;
    if (typeof progress === 'number') data.workflowProgress = Math.max(0, Math.min(100, progress));
    this.setData(data, () => this.updateAgentPanel(data));
  },

  updateAgentPanel(extra = {}) {
    const {
      currentType, audioFile, audioFiles, videoFile, textDocument, textContent,
      workflowStage, workflowStatusText, workflowProgress, isDetecting, detectionResult, processLogs, processStage,
    } = this.data;
    const channelMap = { audio: 1, video: 1, text: 1, multi: 3 };
    const hasInput = !!audioFile || !!videoFile || !!textDocument || (textContent || '').trim().length > 0;
    const activeChannels = currentType === 'multi'
      ? [audioFile, videoFile, textDocument || ((textContent || '').trim() ? true : null)].filter(Boolean).length
      : (hasInput ? channelMap[currentType] || 1 : 0);
    const stage = extra.workflowStage || workflowStage;
    const status = extra.workflowStatusText || workflowStatusText;
    const progress = typeof extra.workflowProgress === 'number' ? extra.workflowProgress : workflowProgress;
    const isDone = stage === 'done';
    const isAnalyzing = stage === 'analyzing';
    const isAdvising = stage === 'advising';
    const isUploaded = stage === 'uploaded';
    const typeName = { audio: '音频', video: '视频', text: '文本', multi: '多模态' }[currentType] || '内容';
    const desc = isDone
      ? `${typeName}链路已完成，已生成风险判断和防范建议。`
      : isAnalyzing
        ? `${typeName}链路正在运行：${status}`
        : isAdvising
          ? '检测结果已返回，DeepSeek 正在组织防范建议。'
          : isUploaded
            ? `${typeName}输入已就绪，点击开始检测后调度模型。`
            : '等待输入内容后，自动调度对应检测链路。';
    const statusClass = isDone ? 'done' : (isDetecting || isAnalyzing || isAdvising ? 'running' : (isUploaded ? 'ready' : 'idle'));
    const steps = [
      { index: 1, label: '输入', state: isUploaded || isAnalyzing || isAdvising || isDone ? 'done' : 'active' },
      { index: 2, label: '分析', state: isDone || isAdvising ? 'done' : (isAnalyzing ? 'active' : '') },
      { index: 3, label: '建议', state: isDone ? 'done' : (isAdvising ? 'active' : '') },
    ];
    const logs = Array.isArray(processLogs) ? processLogs : [];
    const latestLog = logs.length > 0 ? logs[logs.length - 1].text : '';
    const liveText = latestLog || processStage || (isUploaded || isAnalyzing || isAdvising || isDone ? status : '');
    this.setData({
      agentPanel: {
        title: `${typeName} Agent 工作台`,
        desc,
        statusClass,
        channelValue: `${activeChannels}/${channelMap[currentType] || 1}`,
        channelLabel: isDone ? '已完成通道' : (activeChannels > 0 ? '已接入通道' : '待接入'),
        ragValue: currentType === 'video' ? '元数据' : (isAnalyzing || isAdvising || isDone ? '启用' : '待机'),
        ragLabel: currentType === 'video' ? '证据增强' : '知识增强',
        aiValue: isDone ? '完成' : (isAdvising || isAnalyzing ? `${Math.max(0, Math.min(100, progress))}%` : '待机'),
        aiLabel: detectionResult ? '综合研判' : '调度进度',
        liveText,
        steps,
      },
    });
  },

  onTabChange(e) {
    this.setData({ currentType: e.detail.value });
    this.updateCanDetect();
  },

  onBack() { wx.navigateBack(); },

  async chooseAudio() {
    if (this.data.audioFile) { this.removeAudio(); return; }
    try {
      const res = await wx.chooseMessageFile({ count: this.data.currentType === 'audio' ? 9 : 1, type: 'audio' });
      if (res && res.tempFiles && res.tempFiles.length > 0) {
        const files = [];
        for (const file of res.tempFiles) {
          const name = file.name || '';
          const ext = name.split('.').pop().toLowerCase();
          if (ext && !ALLOWED_AUDIO_EXTS.includes(ext)) {
            app.showWarning('不支持的音频格式，请选择mp3/wav/m4a/ogg/flac格式');
            return;
          }
          if (file.size > MAX_AUDIO_SIZE) {
            app.showWarning('单个音频文件不能超过20MB');
            return;
          }
          files.push({ path: file.path, name: name, size: this.formatFileSize(file.size) });
        }
        this.setData({
          audioFile: files[0],
          audioFiles: files,
          uploadError: null,
          workflowStage: 'uploaded',
          workflowStatusText: files.length > 1 ? `已选择 ${files.length} 个音频` : '音频已就绪',
          workflowProgress: 15,
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
          this.setData({ audioFile: null, audioFiles: [], workflowStage: 'idle', workflowStatusText: '待输入', workflowProgress: 0 });
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
        if (ext && !ALLOWED_VIDEO_EXTS.includes(ext)) {
          app.showWarning('不支持的视频格式，请选择mp4/avi/mov/mkv/webm格式');
          return;
        }
        if (res.size > MAX_VIDEO_SIZE) { app.showWarning('视频文件不能超过100MB'); return; }
        this.setData({
          videoFile: { path: res.tempFilePath, name: res.tempFilePath.split('/').pop(), size: this.formatFileSize(res.size), duration: Math.ceil(res.duration) },
          uploadError: null,
          workflowStage: 'uploaded',
          workflowStatusText: '视频已就绪',
          workflowProgress: 15,
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
        if (res.confirm) { this.setData({ videoFile: null, workflowStage: 'idle', workflowStatusText: '待输入', workflowProgress: 0 }); this.updateCanDetect(); }
      },
    });
  },

  onTextChange(e) {
    const text = e.detail.value;
    this.setData({
      textContent: text,
      workflowStage: text.trim().length >= 1 ? 'uploaded' : 'idle',
      workflowStatusText: text.trim().length >= 1 ? '文本已就绪' : '待输入',
      workflowProgress: text.trim().length >= 1 ? 15 : 0,
    });
    this.updateCanDetect();
  },

  useExample(e) {
    this.setData({ textContent: e.currentTarget.dataset.text, workflowStage: 'uploaded', workflowStatusText: '文本已就绪', workflowProgress: 15 });
    this.updateCanDetect();
  },

  async chooseTextDocument() {
    if (this.data.textDocument) { this.removeTextDocument(); return; }
    try {
      const res = await wx.chooseMessageFile({ count: 1, type: 'file' });
      if (!res || !res.tempFiles || res.tempFiles.length === 0) return;
      const file = res.tempFiles[0];
      const name = file.name || '未命名文档';
      const ext = name.includes('.') ? name.split('.').pop().toLowerCase() : '';
      if (!ALLOWED_TEXT_DOC_EXTS.includes(ext)) {
        app.showWarning('仅支持 txt/pdf/docx 文档');
        return;
      }
      if (file.size > MAX_TEXT_DOC_SIZE) {
        app.showWarning('文档不能超过10MB');
        return;
      }
      this.setData({
        textDocument: { path: file.path, name, size: this.formatFileSize(file.size), ext },
        workflowStage: 'uploaded',
        workflowStatusText: '文档已就绪',
        workflowProgress: 15,
      });
      this.updateCanDetect();
    } catch (err) {
      if (err.errMsg && !err.errMsg.includes('cancel')) app.showWarning('选择文档失败');
    }
  },

  removeTextDocument() {
    const hasText = this.data.textContent.trim().length > 0;
    this.setData({
      textDocument: null,
      workflowStage: hasText ? 'uploaded' : 'idle',
      workflowStatusText: hasText ? '文本已就绪' : '待输入',
      workflowProgress: hasText ? 15 : 0,
    });
    this.updateCanDetect();
  },

  clearTextInput() {
    const hasDocument = !!this.data.textDocument;
    this.setData({
      textContent: '',
      workflowStage: hasDocument ? 'uploaded' : 'idle',
      workflowStatusText: hasDocument ? '文档已就绪' : '待输入',
      workflowProgress: hasDocument ? 15 : 0,
    });
    this.updateCanDetect();
  },

  clearAllInputs() {
    this.setData({
      audioFile: null,
      audioFiles: [],
      videoFile: null,
      textDocument: null,
      textContent: '',
      detectionResult: null,
      showResult: false,
      processLogs: [],
      processProgress: 0,
      processStage: '',
      workflowStage: 'idle',
      workflowStatusText: '待输入',
      workflowProgress: 0,
    });
    this.updateCanDetect();
  },

  formatFileSize(size) {
    if (size < 1024) return size + ' B';
    if (size < 1024 * 1024) return (size / 1024).toFixed(1) + ' KB';
    return (size / (1024 * 1024)).toFixed(1) + ' MB';
  },

  // 添加处理日志
  addProcessLog(message) {
    const timestamp = new Date().toLocaleTimeString('zh-CN', { hour12: false });
    const logs = this.data.processLogs;
    logs.push({ time: timestamp, text: message });
    this.setData({ processLogs: logs }, () => this.updateAgentPanel());
  },

  updateProcess(stage, progress, logText) {
    if (logText) this.addProcessLog(logText);
    this.setWorkflow('analyzing', stage, progress, {
      processStage: stage,
      processProgress: Math.max(0, Math.min(100, progress)),
    });
  },

  async startDetection() {
    if (!this.data.canDetect) return;
    const { currentType, audioFile, audioFiles, videoFile, textDocument, textContent } = this.data;

    if (currentType === 'audio' && !audioFile) { app.showWarning('请先上传音频文件'); return; }
    if (currentType === 'video' && !videoFile) { app.showWarning('请先上传视频文件'); return; }
    if (currentType === 'text' && !textContent.trim() && !textDocument) { app.showWarning('请输入文本或上传文档'); return; }

    this.setWorkflow('analyzing', '模型分析中', 35, {
      isDetecting: true,
      canDetect: false,
      uploadError: null,
      processLogs: [],
      processProgress: 0,
      processStage: '',
    });
    this.addProcessLog('开始检测...');

    try {
      let result;
      switch (currentType) {
        case 'audio':
          this.updateProcess('音频文件上传中', 18, '上传音频文件...');
          if (audioFiles && audioFiles.length > 1) {
            const batchResults = [];
            for (let i = 0; i < audioFiles.length; i++) {
              const file = audioFiles[i];
              const itemProgress = Math.min(85, 35 + Math.round((i / audioFiles.length) * 50));
              this.updateProcess(`检测音频 ${i + 1}/${audioFiles.length}`, itemProgress, `检测音频 ${i + 1}/${audioFiles.length}: ${file.name}`);
              try {
                const itemResult = this.unwrapDetectionResult(await detectionAPI.detectAudio(file.path));
                const prob = this.normalizeProbabilities(itemResult).fake;
                this.addProcessLog(`${file.name}: 伪造概率 ${(prob * 100).toFixed(1)}%`);
                batchResults.push({ fileName: file.name, success: true, result: itemResult });
              } catch (itemErr) {
                const message = itemErr?.message || '检测失败';
                this.addProcessLog(`${file.name}: ${message}`);
                batchResults.push({ fileName: file.name, success: false, error: message });
              }
            }
            result = this.buildBatchAudioResult(batchResults);
          } else {
            this.updateProcess('Wav2Vec2 模型推理中', 45, '正在调用音频伪造检测模型...');
            result = await detectionAPI.detectAudio(audioFile.path);
            this.updateProcess('音频结果整理中', 82, '音频模型已返回结果，正在整理报告...');
          }
          break;
        case 'video':
          this.addProcessLog('上传视频文件...');
          result = await detectionAPI.detectVideo(videoFile.path);
          if (result && result.taskId && result.status === 'processing') {
            this.addProcessLog('任务已创建，正在跟踪处理进度...');
            this.setWorkflow('analyzing', '视频任务已提交', 25);
            this.watchVideoTask(result.taskId);
            return;
          }
          break;
        case 'text':
          if (textDocument) {
            this.updateProcess('文档上传与解析中', 24, '正在上传并解析文本文档...');
            result = await detectionAPI.detectTextDocument(textDocument.path);
          } else {
            this.updateProcess('文本内容提交中', 22, '正在提交文本内容...');
            this.updateProcess('DeepSeek 语义分析中', 48, 'DeepSeek 正在分析诈骗话术和风险点...');
            result = await detectionAPI.detectText(textContent);
          }
          this.updateProcess('文本报告生成中', 86, '正在生成中文检测报告...');
          break;
        case 'multi':
          this.addProcessLog('正在综合检测...');
          result = await this.runMultiModalDetection(audioFile, videoFile, textDocument, textContent);
          if (result && result.taskId && ['queued', 'processing', 'waiting_review'].includes(result.status)) {
            this.addProcessLog('多模态任务已创建，正在跟踪处理进度...');
            this.setWorkflow('analyzing', '多模态任务已提交', 25);
            this.watchMultimodalTask(result.taskId);
            return;
          }
          break;
      }
      this.setWorkflow('advising', '生成建议中', 92);
      this.handleDetectionResult(result);
    } catch (err) {
      this.addProcessLog('检测失败：' + (err.message || '未知错误'));
      console.error('检测失败:', err);
      app.showError('检测失败，请检查服务是否可用');
      this.setWorkflow('idle', '检测失败', 0, { isDetecting: false });
      this.updateCanDetect();
    }
  },

  watchVideoTask(taskId) {
    if (this._taskWatcher) this._taskWatcher.destroy();
    const watcher = new TaskWatcher({ preferPolling: true, pollInterval: 1500, maxPollAttempts: 160 });
    this._taskWatcher = watcher;
    watcher.watch(taskId, {
      onProgress: (progress, data) => {
        const msg = data?.message || ('处理中 ' + progress + '%');
        const safeProgress = Math.max(25, Math.min(90, progress || 35));
        this.addProcessLog(msg);
        this.setWorkflow('analyzing', msg, safeProgress, {
          resultName: '分析中...', 
          resultDesc: msg,
          processProgress: safeProgress,
          processStage: msg,
        });
      },
      onDone: (result) => {
        this.addProcessLog('检测完成！');
        this.setWorkflow('advising', '生成建议中', 92, {
          isDetecting: false,
          processProgress: 100,
          processStage: '检测完成',
        });
        this.updateCanDetect();
        this.handleDetectionResult(this.unwrapDetectionResult(result));
      },
      onError: (err) => {
        this.addProcessLog('检测失败：' + (err.message || err));
        this.setWorkflow('idle', '检测失败', 0, { isDetecting: false });
        this.updateCanDetect();
        console.error('视频检测任务失败:', err);
        app.showError('视频检测失败，请重试');
      },
    });
  },

  async runMultiModalDetection(audioFile, videoFile, textDocument, textContent) {
    let effectiveText = textContent;

    if (textDocument && textDocument.path) {
      this.addProcessLog('正在解析文本文档...');
      this.setWorkflow('analyzing', '文档解析中', 30);
      const docResult = this.unwrapDetectionResult(await detectionAPI.detectTextDocument(textDocument.path));
      effectiveText = docResult?.extractedText || textContent || docResult?.report || '';
    }

    this.addProcessLog('正在安全上传音频和视频原始文件...');
    this.setWorkflow('analyzing', '多模态文件上传中', 35);
    return detectionAPI.detectMulti(audioFile.path, videoFile.path, effectiveText);
  },

  watchMultimodalTask(taskId) {
    if (this._taskWatcher) this._taskWatcher.destroy();
    const watcher = new TaskWatcher({ preferPolling: true, pollInterval: 1500, maxPollAttempts: 1200 });
    this._taskWatcher = watcher;
    watcher.watch(taskId, {
      onProgress: (progress, data) => {
        const msg = data?.message || ('多模态分析中 ' + progress + '%');
        this.setWorkflow('analyzing', msg, Math.max(25, Math.min(90, progress || 35)));
      },
      onReviewRequired: () => {
        this.setWorkflow('analyzing', '等待管理员审核', 90, {
          processStage: '音视频模型结论冲突，等待人工审核',
          processProgress: 90,
        });
      },
      onDone: (result) => {
        this.addProcessLog('多模态检测完成');
        this.setWorkflow('advising', '生成建议中', 92, { isDetecting: false });
        this.updateCanDetect();
        this.handleDetectionResult(this.unwrapDetectionResult(result));
      },
      onError: (err) => {
        this.addProcessLog('多模态检测失败：' + (err.message || err));
        this.setWorkflow('idle', '检测失败', 0, { isDetecting: false });
        this.updateCanDetect();
        app.showError('多模态检测失败，请重试');
      },
    });
  },

  waitForVideoTask(taskId) {
    return new Promise((resolve, reject) => {
      const watcher = new TaskWatcher({ preferPolling: true, pollInterval: 1500, maxPollAttempts: 160 });
      watcher.watch(taskId, {
        onProgress: (progress, data) => {
          const msg = data?.message || ('视频处理中 ' + progress + '%');
          const safeProgress = Math.max(55, Math.min(82, progress || 60));
          this.addProcessLog(msg);
          this.setWorkflow('analyzing', msg, safeProgress);
        },
        onDone: (result) => {
          watcher.destroy();
          this.addProcessLog('视频模型检测完成');
          resolve(this.unwrapDetectionResult(result));
        },
        onError: (err) => {
          watcher.destroy();
          reject(err);
        },
      });
    });
  },

  normalizeProbabilities(result) {
    const candidates = [
      result?.riskProbability,
      result?.probabilities?.risk,
      result?.probabilities?.fake,
      result?.spoofProb,
      result?.fakeProbability,
      result?.fake_probability,
      result?.average_fake_probability,
      result?.max_fake_probability,
    ];
    let fake = candidates.find(v => typeof v === 'number' && !Number.isNaN(v));
    if (typeof fake !== 'number') fake = result?.isFake || result?.is_fake ? 0.75 : 0.25;
    fake = Math.max(0, Math.min(1, fake));
    const explicitSafe = result?.safeProbability || result?.probabilities?.safe || result?.probabilities?.real;
    return {
      fake,
      real: typeof explicitSafe === 'number' && !Number.isNaN(explicitSafe)
        ? Math.max(0, Math.min(1, explicitSafe))
        : Math.max(0, Math.min(1, 1 - fake)),
    };
  },

  unwrapDetectionResult(result) {
    if (!result) return result;
    if (result.code === 200 && result.data) return this.unwrapDetectionResult(result.data);
    if (result.result && typeof result.result === 'object' && !result.type && !result.source) {
      return this.unwrapDetectionResult(result.result);
    }
    return result;
  },

  normalizeTextResult(result) {
    if (typeof result === 'string') {
      return {
        type: 'text',
        result: 'unknown',
        confidence: 0,
        probabilities: { fake: 0, real: 1 },
        report: result,
        advice: ['请查看大模型分析报告，并通过官方渠道核实可疑信息'],
        source: 'deepseek-agent-orchestrator',
      };
    }
    if (this.data.currentType !== 'text') return result;
    const plainReport = this.normalizeTextReport(result?.report || result?.analysis || result?.finalResult, result);
    if (result && result.report && result.probabilities && typeof result.confidence === 'number') {
      return { ...result, report: plainReport };
    }
    const riskProbability = typeof result?.riskProbability === 'number'
      ? Math.max(0, Math.min(1, result.riskProbability))
      : (typeof result?.confidence === 'number' ? Math.max(0, Math.min(1, result.confidence)) : 0);
    const probabilities = result?.probabilities || {
      fake: riskProbability,
      real: Math.max(0, Math.min(1, 1 - riskProbability)),
    };
    return {
      ...(result || {}),
      type: 'text',
      confidence: typeof result?.confidence === 'number'
        ? result.confidence
        : riskProbability,
      probabilities,
      report: plainReport,
      advice: result?.advice || ['暂停操作，通过官方渠道核实对方身份和消息来源'],
      source: result?.source || 'deepseek-agent-orchestrator',
    };
  },

  normalizeTextReport(report, result) {
    let text = typeof report === 'string' ? report.trim() : '';
    if (text.startsWith('```')) {
      text = text.replace(/^```[a-zA-Z]*\s*/, '').replace(/```$/, '').trim();
    }
    if (text && text.startsWith('{') && text.endsWith('}')) {
      try {
        const parsed = JSON.parse(text);
        if (parsed.report && typeof parsed.report === 'string') {
          text = parsed.report.trim();
        } else {
          const points = Array.isArray(parsed.suspiciousPoints) ? parsed.suspiciousPoints.join('；') : '';
          const advice = Array.isArray(parsed.advice) ? parsed.advice.join('；') : (parsed.advice || '');
          text = `本次文本疑似${parsed.scamType || '诈骗'}，风险等级为${this.formatRiskLevel(parsed.riskLevel)}。${points ? '主要可疑点包括：' + points + '。' : ''}${advice ? '建议：' + advice + '。' : '建议暂停操作，通过官方渠道核实。'}`;
        }
      } catch (_) {
        text = '';
      }
    }
    if (text && !text.includes('"riskLevel"') && !text.includes('"suspiciousPoints"')) {
      return text;
    }
    const points = Array.isArray(result?.suspiciousPoints) ? result.suspiciousPoints.join('；') : '';
    const advice = Array.isArray(result?.advice) ? result.advice.join('；') : '';
    return `本次文本检测为${this.formatRiskLevel(result?.riskLevel)}，疑似类型为${result?.scamType || '未知诈骗类型'}。${points ? '主要可疑点包括：' + points + '。' : ''}${advice ? '建议：' + advice + '。' : '建议暂停转账、付款或提供验证码，通过官方渠道核实对方身份。'}`;
  },

  formatRiskLevel(level) {
    if (level === 'high') return '高风险';
    if (level === 'low') return '低风险';
    return '中等风险';
  },

  buildBatchAudioResult(batchResults) {
    const successItems = batchResults.filter(item => item.success && item.result);
    const fakeScores = successItems.map(item => this.normalizeProbabilities(item.result).fake);
    const maxFake = fakeScores.length ? Math.max(...fakeScores) : 0;
    const avgConfidence = successItems.length
      ? successItems.reduce((sum, item) => sum + (item.result.confidence || this.normalizeProbabilities(item.result).fake), 0) / successItems.length
      : 0.5;
    const detailItems = batchResults.map((item, index) => {
      if (!item.success) {
        return {
          index: index + 1,
          fileName: item.fileName,
          success: false,
          level: '失败',
          fakeText: '--',
          realText: '--',
          confidenceText: '--',
          report: item.error || '检测失败',
        };
      }
      const prob = this.normalizeProbabilities(item.result).fake;
      const real = this.normalizeProbabilities(item.result).real;
      const confidence = typeof item.result.confidence === 'number' ? item.result.confidence : prob;
      const level = prob > 0.7 ? '高风险' : (prob > 0.4 ? '中风险' : '低风险');
      return {
        index: index + 1,
        fileName: item.fileName,
        success: true,
        level,
        fakeText: (prob * 100).toFixed(1) + '%',
        realText: (real * 100).toFixed(1) + '%',
        confidenceText: (confidence * 100).toFixed(1) + '%',
        report: item.result.report || '',
      };
    });
    const highCount = detailItems.filter(item => item.success && item.level === '高风险').length;
    const mediumCount = detailItems.filter(item => item.success && item.level === '中风险').length;
    const lowCount = detailItems.filter(item => item.success && item.level === '低风险').length;
    const failedCount = detailItems.filter(item => !item.success).length;
    const lines = detailItems.map(item => {
      if (!item.success) return `${item.index}. ${item.fileName}: 检测失败`;
      return `${item.index}. ${item.fileName}: ${item.level}，伪造概率 ${item.fakeText}`;
    });
    return {
      type: 'audio',
      batch: true,
      batchResults,
      batchDetails: detailItems,
      batchSummary: {
        total: batchResults.length,
        success: successItems.length,
        failed: failedCount,
        high: highCount,
        medium: mediumCount,
        low: lowCount,
      },
      confidence: avgConfidence,
      probabilities: { fake: maxFake, real: Math.max(0, 1 - maxFake) },
      report: `批量音频检测完成，共 ${batchResults.length} 个文件，成功 ${successItems.length} 个，失败 ${failedCount} 个。最高伪造概率 ${(maxFake * 100).toFixed(1)}%。高风险 ${highCount} 个，中风险 ${mediumCount} 个，低风险 ${lowCount} 个。\n${lines.join('\n')}`,
      agentSteps: [
        { name: '批量文件接收', status: 'completed', description: `已选择 ${batchResults.length} 个音频文件` },
        { name: '逐文件模型推理', status: 'completed', description: `成功完成 ${successItems.length} 个文件的 Wav2Vec2 检测` },
        { name: '批量风险汇总', status: 'completed', description: `按最高伪造概率给出整体风险，最高 ${(maxFake * 100).toFixed(1)}%` },
      ],
    };
  },

  handleDetectionResult(result) {
    result = this.unwrapDetectionResult(result);
    result = this.normalizeTextResult(result);
    if (!result) {
      app.showError('检测服务返回为空');
      this.setData({ isDetecting: false });
      this.updateCanDetect();
      return;
    }

    const type = this.data.currentType;
    const probabilities = this.normalizeProbabilities(result);
    const confidence = typeof result.confidence === 'number'
      ? result.confidence
      : (type === 'text'
        ? Math.max(0.58, Math.min(0.82, 0.60 + Math.abs(probabilities.fake - 0.5) * 0.35))
        : Math.max(0.65, Math.abs(probabilities.fake - 0.5) * 2));

    let level, icon, iconColor, name, desc;
    const fakeProb = probabilities.fake || 0;

    if (fakeProb > 0.7) {
      level = 'danger'; icon = 'warning-filled'; iconColor = '#ef4444';
      name = '高风险'; desc = type === 'text' ? '检测到明显的诈骗话术特征' : '检测到明显的AI伪造特征';
    } else if (fakeProb > 0.4) {
      level = 'warning'; icon = 'warning'; iconColor = '#f59e0b';
      name = '中等风险'; desc = '存在可疑特征，需谨慎对待';
    } else {
      level = 'success'; icon = 'check-circle-filled'; iconColor = '#10b981';
      name = '低风险'; desc = type === 'text' ? '未检测到明显的高危诈骗话术' : '当前模型未检出明显伪造证据';
    }

    app.addDetectionRecord({
      type,
      result: level,
      detectionResult: {
        ...result,
        type,
        confidence,
        probabilities,
      },
      resultLevel: level,
      fileName: this.data.audioFile?.name || this.data.videoFile?.name || '',
      report: result.report || '',
      confidence,
      probabilities,
      riskScore: Math.round(fakeProb * 100),
      recordData: true,
    });

    this.setWorkflow('done', '检测完成', 100, {
      showResult: true,
      detectionResult: {
        ...result,
        type,
        confidence,
        probabilities,
        confidenceText: (confidence * 100).toFixed(1),
        realText: (probabilities.real * 100).toFixed(1),
        fakeText: (fakeProb * 100).toFixed(1),
        metricLabel: type === 'text' ? '风险判断置信度' : (type === 'video' ? '模型判定置信度' : '检测置信度'),
        realLabel: type === 'text' ? '低风险可能' : (type === 'video' ? '综合低风险' : '真实'),
        fakeLabel: type === 'text' ? '高风险可能' : (type === 'video' ? '综合高风险' : '伪造'),
        videoEvidence: type === 'video' ? this.buildVideoEvidence(result, probabilities) : null,
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

  buildVideoEvidence(result, probabilities) {
    const visual = typeof result?.visualFakeProbability === 'number' ? result.visualFakeProbability : probabilities.fake;
    const avg = typeof result?.averageFakeProbability === 'number' ? result.averageFakeProbability : probabilities.fake;
    const max = typeof result?.maxFakeProbability === 'number' ? result.maxFakeProbability : probabilities.fake;
    const ratio = typeof result?.suspiciousFrameRatio === 'number' ? result.suspiciousFrameRatio : 0;
    const items = [
      { label: '视觉换脸风险', value: (visual * 100).toFixed(1) + '%' },
      { label: '平均单帧风险', value: (avg * 100).toFixed(1) + '%' },
      { label: '最高单帧风险', value: (max * 100).toFixed(1) + '%' },
      { label: '可疑帧占比', value: (ratio * 100).toFixed(1) + '%' },
    ];
    if (result?.aigcMetadataDetected) {
      items.push({ label: '元数据证据', value: '命中 AIGC' });
    }
    return items;
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
    // 优先用 globalData 传完整对象（绕过 URL 2KB 限制，避免长 AI 报告被截断乱码）
    // 回退：若结果异常大，再走 URL 方式
    try {
      const app = getApp();
      app.globalData.pendingDetectionResult = detectionResult;
      wx.navigateTo({
        url: '/pages/result/result?type=' + currentType,
      });
    } catch (e) {
      wx.navigateTo({
        url: '/pages/result/result?type=' + currentType + '&data=' + encodeURIComponent(JSON.stringify(detectionResult)),
      });
    }
  },

  openQA() {
    const app = getApp();
    app.globalData.openQA = true;
    wx.switchTab({ url: '/pages/knowledge/knowledge' });
  },
});
