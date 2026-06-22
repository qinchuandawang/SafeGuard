const app = getApp();

Page({
  data: {
    searchKey: '',
    activeType: 'all',
    activeLevel: 'all',
    records: [],
    filteredRecords: [],
    typeFilters: [
      { label: '全部', value: 'all' },
      { label: '文本', value: 'text' },
      { label: '音频', value: 'audio' },
      { label: '视频', value: 'video' },
      { label: '综合', value: 'multi' },
    ],
    levelFilters: [
      { label: '全部', value: 'all' },
      { label: '高风险', value: 'danger' },
      { label: '可疑', value: 'warning' },
      { label: '低风险', value: 'success' },
    ],
  },

  onLoad() {
    this.loadRecords();
  },

  onShow() {
    this.loadRecords();
  },

  onBack() {
    wx.navigateBack();
  },

  loadRecords() {
    const history = app.globalData.detectionHistory || wx.getStorageSync('detectionHistory') || [];
    const records = history.map((item, index) => this.normalizeRecord(item, index));
    this.setData({ records }, () => this.applyFilters());
  },

  normalizeRecord(item, index) {
    const type = item.type || item.detectionType || item.record?.detectionType || 'text';
    const resultLevel = this.normalizeLevel(item.resultLevel || item.result || item.record?.result);
    const storedResult = item.detectionResult || item.recordData || item.record || item;
    const probabilities = item.probabilities || storedResult.probabilities || {};
    const fakeProb = typeof probabilities.fake === 'number'
      ? probabilities.fake
      : (typeof item.riskScore === 'number' ? item.riskScore / 100 : (resultLevel === 'danger' ? 0.85 : resultLevel === 'warning' ? 0.55 : 0.15));
    const createTime = item.createTime || item.time || item.createdAt || item.record?.createdAt || new Date().toISOString();
    return {
      id: item.id || `${createTime}-${index}`,
      type,
      typeText: this.typeText(type),
      icon: this.typeIcon(type),
      resultLevel,
      levelText: this.levelText(resultLevel),
      fileName: item.fileName || storedResult.fileName || '',
      report: item.report || storedResult.report || item.record?.analysisDetail || '',
      confidence: item.confidence || storedResult.confidence || 0,
      probabilities: {
        fake: Math.max(0, Math.min(1, fakeProb)),
        real: Math.max(0, Math.min(1, probabilities.real ?? (1 - fakeProb))),
      },
      riskScore: Math.round(Math.max(0, Math.min(1, fakeProb)) * 100),
      createTime,
      timeText: this.formatTime(createTime),
      raw: storedResult,
    };
  },

  normalizeLevel(value) {
    if (value === 'dangerous' || value === 'danger' || value === 'high') return 'danger';
    if (value === 'suspicious' || value === 'warning' || value === 'medium') return 'warning';
    return 'success';
  },

  typeText(type) {
    if (type === 'audio') return '音频';
    if (type === 'video') return '视频';
    if (type === 'multi') return '综合';
    return '文本';
  },

  typeIcon(type) {
    if (type === 'audio') return 'sound';
    if (type === 'video') return 'video';
    if (type === 'multi') return 'app';
    return 'text';
  },

  levelText(level) {
    if (level === 'danger') return '高风险';
    if (level === 'warning') return '可疑';
    return '低风险';
  },

  onSearchChange(e) {
    this.setData({ searchKey: e.detail.value || '' }, () => this.applyFilters());
  },

  clearSearch() {
    this.setData({ searchKey: '' }, () => this.applyFilters());
  },

  changeType(e) {
    this.setData({ activeType: e.currentTarget.dataset.value }, () => this.applyFilters());
  },

  changeLevel(e) {
    this.setData({ activeLevel: e.currentTarget.dataset.value }, () => this.applyFilters());
  },

  applyFilters() {
    const key = (this.data.searchKey || '').trim().toLowerCase();
    const { activeType, activeLevel, records } = this.data;
    const filteredRecords = records.filter(item => {
      const matchType = activeType === 'all' || item.type === activeType;
      const matchLevel = activeLevel === 'all' || item.resultLevel === activeLevel;
      const haystack = `${item.typeText} ${item.levelText} ${item.fileName} ${item.report}`.toLowerCase();
      const matchKey = !key || haystack.includes(key);
      return matchType && matchLevel && matchKey;
    });
    this.setData({ filteredRecords });
  },

  openRecord(e) {
    const id = e.currentTarget.dataset.id;
    const item = this.data.records.find(record => String(record.id) === String(id));
    if (!item) return;
    const resultData = {
      ...(item.raw || {}),
      type: item.type,
      result: item.resultLevel,
      confidence: item.confidence,
      probabilities: item.probabilities,
      report: item.report,
      fileName: item.fileName,
      riskScore: item.riskScore,
    };
    app.globalData.pendingDetectionResult = resultData;
    wx.navigateTo({ url: `/pages/result/result?type=${item.type}` });
  },

  clearHistory() {
    wx.showModal({
      title: '清空检测历史',
      content: '仅清空本机缓存的检测历史，不影响当前服务运行。',
      confirmText: '清空',
      confirmColor: '#ef4444',
      success: (res) => {
        if (!res.confirm) return;
        app.globalData.detectionHistory = [];
        wx.setStorageSync('detectionHistory', []);
        this.loadRecords();
      },
    });
  },

  formatTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return '刚刚';
    const pad = n => String(n).padStart(2, '0');
    return `${date.getMonth() + 1}/${date.getDate()} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
  },
});
