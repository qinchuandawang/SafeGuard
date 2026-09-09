# SafeGuard 项目汇报文档

## 1. 项目定位

SafeGuard 是一个面向反诈场景的多模态 AI 检测系统。项目重点不是单独展示某一个模型，而是构建一个以 `DeepSeek-V4-Pro` 为中枢大脑的 Agent 应用：大模型负责理解用户输入、规划检测流程、调用专用工具、融合证据并生成普通用户可读的中文结论。

在系统中，DeepSeek 本身承担文本检测、AI 智能问答、模拟诈骗对话和多模态综合研判；音频检测与视频检测则由 DeepSeek 调度专用模型完成。音频侧使用 Wav2Vec2 输出音频伪造概率，视频侧使用 XceptionNet 输出关键帧换脸/面部篡改概率，DeepSeek 再把模型结果、RAG 知识、元数据和业务规则整合成最终报告。

项目的核心设计目标是：让“大模型 + 专用模型 + 前端体验”形成一条可演示、可解释、可扩展的反诈检测链路。

## 2. 项目架构

项目采用“小程序前端 + Spring Boot 后端 + DeepSeek 中枢 + Python 专用模型服务 + RAG 知识库 + MySQL 数据闭环”的架构。

整体链路如下：

1. 微信小程序负责接收用户输入，包括文本、文档、音频、视频和模拟诈骗对话。
2. Spring Boot 后端统一承接请求，判断任务类型，并构造 Agent 请求或媒体检测任务。
3. DeepSeek-V4-Pro 作为中枢大脑，承担文本语义分析、RAG 问答、模拟诈骗、多模态研判和媒体报告生成。
4. 音频任务由后端调用 Python 音频服务，使用 Wav2Vec2 输出伪造概率、置信度、采样率、时长、推理设备等信息。
5. 视频任务由后端抽取关键帧，再调用 Python 视频服务，使用 XceptionNet 输出逐帧伪造概率，并结合 AIGC 元数据做综合判断。
6. RAG 模块将反诈知识文档切块、向量化并写入 Qdrant，查询时返回相关知识片段，注入到 DeepSeek Prompt 中。
7. MySQL 保存用户、检测记录、模型信息和知识库信息，支撑检测历史与管理后台。

核心代码位置：

- 后端检测入口：`backend/src/main/java/com/sdu/safeguard/controller/DetectionController.java`
- 大模型调用与报告生成：`backend/src/main/java/com/sdu/safeguard/service/LLMService.java`
- Agent 编排：`backend/src/main/java/com/sdu/safeguard/agent/AgentOrchestrator.java`
- RAG 检索：`backend/src/main/java/com/sdu/safeguard/rag/RAGService.java`
- 音频服务：`ai-services/audio/src/app.py`、`ai-services/audio/src/utils.py`
- 视频服务：`ai-services/video/api/app.py`、`ai-services/video/models/xception.py`
- 小程序检测页：`wechat-app/frontend/pages/detection/detection.js`
- 小程序流式问答：`wechat-app/frontend/pages/knowledge/knowledge.js`
- 前端 SSE 封装：`wechat-app/frontend/utils/request.js`
- 异步任务监控：`wechat-app/frontend/utils/taskWatcher.js`

## 3. DeepSeek-V4-Pro 中枢设计

系统不是让音频、视频模型直接给用户最终结论，而是让 DeepSeek-V4-Pro 作为统一中枢进行调度和解释。

DeepSeek 在项目中的职责包括：

- 文本检测：直接理解诈骗文本或上传文档内容，输出风险等级、诈骗类型、可疑点、推理依据和建议。
- AI 智能助手：结合 RAG 检索结果回答反诈问题，并通过 SSE 流式返回自然中文。
- 音频检测报告：接收 Wav2Vec2 输出的 label、伪造概率、真实概率、置信度、时长、采样率、设备等字段，生成贴合文件本身的中文报告。
- 视频检测报告：接收 XceptionNet 的逐帧概率、平均概率、最高单帧概率、可疑帧占比和 AIGC 元数据，区分“视觉换脸风险”和“综合风险”。
- 模拟诈骗：根据诈骗场景、人设、历史对话和反诈知识生成真实感对话，并输出快捷回复、警惕度变化和防范建议。
- 多模态研判：融合文本、音频、视频结果，形成综合风险解释。

