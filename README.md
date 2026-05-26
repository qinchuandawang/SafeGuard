# SafeGuard - AI 反诈骗检测系统

> **诈骗克星** - 基于大模型智能体的全方位 AI 反诈骗检测平台

[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.java.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.4-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![WeChat](https://img.shields.io/badge/WeChat-小程序-07c160.svg)](https://developers.weixin.qq.com/miniprogram/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## 项目简介

SafeGuard 是一个集成了**音频伪造检测**、**视频换脸检测**、**文本话术分析**、**RAG 知识库检索**、**多 Agent 编排推理**等功能的智能化反诈骗检测系统。系统采用微信小程序作为前端入口，Spring Boot 作为后端服务，结合深度学习模型和大语言模型，为用户提供全方位的诈骗检测和防范服务。

## 核心功能

### 🔊 音频伪造检测
- 基于 Wav2Vec2 模型微调
- 识别 AI 合成语音、TTS 转换
- 在 ASVspoof 数据集上训练优化
- 异步检测 + SSE 进度推送

### 🎥 视频换脸检测
- 基于 XceptionNet 迁移学习
- 识别 AI 换脸视频、面部篡改
- 逐帧分析 + 人脸裁剪预处理
- 异步任务 + SSE 逐帧进度推送

### 📝 文本话术分析
- 大模型智能分析（CoT 思维链推理）
- 识别常见诈骗话术模式
- 提供风险评估和建议
- 流式 SSE 逐 token 输出

### 🧠 RAG 知识库检索
- 向量语义检索（BGE 嵌入 + HNSW）
- 混合切块 + 关键词匹配增强
- 语义去重 + 重排序
- 176 条反诈知识库

### 🎭 模拟诈骗体验
- 安全环境中体验真实诈骗话术
- 5 种诈骗剧本（冒充熟人、公检法、刷单、投资、冒充客服）
- SSE 流式对话体验
- 结束自动分析报告

### 🤖 综合 Agent 编排分析
- 多 Agent 并行编排（TEXT_ANALYSIS / KNOWLEDGE / SIMULATION）
- CoT 思维链推理 + ReAct 推理 + 行动循环
- 短期/长期记忆系统
- 综合检测分析报告

## 技术架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     微信小程序前端 (wechat-app/frontend/)           │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────────────┐    │
│  │  首页   │  │ 检测页  │  │ 模拟页  │  │  知识库         │    │
│  └────┬────┘  └────┬────┘  └────┬────┘  └──────┬───────────┘    │
└───────┼────────────┼────────────┼───────────────┼────────────────┘
        │            │            │               │
        └────────────┴─────┬──────┴───────────────┘
                           │ HTTPS
┌──────────────────────────┼──────────────────────────────────────┐
│                  Spring Boot 后端 (backend/)                      │
│  ┌────────────────────────┴───────────────────────────────┐     │
│  │                    大模型智能体调度                       │     │
│  │      AgentOrchestrator + LangChain4j + DeepSeek API     │     │
│  └──────┬──────────┬──────────┬──────────┬────────────────┘     │
│         │          │          │          │                       │
│  ┌──────┴──────┐ ┌─┴──────┐ ┌─┴──────┐ ┌┴──────────────────┐   │
│  │  音频检测 API │ │视频检测 API│ │文本分析 API│ │ Agent 编排 API    │   │
│  │             │ │         │ │         │ │ (CoT+ReAct+RAG)  │   │
│  └──────┬──────┘ └────┬───┘ └────┬───┘ └───────────────────┘   │
└─────────┼─────────────┼──────────┼──────────────────────────────┘
          │             │          │
          ▼             ▼          ▼
┌──────────────┐ ┌────────────┐ ┌─────────────────────────────┐
│ 音频推理服务   │ │ 视频推理服务 │ │  大模型 API + 向量服务        │
│ Flask :5001  │ │Flask :5002 │ │  DeepSeek + SiliconFlow     │
│ Wav2Vec2     │ │XceptionNet │ │  BGE 嵌入 + Qdrant 向量检索  │
└──────────────┘ └────────────┘ └─────────────────────────────┘
```

## 项目结构

```
SafeGuard/
├── wechat-app/                    # 微信小程序
│   ├── frontend/                  # 小程序前端源码
│   │   ├── assets/                # 静态资源（图标、图片）
│   │   ├── pages/                 # 页面（7 个）
│   │   │   ├── index/             # 首页
│   │   │   ├── detection/         # 检测页面
│   │   │   ├── result/            # 结果页面
│   │   │   ├── simulate/          # 模拟诈骗
│   │   │   ├── knowledge/         # 知识库
│   │   │   ├── article/           # 文章详情
│   │   │   └── about/             # 关于我们
│   │   ├── utils/                 # 工具类
│   │   └── app.js / app.json      # 小程序配置
│   ├── audio-training/            # 音频检测模型训练
│   │   ├── src/                   # 训练源码
│   │   └── README.md              # 训练说明
│   ├── video-training/            # 视频检测模型训练
│   │   ├── src/                   # 训练源码
│   │   └── README.md              # 训练说明
│   └── README.md                  # 小程序说明文档
│
├── backend/                       # Spring Boot 后端
│   ├── src/main/java/             # Java 源码（53 个文件）
│   │   ├── com/sdu/safeguard/
│   │   │   ├── agent/             # Agent 编排调度
│   │   │   ├── config/            # 配置类（8 个）
│   │   │   ├── controller/        # REST 控制器（6 个）
│   │   │   ├── dto/               # 数据传输对象（18 个）
│   │   │   ├── rag/               # RAG 向量检索
│   │   │   ├── reasoning/         # AI 推理模块
│   │   │   ├── memory/            # 记忆系统
│   │   │   ├── service/           # 业务服务（7 个）
│   │   │   ├── repository/        # JPA 持久层
│   │   │   └── util/              # 工具类
│   │   └── SafeGuardApplication.java
│   ├── src/main/resources/
│   │   ├── application.yml        # 主配置
│   │   ├── knowledge/             # 反诈知识库（176 条）
│   │   ├── prompts/               # 提示词模板（8 个）
│   │   └── opencv/                # 人脸检测模型
│   ├── src/test/                  # 测试用例（24 个）
│   ├── pom.xml                    # Maven 配置
│   └── README.md                  # 后端说明文档

```

## 技术栈

### 后端技术
| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 4.0.4 | Web 框架 |
| Java | 17 | 运行环境 |
| Maven | 3.9+ | 构建工具 |
| MySQL 8.0 + JPA | - | 知识库持久化 |
| H2 | - | 测试内存数据库 |
| SiliconFlow API | - | BGE 嵌入 / 重排序 |
| DeepSeek API | - | 大语言模型调用 |
| Javacv-platform | 1.5.10 | 视频抽帧 + OpenCV |
| Lombok | - | 简化代码 |

### 前端技术
| 技术 | 用途 |
|------|------|
| 微信小程序 | 前端界面 |
| TDesign 组件库 | UI 组件 |
| 原生小程序 API | 功能调用 |

### AI 模型
| 模型 | 用途 | 提供方 |
|------|------|--------|
| Wav2Vec2 | 音频伪造检测 | 自训练 |
| XceptionNet | 视频换脸检测 | 自训练 |
| DeepSeek-Chat | 文本分析/对话 | DeepSeek API |
| BGE-Large-ZH | 向量嵌入 | SiliconFlow |
| BGE-Reranker | 重排序 | SiliconFlow |

## 快速开始

### 环境要求
- **JDK**: 17+
- **Maven**: 3.9+
- **Node.js**: 16+ (微信小程序开发工具)
- **MySQL**: 8.0+
- **Python**: 3.8+ (模型推理服务)
- **微信开发者工具**: 最新版

### 1. 后端启动

```bash
# 1. 创建 MySQL 数据库
mysql -u root -p
CREATE DATABASE safeguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

# 2. 修改配置文件
# 编辑 backend/src/main/resources/application.yml
# 修改数据库连接、API Key 等配置

# 3. 启动后端
cd backend
mvn clean install
mvn spring-boot:run
```

后端服务将在 `http://localhost:8080` 启动

### 2. 模型推理服务

```bash
# 音频检测服务（端口 5001）
cd wechat-app/audio-training
pip install -r requirements.txt
python src/inference.py

# 视频检测服务（端口 5002）
cd wechat-app/video-training
pip install -r requirements.txt
python src/inference.py
```

### 3. 微信小程序

1. 打开**微信开发者工具**
2. 导入项目：选择 `wechat-app/frontend` 目录
3. 修改 `utils/config.js` 中的后端地址
4. 编译运行

## API 文档

系统提供 28 个 RESTful API 端点，主要包括：

### 文件上传
- `POST /api/upload` - 上传文件返回 fileId
- `POST /api/upload/detect` - 上传并立即检测

### 检测接口
- `POST /api/detection/audio` - 音频检测（同步）
- `POST /api/detection/video` - 视频检测（异步+taskId）
- `POST /api/detection/text` - 文本 LLM 分析
- `POST /api/detection/multi` - 多模态 LLM 分析
- `GET /api/detection/task/{taskId}` - 异步任务轮询查询
- `GET /api/detection/task/{id}/stream` - 异步任务 SSE 进度推送

### 大模型接口
- `POST /api/llm/chat` - 普通对话
- `POST /api/llm/chat/stream` - SSE 流式对话

### 知识库接口
- `GET /api/knowledge` - 查询知识库
- `POST /api/knowledge` - 添加知识条目
- `PUT /api/knowledge/{id}` - 更新知识条目
- `DELETE /api/knowledge/{id}` - 删除知识条目
- `POST /api/knowledge/simulate` - 模拟诈骗对话

### RAG 接口
- `POST /api/rag/query` - RAG 检索查询
- `POST /api/rag/cot` - CoT 思维链推理
- `POST /api/rag/react` - ReAct 推理 + 行动

### Agent 编排
- `POST /api/agent/orchestrate` - 多 Agent 编排分析

详细 API 文档请查看：[backend/README.md](backend/README.md)

## 核心模块说明

### RAG 检索增强生成
```
用户查询 → 混合切块 → BGE 嵌入 → HNSW 向量搜索 → 关键词匹配
         → 语义去重 → 重排序 → 注入提示词 → LLM 生成回答
```
- 176 条反诈知识库，自动切块 + 嵌入
- 混合检索：向量相似度 (0.6) + 关键词匹配 (0.4)
- SiliconFlow API 在线嵌入，离线回退文本 hash

### CoT 思维链推理
```
Step1: 提取关键信息 → Step2: 模式匹配 → Step3: 风险评估 → Step4: 防范建议
```
- 结构化 JSON 输出（scamType / riskLevel / riskProbability / advice）

### ReAct 推理 + 行动
```
Thought → Action → Observation 循环（最多 5 步）
└── SEARCH_KNOWLEDGE / ANALYZE / FINISH
```
- 自动决定搜索知识库、分析或给出结论

### Agent 编排
```
用户请求 → AgentOrchestrator
         ├─ TEXT_AGENT → 文本分析
         ├─ KNOWLEDGE_AGENT → 知识检索
         └─ SIMULATION_AGENT → 模拟对话
         → 综合报告
```
- 多 Agent 并行执行
- 短期/长期记忆支持
- 最终汇总分析

## 开发团队

- **后端开发**: Spring Boot + AI 推理
- **前端开发**: 微信小程序
- **模型训练**: 音频/视频检测模型

## 许可证

本项目采用 MIT 许可证。详见 [LICENSE](LICENSE) 文件。

## 联系方式

如有问题或建议，请通过以下方式联系我们：
- 项目 Issues: GitHub Issues
- 邮箱：[请填写联系邮箱]

---

**⚠️ 免责声明**：本项目仅供学习和研究使用。模拟诈骗功能仅在安全环境中运行，请勿用于非法用途。
