// app.js - 诈骗克星 AI反诈骗检测系统

const CONFIG = require('./utils/config');
const auth = require('./utils/auth');
const { recordAPI } = require('./utils/request');

App({
  globalData: {
    devMode: false,
    apiBaseUrl: CONFIG.API_BASE_URL,
    backupApiBaseUrl: CONFIG.API_BASE_URL,
    userInfo: null,
    detectionHistory: [],
    chatContext: null,
    openQA: false,
  },

  onLaunch() {
    this.initCloud();
    this.loadLocalData();
    this.checkUpdate();
    this.tryAutoLogin();
    this.loadHistoryFromBackend();
    console.log('诈骗克星小程序启动');
  },

  onError(err) {
    console.error('[Global Error]', err);
    if (typeof err === 'string') {
      console.error('[Global Error Detail]', err);
    } else if (err && err.stack) {
      console.error('[Global Error Stack]', err.stack);
    }
  },

  onShow() {
    if (this._statsNeedUpdate) {
      this.loadHistoryFromBackend();
      this._statsNeedUpdate = false;
    }
  },

  initCloud() {},

  loadLocalData() {
    try {
      const history = wx.getStorageSync('detectionHistory');
      if (history) this.globalData.detectionHistory = history;
      const savedUser = wx.getStorageSync(auth.USER_KEY);
      if (savedUser) this.globalData.userInfo = savedUser;
    } catch (e) {
      console.error('加载本地数据失败:', e);
    }
  },

  saveLocalData() {
    try {
      if (wx.getStorageInfoSync) {
        const info = wx.getStorageInfoSync();
        if (info.currentSize + 50 > info.limitSize) {
          console.warn('本地存储空间不足，跳过保存');
          return;
        }
      }
      wx.setStorageSync('detectionHistory', this.globalData.detectionHistory);
      if (this.globalData.userInfo) {
        wx.setStorageSync(auth.USER_KEY, this.globalData.userInfo);
      }
    } catch (e) {
      console.error('保存本地数据失败:', e);
    }
  },

  tryAutoLogin() {
    if (!auth.isLoggedIn()) {
      const loginTimeout = 3000;
      const loginPromise = auth.login();
      const timeoutPromise = new Promise((_, reject) =>
        setTimeout(() => reject(new Error('登录超时')), loginTimeout)
      );
      Promise.race([loginPromise, timeoutPromise]).then(data => {
        if (data) {
          this.globalData.userInfo = {
            userId: data.userId,
            nickname: data.nickname,
            avatarUrl: data.avatarUrl,
            role: data.role,
          };
          console.log('自动登录成功, userId:', data.userId);
        }
      }).catch(err => {
        console.debug('自动登录跳过（匿名模式继续）');
      });
    } else {
      this.globalData.userInfo = auth.getUserInfo();
    }
  },

  async loadHistoryFromBackend() {
    try {
      const userInfo = auth.getUserInfo();
      if (userInfo && userInfo.userId) {
        const records = await recordAPI.list(userInfo.userId, 20);
        if (Array.isArray(records) && records.length > 0) {
          this.globalData.detectionHistory = records.map(r => ({
            id: r.id,
            type: r.detectionType,
            result: r.result === 'dangerous' ? 'danger' : r.result === 'suspicious' ? 'warning' : 'safe',
            time: r.createdAt,
            createTime: r.createdAt,
            record: r,
          }));
          this.saveLocalData();
          return;
        }
      }
      this.loadLocalData();
    } catch (e) {
      console.warn('从后端加载历史失败，使用本地数据:', e);
      this.loadLocalData();
    }
  },

  async addDetectionRecord(record) {
    const recordWithTime = {
      ...record,
      id: record.id || Date.now(),
      createTime: record.createTime || new Date().toISOString(),
    };
    this.globalData.detectionHistory.unshift(recordWithTime);
    if (this.globalData.detectionHistory.length > 100) {
      this.globalData.detectionHistory.pop();
    }
    this.saveLocalData();

    try {
      const userInfo = auth.getUserInfo();
      if (recordWithTime.recordData) {
        const resultLevel = recordWithTime.resultLevel
            || (typeof recordWithTime.result === 'string' ? recordWithTime.result : '');
        await recordAPI.save({
          taskId: recordWithTime.id + '',
          userId: userInfo?.userId,
          detectionType: recordWithTime.type || 'text',
          fileName: recordWithTime.fileName,
          status: 'completed',
          result: resultLevel === 'danger' ? 'dangerous' :
                  resultLevel === 'warning' ? 'suspicious' : 'safe',
          riskScore: recordWithTime.riskScore || (resultLevel === 'danger' ? 80 : resultLevel === 'warning' ? 50 : 20),
          analysisDetail: typeof recordWithTime.report === 'string' ? recordWithTime.report : JSON.stringify(recordWithTime),
          processingTimeMs: recordWithTime.latencyMs || 0,
        });
      }
    } catch (e) {
      console.warn('保存检测记录到后端失败:', e);
    }

    this._statsNeedUpdate = true;

    return recordWithTime;
  },

  checkUpdate() {
    if (wx.canIUse('getUpdateManager')) {
      const updateManager = wx.getUpdateManager();
      updateManager.onCheckForUpdate((res) => { if (res.hasUpdate) console.log('有新版本可用'); });
      updateManager.onUpdateReady(() => {
        // 检查用户推迟更新的次数（P2 fix: E9）
        const postponeKey = 'update_postpone_count';
        let postponeCount = 0;
        try { postponeCount = wx.getStorageSync(postponeKey) || 0; } catch (e) { /* ignore */ }
        
        if (postponeCount >= 2) {
          // 推迟超过 2 次，强制更新
          wx.showModal({
            title: '更新提示',
            content: '新版本已准备好，点击确定立即重启应用。',
            showCancel: false,
            success: () => { updateManager.applyUpdate(); },
          });
        } else {
          wx.showModal({
            title: '更新提示',
            content: '新版本已准备好，是否重启应用？',
            cancelText: '稍后提醒',
            confirmText: '立即更新',
            success: (res) => {
              if (res.confirm) {
                try { wx.removeStorageSync(postponeKey); } catch (e) { /* ignore */ }
                updateManager.applyUpdate();
              } else {
                try { wx.setStorageSync(postponeKey, postponeCount + 1); } catch (e) { /* ignore */ }
              }
            },
          });
        }
      });
      updateManager.onUpdateFailed(() => {
        wx.showModal({ title: '更新失败', content: '新版本下载失败，下次启动时将重试。', showCancel: false });
      });
    }
  },

  showLoading(title) {
    wx.showLoading({ title, mask: true });
  },
  hideLoading() { wx.hideLoading(); },
  showSuccess(title) { wx.showToast({ title, icon: 'success', duration: 2000 }); },
  showError(title) { wx.showToast({ title, icon: 'error', duration: 2000 }); },
  showWarning(title) { wx.showToast({ title, icon: 'none', duration: 2500 }); },

  showLoginTip() {
    wx.showModal({
      title: '功能提示',
      content: '诈骗克星已自动为您登录，检测记录将自动保存到云端。',
      confirmText: '我知道了',
    });
  },

  confirmAction(title, content, confirmText, cancelText) {
    return new Promise((resolve) => {
      wx.showModal({
        title, content,
        confirmText: confirmText || '确定',
        cancelText: cancelText || '取消',
        success: (res) => resolve(res.confirm),
      });
    });
  },

  showSuccessWithCallback(title, callback) {
    wx.showToast({
      title, icon: 'success', duration: 1500,
      success: () => { if (callback) setTimeout(callback, 1500); },
    });
  },

  chooseImage(count, sourceType) {
    return new Promise((resolve, reject) => {
      wx.chooseImage({ count: count || 1, sourceType: sourceType || ['album', 'camera'],
        success: (res) => resolve(res.tempFilePaths), fail: reject });
    });
  },
  chooseVideo(sourceType) {
    return new Promise((resolve, reject) => {
      wx.chooseVideo({ sourceType: sourceType || ['album', 'camera'], maxDuration: 60, camera: 'back',
        success: resolve, fail: reject });
    });
  },
  chooseMessageFile(count, type) {
    return new Promise((resolve, reject) => {
      wx.chooseMessageFile({ count: count || 1, type: type || 'audio',
        success: (res) => resolve(res.tempFiles), fail: reject });
    });
  },
  copyToClipboard(text) {
    wx.setClipboardData({ data: text, success: () => this.showSuccess('已复制到剪贴板') });
  },
  getLocation() {
    return new Promise((resolve, reject) => {
      wx.getLocation({ type: 'gcj02', success: resolve, fail: reject });
    });
  },
});