这种设计的好处是，专用模型负责“识别证据”，大模型负责“理解任务、组织证据、解释结论”。最终用户看到的不是生硬概率，而是“为什么可疑、哪里可疑、下一步怎么做”。

## 4. RAG 流程设计

RAG 模块用于让 DeepSeek 的回答和检测报告更贴近反诈知识，而不是只依赖模型本身的通用知识。

系统启动时，`RAGService.loadKnowledgeDocuments` 会读取 `knowledge/anti_fraud_knowledge.txt`，按章节拆分，再通过 `HybridChunker` 进行混合切块。每个知识片段会生成 embedding，并写入 Qdrant；如果 Qdrant 不可用，`QdrantService` 支持内存回退，保证演示环境仍可运行。

查询时，`RAGService.query` 的流程包括：

1. 规则过滤：`RuleFilter` 先匹配刷单、公检法、安全账户、验证码、转账等高危关键词。
2. 查询改写：`QueryRewriter` 对用户短问题进行扩展，提升召回效果。
3. 查询切块：`HybridChunker.chunkQuery` 将查询拆成适合向量检索的片段。
4. 向量检索：通过 `EmbeddingService` 生成向量，再到 Qdrant 检索候选知识。
5. 关键词打分：结合反诈关键词计算 keyword score。
6. Rerank：使用 SiliconFlow rerank 模型对候选知识重新排序。
7. 多因子融合：综合 rerank、vector score、keyword score 得到最终分数。
8. Embedding 去重：避免重复知识片段挤占 Top-K。
9. Prompt 注入：`formatRagContext` 将最终知识片段格式化为“参考反诈知识库”，交给 DeepSeek 使用。

AI 助手、文本检测、模拟诈骗和多模态分析都会不同程度使用 RAG。这样现场演示时，用户问“刷单兼职是不是诈骗”“96110 是否可信”“AI 换脸诈骗怎么识别”等问题，系统能结合本地知识库给出更稳定的回答。

## 5. Agent 编排流程设计

Agent 编排的核心代码是 `AgentOrchestrator`。它把一次检测拆成三个阶段：

第一阶段是依赖解析。系统根据请求配置并行执行 RAG、CoT 和 ReAct。RAG 负责提供知识上下文，CoT 负责生成分步风险分析，ReAct 负责形成“思考、行动、观察”的工具调用过程。代码中通过 `CompletableFuture` 并行执行这些依赖，减少等待时间。

第二阶段是 Agent 并行执行。`OrchestratorRequest.requiredAgents` 会指定本次需要哪些 Agent，例如文本检测会使用 `TEXT_ANALYSIS` 和 `KNOWLEDGE`。每个 Agent 接收相同的上下文，包括 RAG 结果、CoT 结果、ReAct 过程和 sessionId。

第三阶段是结果聚合。`mergeResults` 会把参考知识、CoT 风险评估、各 Agent 输出和 ReAct 推理过程合并成综合检测报告，并保留 `reasoningSummary`、`ragContext`、`agentResults` 等结构化字段，方便小程序展示“AI 分析过程”。

在媒体检测中，Agent 设计体现为“DeepSeek 中枢调度”。`DetectionController.buildMediaAgentSteps` 会把音频和视频检测包装成三步：DeepSeek 中枢调度、专用模型推理、DeepSeek 综合研判。这让用户在前端能看到当前不是后端写死，而是大模型中枢正在调度模型工具。

## 6. 文本检测设计

文本检测是 DeepSeek-V4-Pro 直接承担的核心能力。小程序支持两种输入：用户直接输入文本，或上传文档。

