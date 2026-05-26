# Mock 数据退役记：从全量 Mock 到全量真实 API 的前端重构

## 前言

在上一篇博客《前后端联调实战》中，我记录了前端与后端 17 个 API 首次打通的过程。但那次联调有一个前提——**Mock 数据仍然作为回退方案保留着**。当时的心态是："先通了再说，Mock 留着也不碍事。"

事实证明，这句"不碍事"是给自己挖坑。

本文记录 SafeGuard 项目后续的一次"大扫除"：将前端所有 Mock 数据连根拔起，涉及 4 个页面、3 个工具模块，删除了 500+ 行 Mock 代码。这件事看似简单——"不就是删代码吗？"——但实际涉及认证体系搭建、历史数据持久化、错误处理重构、以及一个核心思考：**前端到底应该在什么时候信任后端？**

## 一、Mock 数据：天使与魔鬼

### 1.1 为什么我们需要 Mock

在项目早期，Mock 数据是前端开发的氧气。后端还在搭框架、调模型的时候，前端靠 Mock 数据完成了：

- **detection.js** — `generateMockResult()` 生成各类检测结果，完成了结果展示页面的全部 UI 开发
- **simulate.js** — 500+ 行本地硬编码诈骗话术，完成了模拟诈骗对话的完整交互流程
- **result.js** — `loadMockResult()` 随机生成结果数据，完成了结果报告的展示逻辑

没有 Mock，前端开发周期至少要延长 2-3 倍。这是事实。

### 1.2 但 Mock 有"毒性"

Mock 数据最大的问题不是"假"，而是**它会掩盖真实链路上的问题**。以下是我在项目中发现的三类"Mock 中毒"症状：

| 症状 | 表现 | 根因 |
|------|------|------|
| 假阳性安全感 | 页面跑得挺好，一接真实数据就崩 | Mock 数据太规整，没有 null、undefined、边界值 |
| 双倍维护成本 | 后端改接口字段，前端要改两处 | Mock 数据结构需要和真实 API 保持同步 |
| 降级依赖症 | 真实 API 失败就退回 Mock，永远不会发现真实 Bug | 开发模式养成"Mock 保底"的惯性思维 |

第三个症状最致命——当 `callDetectionAPI()` 失败时自动调用 `generateMockResult()`，开发者永远不会意识到接口有问题。

## 二、动手：500+ 行 Mock 数据的"连根拔起"

### 2.1 detection.js：移除了什么

```javascript
// 移除前（约 80 行 mock 代码）
function generateMockResult(type) {
  // 生成随机置信度、概率、分析报告...
  // 根据 type 生成不同特征的检测结果...
  // 封装好的完整 mock 数据，看起来很像真的
}

// 调用链路中隐含的 mock 回退
async callDetectionAPI(type, file, text) {
  try {
    const result = await realAPI(type, file, text);
    return result;
  } catch (err) {
    // ⚠️ 失败时静默退回 mock，开发者永远不知道 API 挂了
    return generateMockResult(type);
  }
}
```

移除后的代码简洁到令人舒适：

```javascript
// 移除后：只有真实 API 调用，没有回退
async callDetectionAPI(type, file, text) {
  const result = await realAPI(type, file, text);
  return result;  // 失败直接抛异常，由上层统一处理
}
```

### 2.2 simulate.js：500 行本地话术的"断舍离"

这是最痛的一刀。`simulate.js` 中硬编码了 20+ 种诈骗场景的对话话术，每个场景包含开场白、追问逻辑、收网话术，总计超过 500 行本地 JSON 数据。

这些数据是我逐条从反诈案例中提取的，每一行都有感情。但问题是：

1. **无法维护** — 每次新增诈骗类型都要改代码、发版
2. **与后端知识库脱节** — 后端 RAG 系统有完整的反诈知识库，前端却在用自己的"私藏"
3. **对话质量封顶** — 本地话术是静态的，没有真正的 AI 动态生成能力

