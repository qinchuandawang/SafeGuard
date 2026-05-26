# SafeGuard Backend — AI反诈骗检测系统后端

SafeGuard 是"诈骗克星"系统的后端服务，基于 **Spring Boot 4.0.4 + Java 17**，集成大语言模型（LLM）、RAG知识检索、CoT思维链推理、ReAct推理循环与多Agent编排调度，为前端提供28个API端点。

## 项目概述

```
backend/
├── src/main/java/com/sdu/safeguard/
│   ├── SafeGuardApplication.java        # 应用入口（@EnableAsync）
│   ├── agent/                           # Agent编排调度
│   │   └── AgentOrchestrator.java       # 多Agent并行编排器
│   ├── config/                          # 配置类（8个）
│   │   ├── AsyncConfig.java             # 自定义线程池
│   │   ├── LLMConfig.java               # DeepSeek大模型配置
│   │   ├── RAGConfig.java               # 向量检索参数配置
│   │   ├── MemoryConfig.java            # 记忆系统配置
│   │   ├── SiliconFlowConfig.java       # BGE嵌入API配置
│   │   ├── VideoProperties.java         # 视频预处理配置
│   │   ├── RestTemplateConfig.java      # 超时+重试拦截器
│   │   ├── WebConfig.java               # 跨域配置
│   │   └── GlobalExceptionHandler.java  # 全局异常处理
│   ├── controller/                      # REST控制器（6个）
│   │   ├── DetectionController.java     # 音频/视频/文本/多模态检测
│   │   ├── LLMController.java           # SSE流式端点
│   │   ├── KnowledgeController.java     # 知识库CRUD + 模拟诈骗
│   │   ├── RAGController.java           # RAG检索 + CoT/ReAct
│   │   ├── AgentController.java         # Agent编排分析
│   │   └── FileUploadController.java    # 文件上传
│   ├── dto/                             # 数据传输对象（18个）
│   ├── rag/                             # RAG向量检索模块
│   │   ├── RAGService.java              # 核心RAG服务
│   │   ├── HybridChunker.java           # 混合切块器
│   │   ├── EmbeddingService.java        # BGE嵌入服务
│   │   ├── QdrantService.java           # 向量存储（Qdrant REST API）
│   │   └── ChunkResult.java             # 切块结果
│   ├── reasoning/                       # AI推理模块
│   │   ├── CoTService.java              # 思维链推理
│   │   └── ReActService.java            # ReAct推理+行动循环
│   ├── memory/                          # 记忆系统
│   │   └── MemoryService.java           # 短期+长期记忆
│   ├── service/                         # 业务服务（7个）
│   │   ├── LLMService.java              # LLM调用 + SSE流式
│   │   ├── KnowledgeService.java        # 知识库JPA持久化
│   │   ├── DetectionService.java        # 检测调度
│   │   ├── DetectionTaskManager.java    # 异步任务管理
│   │   ├── VideoFrameExtractorService.java
│   │   ├── FaceCropService.java
│   │   └── VideoImagePipelineService.java
│   ├── repository/
│   │   └── KnowledgeItemRepository.java # JPA Repository
│   └── util/
│       └── PromptLoader.java            # 提示词模板加载器
├── src/main/resources/
│   ├── application.yml                  # 主配置
│   ├── knowledge/anti_fraud_knowledge.txt  # 176条反诈知识库
│   ├── prompts/                         # 8个提示词模板
│   └── opencv/lbpcascade_frontalface.xml   # 人脸检测模型
├── src/test/                            # 24个测试用例
└── pom.xml
```

## 技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 4.0.4 | Web框架 |
| Java | 17 | 运行环境 |
| Maven | 3.9+ | 构建工具 |
| MySQL 8.0 + JPA | - | 知识库持久化 |
| H2 | - | 测试内存数据库 |
| SiliconFlow API | - | BGE嵌入 / 重排序 |
| DeepSeek API | - | 大语言模型调用 |
| Javacv-platform | 1.5.10 | 视频抽帧 + OpenCV |
| Lombok | - | 简化代码 |

## AI模块详解

### RAG检索增强生成
```
用户查询 → 混合切块 → BGE嵌入 → HNSW向量搜索 → 关键词匹配
         → 语义去重 → 重排序 → 注入提示词 → LLM生成回答
```
- 176条反诈知识库，自动切块+嵌入
- 混合检索：向量相似度(0.6) + 关键词匹配(0.4)
- SiliconFlow API 在线嵌入，离线回退文本hash

### CoT思维链推理
```
Step1: 提取关键信息 → Step2: 模式匹配 → Step3: 风险评估 → Step4: 防范建议
```
- 结构化JSON输出（scamType / riskLevel / riskProbability / advice）

