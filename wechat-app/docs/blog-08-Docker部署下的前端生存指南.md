# Docker 部署下的前端生存指南：微信小程序在微服务架构中的配置之道

## 前言

前一篇博客聊了 Mock 数据退役的故事。项目完成 Mock 移除后，紧接着就是部署上线。但 SafeGuard 不是那种"一个 Spring Boot jar 包打天下"的简单项目——它由 **6 个服务**组成：

| 服务 | 技术栈 | 端口 | 用途 |
|------|--------|------|------|
| MySQL | 8.0 | 3307 | 数据持久化 |
| Milvus | 2.4 | 19530 | 向量数据库（RAG 检索） |
| Spring Boot 后端 | Java 17 | 8080 | 主 API 服务 |
| 音频检测 | Flask + Wav2Vec2 | 5000 | AI 音频伪造检测 |
| 微信小程序前端 | 原生 + TDesign | — | 用户界面 |

当这些服务全部塞进 Docker 容器时，一个看似简单的问题浮出水面：**前端配置中的 `http://localhost:8080`，在容器世界里还走得通吗？**

答案是：走不通。但有意思的是，"走不通"的原因不止一个，而每一个原因背后都藏着一个分布式系统的设计原则。

## 一、localhost 陷阱

### 1.1 问题的表象

在本地开发时，前端配置非常简单：

```javascript
// config.js — 开发环境
const CONFIG = {
  API_BASE_URL: 'http://localhost:8080',
};
```

微信开发者工具打开，前端请求 `localhost:8080`，后端在宿主机上监听 8080 端口，一切正常。

但一旦后端跑进 Docker 容器，问题就来了：**微信小程序跑在手机或开发者工具中，请求的是 `localhost:8080`——这个地址指向的是手机本身，而不是运行 Docker 的电脑**。

这不是 Docker 的问题，这是**网络拓扑的变化**。

### 1.2 前端视角的"三层网络"

在 Docker 部署场景下，前端面临的网络环境分为三层：

| 层级 | 场景 | API 地址 | 说明 |
|------|------|----------|------|
| 开发态 | 微信开发者工具 + 本地后端 | `localhost:8080` | 前后端在同一台机器 |
| 真机调试 | 手机 + 电脑后端 | `192.168.x.x:8080` | 需填电脑局域网 IP |
| 生产部署 | 手机 + 云服务器 | `https://api.domain.com` | 需域名 + HTTPS |

我们的 `config.js` 用注释清晰地标注了这三种场景：

```javascript
// 开发环境: http://localhost:8080
// 真机测试: http://192.168.x.x:8080（改为你的电脑实际IP）
// ==========================================
API_BASE_URL: 'http://localhost:8080',
```

但这里有一个关键问题：**这个文件是要提交到 Git 仓库的，而每个开发者的 `API_BASE_URL` 都不一样**。我们的解决方案是约定"默认提交 `localhost`"，各开发者在本地按需修改。这不完美，但对小程序项目来说已经足够——毕竟配置是静态的，不需要运行时动态切换。

## 二、容器间通信：后端眼中的 localhost 和前端眼中的 localhost 不是同一个

这是 Docker 部署中最核心、也最隐蔽的问题。

### 2.1 问题全景