移除后，模拟对话完全交给后端 LLM（DeepSeek）驱动。前端只负责展示流式输出和打字机效果——这正是前端该做的事：**展示，不捏造**。

### 2.3 result.js：不再"无中生有"

移除 `loadMockResult()` 回退——当检测结果为 null 时，直接提示错误，不再随机生成"看起来像真的"的结果。

```javascript
// 移除前
if (!data) {
  data = loadMockResult();  // 无中生有
}

// 移除后
if (!data) {
  app.showError('检测数据为空');  // 诚实面对失败
  return;
}
```

这个改动看似微小，但它代表了一个原则性的转变：**前端不编造数据，哪怕是 UI 展示需要**。

## 三、拆除 Mock 的前提：基础设施必须到位

直接删除 Mock 数据而不做任何铺垫，必然导致页面白屏。在动手之前，必须先建成三根支柱：

### 3.1 用户认证体系（让后端认识前端）

Mock 模式下，用户是"匿名用户"。真实模式下，后端需要知道"你是谁"才能关联检测记录。

认证链路：
```
微信小程序 → wx.login() → 获取临时 code
  → POST /api/auth/login → 后端用 code 换取 openid
  → 返回 JWT token → 前端存储到 Storage
  → 后续所有请求自动携带 Bearer Token
```

