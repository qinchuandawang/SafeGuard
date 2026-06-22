const app = getApp();
const { recordAPI } = require('../../utils/request');
const auth = require('../../utils/auth');

Page({
  data: {
    navBarHeight: 80,
    statusBarHeight: 20,
    greeting: '你好',
    greetingHint: '今日安全概览已准备好',
    todayDate: '',
    stats: { detectionCount: '0', accuracy: '--', safeRate: '--' },
    dailyTip: { content: '遇到自称公检法机关要求转账的电话，请务必通过官方渠道核实身份，谨防上当受骗。' },
    tips: [
      { content: '遇到自称公检法机关要求转账的电话，请务必通过官方渠道核实身份，谨防上当受骗。' },
      { content: '近期AI换脸诈骗案件频发，接到熟人视频借款请求时，请通过其他方式二次确认。' },
      { content: '网络投资理财需谨慎，高收益必然伴随高风险，选择正规金融机构，避免落入非法集资陷阱。' },
      { content: '警惕假冒客服退款诈骗，正规退款不会要求您转账或提供验证码。' },
      { content: '警惕高薪招聘陷阱，正规工作不会收取任何费用，谨防落入传销组织。' },
      { content: '收到不明链接不要点击，可能含有木马病毒或钓鱼网站。' },
      { content: '不要向任何人透露短信验证码，银行和公安机关不会索要验证码。' },
    ],
    threatLevel: 0,
    threatLabel: '安全',
    threatColor: '#10b981',
    threatBgColor: 'rgba(16,185,129,0.12)',
    recentDetections: [],
    safeCount: 0,
    riskCount: 0,
  },

  onLoad() {
    this.setNavBarHeight();
    this.setGreeting();
    this.setTodayDate();
    this.loadDailyTip();
    this.updateStats();
    this.loadRecentDetections();
  },

  onReady() {
    this.drawScoreRing();
  },

  onShow() {
    this.updateStats();
    this.loadRecentDetections();
    this.setGreeting();
  },

  setNavBarHeight() {
    try {
      const winInfo = wx.getWindowInfo();
      const appInfo = wx.getAppBaseInfo();
      const sbh = appInfo.statusBarHeight || winInfo.statusBarHeight || 20;
      this.setData({ statusBarHeight: sbh, navBarHeight: sbh + 46 });
    } catch (e) { this.setData({ navBarHeight: 80, statusBarHeight: 20 }); }
  },

  setGreeting() {
    const hour = new Date().getHours();
    let text = '你好';
    let hint = '今日安全概览已准备好';
    if (hour < 6 || hour >= 22) {
      text = '夜深了';
      hint = '演示前也请注意休息';
    } else if (hour < 9) {
      text = '早上好';
      hint = '开启今天的安全检测';
    } else if (hour < 12) {
      text = '上午好';
      hint = '今日安全概览已准备好';
    } else if (hour < 14) {
      text = '中午好';
      hint = '保持警惕，安心使用';
    } else if (hour < 18) {
      text = '下午好';
      hint = '继续守护数字生活';
    } else {
      text = '晚上好';
      hint = '今日安全状态一目了然';
    }
    this.setData({ greeting: text, greetingHint: hint });
  },

  setTodayDate() {
    const d = new Date();
    const weekdays = ['日', '一', '二', '三', '四', '五', '六'];
    this.setData({
      todayDate: d.getFullYear() + '年' + (d.getMonth() + 1) + '月' + d.getDate() + '日 星期' + weekdays[d.getDay()]
    });
  },

  updateThreatLevel(dangerRatio) {
    let level, label, color;
    if (dangerRatio >= 0.4) {
      level = 3; label = '高风险'; color = '#ef4444';
    } else if (dangerRatio >= 0.15) {
      level = 2; label = '需留意'; color = '#f59e0b';
    } else {
      level = 1; label = '安全'; color = '#10b981';
    }
    this.setData({
      threatLevel: level,
      threatLabel: label,
      threatColor: color,
      threatBgColor: level === 3
        ? 'rgba(239,68,68,0.12)'
        : level === 2
          ? 'rgba(245,158,11,0.12)'
          : 'rgba(16,185,129,0.12)',
    });
  },

  loadDailyTip() {
    const { tips } = this.data;
    this.setData({ dailyTip: tips[new Date().getDate() % tips.length] });
  },

  refreshTip() {
    const { tips, dailyTip } = this.data;
    let next = Math.floor(Math.random() * tips.length);
    while (tips[next] && tips[next].content === dailyTip.content && tips.length > 1) {
      next = Math.floor(Math.random() * tips.length);
    }
    this.setData({ dailyTip: tips[next] });
  },

  async updateStats() {
    let count = 0;
    let dangerCount = 0;
    let totalConfidence = 0;
    try {
      const userInfo = auth.getUserInfo();
      if (userInfo && userInfo.userId) {
        const records = await recordAPI.list(userInfo.userId, 100);
        if (Array.isArray(records)) {
          count = records.length;
          dangerCount = records.filter(r => r.result === 'dangerous').length;
          // 累加风险分数以计算平均准确率（P1 fix: E4）
          totalConfidence = records.reduce((sum, r) => sum + (r.riskScore || 0), 0);
        }
      }
    } catch (e) { console.debug('统计接口不可用，使用本地检测记录:', e && (e.errMsg || e.message || e)); }

    if (count === 0) {
      const history = app.globalData.detectionHistory || [];
      count = history.length;
      dangerCount = history.filter(h => h.result === 'danger' || h.result === 'dangerous').length;
      // 本地记录也计算准确率
      totalConfidence = history.reduce((sum, h) => sum + (h.riskScore || (h.resultLevel === 'danger' ? 80 : h.resultLevel === 'warning' ? 50 : 20)), 0);
    }

    const safeRate = count > 0 ? Math.round((1 - dangerCount / count) * 100) : '--';
    // 准确率 = 100 - 平均风险分数
    const accuracy = count > 0 ? Math.round(100 - totalConfidence / count) : '--';
    this.setData({
      'stats.detectionCount': count > 0 ? String(count) : '0',
      'stats.accuracy': accuracy,
      'stats.safeRate': safeRate,
    });
    this.updateThreatLevel(count > 0 ? dangerCount / count : 0);
  },

  loadRecentDetections() {
    const history = app.globalData.detectionHistory || [];
    const recent = history.slice(0, 3).map(h => ({
      type: h.type || 'text',
      result: h.result || 'safe',
      time: h.createTime ? this.formatTime(h.createTime) : '刚刚',
      fileName: h.fileName || '',
      riskScore: h.riskScore || 0,
    }));
    this.setData({ recentDetections: recent });
  },

  formatTime(isoStr) {
    try {
      const d = new Date(isoStr);
      const now = new Date();
      const diff = now - d;
      if (diff < 60000) return '刚刚';
      if (diff < 3600000) return Math.floor(diff / 60000) + '分钟前';
      if (diff < 86400000) return Math.floor(diff / 3600000) + '小时前';
      return (d.getMonth() + 1) + '/' + d.getDate();
    } catch (e) { return ''; }
  },

  // ===== Canvas 可视化图表 =====

  drawScoreRing() {
    const query = wx.createSelectorQuery();
    query.select('#scoreCanvas')
      .fields({ node: true, size: true })
      .exec((res) => {
        if (!res || !res[0] || !res[0].node) return;

        const canvas = res[0].node;
        const ctx = canvas.getContext('2d');
        const dpr = wx.getWindowInfo().pixelRatio;
        const width = res[0].width;
        const height = res[0].height;

        canvas.width = width * dpr;
        canvas.height = height * dpr;
        ctx.scale(dpr, dpr);

        const cx = width / 2;
        const cy = height / 2;
        const radius = Math.min(cx, cy) - 12;
        const lineWidth = 14;

        const { stats, threatColor } = this.data;
        const appInfo = wx.getAppBaseInfo();
        const isDark = appInfo.theme === 'dark';
        const raw = stats.safeRate;
        const rate = raw === '--' ? 0 : parseInt(raw) / 100;

        // 颜色适配
        const trackColor = isDark ? 'rgba(255,255,255,0.08)' : 'rgba(200,200,200,0.12)';
        const textMuted = isDark ? 'rgba(148,163,184,0.6)' : 'rgba(148,163,184,0.7)';
        const textEmpty = isDark ? 'rgba(148,163,184,0.5)' : 'rgba(148,163,184,0.4)';
        const trackEmpty = isDark ? 'rgba(255,255,255,0.12)' : 'rgba(200,200,200,0.2)';

        let progress = 0;
        const animate = () => {
          progress = Math.min(progress + 0.025, rate);

          ctx.clearRect(0, 0, width, height);

          // 背景轨迹
          ctx.beginPath();
          ctx.arc(cx, cy, radius, 0, Math.PI * 2);
          ctx.strokeStyle = trackColor;
          ctx.lineWidth = lineWidth;
          ctx.lineCap = 'round';
          ctx.stroke();

          // 进度弧 — 渐变色
          const grad = ctx.createLinearGradient(0, 0, width, 0);
          if (rate >= 0.8) {
            grad.addColorStop(0, '#10b981'); grad.addColorStop(1, '#34d399');
          } else if (rate >= 0.5) {
            grad.addColorStop(0, '#f59e0b'); grad.addColorStop(1, '#fbbf24');
          } else {
            grad.addColorStop(0, '#ef4444'); grad.addColorStop(1, '#f87171');
          }

          const sa = -Math.PI / 2;
          const ea = sa + Math.PI * 2 * progress;
          ctx.beginPath();
          ctx.arc(cx, cy, radius, sa, ea);
          ctx.strokeStyle = grad;
          ctx.lineWidth = lineWidth;
          ctx.lineCap = 'round';
          ctx.stroke();

          // 中心百分比文字
          const pct = Math.round(progress * 100);
          ctx.fillStyle = threatColor;
          ctx.font = `bold ${Math.round(radius * 0.48)}px "PingFang SC", sans-serif`;
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText(pct + '%', cx, cy - 2);

          ctx.fillStyle = textMuted;
          ctx.font = `${Math.round(radius * 0.17)}px "PingFang SC", sans-serif`;
          ctx.fillText('安全率', cx, cy + radius * 0.45);

          if (progress < rate && rate > 0) {
            canvas.requestAnimationFrame(animate);
          }
        };

        if (rate > 0) animate();
        else {
          ctx.beginPath();
          ctx.arc(cx, cy, radius, -Math.PI / 2, Math.PI / 2);
          ctx.strokeStyle = trackEmpty;
          ctx.lineWidth = lineWidth;
          ctx.lineCap = 'round';
          ctx.stroke();

          ctx.fillStyle = textEmpty;
          ctx.font = `${Math.round(radius * 0.22)}px "PingFang SC", sans-serif`;
          ctx.textAlign = 'center';
          ctx.textBaseline = 'middle';
          ctx.fillText('暂无数据', cx, cy);
        }
      });
  },

  // ===== 导航 =====

  goToDetection() { wx.switchTab({ url: '/pages/detection/detection' }); },
  goToSimulate() { wx.switchTab({ url: '/pages/simulate/simulate' }); },
  goToKnowledge() { wx.switchTab({ url: '/pages/knowledge/knowledge' }); },
  goToHistory() { wx.navigateTo({ url: '/pages/history/history' }); },
  callHotline() {
    wx.makePhoneCall({
      phoneNumber: '96110',
      fail: () => { app.showWarning && app.showWarning('拨打电话失败'); }
    });
  },
  openQA() {
    app.globalData.openQA = true;
    wx.switchTab({ url: '/pages/knowledge/knowledge' });
  },
  goToResult(e) {
    const idx = e.currentTarget.dataset.index;
    const history = app.globalData.detectionHistory || [];
    const item = history[idx];
    if (item) {
      const storedResult = item.detectionResult || item.record || item;
      const resultData = {
        ...storedResult,
        type: item.type || 'text',
        result: item.resultLevel || item.result || 'safe',
        confidence: item.confidence || storedResult.confidence || 0.7,
        probabilities: {
          real: (item.probabilities && item.probabilities.real) || (storedResult.probabilities && storedResult.probabilities.real) || (item.result === 'danger' || item.result === 'dangerous' ? 0.05 : 0.95),
          fake: (item.probabilities && item.probabilities.fake) || (storedResult.probabilities && storedResult.probabilities.fake) || (item.result === 'danger' || item.result === 'dangerous' ? 0.95 : 0.05)
        },
        report: item.report || storedResult.report || '',
        fileName: item.fileName || '',
        riskScore: item.riskScore || 0
      };
      app.globalData.pendingDetectionResult = resultData;
      wx.navigateTo({ url: '/pages/result/result?type=' + resultData.type });
    }
  },

  onShareAppMessage() { return { title: '诈骗克星 - AI反诈骗检测系统', path: '/pages/index/index' }; },
  onShareTimeline() { return { title: '诈骗克星 - AI反诈骗检测' }; },
});