后端入口包括：

- `DetectionController.detectText`
- `DetectionController.detectTextDocument`

文本检测请求会构造为 `OrchestratorRequest`，开启 `useRAG`、`useCoT` 和 `useReAct`，并要求 `TEXT_ANALYSIS`、`KNOWLEDGE` 等 Agent 参与。DeepSeek 会结合用户文本、RAG 反诈知识和推理链路输出结构化结果。

为了适配小程序展示，后端不会把原始 JSON 直接丢给用户，而是通过 `buildTextAgentPayload` 整理为风险等级、风险概率、诈骗类型、可疑点、建议、中文报告、知识来源和 Agent 步骤。`LLMService.buildTextDetectionReport` 还会清理大模型可能返回的 JSON 或 Markdown 包裹，尽量保证前端展示为正常中文文本。

## 7. 音频检测设计

音频检测使用 Wav2Vec2 作为专用模型。它负责判断音频是否疑似 AI 合成或伪造，但不直接面向用户输出最终解释。

音频训练部分位于 `ai-services/audio/src/train_wav2vec2.py`。训练脚本面向 ASVspoof 数据组织方式设计，先通过 `parse_protocol` 读取协议文件，将样本映射为 `bonafide` 和 `spoof` 两类；再通过 `load_audio` 完成单声道转换和 16kHz 重采样；`ASVspoofDataset` 会限制最大音频时长，保证训练和演示推理都可控。模型使用 `Wav2Vec2ForSequenceClassification`，训练时支持类别权重、梯度累积、FP16、学习率 warmup、最佳模型保存和 F1/accuracy 等指标评估。

Python 音频服务中，`utils.py` 的 `load_model_once` 负责模型单次加载和缓存，`predict_audio` 负责音频预处理和推理。输出包括：

- label：`bonafide` 或 `spoof`
- 伪造概率和真实概率
- 置信度
- 模型版本
- 推理设备
- 推理耗时
- 原始采样率、模型采样率、声道数
- 原始时长、实际分析时长、是否截断

后端 `DetectionService.detectAudioWithMultipart` 调用音频服务后，`LLMService.generateAudioReport` 会把这些真实模型输出交给 DeepSeek，让 DeepSeek 生成更贴合文件本身的中文报告。报告会解释概率含义、是否截断、模型倾向以及用户应如何核验。

前端检测页支持音频批量上传。`detection.js` 会对多个音频文件逐个上传，并通过进度卡片展示当前处理阶段，让现场演示时能看到“上传、模型推理、DeepSeek 报告生成”的完整过程。

## 8. 视频检测设计

视频检测使用 XceptionNet 作为视觉专用模型，定位是 AI 换脸/面部篡改检测，不是通用视频大模型。

视频训练部分位于 `ai-services/video/train.py`。训练脚本使用 `models/xception.py` 中的 XceptionNet 二分类模型，通过 `get_dataloaders` 加载训练集、验证集和测试集；训练过程使用 CrossEntropyLoss、Adam 优化器和 StepLR 学习率调度。为了适配本地 GPU 训练，脚本支持 CUDA 自动选择、混合精度训练、梯度累积、权重衰减、断点恢复、训练历史保存和最佳验证模型保存。最终得到的 `pretrained/best_model.pth` 会被 Python 视频服务加载，用于关键帧图片的伪造概率推理。

后端视频检测采用异步任务，避免小程序请求超时。`DetectionTaskManager` 创建任务并维护进度，`VideoFrameExtractorService` 抽取关键帧，`VideoImagePipelineService` 对关键帧做人脸裁剪或整帧降级检测，再调用 Python 视频服务的图像检测接口。

系统会记录：

- 共抽取/分析多少帧
- 每一帧的伪造概率
- 平均伪造概率
- 最高单帧伪造概率
- 可疑帧占比
- 是否检测到 AIGC 元数据
- 证据原因