关键代码（[auth.js](file:///d:/Git/SafeGuard/wechat-app/frontend/utils/auth.js)）：

```javascript
async function login() {
  const loginRes = await wx.login();
  const userProfile = await getUserProfile();
  const data = await request({
    url: '/api/auth/login',
    method: 'POST',
    data: {
      code: loginRes.code,
      nickname: userProfile?.nickName || '用户',
    },
  });
  if (data && data.token) {
    saveAuth(data.token, userInfo);
  }
}
```

**核心思考：前端代码中为什么保留了用户头像和昵称的获取？**

这里有一个设计取舍。微信小程序提供了 `wx.getUserProfile` 来获取用户信息，但 iOS 17.4+ 中这个接口已经废弃。我们的方案是"尽力获取"——如果能拿到就用微信昵称，拿不到就用默认值。认证的核心是 `code`，不是昵称。这个"尽力而为"的模式在后续的 Docker 部署中也得到了验证：没有微信环境的部署也能正常使用匿名模式。

### 3.2 请求封装升级（[request.js](file:///d:/Git/SafeGuard/wechat-app/frontend/utils/request.js)）

移除 Mock 之前，必须确保请求层足够健壮：

```javascript
// 自动携带 Token
const authHeader = auth.getAuthHeader();

// 统一处理 401（Token 过期 → 重新登录）
if (res.statusCode === 401) {
  auth.clearAuth();
  // 提示用户重新登录
}

// 统一处理 429（频率限制 → 友好提示）
if (res.statusCode === 429) {
  wx.showToast({ title: '请求过于频繁，请稍后再试' });
}
```

**429 的处理有一个有意思的细节**：后端使用 bucket4j 实现了 IP+路径级别的限流（LLM 接口每分钟 10 次，普通接口 60 次），前端没有做复杂的重试策略，而是选择**直接提示用户**。为什么？

因为在反诈检测场景中，用户的每一次操作都带有明确意图（上传音频、提交文本），"静默重试"反而会让用户困惑——"我点了一下，怎么转了三圈还没结果？"直接告知"请求太频繁"并让用户自主决定是否重试，交互上更透明。

### 3.3 检测历史持久化（不让数据丢失）

Mock 数据被删除后，检测结果不再保存在本地，而是实时写入后端数据库：

```javascript
async saveDetectionResult(result) {
  const recordData = {
    taskId: Date.now() + '',
    userId: userInfo.userId,
    detectionType: this.data.currentType,
    result: fakeProb > 0.7 ? 'dangerous' : fakeProb > 0.4 ? 'suspicious' : 'safe',
    riskScore: Math.round(fakeProb * 100),
  };
  await recordAPI.save(recordData);
}
```

首页的历史记录也从 `app.globalData`（后端数据）加载，而非本地缓存。这意味着用户在任何设备上登录，都能看到完整的检测历史。

## 四、重构过程中的一个核心决策：用户 ID 从哪里来？

这是整个重构过程中最具思考价值的问题。

在 Mock 模式下，用户 ID 是前端随机生成的 UUID。切换到真实后端后，用户 ID 应该由后端统一管理。

但问题来了：**前端调用 `/api/records/save` 时需要传 `userId`，这个 ID 是什么时候知道的？**

方案有两种：

| 方案 | 流程 | 优点 | 缺点 |
|------|------|------|------|
| **A: 登录即知** | `wx.login()` → 后端返回 `userId` → 前端存储 → 后续所有请求携带 | 简单直接 | 需要前端管理 userId 状态 |
| **B: 每次请求由 Token 解析** | 前端只传 `token`，后端从 JWT 中解析 `userId` | 后端完全控制用户身份 | `/api/records/save` 接口需要额外解析逻辑 |

我们最终选择了**方案 A**，因为 `records/save` 接口需要明确知道"为谁保存"。但做了一个妥协：`userId` 从前端传入，但**后端会校验 JWT 中的 userId 与传入的 userId 是否一致**，防止篡改：

```java
// 后端逻辑：双重校验
Long tokenUserId = jwtUtil.parseUserId(token);
if (!tokenUserId.equals(request.getUserId())) {
  throw new AuthException("用户身份不匹配");
}
```

这个设计的思考点在于：**前端传 userId 不是信任前端，而是给后端提供一层额外的上下文校验**。JWT 保证"你是谁"，传入的 userId 告诉后端"你要为谁操作"，两者一致才放行。

## 五、重构前后对比

| 维度 | 重构前 | 重构后 |
|------|--------|--------|
| Mock 代码量 | 500+ 行 | 0 行 |
| 检测数据来源 | Mock 生成 / API 调用（双路径） | 仅 API 调用（单路径） |
| 用户身份 | 匿名 | 微信登录 + JWT 认证 |
| 检测历史 | 本地缓存（`wx.setStorage`） | 后端数据库（跨设备同步） |
| 错误处理 | 失败→Mock 回退（掩盖问题） | 失败→用户提示（暴露问题） |
| 网络请求 | 无认证头 | 自动携带 Bearer Token |
| 限流处理 | 无 | 429 统一拦截 + 友好提示 |
| 模拟对话 | 本地 500+ 行硬编码话术 | 后端 LLM 动态生成 |

## 六、不止前端：后端的"去临时方案"运动（Caffeine 重构）

有意思的是，就在我前端拆除 Mock 数据的同一周，后端也在做一件本质上完全相同的事情——用 Caffeine 替换手写的 `ConcurrentHashMap` 缓存。

这不是巧合。Mock 数据和 `ConcurrentHashMap` 缓存是同一类东西：**临时方案**。它们在项目早期帮助快速推进，但随着系统成熟，必须被更专业的替代品替换。

### 6.1 四个重构场景

后端一共做了 4 处替换：

| 场景 | 替换前 | 替换后 | 收益 |
|------|--------|--------|------|
| 限流桶存储 | `ConcurrentHashMap<String, Bucket>` | Caffeine 1h TTL | 自动驱逐过期桶，无需手动清理 |
| 短期记忆存储 | `ConcurrentHashMap` + 定时线程池 | Caffeine 30min TTL | 消除轮询代码，过期逻辑内聚 |
| 嵌入缓存 | 每次调用 SiliconFlow API | Caffeine 10min TTL | 减少 API 调用次数，节省费用 |
| 统一管理 | 各处分散的 Map 定义 | `CacheConfig.java` 集中配置 4 个 Bean | 一处修改，全局生效 |

其中 **EmbeddingService 的缓存重构**对前端的影响最直接——BGE 大模型嵌入 API 每次调用约 300ms，加上 Caffeine 10 分钟 TTL 后，相同文本的重复查询直接从缓存返回，前端知识库搜索的响应时间从"每次都等"变成了"首次略慢，之后秒回"。

### 6.2 为什么是 Caffeine 而不是其他

后端选择 Caffeine 而不是 Guava Cache 或 Redis，有三个原因：

1. **线程安全开箱即用** — `ConcurrentHashMap` 虽然线程安全，但"过期 + 驱逐"需要手写；Caffeine 的 `expireAfterWrite` + `maximumSize` 一行代码搞定
2. **性能对标 ConcurrentHashMap** — Caffeine 的吞吐量在大部分场景下接近甚至超过 `ConcurrentHashMap`，而 Guava Cache 在高并发下会退化
3. **本地缓存就够了** — 限流桶和短期记忆都是进程级数据，不需要 Redis 这种分布式方案

```java
// 替换前：ConcurrentHashMap + 手写定时清理
private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor();
cleaner.scheduleAtFixedRate(() -> {
    buckets.entrySet().removeIf(entry -> isExpired(entry.getValue()));
}, 1, 1, TimeUnit.MINUTES);

// 替换后：Caffeine 一行搞定
@Bean
public Cache<String, Bucket> rateLimitBucketCache() {
    return Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.HOURS)
        .maximumSize(10_000)
        .build();
}
```

### 6.3 前端 Mock + 后端 Caffeine：同一个故事的两个侧面

| | 前端 Mock 数据 | 后端 ConcurrentHashMap 缓存 |
|------|------|------|
| **定位** | 临时数据源 | 临时存储方案 |
| **问题** | 掩盖真实 API 问题 | 需手写过期/清理逻辑 |
| **替代方案** | 真实后端 API | Caffeine |
| **迁移成本** | 需要认证/持久化等基础设施就绪 | 需要引入依赖 + 配置 Bean |
| **共同目标** | 让系统变得更"真实" | 让系统变得更"专业" |

前端用真实的 API 替换了 Mock 数据，后端用专业的缓存库替换了手写 Map。两个改动没有代码层面的依赖，但指向同一个方向：**项目从"能跑就行"走向"专业可靠"**。

## 七、总结与思考

这次重构最大的收获不是代码量减少了多少，而是**前端角色的重新定位**。

在 Mock 时代，前端承担了"数据生产者"的角色——捏造检测结果、编写对话话术、模拟风险评分。这在原型阶段是高效的，但到了产品化阶段，这种模式变成了毒药：**前端越全能，系统越脆弱**。

移除 Mock 数据不是技术动作，而是架构决策。它意味着前端开始信任后端，也意味着前端开始对用户诚实——不展示"看起来像真的"的数据，只展示真实检测的结果。

有几个标志性的时刻可以说明这种转变：

1. **app.js 启动时自动登录** — 前端不再自说自话，而是主动与后端建立身份关联
2. **首页历史列表从后端加载** — "你的检测记录"不再是你本机的数据，而是你在系统中的完整记录
3. **检测失败直接提示错误** — 不粉饰太平，让问题浮出水面才能被解决

最后一个有趣的思考：**Mock 数据的"毒性"不在 Mock 本身，而在"Mock 回退"的机制。** 如果你在开发时用 Mock，测试时发现 API 通了就切掉 Mock，那没问题。真正危险的是"API 不通时自动降级到 Mock"——这会让你的应用在无声无息中从真实系统退化为玩具。

下一篇文章会介绍 Docker 部署过程中前端面临的挑战——当后端不再是 `localhost:8080`，而变成了一组容器名和端口映射的时候，前端配置应该如何应对。
