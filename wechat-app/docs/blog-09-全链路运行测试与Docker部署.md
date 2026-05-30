# SafeGuard 全链路运行与测试：从前端视角看项目启动到 Docker 部署

## 前言

这是"诈骗克星"系列博客的最后一篇。前六篇博客覆盖了从前端页面构建、API 联调、Mock 数据退役到 Docker 部署的完整历程。本文从**前端开发者**的视角，记录如何完整地运行这个项目、执行测试、以及通过 Docker 部署上线。

SafeGuard 项目的架构可以概括为"两个前端 + 四个后端服务"：

```
                              ┌──────────────────────┐
                              │   微信小程序前端       │
                              │   (wechat-app/)       │
                              └──────────┬───────────┘
                                         │ HTTP (config.js)
                                         ▼
┌──────────────────────────────────────────────────────────┐
│                    Spring Boot 后端 (:8080)               │
│  ┌──────────┐  ┌──────────┐  ┌───────┐  ┌─────────┐  │
│  │ REST API │  │ Admin    │  │ JWT   │  │ Rate    │  │
│  │ /api/*   │  │ /api/admin│ │ Auth  │  │ Limiter │  │
│  └──────────┘  └──────────┘  └───────┘  └─────────┘  │
│              ┌──────────────────────────────────────┐  │
│              │  Static Files /admin/* ← Vue SPA    │  │
│              │  (admin-frontend/dist/)               │  │
│              └──────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
         │                    │                    │          ▲
         ▼                    ▼                    ▼          │
   ┌──────────┐        ┌──────────┐         ┌──────────┐   ┌────────────────┐
   │  MySQL   │        │  Qdrant  │         │  Audio   │   │  Vue 管理后台   │
   │  :3306   │        │  :6333   │         │  :5000   │   │  (admin-frontend)│
   └──────────┘        └──────────┘         └──────────┘   │  :5173 (dev)   │
                                                            │  /admin (prod) │
                                                            └────────────────┘
```

本文假设你拥有一个前端开发者应有的知识储备：能看懂 JavaScript 和 Java，会用微信开发者工具，但不需要深入了解 Spring Boot 或 Docker 内部机制。

## 一、本地开发环境搭建

### 1.1 需要安装的工具清单

| 工具 | 版本要求 | 用途 | 验证命令 |
|------|---------|------|----------|
| JDK | 17+ | 运行 Spring Boot 后端 | `java -version` |
| Maven | 3.9+ | 构建后端项目 | `mvn -version` |
| MySQL | 8.0+ | 持久化数据库 | `mysql --version` |
| Python | 3.8+ | 运行音频检测服务 | `python --version` |
| Node.js | 16+ | 运行 Vue 管理后台 + 安装依赖 | `node -v` |
| npm | 8+ | Vue 项目包管理 | `npm -v` |
| 微信开发者工具 | 最新版 | 运行小程序前端 | — |
| Docker Desktop | 4.x+ | 容器化部署（可选） | `docker info` |

### 1.2 项目结构速览

前端的同学第一次打开项目可能会被目录结构吓到。其实只需要关注几个关键路径：

