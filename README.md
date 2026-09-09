# SafeGuard - AI 反诈骗检测系统

> 基于 Spring Boot、Spring AI、Function Calling 和多模态深度伪造检测模型的 AI 反诈骗检测平台。项目面向后端开发、全栈开发和 AI 应用开发场景，覆盖文本、音频、视频、多模态融合、异步任务、高并发缓存和消息事件链路。

## 核心功能

| 模块 | 功能说明 |
|------|----------|
| AI 智能助手 | 基于 DeepSeek / OpenAI-compatible API 和反诈知识库回答用户问题，支持流式输出中文回复 |
| 文本检测 | 支持文本框输入和文档上传，调用大模型分析诈骗话术、风险等级和处置建议 |
| 音频检测 | 已就绪 Wav2Vec2 ASVspoof 模型；预留 AASIST 等适配器目录，只有实现与权重齐备后才允许激活 |
| 视频检测 | 已就绪 XceptionNet 模型；预留 EfficientNet、MesoNet 适配器目录，禁止静默回退冒充执行 |
| 多模态检测 | Java 接收原始音视频和文本并创建异步任务，LangGraph 并行执行文本、音频、视频节点，统一融合证据并支持人工审核；Spring AI + DeepSeek 负责文本检测与最终解释 |
| Function Calling | 后端通过 Spring AI `ChatClient + ToolCallback` 让模型原生选择并调用工具，不再手写协议或解析 `Action:` 文本 |
| LLM Gateway | Java `LLMService` 统一承接 DeepSeek 文本检测、Function Calling、CoT 与报告生成，共享 Token 预算、按场景缓存、重试和 Prompt 版本治理 |
| 分布式记忆 | Redis List 保存带滑动 TTL 的短期会话上下文，Caffeine 作为本地副本与故障回退，Qdrant 保存重要长期风险特征 |
| AI 工作流 | Python 侧使用 LangGraph 编排文本、音频、视频并行检测、动态融合和人工审核恢复；备用模型仅保留扩展路由，Java 仍维护业务任务状态 |
| 高并发任务链路 | 检测任务状态写入 Redis 快照，并通过 RocketMQ 发布任务事件，支撑可靠投递、异步消费和失败补偿 |
| 热点推理保护 | Redisson 可过期信号量按模型预占集群推理槽位，视频任务按文件哈希与模型版本合并，防止热点素材重复推理压垮模型服务 |
| Token 成本治理 | 调用前按场景预占每日 Token 预算，调用后按供应商 usage 对账并持久化；确定性场景使用带模型和 Prompt 版本的精确缓存 |
| 模拟诈骗训练 | 模拟真实诈骗对话场景，帮助用户识别风险话术和训练应对方式 |
| 检测历史 | 小程序端可查看历史检测记录，便于演示完整业务闭环 |
| 管理后台 | 提供数据统计、检测记录、用户管理、模型信息和知识库管理能力 |

## 项目结构

```text
SafeGuard/
├── backend/                  # Spring Boot 后端，统一 API、任务调度、报告生成
├── ai-services/              # Python AI 推理服务
│   ├── audio/                # Wav2Vec2 音频伪造检测服务，端口 5000
│   └── video/                # XceptionNet 视频/图像检测服务，端口 5002
├── wechat-app/
│   └── frontend/             # 微信小程序源码，演示时打开这个目录
├── web-admin/                # Vue 3 管理后台，端口 5173
├── scripts/                  # 本地服务启动脚本
├── datasets/                 # 数据集 manifest 和授权下载说明
├── test_data/                # 文本、音频、视频测试素材
├── start-backend.cmd         # Windows 一键启动脚本
└── docker-compose.yml        # MySQL、Qdrant、Redis、RocketMQ 等容器编排
```

## 项目归属与职责范围

本项目由本人（朱乘雨）**全栈独立开发**，覆盖后端服务、AI 编排、模型服务化与前端页面；音频 / 视频检测模型的**训练与权重产出由合作同学完成**，本人负责模型接入、Python 推理服务封装、GPU 推理与调度集成。

