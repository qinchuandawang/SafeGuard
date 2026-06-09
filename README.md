# SafeGuard - AI 反诈骗检测系统

> 基于大模型智能体的全方位 AI 反诈骗检测平台

## 项目结构

```
SafeGuard/
├── backend/                  # Spring Boot 后端 (Java 17)
│   ├── src/main/java/        # Java 源码
│   ├── src/main/resources/   # 配置、SQL、知识库
│   └── pom.xml               # Maven 构建
├── ai-services/              # Python AI 推理服务
│   ├── audio/                # 音频伪造检测 (Wav2Vec2)
│   │   └── pretrained/       # 模型文件（已包含）
│   ├── video/                # 视频换脸检测 (XceptionNet)
│   │   └── pretrained/       # 模型文件（已包含）
│   └── run.py                # 一键启动脚本
├── web-admin/                # 管理后台 (Vue 3)
├── wechat-app/               # 微信小程序
│   └── frontend/             # 小程序源码
└── docker-compose.yml        # 容器编排
```

## 快速开始

### 环境要求

| 工具 | 版本 | 用途 |
|------|------|------|
| JDK | 17+ | 运行后端 |
| Maven | 3.9+（或用 mvnw） | 构建后端 |
| MySQL | 8.0+ | 数据库 |
| Python | 3.11 | AI 推理服务 |
| Node.js | 18+ | 管理后台 |
| 微信开发者工具 | 最新 | 运行小程序 |
| Docker（可选） | 最新 | Qdrant / 一键部署 |

---

### 第 1 步：后端

```bash
# 1.1 创建数据库
mysql -u root -p
CREATE DATABASE safeguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

# 1.2 配置 API Key（如需使用大模型和 RAG 功能）
#    编辑 backend/src/main/resources/application.yml
#    - llm.api-key     → DeepSeek / SophNet 的 API Key（文本分析用）
#    - siliconflow.api-key → SiliconFlow 的 API Key（RAG 向量检索用）
#    不配置也不影响运行，只是"文本分析"和"RAG查询"功能会降级

# 1.3 编译并启动
cd backend
mvn compile
mvn spring-boot:run
# 访问 http://localhost:8080/api/health 验证启动
```

后端会自动创建表结构并写入初始数据。

### 第 2 步：AI 推理服务（Python）

```bash
cd ai-services

# 2.1 创建虚拟环境（Python 3.11）
python -m venv .venv

# 2.2 安装依赖
.\.venv\Scripts\python.exe -m pip install -U pip
.\.venv\Scripts\python.exe -m pip install -r requirements.txt

# 2.3 启动（音频 :5000 + 视频 :5002；可通过环境变量覆盖）
.\.venv\Scripts\python.exe run.py
```

> 模型文件已包含在仓库中，无需额外下载：
> - 音频：`audio/pretrained/asvspoof-finetuned/`（361MB）
> - 视频：`video/pretrained/best_model.pth`（245MB，准确率 85%）

### 第 3 步：管理后台

```bash
cd web-admin
npm install
npm run dev
# 访问 http://localhost:5173
# 首次使用需在登录页点击"注册管理员"创建一个管理员账号
```

### 第 4 步：微信小程序

1. 打开**微信开发者工具**
2. 导入项目 → 选择 `wechat-app/frontend` 目录
3. 无需修改配置（默认连接 `localhost:8080`）
4. 编译运行

## 端口一览

| 服务 | 端口 | 说明 |
|------|------|------|
| Spring Boot 后端 | 8080 | API 服务 |
| 音频检测 (Python) | 5000 | 音频伪造检测（`AUDIO_PORT` 覆盖） |
| 视频检测 (Python) | 5002 | 视频换脸检测（`VIDEO_PORT` 覆盖） |
| 管理后台 (Vite) | 5173 | Web 管理界面 |
| MySQL | 3306 | 数据库 |
| Qdrant（可选） | 6333 | 向量数据库 |

## 数据库

### 方式 A：使用 Docker（推荐）

```bash
docker compose up -d db qdrant
```

### 方式 B：本地 MySQL

