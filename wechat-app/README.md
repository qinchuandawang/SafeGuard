# 诈骗克星 - AI反诈骗检测系统

> ⚠️ 项目状态：后端代码已迁移至 [`backend/`](../backend/) 独立目录。

基于大模型智能体的AI反诈骗检测系统，集成音频伪造检测、视频换脸检测、文本话术分析、RAG知识库检索、多Agent编排推理等功能，为用户提供全方位的智能化反诈骗服务。

## 核心功能

### 1. 音频伪造检测
- 基于 Wav2Vec2 模型微调
- 识别AI合成语音、TTS转换
- 在ASVspoof数据集上训练优化
- 异步检测 + SSE 进度推送

### 2. 视频换脸检测
- 基于 XceptionNet 迁移学习
- 识别AI换脸视频、面部篡改
- 逐帧分析 + 人脸裁剪预处理
- 异步任务 + SSE 逐帧进度推送

### 3. 文本话术分析
- 大模型智能分析（CoT思维链推理）
- 识别常见诈骗话术模式
- 提供风险评估和建议
- 流式SSE逐token输出

### 4. RAG知识库检索
- 向量语义检索（BGE嵌入 + HNSW）
- 混合切块 + 关键词匹配增强
- 语义去重 + 重排序
- 176条反诈知识库

### 5. 模拟诈骗体验
- 安全环境中体验真实诈骗话术
- 5种诈骗剧本（冒充熟人、公检法、刷单、投资、冒充客服）
- SSE流式对话体验
- 结束自动分析报告

### 6. 综合Agent编排分析
- 多Agent并行编排（TEXT_ANALYSIS / KNOWLEDGE / SIMULATION）
- CoT思维链推理 + ReAct推理+行动循环
- 短期/长期记忆系统
- 综合检测分析报告

## 技术架构

```
┌──────────────────────────────────────────────────────────────────┐
│                     微信小程序前端 (frontend/)                      │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌──────────────────┐    │
│  │  首页   │  │ 检测页  │  │ 模拟页  │  │  知识库         │    │
│  └────┬────┘  └────┬────┘  └────┬────┘  └──────┬───────────┘    │
└───────┼────────────┼────────────┼───────────────┼────────────────┘
        │            │            │               │
        └────────────┴─────┬──────┴───────────────┘
                           │ HTTPS
┌──────────────────────────┼──────────────────────────────────────┐
│                     Spring Boot 后端 (backend/)                    │
│  ┌────────────────────────┴───────────────────────────────┐     │
│  │                    大模型智能体调度                       │     │
│  │      AgentOrchestrator + LangChain4j + DeepSeek API     │     │
│  └──────┬──────────┬──────────┬──────────┬────────────────┘     │
│         │          │          │          │                       │
│  ┌──────┴──────┐ ┌─┴──────┐ ┌─┴──────┐ ┌┴──────────────────┐   │
│  │  音频检测API │ │视频检测API│ │文本分析API│ │ Agent编排API    │   │
│  │             │ │         │ │         │ │ (CoT+ReAct+RAG)  │   │
│  └──────┬──────┘ └────┬───┘ └────┬───┘ └───────────────────┘   │
└─────────┼─────────────┼──────────┼──────────────────────────────┘
          │             │          │
          ▼             ▼          ▼
┌──────────────┐ ┌────────────┐ ┌─────────────────────────────┐
│ 音频推理服务   │ │ 视频推理服务 │ │  大模型API + 向量服务        │
│ Flask :5001  │ │Flask :5002 │ │  DeepSeek + SiliconFlow     │
│ Wav2Vec2     │ │XceptionNet │ │  BGE嵌入 + Milvus(内存模拟)  │
└──────────────┘ └────────────┘ └─────────────────────────────┘
```

## 项目结构

```
fraud-killer/
├── frontend/                    # 微信小程序前端
│   ├── assets/                  # 静态资源
│   ├── pages/                   # 页面（7个）
│   │   ├── index/               # 首页
│   │   ├── detection/           # 检测页面
│   │   ├── result/              # 结果页面
│   │   ├── simulate/            # 模拟诈骗
│   │   ├── knowledge/           # 知识库
│   │   ├── article/             # 文章详情
│   │   └── about/               # 关于我们
│   ├── utils/                   # 工具类（request/taskWatcher/config）
│   └── app.js / app.json / app.wxss / app.d.ts
├── backend/                     # Spring Boot 后端（独立Maven项目）
│   ├── src/                     # 后端源码（53个Java文件）
│   ├── pom.xml                  # Maven配置
│   └── README.md                # 后端说明
├── audio-training/              # 音频伪造检测模型训练
│   ├── src/                     # 训练源码
│   └── README.md                # 音频训练说明
├── video-training/              # 视频换脸检测模型训练
│   ├── src/                     # 训练源码
│   └── README.md                # 视频训练说明
├── docs/                        # 技术文档 & 博客
├── project.config.json          # 微信小程序项目配置
└── README.md
```