| 层次 | 覆盖范围 |
|------|----------|
| 后端 | Spring Boot 接口、检测任务调度、数据库记录与历史查询、DeepSeek / LLM Gateway 调用、报告生成与多模态融合逻辑 |
| AI 编排 | Spring AI Function Calling 工具适配、LangGraph 并行推理编排、Agentic RAG 与记忆系统、Token 成本治理 |
| 模型服务 | Wav2Vec2 音频伪造检测与 XceptionNet 视频伪造检测的 Python 推理服务、数据预处理、GPU 推理与调度（模型训练与权重由合作同学提供） |
| 前端 | 微信小程序端与管理后台页面，包括检测页面、AI 助手、模拟诈骗、检测历史、结果展示和交互优化 |

## 技术架构

```text
微信小程序 / 管理后台
        |
        v
Spring Boot 后端
        |
        +-- Spring AI / Function Calling：工具 schema 生成、工具调用、智能体编排
        +-- DeepSeek / OpenAI-compatible LLM：文本分析、AI 助手、模拟诈骗、多模态综合研判
        +-- 音频模型目录：Wav2Vec2、AASIST 等音频伪造检测模型
        +-- 视频模型目录：XceptionNet、EfficientNet、MesoNet 等视频伪造检测模型
        +-- Caffeine + Redis：进程内热点缓存、跨实例任务快照与模型状态
        +-- Redisson：模型推理槽位、归档分布式锁、看门狗续期与幂等键原子操作
        +-- Sentinel：音视频推理流控、慢调用/异常比例熔断与治理控制台
        +-- RocketMQ：检测任务事件发布、可靠投递、失败重试和补偿扩展
        +-- MySQL：用户、检测记录、模型记录
        +-- Qdrant / 内存回退：反诈知识检索
```

本项目的设计口径是“大模型作为中枢大脑，专用模型作为可替换工具”：大模型负责理解任务、生成分析报告和融合多源证据；音频、视频训练模型作为专用工具被后端调度。后端统一走 Spring AI 原生 Function Calling；模型不可用时只降级为 RAG/规则结果，不再保留手写 ReAct 工具协议。

## 架构升级亮点

| 方向 | 设计说明 |
|------|----------|
| AI 框架 | 后端引入 Spring AI 依赖和 OpenAI-compatible 调用结构，保留现有 DeepSeek 配置体系 |
| 工具调用 | `FunctionCallingToolService` 把 `ToolRegistry` 适配为 Spring AI `ToolCallback`，由框架完成 schema、调用循环和结果回注 |
| 模型可替换 | `model-catalog` 管理 3+3 候选目录，`enabled` 区分候选与已就绪模型，活动状态通过 Redis 在多实例间共享 |
| 多级缓存 | Caffeine 缓存 Embedding、RAG 结果、知识上下文和服务健康状态；Redis 承载跨实例任务快照与活动模型，缓存失效时回源事实存储 |
| 高并发治理 | Redisson 按模型原子预占集群推理槽位，许可耗尽快速拒绝；Sentinel 对已准入调用实施线程数流控、慢调用比例和异常比例熔断 |
| 幂等与并发 | `Idempotency-Key` + 文件哈希/模型版本组成业务键，Redisson 原子预占/CAS 释放、MySQL 唯一索引兜底；任务状态使用条件更新和乐观锁 |
| 任务可恢复 | 持久化媒体 `objectKey/filePath`，实例重启后按超时窗口和数据库条件更新重新抢占任务，并从 MinIO 恢复输入继续执行 |
| Token 成本 | Redis/Redisson 原子共享集群日预算，MySQL 保存 usage 账本；文本、报告和多模态场景使用版本化精确响应缓存 |
| 冷热分层 | 结构化检测记录按游标批次归档，事务内校验副本后逻辑删除热数据；原始音视频进入 MinIO，MySQL 只保留对象 Key |
| 分布式协调 | Redisson `RLock` 配合看门狗续期保护归档任务，锁覆盖完整数据库事务并按线程所有权释放 |
| 高可用部署 | 独立 HA Compose 提供 Redis 1 主 2 从 3 Sentinel、MySQL GTID 主从；关键写路径固定主库，报表从库显式接入 |
| 数据集准备 | `scripts/download_datasets.py` 生成 ASVspoof、FaceForensics++、DFDC 等数据集目录和授权下载说明 |
| 运行时治理 | Sentinel Dashboard 是流控、熔断规则和实时资源监控的主要入口；Actuator 暴露健康状态与 Caffeine 指标 |
| 可选深度观测 | Prometheus 用于长期指标与告警，OpenTelemetry + Zipkin 仅用于跨服务疑难链路排障，不作为核心演示依赖 |
| 工程兜底 | Qdrant、LLM、音视频服务不可用时保留内存或规则兜底；应用默认不管理 Docker/Qdrant 生命周期 |

