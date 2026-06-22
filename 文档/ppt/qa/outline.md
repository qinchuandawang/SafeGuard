# Deck Outline

- File: D:\Git\SafeGuard\文档\ppt\SafeGuard项目汇报.pptx
- Slides: 13

## Slide 1: (No title)

Body:
- SafeGuard 项目汇报
- 基于 Agent 的多模态 AI 反诈骗检测系统

## Slide 2: (No title)

Body:
- 项目定位：以大模型 Agent 为中枢
- 不是单一模型演示，而是完整反诈应用链路
- Main Narrative
- 面向文本、音频、视频和多模态内容的风险检测。
- DeepSeek 负责理解任务、调度工具、融合证据并生成中文报告。
- Wav2Vec2 与 XceptionNet 作为专用检测工具参与 Agent 链路。
- 微信小程序、后端、Python 模型服务、数据库和管理后台形成闭环。
- Checklist
- Agent 编排
- 多模态检测
- 证据融合
- 可演示闭环
- 项目定位来自 SafeGuard 项目汇报文档与 backend/wechat-app/ai-services 代码梳理

## Slide 3: (No title)

Body:
- 系统核心能力
- 围绕真实反诈场景组织功能入口
- 检测入口
- 文本检测支持输入和文档上传；音频检测支持单文件和批量；视频检测支持异步任务、抽帧和逐帧分析。
- 交互训练
- AI 智能助手提供反诈咨询；模拟诈骗功能以对话训练方式提升用户识别能力。
- 结果闭环
- 检测历史、管理后台和数据库记录让演示从上传、分析、报告到留存形成完整链路。
- 小程序页面：detection、knowledge、simulate；后端接口：DetectionController

## Slide 4: (No title)

Body:
- Agent 工作链路
- 从用户输入到中文风险报告
- 1
- 任务理解
- 小程序提交文本、文档、音频或视频，后端识别检测类型并构造任务上下文。
- 2
- 工具调度
- 文本走 DeepSeek 与知识库；音频调用 Wav2Vec2；视频调用抽帧、XceptionNet 和元数据读取。
- 3
- 证据融合
- 后端聚合模型概率、可疑点、逐帧结果、AIGC 元数据和知识库上下文。
- 4
- 报告生成
- DeepSeek 将模型结果转成可读中文报告，并给出风险等级、原因和处置建议。
- 核心代码：LLMService、DetectionService、VideoImagePipelineService、DetectionTaskManager

## Slide 5: (No title)

Body:
- 技术栈总览
- 前端、后端、大模型、专用模型和数据层协同
- 本地演示环境支持 Windows 一键启动、CUDA GPU 推理和模型预热。

## Slide 6: (No title)

Body:
- 项目优点
- 课程作业重点体现 Agent 应用开发能力
- 不是接口堆叠
- 后端通过 AgentOrchestrator、LLMService 和媒体模型服务，把任务理解、工具调用和报告生成串成链路。
- 贴近真实反诈
- 覆盖文字话术、语音克隆、伪造视频和多模态组合，比单一文本检测更接近实际诈骗流程。
- 演示可观察
- 小程序展示进度和 Agent 状态；后端控制台输出抽帧、逐帧检测、模型调用和任务状态。
- 核心价值：专用模型给证据，大模型负责解释和编排，前端把复杂链路展示成可理解体验。

## Slide 7: (No title)

Body:
- 文本检测与 AI 助手设计
- DeepSeek 承担语义分析、RAG 问答和中文解释
- Main Narrative
- 文本检测支持输入框和文档上传，后端统一构造 OrchestratorRequest。
- 检测链路启用 TEXT_ANALYSIS、KNOWLEDGE、ReAct、CoT 和 RAG。
- AI 助手通过 streamCallLLM 流式返回，前端 knowledge.js 做节流渲染。
- 输出重点从 JSON 字段转为普通用户能读懂的中文风险报告。
- Checklist
- DetectionController.detectText
- DetectionController.detectTextDocument
- LLMService.streamCallLLM
- wechat-app/pages/knowledge
- 文本与问答能力体现 Agent 的语言理解和知识整合能力

## Slide 8: (No title)

Body:
- 音频检测设计：Wav2Vec2 工具化
- 专用模型输出概率，大模型生成贴合文件的报告
- Main Narrative
- ai-services/audio/src/utils.py 负责模型加载、音频预处理和 predict_audio 推理。
- load_model_once 缓存模型，/health 支持预热，SAFEGUARD_DEVICE=cuda 时优先使用 GPU。
- 后端 DetectionService 调用音频服务，LLMService.generateAudioReport 生成中文分析。
- 小程序端支持批量音频检测，并展示处理进度和结果摘要。
- Checklist
- Wav2Vec2
- GPU 优先
- 批量检测
- 报告差异化
- 核心文件：train_wav2vec2.py、utils.py、app.py、asvspoof-finetuned

## Slide 9: (No title)

Body:
- 视频检测设计：XceptionNet 与证据融合
- 换脸检测模型负责视觉风险，后端融合元数据证据
- Main Narrative
- 后端先抽取关键帧，再做人脸裁剪或整帧降级检测。
- Python 视频服务使用 XceptionNet 输出每帧伪造概率。
- 系统统计平均概率、最高单帧概率、可疑帧占比和逐帧明细。
- 如果视频元数据命中 AIGC 标记，综合风险会提高，但报告会区分视觉风险和综合风险。
- Checklist
- VideoFrameExtractorService
- VideoImagePipelineService
- models/xception.py
- pretrained/best_model.pth
- 当前聚焦 AI 换脸/面部篡改检测，不等同于通用视频大模型

## Slide 10: (No title)

Body:
- 小组分工与代码贡献
- 每个人围绕一个核心链路承担工作
- 分工说明结合 README、项目汇报文档和实际代码目录整理。

## Slide 11: (No title)

Body:
- 核心设计思路
- 围绕可演示、可解释、可扩展构建
- Agent 编排
- 将文本、音频、视频统一抽象为任务，由后端选择 DeepSeek、知识库和专用模型。
- 证据融合
- 不只输出一个概率，而是保留可疑点、逐帧明细、元数据、采样信息和模型置信度。
- 演示稳定性
- 一键启动、独立控制台、模型预热、异步视频任务和服务降级保证现场流程可跑通。
- 用户体验
- 小程序展示进度、风险等级、中文报告、历史记录和模拟训练，降低普通用户理解成本。
- 设计目标不是追求单点模型指标，而是把模型能力落到反诈应用流程中。

## Slide 12: (No title)

Body:
- 现场演示建议流程
- 按核心功能从低风险到高复杂度逐步展示
- A
- AI 助手
- 询问一个反诈问题，展示流式中文回答和知识整合能力。
- B
- 文本检测
- 上传文档或输入诈骗话术，展示 Agent 状态、检测报告和处置建议。
- C
- 音频检测
- 选择 test_data 中音频素材，展示模型概率、批量检测和中文报告。
- D
- 视频检测
- 展示异步进度、抽帧明细、逐帧可疑度、AIGC 元数据和综合风险解释。
- 推荐演示目录：D:\Git\SafeGuard\test_data

## Slide 13: (No title)

Body:
- 总结
- 1
- 个可演示的 Agent 反诈应用原型
- DeepSeek 负责理解与编排，Wav2Vec2 和 XceptionNet 提供专用证据，Spring Boot 与微信小程序完成业务闭环。