## 技术栈

### 前端
- **框架**: 微信小程序原生框架
- **组件库**: TDesign Miniprogram 1.14.0
- **样式**: WXSS + CSS Variables
- **数据流**: 内置 Page + App 全局数据

### 后端
- **框架**: Spring Boot 4.0.4 + Java 17
- **数据库**: MySQL 8.0 + JPA / H2（测试）
- **AI框架**: LangChain4j 0.26.0（@AiService / @Tool）
- **推理模块**: CoT（思维链）+ ReAct（推理行动）+ RAG（检索增强）
- **记忆系统**: 短期记忆（STM）+ 长期记忆（LTM）
- **嵌入模型**: BAAI/bge-large-zh-v1.5（SiliconFlow API）
- **向量存储**: Milvus（内存ConcurrentHashMap模拟）
- **SSE流式**: Server-Sent Events 逐token推送

### AI模型
- **音频检测**: Wav2Vec2 + LoRA微调
- **视频检测**: XceptionNet + 迁移学习
- **大语言模型**: DeepSeek-V4 Flash / Qwen2（OpenAI兼容API）
- **嵌入模型**: BGE-large-zh-v1.5

## 后端接口一览（30个API）

| # | 方法 | 路径 | 用途 |
|---|------|------|------|
| 1-2 | POST | `/api/upload`, `/api/upload/detect` | 文件上传与检测 |
| 3-6 | POST | `/api/detection/{audio,video,text,multi}` | 四类内容检测 |
| 7-8 | GET | `/api/detection/task/{id}`, `/api/detection/task/{id}/stream` | 异步任务查询/SSE推送 |
| 9-12 | POST | `/api/llm/analyze`, `/api/llm/analyze/multi`, `/api/llm/analyze/stream`, `/api/llm/scam/chat/stream` | LLM分析/流式 |
| 13-16 | 多方法 | `/api/simulate/{scripts,start,chat,end}` | 模拟诈骗全流程 |
| 17-22 | 多方法 | `/api/knowledge/**` | 知识库CRUD |
| 23-27 | 多方法 | `/api/rag/**`, `/api/analyze/{cot,react}` | RAG检索/CoT/ReAct |
| 28-30 | POST | `/api/agent/analyze`, `/api/agent/analyze/text`, `/api/agent/simulate` | Agent编排分析 |

## 开发团队与分工

| 成员 | 职责 | 模块 | 技术栈 |
|------|------|------|--------|
| **朱乘雨** | 前端开发 | `frontend/` | WXML/WXSS, TDesign |
| **彭宏缤** | 后端与大模型 | `backend/` | Spring Boot, LangChain4j, DeepSeek |
| **刘志恒** | 音频模型训练 | `audio-training/` | Python, PyTorch, Wav2Vec2 |
| **王家和** | 视频模型训练 | `video-training/` | Python, PyTorch, XceptionNet |

## 快速开始

### 前端
```bash
# 1. 安装依赖
npm install tdesign-miniprogram --save

# 2. 打开微信开发者工具 → 工具 → 构建npm
# 3. 设置 project.config.json 中 appid
# 4. 点击编译
```

### 后端
```bash
cd ../backend

# 编译
./mvnw compile

# 运行测试
./mvnw test

# 启动
./mvnw spring-boot:run
```

详见后端 [`backend/README.md`](../backend/README.md)

## 注意事项

1. **免责声明**: 检测结果仅供参考，不能作为法律证据使用
2. **数据安全**: 请勿上传敏感个人信息
3. **DeepSeek API Key**: 需自行配置，已关闭时LLM功能返回fallback提示
4. **SiliconFlow Key**: 用于RAG嵌入，独立配置不受DeepSeek影响
5. **Node_modules**: 请勿提交到Git

## 联系方式

- **反诈热线**: 96110

---

© 2025 诈骗克星 Team
