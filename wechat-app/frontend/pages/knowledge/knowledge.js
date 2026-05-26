// pages/knowledge/knowledge.js - 防骗知识库逻辑

const app = getApp();
const { ragAPI, requestStream, request } = require('../../utils/request');

Page({
  data: {
    // 搜索关键词
    searchKey: '',

    // 当前分类
    currentCategory: 'all',

    // 分类列表
    categories: [
      { id: 'all', name: '全部', icon: 'app', color: '#00d4ff' },
      { id: '冒充公检法', name: '冒充公检法', icon: 'call', color: '#ef4444' },
      { id: '冒充熟人', name: '冒充熟人', icon: 'wifi', color: '#7c3aed' },
      { id: '投资诈骗', name: '投资诈骗', icon: 'chart', color: '#10b981' },
      { id: '刷单诈骗', name: '刷单诈骗', icon: 'chat', color: '#f59e0b' },
      { id: '冒充客服', name: '冒充客服', icon: 'shop', color: '#3b82f6' },
    ],

    // 热门标签
    hotTags: ['AI诈骗', '杀猪盘', '冒充客服', '刷单诈骗', '网络贷款', '假冒公检法'],

    // 文章列表
    articles: [
      {
        id: 1,
        title: '警惕！AI换脸诈骗来袭，视频通话也不可信了',
        summary: '随着AI技术发展，诈骗分子开始利用深度伪造技术冒充熟人诈骗。本文教你如何识别AI换脸视频。',
        cover: '/assets/images/covers/1.jpg',
        tags: ['AI诈骗', '深度伪造', '新技术'],
        category: '刷单诈骗',
        date: '2024-03-15',
        views: 12580,
      },
      {
        id: 2,
        title: '冒充公检法诈骗升级：这些套路你必须知道',
        summary: '公检法诈骗是危害最大的诈骗类型之一。本文详细揭秘诈骗分子的作案手法和话术。',
        cover: '/assets/images/covers/2.jpg',
        tags: ['公检法', '高发诈骗', '转账诈骗'],
        category: '冒充公检法',
        date: '2024-03-12',
        views: 25680,
      },
      {
        id: 3,
        title: '投资理财需谨慎：高收益背后的陷阱',
        summary: '年化收益率20%以上的理财产品，几乎都是诈骗。本文教你识别非法集资和投资诈骗。',
        cover: '/assets/images/covers/3.jpg',
        tags: ['投资诈骗', '非法集资', '高收益陷阱'],
        category: '投资诈骗',
        date: '2024-03-10',
        views: 18920,
      },
      {
        id: 4,
        title: '杀猪盘诈骗完整揭秘：网恋背后的阴谋',
        summary: '杀猪盘是近年来高发的婚恋诈骗类型。本文详细分析杀猪盘的完整作案流程。',
        cover: '/assets/images/covers/4.jpg',
        tags: ['杀猪盘', '婚恋诈骗', '情感诈骗'],
        category: '刷单诈骗',
        date: '2024-03-08',
        views: 31560,
      },
      {
        id: 5,
        title: '网购退款诈骗：客服来电的真相',
        summary: '冒充电商客服退款诈骗手段翻新。本文教你识别真假客服，避免财产损失。',
        cover: '/assets/images/covers/5.jpg',
        tags: ['网购诈骗', '客服诈骗', '退款陷阱'],
        category: '冒充客服',
        date: '2024-03-05',
        views: 14680,
      },
      {
        id: 6,
        title: '网络贷款诈骗：越贷越穷的陷阱',
        summary: '无抵押、秒放款？小心落入贷款诈骗的陷阱。本文教你识别正规贷款和诈骗贷款。',
        cover: '/assets/images/covers/6.jpg',
        tags: ['贷款诈骗', '网络贷款', '资金诈骗'],
        category: '冒充熟人',
        date: '2024-03-03',
        views: 9870,
      },
      {
        id: 7,
        title: '刷单兼职诈骗：躺着赚钱的谎言',
        summary: '刷单诈骗已成为网络诈骗的重灾区。本文揭露刷单诈骗的常见套路和识别方法。',
        cover: '/assets/images/covers/7.jpg',
        tags: ['刷单诈骗', '兼职诈骗', '网络诈骗'],
        category: '刷单诈骗',
        date: '2024-02-28',
        views: 22340,
      },
      {
        id: 8,
        title: 'AI语音合成诈骗：听到的声音也可能是假的',
        summary: '诈骗分子利用AI语音合成技术冒充熟人声音诈骗。本文介绍如何防范此类高科技诈骗。',
        cover: '/assets/images/covers/8.jpg',
        tags: ['AI诈骗', '语音合成', '新技术'],
        category: '冒充公检法',
        date: '2024-02-25',
        views: 8740,
      },
    ],

    // 过滤后的文章
    filteredArticles: [],

    // 当前分类名称
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

    // RAG 知识库（模拟）
    ragKnowledge: [
      { keywords: ['AI换脸', '深度伪造', 'deepfake', '视频伪造', 'AI语音', '语音合成', '声音伪造', 'AI诈骗'], response: 'AI换脸诈骗是近年来高发的诈骗手法。诈骗分子使用深度伪造（Deepfake）技术，将他人面部替换到视频中，冒充熟人进行诈骗。\n\n识别方法：\n1. 观察视频中人物的眨眼、嘴部动作是否自然\n2. 注意面部边缘是否有模糊或拼接痕迹\n3. 要求对方做特定动作（如转头、挥手）\n4. 通过电话或其他渠道二次确认身份\n5. 使用专业的AI检测工具分析', refs: [{ title: '警惕！AI换脸诈骗来袭，视频通话也不可信了', id: 1 }, { title: 'AI语音合成诈骗：听到的声音也可能是假的', id: 8 }] },
      { keywords: ['96110', '反诈热线', '反诈电话'], response: '96110 是全国统一的反电信网络诈骗专用号码，主要用于：\n\n1. 预警劝阻：发现你可能正在被骗时，民警会通过96110联系你\n2. 知识普及：宣传防骗知识\n3. 咨询服务：解答诈骗相关问题\n\n⚠️ 注意：\n- 96110只有接听功能，不会主动要求转账\n- 真正的96110不会索要银行卡号、密码、验证码\n- 如接到自称96110但要求转账的，一定是诈骗', refs: [{ title: '冒充公检法诈骗升级：这些套路你必须知道', id: 2 }] },
      { keywords: ['刷单', '兼职', '刷信誉', '刷单诈骗'], response: '刷单诈骗是网络诈骗的重灾区！\n\n典型套路：\n1. 以"高薪兼职""足不出户"吸引注意\n2. 前几单小额返利骗取信任\n3. 要求做大额"连单"任务\n4. 以"系统故障""需要激活"为由拒绝返款\n\n🔑 防骗要点：\n- 任何要求垫资的兼职都是诈骗\n- 正规兼职不会要求你先付款\n- 刷单本身是违法行为\n- 不要被小额返利迷惑', refs: [{ title: '刷单兼职诈骗：躺着赚钱的谎言', id: 7 }] },
      { keywords: ['冒充公检法', '公安', '检察院', '法院', '安全账户', '洗钱', '涉嫌犯罪'], response: '冒充公检法诈骗是危害最大的诈骗类型之一！\n\n诈骗手法：\n1. 冒充公安、检察院、法院等机关工作人员\n2. 声称你涉嫌洗钱、贩毒等犯罪\n3. 要求将资金转入"安全账户"配合调查\n\n🚨 重要提醒：\n- 公检法机关不会通过电话办案\n- 不存在所谓的"安全账户"\n- 不会要求你转账或提供验证码\n- 遇到此类电话立即挂断并拨打96110核实', refs: [{ title: '冒充公检法诈骗升级：这些套路你必须知道', id: 2 }] },
      { keywords: ['杀猪盘', '婚恋', '网恋', '情感诈骗'], response: '杀猪盘是诈骗分子通过婚恋交友平台实施的诈骗。\n\n典型流程：\n1. 寻找目标：在婚恋平台物色对象\n2. 建立感情：嘘寒问暖获取信任\n3. 诱导投资：推荐"高收益"投资平台\n4. 收网跑路：无法提现后消失\n\n🛡️ 防范建议：\n- 网友推荐的投资平台大多是假平台\n- 未见面就谈钱务必要警惕\n- 高额回报必然伴随高风险\n- 及时与亲友沟通，听取意见', refs: [{ title: '杀猪盘诈骗完整揭秘：网恋背后的阴谋', id: 4 }] },
      { keywords: ['投资', '理财', '高收益', '非法集资'], response: '投资理财诈骗通常以"高收益""稳赚不赔"为诱饵。\n\n常见形式：\n1. 虚假投资平台：后台可操控涨跌\n2. 虚拟货币骗局：庞氏骗局包装\n3. 外汇/期货诈骗：伪造交易记录\n4. 原始股骗局：虚假上市承诺\n\n💡 判断标准：\n- 年化收益超过6%就要警惕\n- 承诺"保本保息"大多有猫腻\n- 查证平台是否具备金融牌照\n- 正规理财不会要求转账到个人账户', refs: [{ title: '投资理财需谨慎：高收益背后的陷阱', id: 3 }] },
      { keywords: ['客服', '退款', '退货', '网购', '快递'], response: '冒充客服退款诈骗是网购高发诈骗类型。\n\n常见话术：\n1. "您购买的XX商品有质量问题，可以双倍退款"\n2. "您的快递丢失了，这边给您理赔"\n3. "您的会员已开通，不取消将扣费"\n\n🔐 防骗指南：\n- 正规退款会原路返回，不需要额外操作\n- 不要点击对方发来的退款链接\n- 不要将验证码告诉任何人\n- 通过官方平台联系客服核实', refs: [{ title: '网购退款诈骗：客服来电的真相', id: 5 }] },
      { keywords: ['贷款', '网贷', '网络贷款', '借款', '贷款诈骗'], response: '网络贷款诈骗以"无抵押、秒放款"为诱饵。\n\n诈骗步骤：\n1. 发布低门槛贷款广告\n2. 要求下载虚假贷款APP\n3. 以"保证金""解冻费"为由要求转账\n4. 不断加码后消失\n\n✅ 正规贷款特征：\n- 放款前不会收取任何费用\n- 需要在正规应用商店下载APP\n- 年化利率明确公示\n- 接入征信系统', refs: [{ title: '网络贷款诈骗：越贷越穷的陷阱', id: 6 }] },
    ],
  },

  onLoad() {
    this._destroyed = false;
    this.setData({
      filteredArticles: this.data.articles,
      currentCategoryName: '全部文章',
    });
    this.loadFromBackend();
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

  loadFromBackend() {
    request({
      url: '/api/knowledge',
      method: 'GET',
      showLoading: false,
    }).then(data => {
      if (Array.isArray(data) && data.length > 0) {
        const articles = data.map((item, idx) => ({
          id: item.id || idx + 100,
          title: item.question || item.title || '',
          summary: item.answer || item.summary || '',
          cover: `/assets/images/covers/${(idx % 8) + 1}.jpg`,
          tags: item.tags || [],
          category: item.category || '',
          date: item.createdAt ? item.createdAt.substring(0, 10) : '',
          views: 0,
        }));
        if (articles.length > 0) {
          this.setData({ articles, filteredArticles: articles });
          this.filterArticles();
        }
      }
    }).catch((err) => {
      console.warn('从后端加载知识库失败，使用本地数据:', err);
      // 后端不可用时使用本地数据
    });
  },

  // 返回
  onBack() {
    wx.navigateBack();
  },

  // 选择分类
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

  // 搜索变化
  onSearchChange(e) {
    this.setData({
      searchKey: e.detail.value,
      currentCategoryName: e.detail.value ? '搜索结果' : this.getCurrentCategoryName(),
    });
    this.filterArticles();
  },

  // 清空搜索
  onClearSearch() {
    this.setData({
      searchKey: '',
      currentCategoryName: this.getCurrentCategoryName(),
    });
    this.filterArticles();
  },

  // 获取当前分类名称
  getCurrentCategoryName() {
    const category = this.data.categories.find(c => c.id === this.data.currentCategory);
    return category ? category.name + '文章' : '全部文章';
  },

  // 提交搜索
  onSearchSubmit() {
    this.filterArticles();
  },

  // 按标签搜索
  searchByTag(e) {
    const { tag } = e.currentTarget.dataset;
    this.setData({
      searchKey: tag,
      currentCategoryName: '搜索结果',
    });
    this.filterArticles();
  },

  // 过滤文章
  filterArticles() {
    const { articles, currentCategory, searchKey } = this.data;

    if (!Array.isArray(articles)) {
      this.setData({ filteredArticles: [] });
      return;
    }

    let filtered = articles.filter(item => item != null);

    // 按分类过滤
    if (currentCategory !== 'all') {
      filtered = filtered.filter((item) => item.category === currentCategory);
    }

    // 按关键词过滤
    if (searchKey) {
      const key = searchKey.toLowerCase();
      filtered = filtered.filter(
        (item) =>
          (item.title && item.title.toLowerCase().includes(key)) ||
          (item.summary && item.summary.toLowerCase().includes(key)) ||
          (Array.isArray(item.tags) && item.tags.some((tag) => tag.toLowerCase().includes(key) || key.includes(tag.toLowerCase())))
      );
    }

    this.setData({
      filteredArticles: filtered,
    });
  },

  // 查看文章
  viewArticle(e) {
    const { id } = e.currentTarget.dataset;
    const article = this.data.articles.find((item) => item.id === id);

    if (article) {
      wx.navigateTo({
        url: `/pages/article/article?id=${id}&data=${encodeURIComponent(JSON.stringify(article))}`,
      });
    }
  },

  // ==================== RAG 问答逻辑 ====================

  // 切换问答面板
  toggleQA() {
    this.setData({ showQA: !this.data.showQA });
  },

  // 输入变化
  onQAInput(e) {
    this.setData({ qaInput: e.detail.value });
  },

  // 发送问题（RAG + LLM 流式输出，失败时回退本地匹配）
  async sendQuestion() {
    const { qaInput } = this.data;
    const text = qaInput.trim();
    if (!text || this.data.qaLoading) return;

    const msgId = this.data.msgCounter + 1;
    const userMsg = { id: msgId, role: 'user', content: text };

    this.setData({
      qaMessages: [...this.data.qaMessages, userMsg],
      qaInput: '',
      qaLoading: true,
      msgCounter: msgId,
      lastMsgId: `msg-${msgId}`,
    });

    let streamTask = null;

    try {
      // Step 1: RAG 检索
      let ragResults = [];
      let ragSuccess = false;
      try {
        ragResults = await ragAPI.query(text);
        ragSuccess = Array.isArray(ragResults) && ragResults.length > 0;
      } catch (e) {
        console.warn('RAG查询失败，使用本地匹配:', e.message);
      }

      let references = [];
      const aiId = msgId + 1;

      if (ragSuccess) {
        const ragContext = ragResults.map((r, i) =>
          `[${i + 1}] ${r.content || ''}`
        ).join('\n');

        const userQuestion = text;
        const contextPrompt = `知识库内容：\n${ragContext}\n\n用户问题：${userQuestion}\n请基于知识库回答，简洁准确。`;

        const aiMsg = { id: aiId, role: 'ai', content: '', references: [] };
        this.setData({
          qaMessages: [...this.data.qaMessages, aiMsg],
          msgCounter: aiId,
          lastMsgId: `msg-${aiId}`,
        });

        let streamOk = false;
        try {
          await new Promise((resolve, reject) => {
            const streamResult = requestStream('/api/llm/analyze/stream', { text: contextPrompt }, {
              onMessage: (chunk, fullContent) => {
                const msgs = this.data.qaMessages.map(m =>
                  m.id === aiId ? { ...m, content: fullContent } : m
                );
                this.setData({ qaMessages: msgs });
              },
              onDone: (fullContent) => {
                streamOk = true;
                resolve();
              },
              onError: (err) => {
                reject(err);
              },
            });
            streamTask = streamResult;
            this._qaStreamTask = streamResult;
          });
        } catch (e) {
          console.warn('LLM流式失败，降级为非流式:', e.message);
        } finally {
          if (streamTask) {
            streamTask.abort();
            this._qaStreamTask = null;
          }
        }

        if (!streamOk) {
          // 降级：非流式 LLM 调用
          try {
            const fullResponse = await ragAPI.analyze(contextPrompt);
            const content = fullResponse || ragResults.map(r => r.content).join('\n\n');
            const msgs = this.data.qaMessages.map(m =>
              m.id === aiId ? { ...m, content } : m
            );
            this.setData({ qaMessages: msgs });
          } catch (e2) {
            const content = ragResults.map(r => r.content).join('\n\n');
            const msgs = this.data.qaMessages.map(m =>
              m.id === aiId ? { ...m, content } : m
            );
            this.setData({ qaMessages: msgs });
          }
        }

        // 匹配参考文章
        references = this._matchReferences(text, ragResults || []);

        // 更新引用
        const finalMsgs = this.data.qaMessages.map(m =>
          m.id === aiId ? { ...m, references } : m
        );
        this.setData({ qaMessages: finalMsgs, qaLoading: false, lastMsgId: `msg-${aiId}` });
      } else {
        // 回退：本地关键词匹配（非流式）
        const local = this.generateRAGAnswer(text);
        references = local.refs || [];
        const aiMsg = { id: aiId, role: 'ai', content: local.response, references };
        this.setData({
          qaMessages: [...this.data.qaMessages, aiMsg],
          qaLoading: false,
          msgCounter: aiId,
          lastMsgId: `msg-${aiId}`,
        });
      }
    } catch (err) {
      console.error('问答处理异常:', err);
      const fallback = this.generateRAGAnswer(text);
      const aiId = this.data.msgCounter + 1;
      const aiMsg = { id: aiId, role: 'ai', content: fallback.response, references: fallback.refs };
      this.setData({
        qaMessages: [...this.data.qaMessages, aiMsg],
        qaLoading: false,
        msgCounter: aiId,
        lastMsgId: `msg-${aiId}`,
      });
    }
  },

  // 将 RAG 结果匹配到本地文章引用
  _matchReferences(question, ragResults) {
    const { articles } = this.data;
    const q = question.toLowerCase();
    const matchedRefs = [];
    const usedIds = new Set();

    // 提取问题关键词
    const keywords = q.split(/[\s,，。.？?！!、；;：:]/).filter(k => k.length >= 2);

    // 遍历本地文章，计算相关性
    const scored = articles.map(article => {
      const title = article.title.toLowerCase();
      const summary = article.summary.toLowerCase();
      const matchCount = keywords.filter(k =>
        title.includes(k) || summary.includes(k)
      ).length;
      return { article, score: matchCount };
    }).filter(s => s.score > 0)
      .sort((a, b) => b.score - a.score)
      .slice(0, 3);

    for (const { article } of scored) {
      matchedRefs.push({ title: article.title, articleId: article.id });
    }

    // 如果本地文章匹配不到，用 RAG 结果中的来源
    if (matchedRefs.length === 0 && ragResults.length > 0) {
      const src = ragResults[0];
      const title = src.source || src.title || src.category || '相关知识库';
      matchedRefs.push({ title, articleId: null });
    }

    return matchedRefs;
  },

  // 点击建议问题
  askQuestion(e) {
    const q = e.currentTarget.dataset.q;
    this.setData({ qaInput: q }, () => {
      this.sendQuestion();
    });
  },

  // RAG 检索 + 生成回答
  generateRAGAnswer(question) {
    const { ragKnowledge, articles } = this.data;
    const q = question.toLowerCase();

    // 检索匹配的知识
    let matched = null;
    let maxScore = 0;

    for (const item of ragKnowledge) {
      let score = 0;
      for (const kw of item.keywords) {
        if (q.includes(kw.toLowerCase())) {
          score += 2;
        }
      }
      // 也检查是否部分匹配
      const wordMatch = item.keywords.some(kw => kw.toLowerCase().split('').some((ch, i) => {
        if (i > 0 && q.includes(ch)) return false;
        return q.includes(kw.toLowerCase());
      }));
      if (wordMatch) score += 1;

      if (score > maxScore) {
        maxScore = score;
        matched = item;
      }
    }

    // 如果没有匹配到，使用默认回答
    if (!matched || maxScore < 1) {
      return {
        response: '感谢您的提问！关于这个问题，建议您查看知识库中的相关文章，或拨打反诈热线 96110 咨询专业人士。您也可以换个关键词试试，比如"AI换脸""刷单""冒充公检法"等。',
        refs: [],
      };
    }

    // 将 refs 中的 id 映射为文章信息
    const refs = (matched.refs || []).map(ref => {
      const article = articles.find(a => a.id === ref.id);
      return { title: ref.title, articleId: ref.id };
    }).filter(Boolean);

    return { response: matched.response, refs };
  },

  // 通过引用跳转文章
  viewArticleByRef(e) {
    const { id } = e.currentTarget.dataset;
    const article = this.data.articles.find(a => a.id === id);
    if (article) {
      wx.navigateTo({
        url: `/pages/article/article?id=${id}&data=${encodeURIComponent(JSON.stringify(article))}`,
      });
    }
  },

  // 拨打热线
  callHotline() {
    wx.makePhoneCall({
      phoneNumber: '96110',
      success: () => {
        console.log('拨打电话成功');
      },
      fail: () => {
        app.showError('拨打电话失败');
      },
    });
  },
});