```bash
mysql -u root -p
CREATE DATABASE safeguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

> Qdrant 不可用时，系统会自动使用内存回退模式，不影响演示。
>
> **RAG 知识库完整使用条件**：① Docker 启动 Qdrant 容器（自动）；② 配置 `SILICONFLOW_API_KEY` 环境变量（用于生成 embedding 向量）。缺一则知识问答降级为关键词匹配。详见"常见问题"。

## 依赖服务说明

| 服务 | 是否必需 | 不配置的影响 |
|------|---------|-------------|
| MySQL | **必需** | 后端无法启动 |
| Qdrant | 可选 | 自动内存回退，功能正常 |
| LLM API Key | 可选 | 文本分析、模拟诈骗功能不可用 |
| SiliconFlow Key | 可选 | RAG 检索降级为关键词匹配 |

### 获取 API Key（如需使用全部功能）

- **LLM API（文本分析、模拟诈骗）**：注册 SophNet / DeepSeek 获取 API Key，填入 `application.yml` 的 `llm.api-key`
- **SiliconFlow（RAG 向量检索）**：注册 SiliconFlow 获取 API Key，填入 `application.yml` 的 `siliconflow.api-key`
- 主要演示功能（音频检测、视频检测、知识库、后台管理）**不需要 API Key**

## 演示流程

1. 依次启动后端 → Python 服务 → 管理后台
2. 浏览器打开管理后台，登录查看知识库、统计数据
3. 微信开发者工具打开小程序，选择音频/视频文件进行检测
4. `test_data/` 目录下已有测试用的音频、视频、文本文件

## 常见问题

**Q: Python 依赖安装失败？**
A: 确保已安装 Python 3.11，使用虚拟环境安装：
```bash
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements.txt
```

**Q: face_recognition 安装报错？**
A: 需要 CMake 编译工具，但也已预编译好的 `dlib-bin` 包：
```bash
.\.venv\Scripts\python.exe -m pip install dlib-bin
```

**Q: 后端启动端口 8080 被占用？**
A: 上次关闭时进程未完全退出，手动杀掉：
```bash
netstat -ano | findstr :8080
taskkill /F /PID 查到的PID
```
> 早期版本曾自动 `taskkill /F /PID` 占用 8080 的进程，但该行为会误杀用户机器上其他服务（调试中的另一组 Spring Boot、API 工具等），已移除。如遇端口冲突请按上述方式手动处理。

**Q: 音频模型需要训练吗？**
A: 不需要。`pretrained/asvspoof-finetuned/` 已包含微调好的模型，直接可用。训练由组员单独负责。

**Q: 视频模型需要训练吗？**
A: 不需要。`pretrained/best_model.pth` 已包含训练好的模型（85% 准确率），直接可用。

**Q: 启动时出现 `Qdrant 批量插入失败: 400 ... value fixed_xxx is not a valid point ID`？**
A: 这是 `QdrantService` 已知 bug。Qdrant 1.7+ 严格要求 point ID 为 unsigned integer 或 UUID，而项目生成的 chunkId 形如 `"fixed_anti_fraud_knowledge.txt_0"` 含 `.` 字符。已在 1 个文件中修复：把 `point.put("id", chunkIds.get(i))` 改为 `point.put("id", toQdrantId(chunkIds.get(i)))`（用 `UUID.nameUUIDFromBytes()` 派生）。**注意**：即使修复了 Qdrant ID，如果 `SILICONFLOW_API_KEY` 仍未配置，向量本身是垃圾，语义检索仍然搜不准。需先配置 SiliconFlow Key 才能体验完整 RAG。

**Q: 管理后台默认账号密码是什么？**
A: 系统**没有默认管理员账号**。首次部署后，请打开登录页点击"注册管理员"创建第一个管理员账号（用户名、昵称、密码 ≥ 6 位）。

**Q: 管理后台 AI 安全助手如何使用？**
A: 右下角浮动按钮 → 输入问题 → 自动调用后端大模型流式接口（SSE）。需要 `application.yml` 中配置 `llm.api-key`（DeepSeek/SophNet），未配置时会返回降级提示。

**Q: Dashboard 趋势图/饼图是真实数据吗？**
A: 是。后端从 `detection_record` 表按日期+结果分组聚合，缺失日期补 0。`web-admin/src/views/Dashboard.vue` 调 `/admin/api/stats/trend?days=7|30` 与 `/admin/api/stats/distribution`。

**Q: 用户列表的 OpenID 为何只显示前 4 后 4？**
A: 为保护用户隐私，列表中 OpenID 默认脱敏显示（`xxxx…xxxx`），鼠标悬停 tooltip 可查看完整值。

## 技术栈

| 技术 | 用途 |
|------|------|
| Spring Boot 4.0 + Java 17 | 后端框架 |
| MyBatis-Plus + MySQL | 数据库 |
| Wav2Vec2 (HuggingFace) | 音频伪造检测 |
| XceptionNet (PyTorch) | 视频换脸检测 |
| DeepSeek / SophNet API | 大模型文本分析 |
| SiliconFlow API | 向量嵌入 + 重排序 |
| Qdrant | 向量数据库 |
| Vue 3 + Element Plus | 管理后台 |
| 微信小程序 + TDesign | 用户端 |