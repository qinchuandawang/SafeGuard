# SafeGuard 项目变更记录

> 汇总所有代码审查修复与功能改进，按时间倒序排列。

---

## [2026-06-09] Qdrant Point ID 修复 — RAG 知识库 26 条切块入库

### 问题

启动日志持续报 `WARN  QdrantService : Qdrant 批量插入失败: 400 Bad Request`，错误信息：

```
"value fixed_anti_fraud_knowledge.txt_0 is not a valid point ID, 
 valid values are either an unsigned integer or a UUID"
```

### 根因

`HybridChunker.fixedWindowChunk` 生成的 `chunkId` 形如 `"fixed_" + source + "_" + index`，当 `source = "anti_fraud_knowledge.txt"` 时 ID 包含 `.` 和 `_`。Qdrant 1.7+ 严格要求 point ID 是 **unsigned integer 或 UUID**，含特殊字符的字符串一律 400 拒绝。

更严重的是：`QdrantService.batchInsertTo` 在 catch 块只 `log.warn` 不抛错，导致 `RAGService` 误以为"26 条已存入Qdrant"（line 131 的 `log.info` 在 batchInsert 调用之后无条件执行）。

### 修复

| # | 文件 | 变更内容 |
|:-:|------|---------|
| 1 | `backend/.../rag/QdrantService.java` | 新增 `static String toQdrantId(String chunkId)`：用 `UUID.nameUUIDFromBytes()` 把任意业务 chunkId 派生为合法 UUID v3（同样输入永远得到同样输出，便于跨重启去重） |
| 2 | `backend/.../rag/QdrantService.java` | `batchInsertTo` line 182：`point.put("id", chunkIds.get(i))` → `point.put("id", toQdrantId(chunkIds.get(i)))` |
| 3 | `backend/.../rag/QdrantService.java` | `insertToQdrant` line 310：单条插入路径同样改用 `toQdrantId()` |
| 4 | `backend/.../rag/QdrantService.java` | 新增 `import java.nio.charset.StandardCharsets;` |
| 5 | （业务影响） | 原 chunkId 字符串**仍保留在 payload**（RAGService.java:175 `payload.put("chunkId", chunkIds.get(i))`），业务按 chunkId 关联 metadata 的逻辑不变 |

### 验证方法

修复后重启后端，观察启动日志：
- ✅ **应消失**：`WARN  Qdrant 批量插入失败: 400`
- ✅ **应仍出现**：`知识文档加载完成: hash=..., 26 条切块已存入Qdrant`（这次是真实入库）

配合 `SILICONFLOW_API_KEY` 环境变量配置后，可通过 `/api/rag/query?q=刷单诈骗` 验证向量检索命中。

### 注意事项

- `MemoryService` 已用 `UUID.randomUUID()`（line 76, 94），不受此 bug 影响
- 内存回退（ConcurrentHashMap）使用字符串 key，不受 Qdrant ID 限制影响
- 修复后 RAG 真实可用，但前提是 **SiliconFlow API Key 已配置**（否则 `getEmbedding()` 返回 null，进 Qdrant 的向量是垃圾）

---

## [2026-06-09] 第五次审查修复 — 安全/资源/性能深度加固

### 严重问题（5 项）

| # | 文件 | 变更内容 |
|:-:|------|---------|
| S1 | `backend/.../SafeGuardApplication.java` | **删除自动端口释放机制**：移除 `releasePortIfOccupied` / `findProcessOnPort` / `killProcessByPid` / `waitForPortReleased` 整套代码。该机制会 `taskkill /F` 任意占用 8080 的进程，会误杀用户其他服务，属严重反模式。保留一个只读 `probePort` 工具供诊断。 |
| S2 | `backend/.../AuthController.java` | `adminLogin` 改用 `findByOpenid("admin_" + username)` 精确按用户名查找；找不到时统一返回"用户名或密码错误"。**禁止"取任意 admin"**——多 admin 场景下密码可互换的逻辑漏洞已修复。 |
| S3 | `backend/.../RateLimitInterceptor.java` | **收紧白名单**：`/api/auth/` 前缀从白名单移除；登录端点 `/api/auth/admin/login` `/api/auth/admin/register` `/api/auth/login` 单独限流 **5 次/分钟/IP**，防暴力破解。 |
| S4 | `backend/.../AgentController.java` | `detectMultiModal` 把 `destFile` 提升为外层变量，**加 `finally` 块清理临时文件**——成功 / 异常 / Orchestrator 抛错任何分支都不再泄漏 `/tmp/safe_guard/` 下的文件。 |
| S5 | `backend/.../KnowledgeController.java` | `search` 入口调 `InputValidator.validateKeyword`（200 字符上限），防止 100KB keyword 直接打到 `LIKE %...%` 全表扫描引发 DoS。 |

### 中等问题（3 项）