看项目的 [application-prod.yml](file:///d:/Git/SafeGuard/backend/src/main/resources/application-prod.yml)，你会发现一个模式：

```yaml
# 开发环境（application.yml）
audio:
  service:
    url: http://localhost:5000/audio/detect

# 生产环境（application-prod.yml）
audio:
  service:
    url: http://safeguard-audio:5000/audio/detect
```

同一个配置项，开发环境指向 `localhost:5000`，生产环境指向 `safeguard-audio:5000`。区别在哪？

当后端以 jar 包形式在宿主机上运行时，`localhost:5000` 指向宿主机的 5000 端口——音频服务正好在那里监听。但当后端跑在 Docker 容器里时，**容器内的 `localhost` 是容器自己的网络命名空间，不是宿主机的**。所以 `localhost:5000` 指向的是后端的容器内部——那里并没有运行音频服务。

### 2.2 Docker Compose 的 DNS 解析

Docker Compose 会自动为每个服务创建一个 DNS 记录，服务名就是容器名。所以在同一个 Compose 网络内，容器可以通过服务名互相访问：

```
backend 容器内:  ping safeguard-audio → 172.x.x.x（音频容器的 IP）
backend 容器内:  ping localhost       → 127.0.0.1（自己）
```

所以后端必须使用 `safeguard-audio:5000` 而不是 `localhost:5000` 来访问音频服务。

### 2.3 为什么不用一个配置覆盖所有？

这时你可能会想：为什么不直接在 `application.yml` 中用 `safeguard-audio:5000`，这样开发和生产就统一了？

因为**开发环境没有 Docker DNS**。在本地以 `mvn spring-boot:run` 启动后端时，`safeguard-audio` 这个域名不会被解析——它不是互联网域名，只是 Docker Compose 的内部 DNS 记录。

所以，两套配置是必须的。我们用 `Spring Profiles` 来解决：

```yaml
# application.yml — 默认配置（本地开发）
audio.service.url: http://localhost:5000/audio/detect

# application-prod.yml — 生产配置（Docker 部署）
# 通过 SPRING_PROFILES_ACTIVE=prod 激活
audio.service.url: http://safeguard-audio:5000/audio/detect
```

Dockerfile 中设置环境变量 `SPRING_PROFILES_ACTIVE=prod`，容器启动时自动加载生产配置。

## 三、前端视角的 Docker 部署：六个必须知道的事

以下是从前端角度总结的 Docker 部署要点：

### 3.1 后端端口映射：安全第一

```yaml
# docker-compose.yml
ports:
  - "3307:3306"   # MySQL：容器内3306，宿主机3307
  - "8080:8080"   # 后端：容器内8080，宿主机8080
  - "5000:5000"   # 音频：容器内5000，宿主机5000
  - "19530:19530" # Milvus：容器内19530，宿主机19530
```

注意 MySQL 的端口映射是 `3307:3306`——容器内 MySQL 使用默认的 3306，但宿主机的 3306 可能被本地 MySQL 占用，所以映射到 3307。前端不需要关心这个映射，因为**前端只访问后端 API（8080），不直连数据库**。

### 3.2 GPU 依赖：注释比删除好

最初的 `docker-compose.yml` 中音频服务的 GPU 配置是直接启用的：

```yaml
deploy:
  resources:
    reservations:
      devices:
        - driver: nvidia
          count: all
          capabilities: [gpu]
```

这在开发机上工作得很好（RTX 4060 Laptop GPU），但部署到没有 NVIDIA GPU 的服务器上就会直接崩溃——Docker Compose 会尝试分配不存在的 GPU 资源。

**修复方式不是删除，而是注释+说明**：

```yaml
# GPU 支持（可选，有 NVIDIA GPU 时取消注释）
# deploy:
#   resources:
#     reservations:
#       devices:
#         - driver: nvidia
#           count: all
#           capabilities: [gpu]
```

这个修复体现了一个原则：**配置应该是包容性的，不应假设所有部署环境都一样**。没有 GPU 的机器用 CPU 跑推理（虽然慢一点），有 GPU 的机器取消注释就能启用加速。

### 3.3 预训练模型挂载：匿名卷 vs Bind Mount

audio 服务的 Dockerfile 中包含了预训练模型目录，但因为 `.dockerignore` 可能排除大型文件，或者镜像层缓存问题，模型文件经常"丢"。最初的配置使用匿名卷：

```yaml
volumes:
  - audio_models:/app/pretrained  # ❌ 匿名卷：文件存在哪里不确定
```

改为 bind mount 后：

```yaml
volumes:
  - ./audio-training/pretrained:/app/pretrained  # ✅ 直接挂载：文件在哪一目了然
```

对前端来说，这个变更的启示是：**不要假设所有文件都适合打进镜像**。大型模型文件、配置文件应该通过挂载卷管理，前端虽然没有 Docker 镜像，但类似的思路体现在 `config.js` 中——配置不硬编码在代码里，而是通过 `project.config.json` 的 `env` 字段或构建脚本注入。

### 3.4 .env 文件：API Key 的第一道防线

```bash
# .env.example（提交到 Git）
DEEPSEEK_API_KEY=
SILICONFLOW_API_KEY=
WECHAT_APPID=
WECHAT_SECRET=
DB_PASSWORD=root123

# .env（不提交到 Git）— 开发者手动填写
DEEPSEEK_API_KEY=sk-xxxxx
SILICONFLOW_API_KEY=sk-xxxxx
```

这是一个标准的"模板 + 实际值"模式。`.env.example` 提交到 Git 作为模板，`.env` 在 `.gitignore` 中排除，每个部署者自己填写。部署脚本中自动检测 `.env` 是否存在，不存在则从模板复制：

```bash
check_env() {
  if [ ! -f .env ]; then
    cp .env.example .env
  fi
}
```

### 3.5 服务启动顺序：健康检查是关键

```yaml
backend:
  depends_on:
    db:
      condition: service_healthy  # ⚠️ 不是 service_started
    milvus:
      condition: service_healthy
```

这里的 `service_healthy` 比 `service_started` 更重要。"启动"不等于"就绪"——MySQL 进程跑起来了，但可能还没完成初始化。健康检查通过实际的 ping 命令确认服务真的可用：

```yaml
healthcheck:
  test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
  interval: 10s
  timeout: 5s
  retries: 5
```

**这对前端的启发是：不要假设 API 就绪。** `request.js` 中每一次请求都应该做好失败处理，后端在 Docker 环境下启动可能比本地慢得多（数据库初始化、模型加载等）。

### 3.6 一键部署脚本：给运维的"傻瓜式"工具

[deploy.sh](file:///d:/Git/SafeGuard/deploy.sh) 提供了 7 个子命令：

```bash
bash deploy.sh all       # 部署全部
bash deploy.sh core      # 仅核心服务（无音频）
bash deploy.sh audio     # 仅音频服务
bash deploy.sh logs      # 查看日志
bash deploy.sh status    # 查看状态
bash deploy.sh restart   # 重启
bash deploy.sh clean     # 清理
```

区分 `all` 和 `core` 是因为音频服务依赖 GPU 和预训练模型，不是所有部署环境都需要它。"核心服务"（MySQL + Milvus + 后端）已经能提供完整的 API 功能。

## 四、一次典型的 Docker 部署故障排查

把以上所有知识点串起来，看一个实际的部署故障场景：

### 场景：后端启动后，音频检测一直失败

**现象**：`POST /api/detection/audio` 返回 500，后端日志显示 `Connection refused: localhost:5000`。

**排查链路**：

1. **第一步：检查网络模式**
   - 后端容器内 `localhost:5000` 指向自己，不是音频容器
   - ✅ 修复：使用 `safeguard-audio:5000` 替代 `localhost:5000`
   - 🎯 根因：`application.yml` 使用 localhost，容器内不可用
   - 🔧 修复：新建 `application-prod.yml` 覆盖为容器名

2. **第二步：检查服务是否就绪**
   - 音频容器启动了，但 Wav2Vec2 模型加载需要 ~30 秒
   - 后端在音频就绪前就开始转发请求
   - ✅ 修复：后端添加重试机制，或依赖 `service_healthy`

3. **第三步：检查模型挂载**
   - 首次部署时未挂载 `pretrained/` 目录
   - 容器内没有预训练模型，推理直接报错
   - ✅ 修复：`docker-compose.yml` 中改为 bind mount

这三个问题层层递进，每个都对应一个 Docker 部署的核心概念：**容器网络 → 健康检查 → 数据卷管理**。

## 五、从 Docker 部署反观前端架构

Docker 部署看似是后端和运维的事，但它对前端架构有深远影响：

### 5.1 配置必须分层

Docker 环境的出现，迫使我们的配置从"单层"变为"多层"：

```javascript
// 本地开发
API_BASE_URL = 'http://localhost:8080'

// 真机调试
API_BASE_URL = 'http://192.168.1.100:8080'

// 生产环境（部署到云服务器后）
API_BASE_URL = 'https://api.safeguard.com'
```

微信小程序没有 runtime 环境变量注入的机制，所以我们选择了一种务实的做法：**`config.js` 提交默认值，开发者按需修改，在 `project.config.json` 中通过 `env` 字段支持不同构建配置**。

### 5.2 错误处理必须更健壮

Docker 环境下，服务启动顺序、网络延迟、资源竞争都放大了"后端不可用"的概率。前端的请求层因此变得更健壮：

- 401 处理（Token 过期）
- 429 处理（频率限制）
- 网络超时处理
- SSE 降级轮询

这些在本地开发时很少触发，但在 Docker 部署环境中是常态。

### 5.3 前端无需 Docker，但需要理解 Docker

微信小程序没有 Docker 镜像——用户直接访问部署好的服务，不关心服务是怎么组织的。但作为前端开发者，理解 Docker 的部署架构能帮你回答一个关键问题：

> **"当 API 请求失败时，是前端的问题、后端的问题、网络的问题，还是容器的问题？"**

有了 Docker 的视角，排查问题的链路变得更清晰：

```
小程序 → config.js 中的 API_BASE_URL → 宿主机 8080 端口
  → Docker 端口映射 → backend 容器 → service_healthy?
  → safeguard-audio:5000（容器 DNS）→ audio 容器 → 模型加载?
```

每一层都可能出问题，而理解每一层的职责是快速定位问题的关键。

## 六、总结

本次 Docker 部署改造前端涉及的变化很小——只是在 `config.js` 中添加了注释说明。但**前端代码变动小，不代表前端不需要理解 Docker**。

回顾整个 Docker 部署过程，有三个核心启发：

1. **localhost 在容器世界里是个相对概念** — 每个容器有自己的 localhost，指向自己而不是宿主机。这是分布式系统入门的"第一课"。

2. **配置分环境不是过度工程，而是必需品** — 当你的系统从一个进程变成多个容器，配置就必须从"一个配置走天下"变成"环境感知"。`application.yml` + `application-prod.yml` 的双配置模式，在 `config.js` 中的对应思路就是"开发环境用 localhost，真机调试用局域网 IP，生产用域名"。

3. **一键部署脚本是最值得的投资** — `deploy.sh` 只有 119 行，但它让部署从"记住 5 个 docker 命令"变成了"记住 1 个 bash 命令"。对团队来说，降低运维心智负担就是提高迭代效率。

最后，给前端同学一个建议：**下次后端同学说"项目已经 Docker 化了"的时候，不要只说"好的"，可以问一句：容器间用 hostname 还是 IP 通信？healthcheck 配了吗？** 这些问题会让你从一个"调接口的"变成一个"理解系统的"。