`LLMService.generateVideoReport` 的设计重点是避免误导。报告必须区分视觉模型结论和综合结论：如果 XceptionNet 分数低，只能说明“未发现明显换脸伪造风险”；如果元数据命中 AIGC 标记，综合风险会提高，但不能说是视觉模型判高风险；如果局部帧超过 50%，即使整体平均不高，也要说明“存在局部可疑帧”。

前端通过 `TaskWatcher` 监听视频异步任务，优先使用 SSE，必要时回退轮询。检测页顶部 Agent 卡片和处理进度面板会同步展示抽帧、逐帧推理、结果聚合和报告生成进度。

## 9. AI 智能助手与流式输出适配

AI 智能助手位于小程序知识库页面。它不是简单问答，而是“RAG 检索 + DeepSeek 流式生成 + 前端节流渲染”的组合。

前端 `knowledge.js` 的流程是：

1. 用户发送问题。
2. 前端先调用 `ragAPI.query` 获取相关知识。
3. 取 Top 结果拼成上下文 Prompt。
4. 调用 `/api/llm/analyze/stream` 发起 SSE 流式请求。
5. `requestStream` 使用 `enableChunked` 接收后端分块。
6. 前端按 120ms 左右节流刷新，避免逐 token 更新造成卡顿。
7. 如果流式请求失败，则回退到普通非流式接口或本地兜底答案。

后端 `LLMService.streamCallLLM` 会向 DeepSeek 请求 `stream=true`，逐行读取 `data:` 事件，解析 delta content 后通过 `SseEmitter` 转发给小程序。这样用户能更快看到正常中文输出，而不是先出现一段 JSON 或中间格式。

## 10. 模拟诈骗设计

模拟诈骗功能由 DeepSeek 负责生成真实感对话。它不是固定脚本，而是根据诈骗场景、历史对话、用户回复和反诈知识实时生成下一轮内容。

后端 `LLMService.scamSimulationStructured` 会调用 `scamSimulation`，再将大模型输出解析为结构化响应，包括：

- 对话内容
- 快捷回复
- 警惕度变化
- 是否结束
- 用户表现分析
- 防范建议

小程序端 `simulate.js` 负责把结构化结果展示成对话体验，并支持 SSE 或本地打字机式流式输出。设计思路是让用户在接近真实诈骗对话的场景中学习识别风险，而不是只阅读静态知识。

## 11. 前端适配设计

前端包含微信小程序端和 Web 管理端。汇报时应重点介绍微信小程序端，因为它承载现场演示的主要用户链路；Web 管理端用于辅助展示数据闭环、模型信息、知识库和检测记录。

微信小程序端的核心工作是把复杂的 Agent 和模型链路转成可理解的演示体验。

检测页 `detection.js` 和 `detection.wxml` 重点做了三类适配：

1. Agent 状态可视化：顶部 Agent 卡片会根据输入、分析、建议、完成动态更新，不再只是装饰。
2. 处理进度可视化：文本、音频、视频检测都有进度卡片；视频异步任务还能显示后端推送的真实进度。
3. 结果解释可视化：风险概率、风险等级、可疑点、建议、中文报告和 Agent 步骤都会拆开展示，避免用户面对原始模型 JSON。

工具层 `request.js` 统一封装普通请求、文件上传和 SSE 流式请求，并处理微信开发工具下中文响应可能乱码的问题。`TaskWatcher` 封装异步任务监听，优先 SSE、失败回退轮询，使视频检测在耗时较长时仍能稳定演示。

Web 管理端位于 `web-admin`，使用 Vue 3、Vue Router、Axios、Element Plus 和 ECharts。它不是主要演示入口，但用于展示检测记录、用户信息、模型信息、知识库数据和统计图表，说明系统不只是小程序页面，也具备数据管理和后台展示能力。

## 12. 技术栈

