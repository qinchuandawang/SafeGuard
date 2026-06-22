# SafeGuard - AI 反诈骗检测系统

> 基于 DeepSeek 智能体调度的多模态 AI 反诈骗检测平台。项目面向课程设计演示，重点实现文本、音频、视频和多模态检测链路跑通，并提供微信小程序端、管理后台和本地 AI 推理服务。

## 核心功能

| 模块 | 功能说明 |
|------|----------|
| AI 智能助手 | 基于 DeepSeek 和反诈知识库回答用户问题，支持流式输出中文回复 |
| 文本检测 | 支持文本框输入和文档上传，调用大模型分析诈骗话术、风险等级和处置建议 |
| 音频检测 | 使用 Wav2Vec2 音频伪造检测模型，支持单文件和批量音频检测 |
| 视频检测 | 使用 XceptionNet 对视频关键帧进行换脸/面部篡改检测，并结合 AIGC 元数据证据生成报告 |
| 多模态检测 | 由 DeepSeek 作为中枢 Agent 融合文本、音频、视频结果并输出综合判断 |
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
├── test_data/                # 文本、音频、视频测试素材
├── start-backend.cmd         # Windows 一键启动脚本
└── docker-compose.yml        # MySQL、Qdrant 等容器编排
```

## 小组分工

| 成员 | 分工 | 工作内容 |
|------|------|----------|
| 彭宏缤 | 后端 | 负责 Spring Boot 后端接口、检测任务调度、数据库记录、历史查询、DeepSeek 调用、报告生成和多模态融合逻辑 |
| 朱乘雨 | 前端 | 负责微信小程序端和管理后台页面，包括检测页面、AI 助手、模拟诈骗、检测历史、结果展示和交互优化 |
| 刘志恒 | 音频训练 | 负责音频伪造检测方向，包括 Wav2Vec2 模型训练、音频预处理、模型权重整理和 Python 音频推理服务 |
| 王家和 | 视频训练 | 负责视频检测方向，包括 XceptionNet 模型训练、视频抽帧、人脸/关键帧检测、模型权重整理和 Python 视频推理服务 |

## 技术架构

```text
微信小程序 / 管理后台
        |
        v
Spring Boot 后端
        |
        +-- DeepSeek：文本分析、AI 助手、模拟诈骗、多模态综合研判
        +-- Wav2Vec2 音频服务：音频伪造检测
        +-- XceptionNet 视频服务：视频关键帧换脸/面部篡改检测
        +-- MySQL：用户、检测记录、模型记录
        +-- Qdrant / 内存回退：反诈知识检索
```

本项目的设计口径是“大模型作为中枢大脑”：DeepSeek 负责理解任务、生成分析报告和融合多源证据；音频、视频训练模型作为专用工具被后端调度。视频检测目前聚焦换脸/面部篡改风险，不等同于通用视频大模型直接判断所有 AI 生成视频。

## 环境要求

| 工具 | 建议版本 | 用途 |
|------|----------|------|
| JDK | 17+ | 运行后端 |
| Maven | 3.9+ | 构建后端 |
| MySQL | 8.0+ | 数据库 |
| Python | 3.11 | 音频/视频 AI 服务 |
| PyTorch | CUDA 版本优先 | GPU 推理 |
| Node.js | 18+ | 管理后台 |
| 微信开发者工具 | 最新版 | 运行小程序 |
| Docker | 可选 | Qdrant / MySQL 容器 |

如果本机有 NVIDIA GPU，音频和视频服务默认会优先使用 `cuda`。启动窗口会打印设备信息，示例：`设备: cuda`。

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

重新克隆项目后，需要从组员提供的模型包或备份中恢复上述文件。

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
