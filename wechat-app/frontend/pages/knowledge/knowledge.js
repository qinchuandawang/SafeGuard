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
        // 从后端文章数据提取分类和标签
        const catMap = {};
        const tagSet = new Set();
        data.forEach(a => {
          if (a.category) catMap[a.category] = true;
          if (Array.isArray(a.tags)) a.tags.forEach(t => tagSet.add(t));
        });
        const categories = [
          { id: 'all', name: '全部', icon: 'app', color: '#00d4ff' },
          ...Object.keys(catMap).map((cat, i) => {
            const colors = ['#ef4444','#7c3aed','#10b981','#f59e0b','#3b82f6','#06b6d4','#8b5cf6'];
            return { id: cat, name: cat, icon: 'app', color: colors[i % colors.length] };
          }),
        ];
        this.setData({
          articles: data,
          filteredArticles: data,
          categories,
          hotTags: [...tagSet].slice(0, 6),
          currentCategoryName: '全部文章',
        });
      }
    }).catch(err => {
      wx.showToast({ title: '加载知识库失败', icon: 'none' });
    });
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
        const ragContext = ragResults.map((r, i) => `[${i + 1}] ${r.content || ''}`).join('\n');
        const contextPrompt = `知识库内容：\n${ragContext}\n\n用户问题：${text}\n请基于知识库回答，简洁准确。`;

        this.setData({ qaMessages: [...this.data.qaMessages, { id: aiId, role: 'ai', content: '', references: [] }], msgCounter: aiId, lastMsgId: `msg-${aiId}` });

        let streamOk = false;
        try {
          await new Promise((resolve, reject) => {
            const streamResult = requestStream('/api/llm/analyze/stream', { text: contextPrompt }, {
              onMessage: (chunk, fullContent) => {
                this.setData({ qaMessages: this.data.qaMessages.map(m => m.id === aiId ? { ...m, content: fullContent } : m) });
              },
              onDone: (fullContent) => { streamOk = true; resolve(); },
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