| 层级 | 技术 | 作用 |
|------|------|------|
| 小程序端 | 微信小程序、TDesign | 检测入口、AI 助手、模拟诈骗、进度展示、历史记录 |
| 管理后台 | Vue 3、Element Plus、Vite | 检测记录、模型信息、知识库和统计管理 |
| 后端 | Spring Boot、Java 17、MyBatis-Plus | API、Agent 编排、异步任务、数据库、报告生成 |
| 大模型 | DeepSeek-V4-Pro | 中枢大脑、文本检测、问答、模拟诈骗、综合研判 |
| RAG | Qdrant、SiliconFlow Embedding/Rerank、Caffeine Cache | 反诈知识检索、重排、缓存和 Prompt 增强 |
| 音频模型 | Wav2Vec2、PyTorch、Transformers | 音频伪造检测 |
| 视频模型 | XceptionNet、PyTorch、OpenCV、FFmpeg/JavaCV | 视频关键帧检测和换脸风险分析 |
| 数据库 | MySQL | 用户、检测记录、模型、知识库信息 |
| 本地运行 | Windows 批处理、CUDA GPU | 一键启动、模型预热、GPU 推理 |

## 13. 职责范围与分工

本项目由本人（朱乘雨）**全栈独立开发**，音频 / 视频检测模型的**训练与权重产出由合作同学完成**。

### 本人：全栈开发

负责 Spring Boot 后端、Agent 编排、DeepSeek 接入、RAG 调用、音视频服务调度、异步任务进度和检测结果统一封装，以及微信小程序检测页、AI 智能助手、模拟诈骗、异步任务监听、流式输出适配、进度卡片、检测历史和 Web 管理端页面。

后端核心代码包括 `DetectionController`、`LLMService`、`AgentOrchestrator`、`DetectionService`、`VideoImagePipelineService`、`DetectionTaskManager`；前端核心代码包括 `detection.js`、`detection.wxml`、`knowledge.js`、`simulate.js`、`request.js`、`taskWatcher.js` 和 `web-admin`。

后端的设计重点是让后端成为 Agent 调度层，而不是简单接口转发层：文本检测由 DeepSeek 和 RAG 直接分析，音频和视频先调用专用模型，再由 DeepSeek 解释结果，最终统一返回可被小程序展示的结构化结果。

前端的设计重点是把复杂的 AI 链路展示成用户能理解的操作流程：上传素材、查看进度、看到 Agent 步骤、阅读中文报告、保存历史记录。Web 管理端承担记录管理、模型状态和统计展示，不作为答辩主线，但能体现完整工程闭环。

### 合作同学：模型训练

负责 Wav2Vec2 音频伪造检测与 XceptionNet 视频换脸检测模型的训练与权重产出；本人负责模型接入、Python 推理服务封装、GPU 推理与调度集成。

音频侧关注协议解析、重采样、类别不平衡、F1/accuracy 指标和最佳模型保存；服务化阶段关注模型缓存、GPU 推理、概率输出、时长截断和设备信息，便于 DeepSeek 生成差异化报告。

视频侧关注 XceptionNet 二分类、混合精度、梯度累积、断点恢复和最佳模型保存；服务化阶段关注关键帧推理、人脸裁剪、整帧降级和进度日志。由于 XceptionNet 聚焦换脸/面部篡改，系统在报告中明确说明它不是通用视频大模型，并通过元数据证据补充综合判断。

## 14. 总结

SafeGuard 的核心价值在于把大模型 Agent 应用落到了一个完整反诈场景里。DeepSeek-V4-Pro 是中枢大脑，负责文本、问答、模拟诈骗和综合研判；Wav2Vec2 与 XceptionNet 是可调度的专用检测工具；RAG 提供反诈知识增强；Spring Boot 后端负责任务编排和数据闭环；微信小程序负责将整个链路可视化。

因此，本项目的汇报重点应放在“Agent 如何调度工具、RAG 如何增强决策、前端如何适配流式输出和异步进度、专用模型如何作为工具被大模型解释”上，而不是简单罗列功能页面。