| # | 文件 | 变更内容 |
|:-:|------|---------|
| M1 | `backend/.../DetectionRecordController.java` + `AdminController` + `AdminApiController` + `UserMapper` | 三处 `userMapper.selectList(null)` / `detectionRecordMapper.selectList(null)` 改为 `findRecentUsers(1000)` / `findRecentRecords(limit)`，**全表查询加 LIMIT 兜底**，生产环境不再 OOM/雪崩。 |
| M2 | `backend/.../dto/Result.java` | 新增 `Result.badRequest(String)` 业务码方法（code=400），与服务器错误（500）语义区分。`AuthController.adminLogin` / `AgentController.detectMultiModal` / `DetectionController.detectVideo` / `KnowledgeController.search` 全部改用。 |
| M3 | `backend/.../DetectionController.java` | `detectVideo` 在 `createTask` 抛异常时（DB 故障）由外层 try-catch 立即 `deleteQuietly(filePath)`，不再有"createTask 失败后临时文件泄漏"的窗口。 |

---

## [2026-06-09] 第四次审查修复 — Dashboard 真实化 / AI 助手 SSE / 注册与登录历史 / 死代码清理

### 严重问题（5 项）

| # | 文件 | 变更内容 |
|:-:|------|---------|
| 1 | `backend/.../config/JwtAuthFilter.java` | `PUBLIC_PATHS` 补充 `/api/auth/admin/register`，注册时不再被 JWT 拦截返回 401 |
| 2 | `backend/.../entity/User.java` + `sql/schema.sql` | User 实体新增 `email/phone/department/bio` 4 字段，schema.sql 同步加列 |
| 3 | `backend/.../controller/AuthController.java` | 新增 `PUT /api/auth/admin/profile`、`PUT /api/auth/admin/password`、`POST /api/auth/admin/avatar` 三个 Profile 依赖的端点 |
| 4 | `backend/.../controller/AdminController.java` | 移除硬编码 `admin/admin123`，按 `openid = "admin_" + username` 查库 → 兜底 `findAnyAdmin()` → BCrypt 校验 → 写 session（admin_token / admin_id / admin_name） |
| 5 | `web-admin/src/views/Knowledge.vue` | `savePriority` 只传 `{ priority }`，不再误覆盖 question/answer/category/tags/enabled |

### 其它修复

| # | 文件 | 变更内容 |
|:-:|------|---------|
| 6 | `web-admin/src/views/Profile.vue` | 头像上传改为 base64 dataURL（匹配后端 JSON 协议）+ 500KB 大小校验；`el-avatar` 绑定 `:src="profile.avatarUrl"` |
| 7 | `backend/.../service/KnowledgeService.java` | `update()` 补 `priority` + `enabled` 字段同步，前端 `toggleEnabled` 真正持久化 |
| 8 | `ai-services/audio/{src/app.py, app.py, run.py}` | 默认端口 5001 → **5000**，与 `application.yml` / `README` / `docker-compose` 一致 |
| 9 | `ai-services/video/config.yaml` | `max_video_frames: 30` → **24**，与 `api/app.py` 和 `application.yml` 一致 |
| 10 | `web-admin/src/views/Login.vue` | 清空默认账号 `admin/admin123` → `''` `''`，避免误导用户 |
| 11 | `backend/.../controller/AdminController.java` | `videoCompletedCount` 三分支合并为 `if (!"failed".equals(result))`；dashboard catch 块补默认值 0/空 Map/空 List，模板渲染不 NPE |
| 12 | `backend/.../mapper/AsyncTaskMapper.java` | 删除 `findByUserId` 死代码 |

---

## [2026-06-09] 第三次审查修复 — 安全与稳定性增强

### 前端数据集成（用户自修）

| # | 文件 | 变更内容 |
|:-:|------|---------|
| 1 | `ai-services/video/api/app.py` | `predict_video` 默认 `max_frames` 从 30 改为 **24**，与 `application.yml` 配置一致 |
| 2 | `ai-services/video/utils/data_loader.py` | 损坏图片占位张量从 `224×224` 改为 **`299×299`**，与 transform 输出对齐 |
| 3 | `backend/.../DetectionRecordMapper.java` | 新增 `countByDateAndResult` 按日期+结果分组统计 Mapper |
| 4 | `backend/.../AdminController.java` | 重写 `getTrendStats` 接口，返回 `dates/safe/suspicious` 三序列，补齐缺失日期 |
| 5 | `web-admin/src/api/records.js` | 新增 `getTrendStats()` / `getDistributionStats()` API 封装 |
| 6 | `web-admin/src/views/Dashboard.vue` | 趋势图改用 `getTrendStats` 真实数据，移除 `Math.random()` |
| 7 | `web-admin/src/views/Dashboard.vue` | 饼图改用 `getDistributionStats` 按结果统计，移除硬编码 5 类假数据 |
| 8 | `web-admin/src/api/request.js` | 拦截器识别 `responseType: stream/text` 时直接返回原始 Response |
| 9 | `web-admin/src/components/AiAssistant.vue` | 改为 SSE 流式调用后端 `/api/llm/analyze/stream`，实时输出 LLM 回复 |