### ReAct推理+行动
```
Thought → Action → Observation 循环（最多5步）
└── SEARCH_KNOWLEDGE / ANALYZE / FINISH
```
- 自动决定搜索知识库、分析或给出结论

### Agent编排调度
- 注入 Spring detectionTaskExecutor 线程池，CompletableFuture 并行执行
- 5种Agent类型（TEXT_ANALYSIS / KNOWLEDGE / SIMULATION / AUDIO_DETECTION / VIDEO_DETECTION）
- 30秒超时保护，单个Agent失败不阻塞整体

### 记忆系统
- 短期记忆：会话级（50条上限）
- 长期记忆：持久化到磁盘（1000条上限）
- 重要性评分自动升级短→长

## API文档（28个端点）

### 文件上传
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/upload` | 上传文件，返回fileId |
| POST | `/api/upload/detect` | 上传并立即检测 |

### 内容检测
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/detection/audio` | 音频检测（同步） |
| POST | `/api/detection/video` | 视频检测（异步，返回taskId） |
| POST | `/api/detection/text` | 文本话术分析 |
| POST | `/api/detection/multi` | 多模态综合分析 |
| GET | `/api/detection/task/{id}` | 异步任务轮询 |
| GET | `/api/detection/task/{id}/stream` | 异步任务SSE进度推送 |

### LLM流式分析
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/llm/analyze/stream` | 流式文本分析（SSE） |
| POST | `/api/llm/scam/chat/stream` | 模拟对话（SSE流式） |

### 模拟诈骗
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/simulate/scripts` | 剧本列表 |
| GET | `/api/simulate/start/{id}` | 获取开场白 |
| POST | `/api/simulate/chat` | 模拟对话 |
| POST | `/api/simulate/end` | 结束分析报告 |

### 知识库
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/knowledge/search?keyword=` | 关键词检索 |
| GET | `/api/knowledge/category/{c}` | 按分类查询 |
| GET | `/api/knowledge` | 全量列表 |
| POST | `/api/knowledge` | 新增条目 |
| PUT | `/api/knowledge/{id}` | 更新条目 |
| DELETE | `/api/knowledge/{id}` | 删除条目 |

### RAG / 推理
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/rag/query?q=` | RAG知识检索 |
| GET | `/api/rag/stats` | 知识库状态 |
| POST | `/api/rag/reload` | 重载知识库 |
| POST | `/api/analyze/cot` | CoT思维链分析 |
| POST | `/api/analyze/react` | ReAct推理分析 |

### Agent编排
| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/agent/analyze` | 通用Agent编排 |
| POST | `/api/agent/analyze/text` | 文本Agent分析 |
| POST | `/api/agent/simulate` | 模拟Agent分析 |

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.9+
- MySQL 8.0+
- DeepSeek API Key（可选，LLM功能）
- SiliconFlow API Key（可选，RAG嵌入）

### 配置

编辑 `src/main/resources/application.yml`：

```yaml
llm:
  api-key: sk-your-deepseek-key
  api-url: https://api.deepseek.com/v1/chat/completions
  model: deepseek-chat
  temperature: 0.7
  max-tokens: 2048

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/safeguard?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=UTF-8
    username: root
    password: your-password
```

### 构建与运行

```bash
# 1. 创建数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS safeguard DEFAULT CHARACTER SET utf8mb4"

# 2. 编译
./mvnw compile

# 3. 运行测试（24个测试用例）
./mvnw test

# 4. 启动
./mvnw spring-boot:run
```

### 验证

```bash
curl http://localhost:8080/api/knowledge
# → {"code":200,"message":"success","data":[...5条知识库...]}
```

## 测试

```bash
./mvnw test
# Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
```

覆盖范围：全局异常处理、DTO字段、知识库CRUD、提示词模板、RAG端到端注入。

## 项目依赖

- [wechat-app/frontend](../wechat-app/frontend/) — 微信小程序前端
- [wechat-app/audio-training](../wechat-app/audio-training/) — 音频检测Flask服务
- [wechat-app/video-training](../wechat-app/video-training/) — 视频检测Flask服务

## 注意事项

1. **DeepSeek API Key 已关闭**：LLM功能返回fallback提示，不影响非LLM功能
2. **SiliconFlow Key 独立**：RAG嵌入不受DeepSeek影响
3. **MySQL 需手动创建**：首次运行前需创建 `safeguard` 数据库
4. **JPA自动建表**：`ddl-auto: update` 会自动创建 `knowledge_item` 表并初始化5条默认数据

---

© 2025 彭宏缤 — SafeGuard Backend
