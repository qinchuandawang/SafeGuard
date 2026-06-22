// pages/knowledge/knowledge.js - 防骗知识库逻辑

const app = getApp();
const { ragAPI, requestStream, request } = require('../../utils/request');

Page({
  data: {
    searchKey: '',
    currentCategory: 'all',
    categories: [
      { id: 'all', name: '全部', icon: 'app', color: '#00d4ff' },
    ],
    hotTags: [],
    articles: [],
    filteredArticles: [],
    currentCategoryName: '全部文章',

    // ===== RAG 智能问答 =====
    showQA: false,
    qaInput: '',
    qaLoading: false,
    qaMessages: [],
    lastMsgId: '',
    msgCounter: 0,
    suggestions: [
      'AI换脸诈骗怎么识别？',
      '收到96110电话是真的吗？',
      '刷单兼职是诈骗吗？',
    ],
  },

  onLoad() {
    this._destroyed = false;
    this.loadArticles();
  },

  onShow() {
    const app = getApp();
    if (app.globalData.openQA) {
      app.globalData.openQA = false;
      this.setData({ showQA: true });
    }
  },

  onUnload() {
    this._destroyed = true;
    if (this._qaStreamTask) {
      this._qaStreamTask.abort();
      this._qaStreamTask = null;
    }
  },

  /** 从后端加载文章列表 */
  loadArticles() {
    request({
      url: '/api/knowledge/articles',
      method: 'GET',
      showLoading: false,
    }).then(data => {
      if (Array.isArray(data) && data.length > 0) {
        this.applyArticleData(data);
      } else {
        this.applyArticleData(this.getDefaultArticles());
      }
    }).catch(err => {
      console.debug('知识库接口不可用，使用本地演示数据:', err && (err.errMsg || err.message || err));
      this.applyArticleData(this.getDefaultArticles());
    });
  },

  applyArticleData(articles) {
    const safeArticles = Array.isArray(articles) ? articles.filter(Boolean) : [];
    const catMap = {};
    const tagSet = new Set();
    safeArticles.forEach(a => {
      if (a.category) catMap[a.category] = true;
      if (Array.isArray(a.tags)) a.tags.forEach(t => tagSet.add(t));
    });
    const colors = ['#ef4444', '#7c3aed', '#10b981', '#f59e0b', '#3b82f6', '#06b6d4', '#8b5cf6'];
    const categories = [
      { id: 'all', name: '全部', icon: 'app', color: '#00d4ff' },
      ...Object.keys(catMap).map((cat, i) => ({ id: cat, name: cat, icon: 'app', color: colors[i % colors.length] })),
    ];
    this.setData({
      articles: safeArticles,
      filteredArticles: safeArticles,
      categories,
      hotTags: [...tagSet].slice(0, 8),
      currentCategoryName: '全部文章',
    });
  },

  getDefaultArticles() {
    return [
      {
        id: 1,
        title: 'AI换脸诈骗识别指南：视频里的熟人也要二次确认',
        summary: '通过面部边缘、光线阴影、眨眼嘴型和多渠道验证，快速识别深度伪造视频诈骗。',
        category: 'AI诈骗',
        tags: ['AI换脸', '视频伪造', '身份核验'],
        date: '2024-03-18',
      },
      {
        id: 8,
        title: 'AI语音合成诈骗：听到的声音也可能是假的',
        summary: '诈骗分子可能用短音频克隆亲友声音，遇到紧急借钱要通过视频、暗号或常用号码核实。',
        category: 'AI诈骗',
        tags: ['AI语音', '声音克隆', '熟人诈骗'],
        date: '2024-03-15',
      },
      {
        id: 2,
        title: '冒充公检法诈骗：不存在所谓安全账户',
        summary: '公检法机关不会电话办案、不会要求转账、不会索要验证码，接到可疑电话应立即拨打96110。',
        category: '高发套路',
        tags: ['公检法', '安全账户', '96110'],
        date: '2024-03-12',
      },
      {
        id: 3,
        title: '刷单兼职都是陷阱：先返小钱再骗大钱',
        summary: '刷单诈骗通常先用小额返利建立信任，再用连单、提现失败等理由诱导持续垫资。',
        category: '高发套路',
        tags: ['刷单', '兼职', '垫资'],
        date: '2024-03-10',
      },
      {
        id: 4,
        title: '杀猪盘完整揭秘：网恋背后的投资骗局',
        summary: '陌生网友长期培养感情后推荐投资平台，常见于婚恋交友、虚拟货币和高收益理财场景。',
        category: '情感诈骗',
        tags: ['杀猪盘', '网恋', '虚假投资'],
        date: '2024-03-08',
      },
      {
        id: 5,
        title: '网购退款诈骗：客服来电的真相',
        summary: '正规退款会原路返回，任何要求提供银行卡密码、短信验证码或点击陌生链接的客服都要警惕。',
        category: '生活防骗',
        tags: ['冒充客服', '退款', '验证码'],
        date: '2024-03-05',
      },
      {
        id: 6,
        title: '虚假投资理财诈骗：高收益承诺背后的风险',
        summary: '承诺保本保息、稳赚不赔、内部消息的平台通常风险极高，投资前应核验金融牌照。',
        category: '金融诈骗',
        tags: ['投资理财', '高收益', '非法平台'],
        date: '2024-03-01',
      },
      {
        id: 7,
        title: '钓鱼网站和诈骗短信：不要被相似页面骗走信息',
        summary: '收到短信链接不要直接点击，重要业务请手动打开官方 App 或官网核实。',
        category: '生活防骗',
        tags: ['钓鱼网站', '诈骗短信', '隐私保护'],
        date: '2024-02-28',
      },
    ];
  },

  onBack() { wx.navigateBack(); },

  selectCategory(e) {
    const { id } = e.currentTarget.dataset;
    const category = this.data.categories.find(c => c.id === id);
    this.setData({
      currentCategory: id,
      currentCategoryName: category ? category.name + '文章' : '全部文章',
      searchKey: '',
    });
    this.filterArticles();
  },

  onSearchChange(e) {
    this.setData({ searchKey: e.detail.value, currentCategoryName: e.detail.value ? '搜索结果' : this.getCurrentCategoryName() });
    this.filterArticles();
  },

  onClearSearch() {
    this.setData({ searchKey: '', currentCategoryName: this.getCurrentCategoryName() });
    this.filterArticles();
  },

  getCurrentCategoryName() {
    const c = this.data.categories.find(c => c.id === this.data.currentCategory);
    return c ? c.name + '文章' : '全部文章';
  },

  onSearchSubmit() { this.filterArticles(); },

  searchByTag(e) {
    const { tag } = e.currentTarget.dataset;
    this.setData({ searchKey: tag, currentCategoryName: '搜索结果' });
    this.filterArticles();
  },

  filterArticles() {
    let { articles, currentCategory, searchKey } = this.data;
    if (!Array.isArray(articles)) { this.setData({ filteredArticles: [] }); return; }
    let filtered = articles.filter(Boolean);
    if (currentCategory !== 'all') filtered = filtered.filter(i => i.category === currentCategory);
    if (searchKey) {
      const key = searchKey.toLowerCase();
      filtered = filtered.filter(i =>
        (i.title && i.title.toLowerCase().includes(key)) ||
        (i.summary && i.summary.toLowerCase().includes(key)) ||
        (Array.isArray(i.tags) && i.tags.some(t => t.toLowerCase().includes(key)))
      );
    }
    this.setData({ filteredArticles: filtered });
  },

  viewArticle(e) {
    const { id } = e.currentTarget.dataset;
    const article = this.data.articles.find(i => i.id === id);
    if (article) {
      wx.navigateTo({
        url: `/pages/article/article?id=${id}&data=${encodeURIComponent(JSON.stringify(article))}`,
      });
    }
  },

  // ==================== RAG 问答逻辑 ====================

  toggleQA() { this.setData({ showQA: !this.data.showQA }); },

  onQAInput(e) { this.setData({ qaInput: e.detail.value }); },

  async sendQuestion() {
    const { qaInput } = this.data;
    const text = qaInput.trim();
    if (!text || this.data.qaLoading) return;

    const msgId = this.data.msgCounter + 1;
    const userMsg = { id: msgId, role: 'user', content: text };
    this.setData({ qaMessages: [...this.data.qaMessages, userMsg], qaInput: '', qaLoading: true, msgCounter: msgId, lastMsgId: `msg-${msgId}` });

    let streamTask = null;
    try {
      let ragResults = [];
      let ragSuccess = false;
      try {
        ragResults = await ragAPI.query(text);
        ragSuccess = Array.isArray(ragResults) && ragResults.length > 0;
      } catch (e) { console.warn('RAG查询失败:', e.message); }

      let references = [];
      const aiId = msgId + 1;

      if (ragSuccess) {
        const selectedResults = ragResults.slice(0, 2);
        const ragContext = selectedResults
          .map((r, i) => `[${i + 1}] ${this._truncateText(r.content || r.answer || r.summary || '', 220)}`)
          .filter(Boolean)
          .join('\n');
        const contextPrompt = `知识库内容：\n${ragContext}\n\n用户问题：${text}\n请基于知识库回答，直接输出自然中文。`;

        this.setData({ qaMessages: [...this.data.qaMessages, { id: aiId, role: 'ai', content: '', references: [] }], msgCounter: aiId, lastMsgId: `msg-${aiId}` });

        let streamOk = false;
        let pendingContent = '';
        let lastRenderAt = 0;
        const renderStreamContent = (fullContent, force = false) => {
          pendingContent = fullContent || pendingContent;
          const now = Date.now();
          if (!force && now - lastRenderAt < 120) return;
          lastRenderAt = now;
          this.setData({
            qaMessages: this.data.qaMessages.map(m => m.id === aiId ? { ...m, content: pendingContent } : m),
          });
        };
        try {
          await new Promise((resolve, reject) => {
            const streamResult = requestStream('/api/llm/analyze/stream', { text: contextPrompt }, {
              onMessage: (chunk, fullContent) => {
                renderStreamContent(fullContent, false);
              },
              onDone: (fullContent) => {
                streamOk = true;
                if (fullContent) {
                  renderStreamContent(fullContent, true);
                }
                resolve();
              },
              onError: (err) => { reject(err); },
            });
            streamTask = streamResult;
            this._qaStreamTask = streamResult;
          });
        } catch (e) {
          console.warn('LLM流式失败:', e.message);
          if (streamTask) streamTask.abort();
        } finally {
          this._qaStreamTask = null;
        }

        if (!streamOk) {
          try {
            const fullResponse = await ragAPI.analyze(contextPrompt);
            this.setData({ qaMessages: this.data.qaMessages.map(m => m.id === aiId ? { ...m, content: fullResponse || ragResults.map(r => r.content).join('\n\n') } : m) });
          } catch (e2) {
            this.setData({ qaMessages: this.data.qaMessages.map(m => m.id === aiId ? { ...m, content: ragResults.map(r => r.content).join('\n\n') } : m) });
          }
        }
        references = this._matchReferences(text, ragResults);
        this.setData({ qaMessages: this.data.qaMessages.map(m => m.id === aiId ? { ...m, references } : m), qaLoading: false, lastMsgId: `msg-${aiId}` });
      } else {
        const fallback = this._fallbackAnswer(text);
        references = fallback.refs || [];
        this.setData({ qaMessages: [...this.data.qaMessages, { id: aiId, role: 'ai', content: fallback.response, references }], qaLoading: false, msgCounter: aiId, lastMsgId: `msg-${aiId}` });
      }
    } catch (err) {
      console.error('问答异常:', err);
      const fallback = this._fallbackAnswer(text);
      const aiId = this.data.msgCounter + 1;
      this.setData({ qaMessages: [...this.data.qaMessages, { id: aiId, role: 'ai', content: fallback.response, references: fallback.refs }], qaLoading: false, msgCounter: aiId, lastMsgId: `msg-${aiId}` });
    }
  },

  _matchReferences(question, ragResults) {
    const { articles } = this.data;
    const q = question.toLowerCase();
    const keywords = q.split(/[\s,，。.？?！!、；;：:]/).filter(k => k.length >= 2);
    const scored = articles.map(a => ({
      article: a,
      score: keywords.filter(k => (a.title || '').toLowerCase().includes(k) || (a.summary || '').toLowerCase().includes(k)).length,
    })).filter(s => s.score > 0).sort((a, b) => b.score - a.score).slice(0, 3);
    const matched = scored.map(s => ({ title: s.article.title, articleId: s.article.id }));
    if (matched.length === 0 && ragResults.length > 0) {
      matched.push({ title: ragResults[0].source || ragResults[0].category || '相关知识库', articleId: null });
    }
    return matched;
  },

  _truncateText(text, maxLength) {
    const value = String(text || '').replace(/\s+/g, ' ').trim();
    if (value.length <= maxLength) return value;
    return value.slice(0, maxLength) + '...';
  },

  askQuestion(e) {
    const q = e.currentTarget.dataset.q;
    this.setData({ qaInput: q }, () => { this.sendQuestion(); });
  },

  _fallbackAnswer(question) {
    const q = question.toLowerCase();
    const localQA = [
      { kw: ['AI换脸', '深度伪造', 'deepfake', '视频伪造'], ans: 'AI换脸诈骗是诈骗分子使用深度伪造技术冒充熟人行骗。识别方法：1)观察眨眼嘴部是否自然 2)注意面部边缘模糊 3)要求对方做特定动作 4)通过其他渠道确认。', ref: '' },
      { kw: ['96110', '反诈热线'], ans: '96110是全国反电信网络诈骗专用号码，用于预警劝阻和咨询。它不会主动要求转账或索要验证码。接到96110来电务必接听。', ref: '' },
      { kw: ['刷单', '兼职', '刷信誉'], ans: '刷单诈骗：前几单小额返利骗取信任，然后要求做大额"连单"后拒绝返款。任何要求垫资的兼职都是诈骗，刷单本身也违法。', ref: '' },
      { kw: ['公检法', '安全账户', '洗钱'], ans: '冒充公检法：公检法机关不会通过电话办案，不存在"安全账户"，不会要求转账。遇到此类电话立即挂断并拨打96110。', ref: '' },
      { kw: ['杀猪盘', '婚恋', '网恋'], ans: '杀猪盘：诈骗分子通过婚恋平台建立感情后诱导投资。网友推荐的投资平台大多是假平台，未见面就谈钱务必警惕。', ref: '' },
      { kw: ['投资', '理财', '高收益'], ans: '投资理财诈骗以高收益为诱饵。年化超6%就要警惕，承诺保本保息大多有猫腻，查证平台是否具备金融牌照。', ref: '' },
      { kw: ['客服', '退款', '理赔'], ans: '冒充客服退款：正规退款原路返回，不需要额外操作。不要点击对方发来的链接，不要将验证码告诉任何人。', ref: '' },
    ];
    for (const item of localQA) {
      if (item.kw.some(k => q.includes(k))) return { response: item.ans, refs: [] };
    }
    return { response: '关于这个问题，建议查看知识库中的相关文章或拨打反诈热线96110咨询。', refs: [] };
  },

  viewArticleByRef(e) {
    const { id } = e.currentTarget.dataset;
    const article = this.data.articles.find(a => a.id === id);
    if (article) {
      wx.navigateTo({ url: `/pages/article/article?id=${id}&data=${encodeURIComponent(JSON.stringify(article))}` });
    }
  },

  callHotline() {
    wx.makePhoneCall({ phoneNumber: '96110', fail: () => { app.showError('拨打电话失败'); } });
  },
});
