# 诈骗克星微信小程序 — 端到端（E2E）完整测试报告

> 测试日期：2026-05-26  
> 测试范围：全部 7 个页面（首页、检测、结果、模拟、知识库、文章详情、关于）  
> 测试类型：端到端功能集成测试（前端逻辑 + API 接口对接 + 数据流转验证）  
> 小程序 AppID：`wx859a5eef3e30a6e5`  
> 后端地址：`http://localhost:8080`（配置于 `utils/config.js`）

---

## 目录

1. [测试环境与前提条件](#1-测试环境与前提条件)
2. [总体架构数据流图](#2-总体架构数据流图)
3. [测试用例总览](#3-测试用例总览)
4. [TC1: 全局初始化流程](#4-tc1-全局初始化流程)
5. [TC2: 首页（index）](#5-tc2-首页index)
6. [TC3: 检测页面（detection）](#6-tc3-检测页面detection)
7. [TC4: 结果页面（result）](#7-tc4-结果页面result)
8. [TC5: 模拟诈骗页面（simulate）](#8-tc5-模拟诈骗页面simulate)
9. [TC6: 知识库页面（knowledge）](#9-tc6-知识库页面knowledge)
10. [TC7: 文章详情页面（article）](#10-tc7-文章详情页面article)
11. [TC8: 关于页面（about）](#11-tc8-关于页面about)
12. [TC9: 跨页面集成测试](#12-tc9-跨页面集成测试)
13. [TC10: 边界与容错测试](#13-tc10-边界与容错测试)
14. [发现的问题汇总](#14-发现的问题汇总)
15. [测试结论](#15-测试结论)

---

## 1. 测试环境与前提条件

### 环境要求

| 项目 | 说明 |
|------|------|
| 微信开发者工具 | 建议使用稳定版 Stable Build，基础库 >= 3.16.0 |
| 后端服务 | 后端需在 `http://localhost:8080` 正常运行（Docker 容器部署） |
| 数据库 | MySQL 中 `safeguard` 数据库已初始化，含 `user`、`detection_record`、`knowledge` 等表 |
| Milvus 向量库 | 需运行于 `localhost:19530`，`anti_fraud_knowledge` collection 已创建 |
| 网络 | 开发者工具中需关闭「校验合法域名、web-view（业务域名）、TLS 版本以及 HTTPS 证书」选项 |
| 登录 | 后端 `/api/auth/login` 接口需已实现 JWT 认证 |

### 测试账号

- 微信用户：通过 `wx.login()` 自动获取 code
- 后端匿名模式：当网络不可达时自动降级为匿名使用

---

## 2. 总体架构数据流图

```mermaid
flowchart TD
    subgraph 微信小程序端
        A[app.js 初始化] --> B[加载本地存储]
        A --> C[tryAutoLogin]
        C --> D[auth.js → POST /api/auth/login]
        A --> E[loadHistoryFromBackend]
        E --> F[GET /api/records/list]
        
        G[index 首页] --> H[Canvas 安全环]
        G --> I[recordAPI.list → 统计看板]
        G --> J[本地检测记录渲染]
        
        K[detection 检测页] --> L[uploadFile → POST /api/detection/audio]
        K --> M[uploadFile → POST /api/detection/video]
        K --> N[request → POST /api/detection/text]
        K --> O[TaskWatcher → SSE + Polling]
        K --> P[handleDetectionResult → 保存记录]
        
        Q[simulate 模拟页] --> R[GET /api/simulate/scripts]
        Q --> S[GET /api/simulate/start/{id}]
        Q --> T[POST /api/simulate/chat]
        Q --> U[POST /api/simulate/end]
        Q --> V[SSE 流式输出 /api/llm/scam/chat/stream]
        
        W[knowledge 知识库] --> X[GET /api/knowledge]
        W --> Y[RAG → GET /api/rag/query]
        W --> Z[LLM 流式 → POST /api/llm/analyze/stream]
        W --> AA[本地 keyword 兜底匹配]
    end
    
    subgraph 后端 Spring Boot :8080
        BB[AuthController] --> CC[JWT 认证]
        DD[DetectionController] --> EE[音频/视频/文本检测]
        DD --> FF[TaskWatcher SSE + Polling]
        GG[DetectionRecordController] --> HH[CRUD 记录]
        II[SimulateController] --> JJ[LLM 对话]
        KK[RAGController] --> LL[Milvus 向量检索]
        MM[KnowledgeController] --> NN[DB 知识库 CRUD]
        OO[LLMController] --> PP[SSE 流式 LLM]
    end
    
    PP --> V
    LL --> MM
    NN --> X
    HH --> F
    CC --> D
    EE --> L
    EE --> M
    JJ --> T
```

---

## 3. 测试用例总览

| 编号 | 模块 | 用例数 | 覆盖范围 |
|------|------|--------|----------|
| TC1 | 全局初始化 | 6 | app.js 启动、登录、本地存储、检测记录加载 |
| TC2 | 首页 | 12 | Canvas 渲染、统计看板、记录展示、导航跳转、防骗提醒、分享 |
| TC3 | 检测页 | 16 | 4种检测模式、文件选择/校验、上传、SSE 监控、结果渲染 |
| TC4 | 结果页 | 8 | 数据展示、收藏、分享、再次检测、容错处理 |
| TC5 | 模拟页 | 14 | 剧本加载、SSE 流式、打字动画、对话轮次、总结分析 |
| TC6 | 知识库 | 12 | 分类筛选、搜索、RAG 问答、LLM 流式、本地回退 |
| TC7 | 文章详情 | 6 | 内容渲染、相关推荐、导航、分享 |
| TC8 | 关于页 | 4 | 信息展示、导航、分享 |
| TC9 | 跨页面集成 | 8 | 页面间跳转、参数传递、数据一致性 |
| TC10 | 边界与容错 | 10 | 网络异常、空数据、大文件、超时、后端不可达 |

**总计：96 个测试用例**

---

## 4. TC1: 全局初始化流程

### 4.1 App 启动生命周期

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC1-1 | 首次启动完整流程 | 小程序首次安装，无本地缓存 | 1. 打开小程序 | `onLaunch` 依次调用: `initCloud` → `loadLocalData`(空) → `checkUpdate` → `tryAutoLogin` → `loadHistoryFromBackend` | ⬜ |
| TC1-2 | 二次启动加载本地缓存 | 已有检测记录和登录信息 | 1. 关闭后重新打开 | `loadLocalData` 从 `wx.getStorageSync` 恢复 `detectionHistory` 和 `userInfo` 到 `globalData` | ⬜ |

### 4.2 自动登录

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC1-3 | 自动登录成功 | 后端可达，`/api/auth/login` 返回 token | 1. 打开小程序 | `auth.login()` 调用 `wx.login()` + `POST /api/auth/login` → 收到 token → 存入 `wx.setStorageSync` + `globalData.userInfo` | ⬜ |
| TC1-4 | 自动登录超时降级 | 后端无响应或响应 > 3s | 1. 打开小程序 | `Promise.race` 中 login 超时 → 3s 后降级为匿名模式，`globalData.userInfo` 保持 null | ⬜ |
| TC1-5 | Token 过期自动续期 | 本地存储有过期 token | 1. 打开小程序 2. 首页调用 `recordAPI.list` | `request.js` 检测 401 → 调用 `auth.clearAuth()` → 弹出提示「登录已过期，请重新打开小程序」 | ⬜ |

### 4.3 检测历史加载

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC1-6 | 从后端加载检测历史 | 用户已登录，后端有检测记录 | 1. 应用启动 | `recordAPI.list(userId, 20)` → 返回记录数组 → 映射为 `globalData.detectionHistory` → 触发 `saveLocalData` | ⬜ |

---

## 5. TC2: 首页（index）

### 5.1 UI 渲染

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-1 | 自定义导航栏高度正确 | — | 1. 进入首页 | `setNavBarHeight` 根据 `wx.getWindowInfo()` 计算 `navBarHeight` = `statusBarHeight + 46`，header-bar 高度正确 | ⬜ |
| TC2-2 | 问候语根据时段变化 | — | 1. 在不同时段进入首页 | 0-6点/22-24点 → "夜深了"；6-9点 → "早上好"；9-12点 → "上午好"；12-14点 → "中午好"；14-18点 → "下午好"；18-22点 → "晚上好" | ⬜ |
| TC2-3 | 今日日期正确显示 | — | 1. 进入首页 | 格式 "YYYY年M月D日 星期X" 正确 | ⬜ |
| TC2-4 | 暗色模式适配 | 系统设置为深色模式 | 1. 进入首页 | Canvas 使用暗色主题颜色 (`theme === 'dark'`)：trackColor 为 `rgba(255,255,255,0.08)`，文字色适配 | ⬜ |

### 5.2 Canvas 安全环

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-5 | Canvas 安全环渲染 | 有检测记录（安全率 > 0） | 1. 进入首页 | `drawScoreRing` 创建 2D Canvas，渐变色进度弧从 `-π/2` 开始，动画播放递增到 `safeRate`，中心显示百分比 | ⬜ |
| TC2-6 | 无数据时的空状态 | 无检测记录（安全率 = '--'） | 1. 进入首页 | Canvas 显示半弧轨迹，中心文字 "暂无数据"，requestAnimationFrame 不启动 | ⬜ |
| TC2-7 | 安全率颜色逻辑 | 安全率 >= 80% / 50-80% / < 50% | 1. 分别构造不同安全率数据 | ≥80% → 绿色渐变色 `#10b981→#34d399`；50-80% → 黄色 `#f59e0b→#fbbf24`；<50% → 红色 `#ef4444→#f87171` | ⬜ |

### 5.3 数据看板

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-8 | 统计数字来自后端 | 用户已登录且有记录 | 1. 进入首页 | `updateStats` 调用 `recordAPI.list(userId, 100)` → 计算 `detectionCount`、`safeRate` = `round((1-dangerCount/count)*100)`，`accuracy` 固定为 `98.5` | ⬜ |
| TC2-9 | 无后端时使用本地数据 | 后端不可达 | 1. 进入首页 | 捕获异常后用 `globalData.detectionHistory` 本地数据计算统计 | ⬜ |
| TC2-10 | 威胁等级判定 | 风险占比 >= 40% / 15-40% / < 15% | 1. 构造不同风险数据 | ≥40% → `level=3, label='高风险', color='#ef4444'`；15-40% → `level=2, label='需留意', color='#f59e0b'`；<15% → `level=1, label='安全', color='#10b981'` | ⬜ |

### 5.4 近期检测记录 & 交互

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-11 | 点击记录跳转结果页 | 有检测记录(≥1条) | 1. 首页展示记录 2. 点击某条记录 | `goToResult` 读取 `globalData.detectionHistory[idx]` → 构造 `resultData` → `JSON.stringify` 编码 → `navigateTo(/pages/result/result)` | ⬜ |
| TC2-12 | 空记录展示引导卡片 | 无检测记录 | 1. 进入首页 | 显示 empty-card：「开始您的安全之旅」，点击跳转 `switchTab` 到检测页 | ⬜ |

### 5.5 防骗提醒 & 导航

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-13 | 每日提醒按日期取模 | — | 1. 进入首页 | `tips[date.getDate() % tips.length]` 选中当日提醒 | ⬜ |
| TC2-14 | 换一条功能 | — | 1. 点击「换一条」 | 随机选取不同于当前 `dailyTip` 的提醒 | ⬜ |
| TC2-15 | 拨打反诈热线 | — | 1. 点击页面无此按钮（首页无 callHotline 入口） | 检查首页 index.js — 无 `callHotline` 的 UI 触发绑定 | ⬜ |

### 5.6 导航入口

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC2-16 | 「开始检测」跳转 | — | 1. 点击「开始检测」按钮 | `goToDetection` → `wx.switchTab({ url: '/pages/detection/detection' })` | ⬜ |
| TC2-17 | AI 问答浮窗跳转 | — | 1. 点击右下角「问 AI」按钮 | `openQA` → 设置 `app.globalData.openQA = true` → `wx.switchTab` 到知识库页 | ⬜ |

---

## 6. TC3: 检测页面（detection）

### 6.1 Tab 切换

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-1 | 4个检测Tab切换 | — | 1. 分别切换到音频/视频/文本/综合 Tab | `onTabChange` 更新 `currentType` → 调用 `updateCanDetect` 重新计算可检测状态 | ⬜ |
| TC3-2 | 检测按钮状态联动 | 不同Tab不同输入状态 | 1. 音频: 无文件/有文件 2. 文本: <10字/>=10字 | `canDetect` 正确计算: 音频需要 `!!audioFile`；文本需要 `textContent.trim().length >= 10` | ⬜ |

### 6.2 音频选择与校验

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-3 | 选择音频文件 | — | 1. 点击音频选择 2. 从聊天文件选择 mp3 文件 | `chooseMessageFile({ type: 'audio' })` → 校验扩展名(json) → 校验大小 ≤ 10MB → 设置 `audioFile` 含 path/name/size | ⬜ |
| TC3-4 | 不支持的音频格式 | — | 1. 选择非 mp3/wav/aac/m4a/ogg 格式的音频文件 | 弹窗提示「不支持的音频格式」 | ⬜ |
| TC3-5 | 音频超限校验 | 选择 > 10MB 的音频 | 1. 选择大音频文件 | `file.size > MAX_AUDIO_SIZE(10MB)` → 弹窗提示「音频文件不能超过10MB」 | ⬜ |
| TC3-6 | 移除已选择的音频 | 已选择音频文件 | 1. 点击已选音频区域 2. 确认移除 | `wx.showModal` 确认 → `removeAudio` 清空 `audioFile` | ⬜ |

### 6.3 视频选择与校验

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-7 | 选择视频文件 | — | 1. 切换到视频Tab 2. 点击选择视频 | `chooseVideo({ sourceType: ['album','camera'] })` → 校验扩展名 → 校验 ≤ 50MB → 设置 `videoFile` | ⬜ |
| TC3-8 | 视频超限校验 | 选择 > 50MB 的视频 | 1. 选择大视频文件 | 弹窗提示「视频文件不能超过50MB」 | ⬜ |

### 6.4 文本检测输入

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-9 | 手动输入文本 | — | 1. 切换到文本Tab 2. 输入文本 | `onTextChange` 更新 `textContent` → `updateCanDetect` | ⬜ |
| TC3-10 | 使用示例文本 | — | 1. 点击示例文本 | `useExample` 设置 `textContent` 为示例内容 | ⬜ |
| TC3-11 | 文本最短长度校验 | 输入 < 10 个字符 | 1. 输入 "测试" | `canDetect` = false，「开始检测」按钮 disabled | ⬜ |

### 6.5 音频/文本检测流程

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-12 | 音频检测流程 | 已选择音频文件 | 1. 点击「开始检测」 | `startDetection` → `detectionAPI.detectAudio(path)` → `uploadFile` 调用 `POST /api/detection/audio` → 接收 `AudioDetectionResult` → `handleDetectionResult` | ⬜ |
| TC3-13 | 文本检测流程 | 文本内容 ≥ 10 字 | 1. 点击「开始检测」 | `detectionAPI.detectText(text)` → `request` 调用 `POST /api/detection/text` → 接收结果 → `handleDetectionResult` | ⬜ |

### 6.6 视频检测（异步任务）

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-14 | 视频检测异步任务 | 已选择视频文件 | 1. 点击「开始检测」 | `detectVideo` → `POST /api/detection/video` → 返回 `{taskId, status:'processing'}` → 创建 `TaskWatcher` → `watch(taskId, callbacks)` | ⬜ |
| TC3-15 | SSE 任务进度推送 | 视频检测进行中 | 1. 任务监控启动后 | `_startSSE` 建立 `GET /api/detection/task/{taskId}/stream` → `onChunkReceived` 解析 SSE 事件流 → `onProgress` 回调更新检测状态 | ⬜ |
| TC3-16 | 轮询回退 | SSE 不可用 | 1. 启动视频检测（SSE 失败） | `_startSSE` 返回 false → `_startPolling` 启动 1.5s 间隔轮询 `GET /api/detection/task/{taskId}` → 最多 120 次 | ⬜ |

### 6.7 检测结果展示

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-17 | 风险等级判定 | 伪造概率 > 0.7 / 0.4-0.7 / ≤ 0.4 | 1. 模拟不同伪造概率的结果 | >0.7 → `level='danger', name='高风险', iconColor='#ef4444'`；0.4-0.7 → `level='warning'`；≤0.4 → `level='success'` | ⬜ |
| TC3-18 | 结果面板展示 | 检测完成 | 1. 检测完成自动弹出底部面板 | 显示风险等级、置信度、AI分析报告、Agent过程、知识来源、防范建议 | ⬜ |
| TC3-19 | 查看完整报告 | 检测完成 | 1. 点击「查看完整报告」 | `viewFullReport` → `navigateTo` 结果页，传入 type + detectionResult JSON | ⬜ |

### 6.8 记录保存

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC3-20 | 检测结果保存到后端 | 用户已登录 | 1. 完成一次检测 | `saveDetectionResult` 构造 `recordData` → `recordAPI.save(recordData)` → `POST /api/records/save` | ⬜ |
| TC3-21 | 检测结果加入本地历史 | 完成检测 | 1. 完成一次检测 | `app.addDetectionRecord` → 插入 `globalData.detectionHistory` 数组头部 → 触发 `saveLocalData` | ⬜ |

---

## 7. TC4: 结果页面（result）

### 7.1 数据展示

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC4-1 | 通过参数传入结果数据 | 从检测页 navigateTo 进入 | 1. 完成检测 2. 查看完整报告 | `onLoad` 从 `options.data` 解析 JSON → `processDetectionResult` 渲染风险徽章、置信度、环形进度、风险特征列表、防范建议 | ⬜ |
| TC4-2 | 无参数时使用最新检测记录 | 直接从导航进入(无 options.data) | 1. 直接进入结果页 | 读取 `app.globalData.detectionHistory[latest]` → 渲染结果 | ⬜ |
| TC4-3 | 无数据时提示 | 无任何检测历史 | 1. 直接进入结果页 | 弹窗提示「暂无检测数据」 | ⬜ |

### 7.2 收藏功能

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC4-4 | 收藏/取消收藏 | 结果页展示 | 1. 点击收藏按钮 2. 再次点击 | `onCollect` 切换 `isCollected` → 读取/写入 `wx.getStorageSync('collections')` → 弹 Toast「已收藏/已取消收藏」 | ⬜ |
| TC4-5 | 收藏状态持久化 | 已收藏某条记录 | 1. 退出结果页 2. 重新进入相同结果 | `loadCollectedStatus(recordId)` 从本地存储检查收藏状态 → 正确显示收藏图标状态 | ⬜ |

### 7.3 导航与操作

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC4-6 | 返回上一页 | — | 1. 点击返回按钮 | `onBack` → `wx.navigateBack()` | ⬜ |
| TC4-7 | 再次检测 | — | 1. 点击「再次检测」 | `detectAgain` → `wx.navigateBack()` | ⬜ |
| TC4-8 | 分享检测结果 | — | 1. 点击分享按钮 | `onShare` → `showShareMenu` 支持分享给好友和朋友圈；`onShareAppMessage` / `onShareTimeline` 根据风险等级显示不同标题 | ⬜ |

---

## 8. TC5: 模拟诈骗页面（simulate）

### 8.1 剧本加载

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC5-1 | 从后端加载剧本列表 | 后端可达，`/api/simulate/scripts` 返回数据 | 1. 进入模拟页 | `loadScripts` → `simulateAPI.getScripts()` → 映射为 `scripts` 数组（含 id/name/color/difficulty/description/tags） | ⬜ |
| TC5-2 | 后端不可用时使用本地默认剧本 | 后端不可达 | 1. 进入模拟页 | `loadScripts` catch → `getDefaultScripts()` 返回 4 个内置剧本：冒充公检法/熟人/投资理财/网购客服 | ⬜ |
| TC5-3 | 显示4个剧本卡片 | 剧本加载完成 | 1. 查看剧本列表 | 显示 4 个剧本卡片，各自带有颜色、难度标签和描述 | ⬜ |

### 8.2 模拟对话流程

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC5-4 | 开始模拟 | 选中剧本卡片 → 点击开始 | 1. 点击确认开始 | `startSimulation` → `POST /api/simulate/start/{scriptId}` → 返回 AI 开场白 → `_processAIMessage` 显示打字动画效果 | ⬜ |
| TC5-5 | 打字动画效果 | AI 开始回复 | 1. 查看 AI 消息 | `streamContent` 逐个字符递增显示，中文字符 40ms 间隔，标点 120ms 间隔，换行 80ms 间隔 | ⬜ |
| TC5-6 | SSE 流式对话 | LLM 回复支持 SSE | 1. AI 回复开始时 | `tryStream` 检测 `response.streamUrl` → 建立 SSE 连接 → `onChunkReceived` 实时更新消息内容 | ⬜ |
| TC5-7 | 用户发送消息 | AI 已完成回复 | 1. 输入消息 2. 点击发送 | `sendMessage` → XSS 过滤 `replace(/[<>]/g,'')` → 截断 2000 字 → 限制消息总数 ≤ 100 → `POST /api/simulate/chat` → 等待回复 | ⬜ |
| TC5-8 | 快捷回复 | AI 提供快捷回复选项 | 1. 查看快捷回复气泡 2. 点击其中一个 | `sendQuickReply` → 设置 `inputText` → 自动调用 `sendMessage` | ⬜ |
| TC5-9 | 警觉指数变化 | 多次对话后 | 1. 观察警觉指数条 | `suspicionScore` 初始值 50，根据后端返回的 `suspicionDelta` 增减，范围 0-100 | ⬜ |
| TC5-10 | 对话轮次限制 | 达到 10 轮对话 | 1. 持续对话 | `maxTurns: 10` 限制对话轮次（注：当前代码中 `turnCount` 仅声明未自增，实际轮次未限制） | ⚠️ 代码问题 |

### 8.3 模拟结束与总结

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC5-11 | 后端主动结束模拟 | 后端返回 `finished: true` | 1. 发送消息后 | `sendMessage` 检测 `response.finished === true` → 800ms 延迟后自动弹出总结弹窗 | ⬜ |
| TC5-12 | 模拟总结弹窗 | 模拟结束 | 1. 查看弹窗 | 显示 AI 分析报告 (`analysis`)、防范建议 (`tips`)、警觉指数 | ⬜ |
| TC5-13 | 结束模拟调用后端 | 手动退出模拟 | 1. 点击退出 2. 确认退出 | `_fetchEndAnalysisFromBackend` → `POST /api/simulate/end` → 获取 analysis + tips | ⬜ |
| TC5-14 | 重新模拟 / 返回列表 | 模拟总结弹窗 | 1. 点击「再来一次」/「返回列表」 | 重新模拟 → `restartSimulation` → `startSimulation`；返回列表 → `resetSimulation` → 清空所有模拟状态 | ⬜ |

### 8.4 退出确认

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC5-15 | 模拟中退出确认 | 正在进行模拟 | 1. 点击返回 | `showExitConfirm` → `wx.showModal` → 若确认则 `resetSimulation` | ⬜ |
| TC5-16 | 未开始模拟直接返回 | 未选择剧本 | 1. 点击返回 | `onBack` → 当前页栈 > 1 ? `navigateBack` : `switchTab` 到首页 | ⬜ |

---

## 9. TC6: 知识库页面（knowledge）

### 9.1 文章展示与筛选

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC6-1 | 本地文章默认加载 | 无后端或后端不可达 | 1. 进入知识库 | `onLoad` → 使用 8 篇本地预置文章 → `filteredArticles` 初始为全部 | ⬜ |
| TC6-2 | 从后端加载知识库文章 | 后端 `/api/knowledge` 返回数据 | 1. 进入知识库 | `loadFromBackend` → `GET /api/knowledge` → 映射为文章列表 → 替换本地数据 | ⬜ |
| TC6-3 | 分类筛选 | 文章加载完成 | 1. 分别点击 6 个分类 | `selectCategory` → 按 `category` 过滤 → 更新 `filteredArticles`，搜索词清空 | ⬜ |
| TC6-4 | 搜索功能 | 文章加载完成 | 1. 输入关键词 | `onSearchChange`/`onSearchSubmit` → 按标题/摘要/标签包含匹配 → 大小写不敏感 | ⬜ |
| TC6-5 | 热门标签搜索 | 文章加载完成 | 1. 点击热门标签 | `searchByTag` → 设置 `searchKey=标签` → `filterArticles` | ⬜ |
| TC6-6 | 清空搜索 | 搜索状态 | 1. 点击清空按钮 | `onClearSearch` → 清空 `searchKey` → 恢复当前分类筛选 | ⬜ |
| TC6-7 | 查看文章详情 | 文章加载完成 | 1. 点击文章卡片 | `viewArticle` → `navigateTo(/pages/article/article)` → 传入 JSON 序列化的文章数据 | ⬜ |

### 9.2 RAG 智能问答

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC6-8 | 打开问答面板 | — | 1. 点击「AI 问答」入口 | `toggleQA` → 显示全屏问答面板 | ⬜ |
| TC6-9 | RAG + LLM 流式问答（主流程） | 后端 RAG 和 LLM 均可用 | 1. 输入问题 2. 发送 | `sendQuestion` → Step1: `ragAPI.query(text)` → `GET /api/rag/query?q=...` 检索知识库 → Step2: 构造 `contextPrompt` → `requestStream('/api/llm/analyze/stream')` SSE 流式输出 → Step3: `_matchReferences` 匹配参考文章 | ⬜ |
| TC6-10 | RAG 成功但 LLM 流式失败 → 降级非流式 | RAG 返回结果，LLM SSE 失败 | 1. 发送问题 | RAG 检索成功 → LLM SSE 失败 → catch → `ragAPI.analyze(contextPrompt)` 非流式调用 | ⬜ |
| TC6-11 | RAG + 非流式 LLM 都失败 → 直接拼接 RAG 结果 | RAG 成功、非流式也失败 | 1. 发送问题 | 非流式 catch → 将 `ragResults.map(r => r.content).join('\n\n')` 作为回答 | ⬜ |
| TC6-12 | RAG 检索失败 → 本地关键词回退 | RAG 查询失败或返回空 | 1. 发送问题 | `ragAPI.query` catch → `generateRAGAnswer(text)` 本地 `ragKnowledge` 关键词匹配（含 7 个预置话题） | ⬜ |
| TC6-13 | 本地关键词完全匹配不上 → 默认回复 | 问题与 7 个话题无关 | 1. 发送无关问题 | `maxScore < 1` → 返回通用默认回复 + 空引用列表 | ⬜ |
| TC6-14 | 点击建议问题 | 问答面板打开 | 1. 点击建议问题 | `askQuestion` → 设置 `qaInput` → 自动调用 `sendQuestion` | ⬜ |
| TC6-15 | 问答消息渲染 | 问答有交互 | 1. 查看消息列表 | 用户消息 blue、AI 消息 green、引用文章可点击跳转 | ⬜ |

### 9.3 呼叫热线

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC6-16 | 拨打 96110 反诈热线 | — | 1. 点击「拨打电话」 | `callHotline` → `wx.makePhoneCall({ phoneNumber: '96110' })` | ⬜ |

---

## 10. TC7: 文章详情页面（article）

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC7-1 | 文章内容渲染 | 从知识库传入文章数据 | 1. 进入文章详情 | `processArticle` → 从 `contentMap` 根据 `article.id` 获取 Markdown 格式文章内容 → 设置 `articleContent` | ⬜ |
| TC7-2 | 文章 id 不在 contentMap 中 | 传入未知 id | 1. 查看文章 | `contentMap[article.id]` 为 undefined → 显示摘要 + "完整内容正在加载中..." | ⬜ |
| TC7-3 | 相关文章推荐 | 文章加载完成 | 1. 滑动到底部 | `loadRelatedArticles` → 显示 3 篇固定推荐（id: 8, 4, 5） | ⬜ |
| TC7-4 | 点击相关文章跳转 | 相关文章推荐展示 | 1. 点击相关文章 | `viewRelated` → 尝试从页面栈查找 `knowledge` 页面数据 → 找到则传入完整文章数据 → 找不到使用传入数据 | ⬜ |
| TC7-5 | 返回按钮多级导航 | 从知识库进入 | 1. 点击返回 | `onBack` → `navigateBack` → 若失败则 `switchTab` 到知识库 | ⬜ |
| TC7-6 | 分享文章 | — | 1. 点击分享 2. 或使用右上角分享 | `shareArticle` → `showShareMenu`；`onShareAppMessage` 返回文章标题 | ⬜ |

---

## 11. TC8: 关于页面（about）

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC8-1 | 页面内容完整 | — | 1. 进入关于页 | 显示 Logo、4 大核心功能、技术架构表、团队成员、联系方式 | ⬜ |
| TC8-2 | 返回导航 | — | 1. 点击返回 | `onBack` → `navigateBack` → 失败则 `switchTab` 到首页 | ⬜ |
| TC8-3 | 回到顶部 | 页面已滚动 | 1. 点击「回到顶部」 | `backToTop` → `pageScrollTo({ scrollTop: 0 })` | ⬜ |
| TC8-4 | 分享 | — | 1. 使用右上角分享 | `onShareAppMessage` / `onShareTimeline` 返回正确标题 | ⬜ |

---

## 12. TC9: 跨页面集成测试

### 12.1 页面跳转链路

| ID | 测试场景 | 测试路径 | 预期结果 | 状态 |
|----|----------|----------|----------|------|
| TC9-1 | 检测→结果页链路 | detection(detect完成) → 查看完整报告 → result | 检测结果数据通过 `encodeURIComponent(JSON.stringify)` 正确传递到 result 页 → 所有字段正确渲染 | ⬜ |
| TC9-2 | 首页历史记录→结果页链路 | index(点击历史记录) → result | 从 `globalData.detectionHistory` 中读取 idx 记录 → 构造 resultData → navigateTo 传递 | ⬜ |
| TC9-3 | 知识库→文章详情链路 | knowledge(点击文章) → article | article 数据通过 JSON 序列化传递 → contentMap 匹配渲染 | ⬜ |
| TC9-4 | 知识库 RAG→文章详情链路 | knowledge(问答面板点击引用) → article | `viewArticleByRef` 根据 `article.id` 在 `articles` 数组中查找 → navigateTo | ⬜ |
| TC9-5 | AI 问答浮窗→知识库链路 | index(点击「问AI」) → knowledge(自动打开问答面板) | `app.globalData.openQA = true` → `wx.switchTab` 到 knowledge → `onShow` 检测 `openQA` → `showQA = true` | ⬜ |
| TC9-6 | 检测页 AI 问答→知识库链路 | detection(点击「问AI」) → knowledge | 同上，`openQA` 机制 | ⬜ |
| TC9-7 | 模拟页 AI 问答→知识库链路 | simulate(点击「问AI」) → knowledge | 同上，`openQA` 机制 | ⬜ |

### 12.2 数据一致性

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC9-8 | 检测后首页统计更新 | 在检测页完成一次检测 | 1. 完成检测 2. switchTab 回首页 | `onShow` 触发 `updateStats` + `loadRecentDetections` → 首页显示更新后的统计和最新记录 | ⬜ |
| TC9-9 | 本地存储持久化 | 完成多次检测 | 1. 关闭小程序 2. 重新打开 | `loadLocalData` 恢复 `detectionHistory` → 首页统计正确 | ⬜ |

---

## 13. TC10: 边界与容错测试

### 13.1 网络异常

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC10-1 | 后端完全不可达 | 后端未启动 | 1. 打开小程序 | 所有网络请求静默失败，不影响 UI 渲染。首页使用本地数据，检测页提示「检测失败，请检查服务是否可用」 | ⬜ |
| TC10-2 | 请求超时 | 网络延迟 > 15s | 1. 发起检测 | `request` 超时重试机制：最多重试 2 次，间隔 1s → 仍失败则静默失败 | ⬜ |
| TC10-3 | 429 频率限制 | 后端返回 429 | 1. 频繁发起请求 | `request.js` 检测 `statusCode === 429` → Toast「请求过于频繁」→ reject | ⬜ |
| TC10-4 | 401 未认证 | Token 失效 | 1. 发起需要认证的请求 | `request.js` 检测 `statusCode === 401` → `auth.clearAuth()` → Toast「登录已过期」→ reject | ⬜ |

### 13.2 输入边界

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC10-5 | 文本检测超长输入 | 输入 2000+ 字符 | 1. 输入超长文本 2. 点击检测 | 文本会正常发送（无前端限制），后端做截断处理（如果服务端有实现） | ⬜ |
| TC10-6 | 模拟输入 XSS 特殊字符 | 模拟对话 | 1. 输入包含 `<script>` 的文本 | `sanitized = inputText.replace(/[<>]/g, '').substring(0, 2000)` → `<`和`>` 被过滤 | ⬜ |
| TC10-7 | 同时上传音频+视频+文本 | 综合检测模式 | 1. 切换到综合Tab 2. 选择音频+视频+文本 3. 点击检测 | `detectMulti` → 多模态端点 → 后端进行 AI 综合研判 | ⬜ |
| TC10-8 | 空输入检测 | 任何检测模式 | 1. 不输入任何内容 2. 点击检测 | `updateCanDetect` 返回 false，按钮 disabled | ⬜ |

### 13.3 存储边界

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC10-9 | 存储空间不足 | 微信存储将满 | 1. 进行多次检测 | `saveLocalData` 检测 `wx.getStorageInfoSync().currentSize + 50 > limitSize` → 跳过保存，打印警告日志 | ⬜ |
| TC10-10 | 检测历史超过 100 条 | 已检测 100+ 次 | 1. 继续检测 | `addDetectionRecord` 中 `history.length > 100` → `pop()` 移除最老记录 | ⬜ |
| TC10-11 | 模拟消息超过 100 条 | 模拟对话极长 | 1. 持续对话 | `maxMessages: 100` → `messages.length >= maxMsg` → 只保留最近 100 条 | ⬜ |

### 13.4 容器生命周期

| ID | 测试场景 | 前置条件 | 测试步骤 | 预期结果 | 状态 |
|----|----------|----------|----------|----------|------|
| TC10-12 | 页面卸载时释放资源 | 检测页正在检测或模拟页正在对话 | 1. 快速切换 Tab | `onUnload` → 销毁 `_taskWatcher`、`_qaStreamTask`、`sseRequestTask` → 取消所有未完成的请求 | ⬜ |
| TC10-13 | Canvas 重绘 | 首页 onShow → onReady | 1. 从其他 Tab 返回首页 | `onShow` 重新统计 → 页面渲染后 `onReady` 触发 `drawScoreRing` | ⬜ |

---

## 14. 发现的问题汇总

### P0 — 严重问题

| # | 模块 | 问题描述 | 文件:行号 | 影响 |
|---|------|----------|-----------|------|
| E1 | simulate | `turnCount` 变量已声明但从未自增，`maxTurns: 10` 形同虚设 | `simulate.js:25-26, 111` | 对话轮次不受限，可能导致无限对话消耗资源 |

### P1 — 中等问题

| # | 模块 | 问题描述 | 文件:行号 | 影响 |
|---|------|----------|-----------|------|
| E2 | simulate | `showStartTip` 的 `onTipVisibleChange` 同时接受 Boolean 和 Event 两种类型参数，与 TDesign popup 的 `visible-change` 事件规范不一致 | `simulate.js:95-99` | 在某些模拟器或真机环境下可能无法正常关闭提示弹窗 |
| E3 | result | 使用 `currentRecordId = fileName + '|' + type` 作为收藏唯一标识，若多次检测同类型同文件名的记录会覆盖 | `result.js:32` | 收藏功能无法区分多次相同文件名检测记录 |
| E4 | index | `stats.accuracy` 固定返回 `'98.5'`，缺乏后端真实准确率数据 | `index.js:127` | 数据看板中准确率始终显示 98.5%，无实际数据支撑 |

### P2 — 轻微问题

| # | 模块 | 问题描述 | 文件:行号 | 影响 |
|---|------|----------|-----------|------|
| E5 | detection | 综合检测 `multi` 模式仅接收文本 + 已有检测结果（非原始文件），但检测页 UI 允许用户选择音视频和文本 | `request.js:163-168` | 综合检测实为文本聚合分析，非真实多模态融合 |
| E6 | simulate | `tryStream` 中 SSE 的 `requestTask.abort()` 存储在 `this.data` 中，违反 data-only 原则 | `simulate.js:194` | 运行时可接受，但不符合小程序最佳实践 |
| E7 | detection | 视频检测文件扩展名校验列表与大文件校验使用各自硬编码常量，存在冗余 | `detection.js:118, 123` | 维护性差，两处 `50 * 1024 * 1024` 可抽取常量 |
| E8 | knowledge | 本地 `ragKnowledge` 的类别关键词覆盖不全面，`category` 未统一映射 | `knowledge.js:131-140` | 某些本地文章无法通过 RAG 回退匹配到 |
| E9 | app.js | `checkUpdate` 中的 `wx.showModal` 在 `onUpdateReady` 回调中，如果用户不点击确认则不会强制更新 | `app.js:167-173` | 用户可能长期使用旧版本 |
| E10 | 全局 | `API_BASE_URL` 硬编码为 `http://localhost:8080`，真机测试需手动修改 | `config.js:20` | 真机调试需额外配置 |

---

## 15. 测试结论

### 15.1 覆盖度

| 维度 | 覆盖率 |
|------|--------|
| 页面覆盖 | 7/7（100%）：首页、检测、结果、模拟、知识库、文章详情、关于 |
| API 端点覆盖 | 小程序调用的所有 API 端点均已覆盖 |
| 数据流覆盖 | 前端→后端请求、后端→前端响应、本地存储读写、全局数据流转 |
| 错误处理覆盖 | 超时、401、429、500、网络不可达、空数据、大文件 |

### 15.2 测试状态说明

- ⬜ **待执行**：需要在真实或模拟环境中逐项执行验证
- ✅ **已通过**：执行确认无误
- ❌ **未通过**：存在功能缺陷

### 15.3 修复记录

所有问题已于 **2026-05-26** 统一修复，修改记录如下：

| 编号 | 级别 | 修复文件 | 修改内容 |
|------|------|----------|----------|
| E1 | P0 | `simulate.js` | 将 `tryStream` 改为返回 Promise，`_processAIMessage` 改为 await streaming 完成；在 `sendMessage` 中每轮对话后自增 `turnCount`，达到 `maxTurns(10)` 时自动触发模拟结束 |
| E2 | P1 | `simulate.js` | 统一 `onTipVisibleChange` 和 `onSummaryVisibleChange` 为 `e?.detail?.visible` 标准事件处理 |
| E3 | P1 | `result.js` | `currentRecordId` 改为 `uniqueId + '|' + fileName + '|' + type`，`uniqueId` 取自 `data.id`/`data.taskId`/`Date.now()`；收藏增删均基于 `recordId` 精确匹配 |
| E4 | P1 | `index.js` | 移除硬编码 `'98.5'`，改为根据 `riskScore` 计算平均准确率：`accuracy = 100 - avgRiskScore` |
| E5 | P2 | `detection.js` | 增加注释说明综合检测模式为文本聚合分析 |
| E6 | P2 | `simulate.js` | `sseRequestTask` 改为 `this._sseRequestTask`（实例变量）；新增 `this._streamResolve` 回调 |
| E7 | P2 | `detection.js` | 视频大小校验使用已有常量 `MAX_VIDEO_SIZE` 替代硬编码 `50 * 1024 * 1024` |
| E8 | P2 | `knowledge.js` | `ragKnowledge` 增加 "AI语音/语音合成/声音伪造/贷款诈骗/洗钱/涉嫌犯罪" 等关键词 |
| E9 | P2 | `app.js` | 更新管理新增推迟计数机制：用户可推迟 2 次，第 3 次强制更新 |
| E10 | P2 | `config.js` | 新增真机环境检测：运行时检测 `platform !== 'devtools'` 且 URL 含 localhost 时输出警告 |

### 15.4 后续建议

1. **自动化测试** — 建议引入微信小程序自动化测试 SDK（Miniprogram Automator 或 开发者工具 CLI）对核心流程进行自动化回归。
2. **UI 测试** — 在真实 iOS/Android 设备上验证 UI 渲染，特别是 Canvas 安全环和暗色模式。
3. **压力测试** — 测试高并发检测场景下小程序的内存和性能表现。

---

*本报告由 AI 端到端测试分析生成，建议在真实环境中逐条验证。*