可靠性管理接口统一位于 `/api/admin/reliability`，包含推理容量、Token 预算和归档统计。生产环境默认关闭演示身份，必须使用管理员 JWT。

面向简历的精简项目介绍与 8 项技术亮点见 [`docs/project-resume-description.md`](docs/project-resume-description.md)。其中量化数据为模拟压测目标，必须通过统一环境复测后再作为正式结果使用。

HA 环境与故障演练见 [`docs/high-availability-runbook.md`](docs/high-availability-runbook.md)。

## 环境要求

| 工具 | 建议版本 | 用途 |
|------|----------|------|
| JDK | 17+ | 运行后端 |
| Maven | 3.9+ | 构建后端 |
| MySQL | 8.0+ | 数据库 |
| Redis | 7+ | 任务状态快照和热点查询 |
| Sentinel Dashboard | 1.8.8 | 流控、熔断规则和实时资源监控 |
| RocketMQ | 5.3+ | 任务事件发布、可靠投递和失败补偿 |
| Python | 3.11 | 音频/视频 AI 服务 |
| PyTorch | CUDA 版本优先 | GPU 推理 |
| Node.js | 18+ | 管理后台 |
| 微信开发者工具 | 最新版 | 运行小程序 |
| Docker | 可选 | Qdrant / MySQL 容器 |

如果本机有 NVIDIA GPU，音频和视频服务默认会优先使用 `cuda`。启动窗口会打印设备信息，示例：`设备: cuda`。

## 数据集准备

项目提供数据集准备脚本：

```bash
python scripts/download_datasets.py
```

脚本会生成 `datasets/manifest.json`，并为 ASVspoof2019、ASVspoof2021、FaceForensics++、DFDC 创建目录和 README。当前仓库仅包含 demo 样本；上述正式数据集需要官方网站授权或 Kaggle token，脚本不会绕过数据集许可，也不会把“目录已创建”标记成“数据已下载”。

## 快速启动

### 方式 A：Windows 一键启动（推荐演示使用）

在项目根目录运行：

```bat
start-backend.cmd
```

脚本会打开独立控制台窗口启动：

| 服务 | 地址 |
|------|------|
| Java 后端 | `http://localhost:8080` |
| Sentinel 控制台 | `http://localhost:8858`（默认账号/密码：`sentinel/sentinel`） |
| 音频检测服务 | `http://localhost:5000/health` |
| 视频检测服务 | `http://localhost:5002/api/health` |
| 管理后台 | `http://localhost:5173` |

一键脚本会在启动后访问音频和视频健康检查接口，提前加载本地模型，减少第一次检测时的等待时间。演示前建议等待主窗口出现：

```text
[OK] Audio model ready and warmed.
[OK] Video model ready and warmed.
```

### 方式 B：手动启动

后端：

```bash
cd backend
mvn spring-boot:run
```

音频服务：

```bash
scripts/start-audio-service.cmd
```

视频服务：

```bash
scripts/start-video-service.cmd
```

管理后台：

```bash
cd web-admin
npm install
npm run dev
```

微信小程序：

1. 打开微信开发者工具。
2. 导入项目时选择 `wechat-app/frontend`。
3. 编译运行。
4. 默认连接 `http://localhost:8080`，真机预览时需要把接口地址改成电脑局域网 IP。

## 配置说明

项目支持通过根目录 `.env` 或系统环境变量配置关键参数。演示环境通常使用本地 MySQL：

```env
DB_HOST=localhost
DB_PORT=3306
DB_NAME=safeguard
DB_USERNAME=root
DB_PASSWORD=

LLM_API_KEY=你的 DeepSeek API Key
LLM_MODEL=DeepSeek-V4-Pro
SILICONFLOW_API_KEY=你的 SiliconFlow API Key
SAFEGUARD_DEVICE=cuda
```

说明：

- `LLM_API_KEY` 用于文本检测、AI 助手、模拟诈骗和综合报告。
- `SILICONFLOW_API_KEY` 用于 RAG 向量检索；未配置时会降级为关键词检索或内存回退。
- `SAFEGUARD_DEVICE=cuda` 表示音频/视频 Python 服务优先使用 GPU；如果 PyTorch 未检测到 CUDA，会自动退回 CPU 并在控制台提示。