### 高优先级

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **2** | `ai-services/video/train.py` | `torch.amp` 导入改为 `try-except` 回退到 `torch.cuda.amp`，兼容 PyTorch 2.0.x ~ 2.5.x |
| **4** | `ai-services/video/api/app.py` | `predict_video()` 逻辑包裹 `try-finally`，保证异常时临时目录被清理 |
| **5** | `ai-services/run.py` | `signal.signal(SIGTERM)` 加 `hasattr` 保护，Windows 正常启动 |
| **6,7** | `web-admin/src/components/Layout.vue` | 导入 `Expand, Fold, Sunny, Moon` 组件对象；两处 `adminName.charAt(0)` → `(adminName \|\| '管')[0]` |
| **10** | `ai-services/video/api/app.py` | 模型文件不存在时 `raise FileNotFoundError`，不再返回随机结果 |
| **11** | `backend/src/main/resources/application.yml` | 清除 4 个硬编码密钥（JWT Secret、微信 AppSecret、LLM Key、SiliconFlow Key），改为空默认值 |

### 中优先级

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **17** | `web-admin/src/router/index.js` | 添加 catch-all 路由 `/:pathMatch(.*)*` |
| | `web-admin/src/views/NotFound.vue` | **新建** 404 页面组件 |
| **18** | `backend/.../FaceCropService.java` | `cropLargestFace()` 加 `try-finally`，`finally` 中 `faceClassifierHolder.remove()` 防内存泄漏 |
| **19** | `ai-services/video/utils/data_loader.py` | `__getitem__` 中 `Image.open()` 加 `try-except`，损坏图片返回占位张量 |
| **20** | `ai-services/video/utils/video_processor.py` | `VideoCapture` 加 `try-finally`，`finally` 中 `cap.release()` |
| **21** | `backend/src/main/resources/application.yml` | `sql.init.mode: always` → `never` |
| **22** | `ai-services/video/models/xception.py` | `_load_pretrained_weights` 添加完整 TODO 及示例代码 |

### 低优先级

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **15** | `web-admin/src/views/Login.vue` + `api/auth.js` | 登录/注册改用 `adminLogin()` / `adminRegister()`，删除 `request` 直接调用 |
| **16** | `web-admin/src/views/Knowledge.vue` | 替换 `silentGet/silentPut` 为 `getKnowledgeList()` / `updateKnowledge()` |

---

## [2026-06-09] 第二次审查修复 — 文件缺失与配置对齐

### 文件缺失修复

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **3** | `backend/src/main/resources/prompts/` | **新建** 7 个提示词模板文件（text_analysis, multimodal_analysis, scam_simulation, cot_analysis, react_thought, react_final, react_analysis） |
| **4** | `backend/src/main/resources/knowledge/anti_fraud_knowledge.txt` | **新建** 12 种诈骗模式知识库（刷单、冒充公检法、杀猪盘、AI 换脸、AI 语音克隆等） |
| **5** | `ai-services/requirements.txt` | **新建** 根目录合并依赖文件 |

### 端口与配置修复

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **6** | `ai-services/audio/src/app.py` | 音频服务端口 5000 → **5001** |
| | `ai-services/audio/app.py` | 音频服务端口 5000 → **5001** |
| | `backend/src/main/resources/application.yml` | 音频服务 URL 同步更新为 5001 |

### API 响应格式统一

| # | 文件 | 变更内容 |
|:-:|------|---------|
| **7** | `ai-services/video/api/app.py` | 视频 API 统一为 `{code, message, data}` 格式 |
| | `backend/.../DetectionService.java` | 新增兼容新旧两种响应格式的 `code/success` 双重判断 |
| | `backend/.../VideoImagePipelineService.java` | 注释更新说明双格式兼容 |

---

## [2026-06-04] 第16~17次修改 — 容器化部署与 GPU 优化

### 主要变更

- Docker 环境对齐 + Qdrant 迁移
- FaceCropService ThreadLocal + CascadeClassifier 线程安全
- Python 依赖文件补充（audio/ + video/ requirements.txt）
- 6 处代码 Bug 修复（knowledge.js 流式 abort、result.js 嵌套字段、Records.vue default-sort 等）
- HashMap → ConcurrentHashMap 线程安全修复
- 项目无用文件删除 + 目录结构整理

### 文件变更清单

| 操作 | 文件 |
|------|------|
| 修改 | `docker-compose.yml`, `.gitignore` |
| 修改 | `ai-services/audio/Dockerfile`, `ai-services/video/Dockerfile` |
| 修改 | `FaceCropService.java`, `WebConfig.java` |
| 修改 | `wechat-app/frontend/app.json`, `knowledge.js`, `result.js` |
| 修改 | `web-admin/src/views/Records.vue` |
| 新增 | `ai-services/audio/requirements.txt`, `ai-services/video/requirements.txt` |