```
SafeGuard/
├── wechat-app/                   # ← 前端同学的主要工作目录
│   ├── frontend/                 #    小程序源码（app.js + pages/ + utils/）
│   │   ├── utils/
│   │   │   ├── config.js         #    ★ 唯一的配置入口（改 IP 的地方）
│   │   │   ├── request.js        #    网络请求封装
│   │   │   ├── auth.js           #    微信登录 + JWT 管理
│   │   │   └── taskWatcher.js    #    SSE + 轮询双模监控
│   │   └── pages/
│   │       ├── index/            #    首页 + 检测历史
│   │       ├── detection/        #    检测页（音频/视频/文本）
│   │       ├── simulate/         #    模拟诈骗对话
│   │       ├── knowledge/        #    反诈知识库
│   │       └── result/           #    检测结果页
│   ├── project.config.json       #    小程序项目配置
│   └── docs/                     #    ★ 系列博客存放处
│
├── backend/                      # 后端（前端只需了解启动方式）
│   ├── src/main/java/            #    53 个 Java 文件
│   ├── src/main/resources/       #    配置 + SQL + 提示词模板
│   ├── src/test/                 #    90+ 测试方法
│   ├── pom.xml                   #    Maven 依赖
│   ├── Dockerfile                #    后端 Docker 构建
│   └── README.md                 #    后端说明
│
├── audio-training/               # 音频检测（Python，前端只需启动它）
│   ├── src/app.py                #    Flask 检测服务
│   ├── src/utils.py              #    音频处理工具
│   ├── src/train_wav2vec2.py     #    训练脚本
│   ├── src/infer_audio.py        #    单条推理
│   ├── Dockerfile                #    音频服务 Docker 构建
│   └── requirements.txt          #    Python 依赖
│
├── admin-frontend/                # ★ Vue 管理后台前端
│   ├── src/
│   │   ├── api/index.js           #    API 请求封装（axios + JWT）
│   │   ├── router/index.js        #    Vue Router（/admin/ 子路径）
│   │   ├── views/
│   │   │   ├── Login.vue          #    管理员登录页
│   │   │   ├── Dashboard.vue      #    统计概览（Chart.js 图表）
│   │   │   ├── Users.vue          #    用户管理
│   │   │   ├── Knowledge.vue      #    知识库
│   │   │   ├── Records.vue        #    检测记录
│   │   │   └── Models.vue         #    模型管理
│   │   └── assets/style.css       #    全局样式
│   ├── index.html
│   ├── package.json
│   ├── vite.config.js             #    Vite dev server + proxy
│   └── dist/                      #    构建产物（npm run build）
│
├── docker-compose.yml            # ★ 一键部署的核心编排文件
├── deploy.sh                     # ★ 一键部署脚本
├── .env.example                  #   API Key 配置模板
└── 修改记录.md                    #   项目全量修改记录
```

### 1.3 最简启动方式：三步跑通核心功能

**第一步：启动后端**

```bash
cd backend

# 确保 MySQL 已启动，创建 safeguard 数据库
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS safeguard;"

# 启动后端（自动建表 + 初始化数据）
mvn spring-boot:run
```

看到 `Started SafeGuardApplication` 日志说明启动成功。后端监听在 `localhost:8080`。

**第一次启动的等待时间**：Maven 首次运行会下载依赖（约 2-3 分钟），Spring Boot 启动约 15-20 秒。后续启动会快很多。

**第二步：选择启动前端**

本地开发时，两个前端可以同时跑：

```bash
# 前端 A：微信小程序
# 1. 打开微信开发者工具
# 2. 项目目录选择 wechat-app/（不是 SafeGuard/ 根目录！）
# 3. 确认 config.js 中 API_BASE_URL = 'http://localhost:8080'
# 4. 点击编译

# 前端 B：Vue 管理后台
cd admin-frontend
npm install      # 首次运行需要安装依赖
npm run dev      # 启动 Vite Dev Server，默认 :5173
```

管理后台开发访问 `http://localhost:5173/admin/`，Vite 会自动将 `/api/*` 请求代理到 `localhost:8080`。

**生产构建**（部署前执行）：

```bash
cd admin-frontend
npm run build    # 构建到 dist/ 目录
```

构建产物由 Spring Boot 的静态资源处理器提供，访问 `http://localhost:8080/admin/` 即可。

**第三步：启动音频检测（可选）**

```bash
cd audio-training
pip install -r requirements.txt
python src/app.py
```

音频服务监听在 `localhost:5000`。后端检测到音频服务可用后，会自动将音频检测请求转发给它。

### 1.4 前端开发者的"本地 MySQL"痛点

这是前端同学在本地开发时最常遇到问题的地方。后端需要 MySQL 8.0+，但很多前端同学的电脑上根本没有装 MySQL。

**方案一：用 Docker 仅启动数据库（推荐）**

```bash
# 只启动 MySQL，不启动后端
docker compose up -d db
```

这会在宿主机 3307 端口暴露 MySQL（容器内 3306 映射到 3307，避免和本地 MySQL 冲突）。后端的 `application.yml` 中数据库连接 URL 是：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/safeguard
```

注意这里用的是 `3306` 而不是 `3307`。如果使用 Docker MySQL（3307 端口），需要修改 `application.yml` 中的 URL 为 `jdbc:mysql://localhost:3307/safeguard`。

**方案二：使用 H2 内嵌数据库（仅限测试）**

切换到 `application-test.yml` 配置，使用 H2 内存数据库，不需要安装 MySQL：

```bash
cd backend
mvn spring-boot:run -Dspring-boot.run.profiles=test
```