## 模型文件

大模型权重不提交到 Git，演示机器本地需要保留：

| 模型 | 路径 |
|------|------|
| 音频 Wav2Vec2 | `ai-services/audio/pretrained/asvspoof-finetuned/model.safetensors` |
| 视频 XceptionNet | `ai-services/video/pretrained/best_model.pth` |

重新克隆项目后，需要从本地模型包或备份中恢复上述文件。

## 测试素材

`test_data/` 目录提供演示素材：

```text
test_data/
├── text/       # 文本诈骗话术与文档素材
├── audio/      # 音频检测素材
└── video/      # 视频检测素材
```

建议演示顺序：

1. 打开 AI 智能助手，提问一个反诈问题，展示流式中文回复。
2. 文本检测：输入文本或上传文档，展示大模型分析报告。
3. 音频检测：上传 `test_data/audio` 中的素材，展示 Wav2Vec2 检测结果和处理进度。
4. 视频检测：上传 `test_data/video` 中的素材，展示抽帧进度、逐帧风险、元数据证据和综合报告。
5. 打开检测历史，展示记录已保存。
6. 打开模拟诈骗，展示真实感对话训练。

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 8080 | 小程序和管理后台 API |
| Sentinel Dashboard | 8858 | 流控、熔断规则和实时资源监控 |
| 音频检测服务 | 5000 | Wav2Vec2 音频伪造检测 |
| 视频检测服务 | 5002 | XceptionNet 图像/视频检测 |
| 管理后台 | 5173 | Vue 3 Web 管理界面 |
| MySQL | 3306 | 业务数据库 |
| Qdrant | 6333 | 向量数据库，可选 |

## 数据库

本地 MySQL 示例：

```bash
mysql -u root -p
CREATE DATABASE safeguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

如果使用 Docker：

```bash
docker compose up -d db qdrant
```

Qdrant 不可用时，系统会使用内存回退模式，不影响核心演示。

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot + Java 17 | 后端 API、任务调度、报告生成 |
| MyBatis-Plus + MySQL | 数据持久化 |
| Spring AI + Function Calling | 大模型接入和结构化工具调用 |
| Sentinel | 推理资源流控、慢调用与异常比例熔断 |
| Caffeine + Redis + Redisson | 多级缓存、跨实例状态、分布式锁和原子幂等操作 |
| RocketMQ 事务消息 | 半消息、本地事务回查和消费幂等 |
| DeepSeek | 文本分析、AI 助手、多模态研判 |
| Wav2Vec2 + PyTorch | 音频伪造检测 |
| XceptionNet + PyTorch | 视频换脸/面部篡改检测 |
| FFmpeg / JavaCV | 视频元数据读取、抽帧 |
| SiliconFlow + Qdrant | RAG 知识检索 |
| 微信小程序 + TDesign | 用户端 |
| Vue 3 + Element Plus | 管理后台 |

## 常见问题

**Q：微信开发者工具应该打开哪个目录？**  
A：打开 `wechat-app/frontend`，不要打开 `frontend` 或仓库根目录。

**Q：首次检测为什么慢？**  
A：音频和视频本地模型首次运行需要加载权重到 CPU/GPU。一键启动脚本已经通过健康检查预热模型，建议等预热完成后再演示。

**Q：为什么视频检测报告会区分视觉风险和综合风险？**  
A：XceptionNet 主要检测换脸/面部篡改痕迹；如果视频元数据包含 AIGC 来源标记，系统会把它作为强证据提高综合风险。因此可能出现“视觉换脸风险不高，但综合风险较高”的情况。

**Q：文本检测为什么需要 DeepSeek？**  
A：文本诈骗识别需要理解语义、话术、上下文和反诈知识，不能靠后端写死规则。后端会调用 DeepSeek 输出结构化风险判断，再转成正常中文报告。

**Q：没有配置 SiliconFlow 会怎样？**  
A：RAG 语义检索能力会下降，但系统会降级为关键词检索或内存回退，不影响核心检测链路演示。

**Q：管理后台默认账号是什么？**  
A：没有默认管理员账号。首次使用需要在登录页注册管理员。