但注意测试配置中音频服务的 URL 指向 `localhost:9999`（一个不存在的地址），音频检测功能不可用。适合纯 API 调试。

## 二、测试体系：18 个分组、90+ 个测试方法

作为前端开发者，你可能会觉得"测试是后端的事"。但这个项目的测试体系很值得一看——因为它覆盖了从前端 API 调用到底层 Mapper 的完整链路。

### 2.1 测试架构

测试文件 [SafeGuardApplicationTests.java](file:///d:/Git/SafeGuard/backend/src/test/java/com/sdu/safeguard/SafeGuardApplicationTests.java) 使用 **JUnit 5 + H2 内存数据库**，测试配置通过 `@ActiveProfiles("test")` 激活：

```java
@SpringBootTest
@ActiveProfiles("test")
class SafeGuardApplicationTests {
    // 18 个 @Nested 内部类，每个是一个测试分组
    // 90+ 个 @Test 方法
}
```

测试配置 [application-test.yml](file:///d:/Git/SafeGuard/backend/src/test/resources/application-test.yml) 的关键点：

| 配置项 | 测试环境值 | 说明 |
|--------|----------|------|
| 数据库 | H2 内存（MODE=MYSQL） | 无需安装 MySQL，每次测试独立 |
| LLM API | `localhost:9999` | Mock 地址，不调用真实 API |
| 音频服务 | `localhost:9999` | Mock 地址，不调用真实 Flask |
| 服务端口 | 0（随机端口） | 避免端口冲突 |

### 2.2 18 个测试分组一览

| 分组 | 测试方法数 | 覆盖内容 | 前端相关性 |
|------|-----------|----------|-----------|
| 1. 基础架构 | 13 | Spring 上下文、异常处理、JWT、限流器、PromptLoader | 高（JWT 认证） |
| 2. 实体与 DTO | 13 | 所有实体/DTO 的构造、序列化 | 中（确认接口字段） |
| 3. 数据库/Mapper | 10 | Mapper 查询、计数、条件过滤 | 低 |
| 4. 知识库服务 | 5 | CRUD、异常场景 | 高（知识库搜索 API） |
| 5. 用户服务 | 2 | 登录创建、查询 | 高（认证 API） |
| 6. JWT 工具 | 3 | Token 生成/验证/角色 | 高（Token 管理） |
| 7. 检测服务 | 5 | TaskManager、概率计算 | 高（检测 API） |
| 8. RAG 服务 | 6 | 关键词匹配、空查询 | 高（知识库搜索） |
| 9. CoT 推理 | 4 | 结果构建、边界值 | 低 |
| 10. ReAct 推理 | 3 | Thought 构建 | 低 |
| 11. 记忆服务 | 3 | 短期记忆增删查 | 低 |
| 12. 统计与音频 | 4 | 统计方法、活跃模型 | 中 |
| 13. Agent 编排 | 3 | 完整编排 | 低 |
| 14. 限流拦截器 | 1 | 基础拦截 | 高（429 处理） |
| 15. LLM 服务 | 1 | Prompt 构建 | 中 |
| 16. 控制器接口 | 6 | 反射校验 Controller 方法签名 | 高（API 路径） |
| 17. 音频模型数据 | 1 | 初始数据校验 | 低 |
| 18. 多模态检测 | 1 | 请求体结构 | 中 |

### 2.3 运行测试

```bash
cd backend

# 运行全部测试
mvn test

# 运行指定分组（通过 JUnit 5 @Tag 或类名过滤）
mvn test -Dtest=SafeGuardApplicationTests

# 测试编译验证（只编译测试代码，不运行）
mvn test-compile
```

### 2.4 那些失败的测试

当前测试结果：**67/86 通过，19 个预期失败**。这 19 个失败不是代码 Bug，而是测试环境限制导致的预期行为：

| 失败原因 | 涉及测试 | 说明 |
|----------|---------|------|
| H2 兼容性 | 数据库 Mapper 测试（约 10 个） | MySQL 的 `FIND_IN_SET`、`GROUP_CONCAT` 等函数在 H2 中不可用 |
| LLM API 不可用 | LLM/Agent/CoT/ReAct 测试（约 5 个） | 测试配置指向 `localhost:9999`，没有真实 API |
| 音频服务不可用 | 音频相关测试（约 4 个） | 测试配置指向 `localhost:9999`，没有真实 Flask 服务 |

**对于前端开发者的意义**：这些失败的测试告诉你三件事：
1. 测试用了 H2 而不是真实 MySQL，所以某些 SQL 特性不可用——**不影响真实部署**
2. 测试不调用真实 AI API——**不影响功能联调**
3. 测试中音频服务是 Mock 的——**本地开发时也可以跳过音频服务**

运行 `mvn test-compile`（只编译不运行）是验证后端代码是否修改正确的最快方式，约 30 秒。

## 三、Docker 部署：从"能跑"到"能上线"

### 3.1 部署架构

SafeGuard 的 Docker 部署不是简单的"把 jar 包塞进容器"，而是涉及 **4 个容器 + 1 个编排文件 + 1 个部署脚本**：

```
                    docker-compose.yml
                    ┌────────────────────────────┐
                    │  docker compose up -d       │
                    │  deploy.sh all/core         │
                    └────────────────────────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
    │    MySQL     │ │   Qdrant    │ │   Backend    │
    │  (image)     │ │  (image)    │ │  (build)     │
    │  :3307       │ │  :6333      │ │  :8080       │
    │  ↓:3306      │ │             │ │  ↓:8080      │
    └──────┬───────┘ └──────┬──────┘ └──────┬───────┘
           │                │               │
           └────────────────┴───────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │    Audio     │
                    │  (build)     │
                    │  :5000       │
                    │  ↓:5000      │
                    └──────────────┘
```

### 3.2 部署流程（从前端视角）

**阶段 1：准备工作（做一次就行）**

```bash
# 1. 安装 Docker Desktop（Windows/Mac）
#    下载 https://www.docker.com/products/docker-desktop/
#    确保切换到 Linux 容器模式

# 2. 配置 WSL2 内存（Windows 用户）
#    在 %UserProfile%\.wslconfig 中写入：
#    [wsl2]
#    memory=6GB
#    然后执行 wsl --shutdown 重启

# 3. 准备 API Key（在项目根目录）
cp .env.example .env
# 编辑 .env，填入 KEY（可选，不填也可以部署）
```

**阶段 2：首次部署（约 15-20 分钟）**

```bash
cd SafeGuard

# 先构建 Vue 管理后台（确保 dist/ 目录存在）
cd admin-frontend && npm run build && cd ..

# 一键部署所有服务
bash deploy.sh all
```

底层实际执行的是 `docker compose up -d --build`。首次部署的时间分布：

| 步骤 | 耗时 | 说明 |
|------|------|------|
| 拉取 MySQL 8.0 镜像 | 2-3 分钟 | 约 500MB，只需一次 |
| 拉取 Qdrant 镜像 | 1-2 分钟 | 约 200MB，轻量级向量数据库 |
| Maven 多阶段构建 | 3-5 分钟 | 下载依赖 + 编译 80 个源文件 |
| 音频服务 pip install | 2-3 分钟 | 下载 torch + transformers 等 |
| 容器启动初始化 | 30-60 秒 | MySQL 建表 + Qdrant 就绪 + 后端就绪 |

**总计时约 13-18 分钟。后续部署只需 10-30 秒（镜像已缓存）。**

**阶段 3：验证部署**

```bash
# 查看所有容器状态
bash deploy.sh status

# 健康的输出应该是：
#   safeguard-db       Up (healthy)
#   safeguard-qdrant   Up (healthy)
#   safeguard-backend  Up
#   safeguard-audio    Up

# 查看后端日志（确认没有异常）
bash deploy.sh logs backend

# 验证 API 是否可用
curl http://localhost:8080/api/audio/status
# 应该返回 JSON：{"code":0,"data":{...}}
```

**阶段 4：连接前端**

```bash
# 微信小程序
# 修改 wechat-app/frontend/utils/config.js
API_BASE_URL: 'http://localhost:8080'   # 开发者工具
# 或
API_BASE_URL: 'http://192.168.1.105:8080'  # 真机调试（改为实际IP）

# Vue 管理后台（生产模式）
# 直接浏览器访问 http://localhost:8080/admin/
# 默认账号 admin / admin123
#
# 开发模式（本地调试 UI）：
# cd admin-frontend && npm run dev
# 浏览器访问 http://localhost:5173/admin/
```

### 3.3 两个前端在部署后的验证清单

**微信小程序验证项**：

```
□ 首页加载 → 显示检测统计和历史记录
□ 自动登录 → 确认无 401 错误
□ 文本检测 → 输入"转账到安全账户"，返回分析结果
□ 音频检测 → 上传音频文件，返回检测结果
□ 模拟诈骗 → 选择场景，对话能流式输出
□ 知识库 → 搜索"公检法"，返回结果
□ 检测历史 → 多次检测后，首页历史列表能更新
```

**Vue 管理后台验证项**：

```
□ 登录页 → 浏览器访问 /admin/，显示 Vue 登录页
□ 登录 → admin/admin123，能跳转到 Dashboard
□ Dashboard → 4 个统计卡片显示数字
□ Dashboard → 4 个图表渲染正常（趋势图、饼图、柱状图）
□ Dashboard → 音频服务状态显示"在线"或"离线"
□ 用户管理 → 能看到微信登录的用户列表
□ 知识库 → 能看到反诈知识条目
□ 检测记录 → 能看到检测记录列表
□ 模型管理 → 能看到音频模型信息
```

### 3.4 两种部署模式的选择

| 模式 | 命令 | 启动的服务 | 什么时候用 |
|------|------|-----------|-----------|
| **全量模式** | `bash deploy.sh all` | MySQL + Qdrant + 后端 + 音频 | 完整功能验证、演示 |
| **核心模式** | `bash deploy.sh core` | MySQL + Qdrant + 后端 | 调试前端 UI、API 链路验证 |

核心模式的价值：音频服务是 GPU 密集型，启动慢（模型加载约 30 秒），资源占用高。如果只是调试前端界面或验证文本检测 API，完全不需要启动它。

### 3.5 部署中的典型故障及排查

以下是在实际部署中遇到的故障，按出现频率排序：

**故障 1：后端容器反复重启**

```
现象: docker ps 显示 safeguard-backend 状态为 "Restarting"
原因: MySQL 还没就绪，后端启动时连接数据库失败
排查: docker compose logs backend
解决: 等待 MySQL 健康检查通过后自动重启（默认配置了 restart: unless-stopped）
```

**故障 2：音频检测返回 500**

```
现象: 前端调用音频检测，返回服务器错误
原因: 后端使用 localhost:5000 访问音频服务，但容器内 localhost 指向自己
排查: 后端日志显示 "Connection refused: localhost:5000"
解决: 使用 application-prod.yml，将地址改为 safeguard-audio:5000
      确认 SPRING_PROFILES_ACTIVE=prod 已设置
```

**故障 3：管理后台页面空白或样式丢失**

```
现象: 访问 /admin/dashboard 只显示纯文本
原因: JWT 过滤器拦截了 CSS 静态资源路径
排查: 浏览器开发者工具 → Network → 看 CSS 请求是否 302 重定向到 /admin/login
解决: 将 /admin/css 加入 JwtAuthFilter 的 PUBLIC_PATHS
```

**故障 4：Docker 构建过程卡死**

```
现象: mvn package 阶段长时间无响应
原因: WSL2 内存不足（默认 2GB），Maven 被 OOM killer
排查: 查看 Docker Desktop 日志
解决: 在 .wslconfig 中设置 memory=6GB，执行 wsl --shutdown
```

**故障 5：小程序请求报 401**

```
现象: 所有 API 请求返回 401 Unauthorized
原因: Token 过期（72 小时有效期）
排查: 查看前端 Storage 中的 token 是否存在
解决: 清除小程序缓存，重新打开自动登录
```

### 3.6 向量数据库的选型故事：从 Milvus 到 Qdrant

项目初期向量数据库选用的是 **Milvus 2.4**，但在实际运行中遇到了几个问题：

| 问题 | 表现 | 影响 |
|------|------|------|
| **镜像过大** | Milvus 镜像约 1.5GB，含 etcd + MinIO 等多个组件 | 首次部署拉取耗时长（3-5 分钟），占磁盘空间 |
| **内存占用高** | Milvus Standalone 模式默认占用 2GB+ 内存 | 低配服务器（4GB 内存）上无法与其他服务共存 |
| **启动依赖多** | Milvus 依赖 etcd（嵌入模式仍有额外进程） | 容器启动慢，健康检查需要 30 秒以上 |
| **SDK 版本兼容性** | `milvus-sdk-java` 2.4.0 移除旧版 API，与 Spring Boot 4.x 存在版本摩擦 | 需要降级 SDK、手写 REST API 调用 |

这些问题在项目后期愈发突出——当我们需要在团队成员的笔记本上做演示时，Milvus 的重资源消耗成了拦路虎。特别是对前端同学来说，每次重启 Docker 都要等 Milvus 两三分钟就绪，非常影响调试效率。

**为什么切换到 Qdrant？**

| 对比维度 | Milvus 2.4 | Qdrant |
|---------|-----------|--------|
| 镜像大小 | ~1.5GB（含 etcd+MinIO） | ~200MB（纯二进制） |
| 内存占用 | 1.5-2.5GB | 200-512MB |
| 启动时间 | 20-40 秒 | 2-5 秒 |
| API 方式 | gRPC（需 SDK）+ HTTP | 纯 HTTP REST API |
| Java 集成 | milvus-sdk-java（版本兼容问题） | RestTemplate 直调，零额外依赖 |
| 部署复杂度 | 需配置 etcd/存储/MinIO | 单容器，挂载即可 |
| Docker 资源限制 | 难以限制（多进程架构） | 支持 `mem_limit: 512m` |
| 社区活跃度 | 高（Linux Foundation 项目） | 高（增长迅速） |

切换带来的直接收益：

1. **Docker Compose 配置大幅简化** — Milvus 的 `etcd` 嵌入配置 + 健康检查参数 + 环境变量从 30 行压缩到 Qdrant 的 15 行
2. **启动速度从 30 秒降到 3 秒** — 后端不再需要等待 Milvus 就绪，开发调试效率提升明显
3. **内存从 2GB 降到 512MB** — 低配服务器（如 4GB 内存的云主机）也能同时跑 MySQL + Qdrant + 后端
4. **零额外依赖** — 移除 `milvus-sdk-java` 和 `grpc-netty-shaded` 两个依赖包

**对前端的影响**：Qdrant 和 Milvus 的切换对前端**完全透明**——前端只通过后端 API（`/api/rag/query`）进行知识检索，不关心底层用的是哪个向量数据库。启动 Docker 后的等待时间从 2 分钟缩短到 30 秒，调试体验更好。`docker-compose.yml` 中的服务名从 `milvus` 改为 `qdrant`，端口从 `19530` 改为 `6333`，但这些对前端代码没有影响，因为前端只访问后端的 8080 端口。

**总结**：Milvus 适合大规模生产环境（数亿向量、分布部署），而 Qdrant 更适合中小型项目和本地开发。对于 SafeGuard 这个级别的反诈系统（知识库约 500 条、向量维度 1024），Qdrant 是更务实的选择。

## 四、项目运行的各种模式对比

| 运行模式 | 后端 | 数据库 | 音频 | 小程序前端 | Vue 管理后台 | 适用场景 |
|---------|------|--------|------|-----------|-------------|---------|
| **纯本地开发** | `mvn spring-boot:run` | 本地 MySQL | `python app.py` | 开发者工具 | `npm run dev` (:5173) | 日常开发 |
| **Docker 核心** | Docker 容器 | Docker MySQL | 无 | 开发者工具 | `npm run dev` | 调试前端 UI |
| **Docker 全量** | Docker 容器 | Docker MySQL | Docker 容器 | 开发者工具 | `npm run build` + /admin | 完整功能验证 |
| **真机调试** | 本地或 Docker | 同上 | 同上 | 手机扫码 | 手机浏览器 :5173 | 移动端体验 |
| **生产部署** | 云服务器 Docker | 同上 | 同上 | 审核发布 | `npm run build` + /admin | 正式上线 |

我最常用的模式是 **纯本地开发** + **Docker 核心** 的组合：本地跑后端写代码，用 Docker 跑数据库省去安装 MySQL 的麻烦。

## 五、前端开发者的"运维清单"

项目部署上线后，前端开发者需要关注以下运维指标：

### 5.1 后端日志中的前端线索

```bash
# 查看所有请求日志
bash deploy.sh logs backend | grep "RequestMethod"

# 查看认证失败记录
bash deploy.sh logs backend | grep "401"

# 查看频率限制触发
bash deploy.sh logs backend | grep "Rate limit"

# 查看音频检测错误
bash deploy.sh logs backend | grep "audio"
```

### 5.2 管理后台的健康信号

登录 Vue 管理后台 Dashboard，重点关注：

| 指标 | 健康值 | 异常信号 |
|------|--------|---------|
| 音频服务状态 | ✅ 正常 | ❌ 离线 |
| 检测总数 | ✅ 持续增长 | ⚠️ 长时间无增长 |
| 错误日志 | ✅ 无异常 | ❌ 大量 error 日志 |

### 5.3 小程序端的健康信号

| 指标 | 健康值 | 异常信号 |
|------|--------|---------|
| 首页加载 | ✅ 显示数据 | ❌ 白屏 / 网络错误 |
| 自动登录 | ✅ 无感 | ❌ 频繁弹 401 |
| API 响应 | ✅ < 2s | ❌ 超时 / 429 |

## 六、项目演进中的测试与部署故事

回顾整个项目的测试与部署过程，有几个值得记录的时刻：

### 6.1 测试暴露的"配置漂移"

后端从 JPA 切换到 MyBatis-Plus 时，`application.yml` 中的数据库密码被误改为空字符串（从 Gitee 仓库同步的遗留问题）。运行 `mvn test` 时，H2 测试通过了——但部署时 MySQL 连接失败。这类"测试环境通过，生产环境失败"的问题，根源在于**测试用的是 H2，不校验 MySQL 连接配置**。

解决方案是添加一个"配置校验测试"：在测试中检查 `application.yml` 的 API Key 默认值不为空字符串（防止误提交密钥），以及数据库 URL 包含正确的参数。

### 6.2 Docker 构建中的"COPY 陷阱"

后端 Dockerfile 使用了 Maven 多阶段构建：

```dockerfile
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/SafeGuard-0.0.1-SNAPSHOT.jar /app/app.jar
```

有一次，`COPY --from=build` 的路径拼写错误（少了个 `from=`），导致运行镜像中没有 jar 包，容器启动失败。这个问题在 `deploy.sh` 构建时暴露——构建成功（Maven 编译通过），但容器启动就崩溃。

教训：**Docker 构建成功 ≠ 部署成功**。必须验证容器是否能正常启动。

### 6.3 "先跑测试，再部署"的工作流

这次项目让我养成了一个习惯：每次修改后端代码后，先跑 `mvn test-compile`（编译验证，约 30 秒），再跑 `mvn test`（全量测试，约 2 分钟），确认测试通过后再部署。这个流程虽然简单，但至少避免了 5 次"改了一行代码，部署后报错"的情况。

### 6.4 关于"预存失败"的沟通

项目中 19 个测试是"预存失败"——团队成员都知道它们会因为 H2 兼容性和 Mock API 不可用而失败。这本身是一个合理的权衡（使用 H2 而不是 MySQL 作为测试数据库，换来了测试速度和环境一致性）。但对新加入的开发者来说，看到 `67/86 通过` 的第一反应是"项目有 Bug"。

后来我们添加了一个测试文档说明：[SafeGuardApplicationTests.java](file:///d:/Git/SafeGuard/backend/src/test/java/com/sdu/safeguard/SafeGuardApplicationTests.java) 的类注释中写明了哪些失败是预期的。这是一个好的实践：**测试结果中的"预期失败"和"意外失败"要能被清晰区分**。

## 七、系列总结

从第一篇博客到最后一篇，SafeGuard 项目经历了一个典型的前端项目演进过程：

| 阶段 | 博客 | 核心变化 |
|------|------|---------|
| 页面构建 | 整合文档 | 7 个页面 + TDesign 组件库 |
| API 打通 | #6 前后端联调 | 17 个 API 首次对接 |
| 技术决策 | #6 ConcurrentHashMap vs Caffeine | 任务管理器的方案选型 |
| Mock 移除 | #7 Mock 数据退役 | 500+ 行 Mock 代码删除 |
| 后端重构 | #7 Caffeine 替换 | 4 个场景的临时方案升级 |
| Docker 部署 | #8 双前端适配 | 小程序 + 管理后台的容器化 |
| **完整运行** | **#9 全链路运行** | **本地 + 测试 + 部署的完整流程** |

作为前端开发者，在这个项目中最重要的一件事是：**不要把自己局限在"前端"的范围内**。理解后端的架构决策（为什么用 Caffeine 而不是 ConcurrentHashMap）、理解测试体系（为什么 67/86 通过是正常的）、理解部署架构（为什么 localhost 在容器里不 work）——这些理解解决的不是"怎么写代码"的问题，而是"代码跑起来后怎么办"的问题。

最后，用一句话总结整个系列：**前端不只是一堆页面，而是整个系统中最接近用户的那一层。理解全栈，才能做好前端。**
