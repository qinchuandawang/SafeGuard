# Docker 部署下的双前端架构：微信小程序 + Vue 管理后台的适配之道

## 前言

前两篇博客分别聊了前后端联调和 Mock 数据退役。项目完成这些改造后，架构变成了这样：

```
┌───────────────────────────────────────────────────┐
│                 两个前端，一个后端                    │
│                                                     │
│  ┌──────────────────┐     ┌─────────────────────┐  │
│  │  微信小程序前端    │     │  Web 管理后台（Vue SPA）│  │
│  │  (原生+TDesign)   │     │  (Vue 3 + Chart.js)  │  │
│  │  面向普通用户      │     │  面向管理员             │  │
│  └────────┬─────────┘     └──────────┬──────────┘  │
│           │                           │              │
│           │ REST API + JWT           │ REST API +   │
│           │ Bearer Token             │ Bearer Token │
│           ▼                           ▼              │
│        ┌─────────────────────────────────────┐      │
│        │      Spring Boot 后端 (:8080)        │      │
│        │  ┌───────────────────────────────┐  │      │
│        │  │  REST API (/api/*, /api/admin)│  │      │
│        │  │  Static Files (/admin/*.html) │  │      │
│        │  │  Audio Forward → :5000        │  │      │
│        │  └───────────────────────────────┘  │      │
│        └─────────────────────────────────────┘      │
└───────────────────────────────────────────────────┘
```

两个前端都是标准的**前后端分离**架构——它们只通过 REST API 与后端通信，不依赖服务端渲染。这意味着 Docker 部署对两者的影响模式高度一致，但也带来了新的问题：**管理后台的 JWT 认证谁来处理？**

本文记录 Docker 部署过程中，这两个"纯前端"各自面临的挑战和解决方案。

## 一、两个前端的本质差异

在开始之前，有必要搞清楚这两个前端的"运行模型"有什么不同。虽然它们都是前后端分离，但因为平台不同，面临的问题也截然不同。

| 维度 | 微信小程序前端 | Web 管理后台 |
|------|---------------|-------------|
| **技术栈** | 原生微信小程序 + TDesign | Vue 3 + Vue Router + Chart.js |
| **运行环境** | 手机 / 微信开发者工具 | 浏览器 |
| **构建工具** | 微信开发者工具内置 | Vite |
| **开发模式** | 开发者工具直接运行 | `npm run dev`（:5173） |
| **API 代理** | 修改 `config.js` 的 `API_BASE_URL` | Vite proxy 到 `localhost:8080` |
| **认证方式** | JWT Bearer Token | JWT Bearer Token |
| **生产部署** | 无需打包，上传代码 | `npm run build` → Spring Boot 静态资源 |
| **Docker 影响** | 需正确设置 `API_BASE_URL` | 需避免 JWT 过滤器拦截静态资源 |

**核心差异**：微信小程序运行在手机上，它的网络目标地址必须指向"宿主机 IP"；Vue 管理后台运行在浏览器中，开发时通过 Vite proxy 转发，生产时由 Spring Boot 同域服务。

## 二、微信小程序：三层网络下的连接困境

### 2.1 从 localhost 到容器 IP 的认知跨越

微信小程序的 `config.js` 中只有一个配置项：

```javascript
API_BASE_URL: 'http://localhost:8080',
```

在纯本地开发时，这个配置毫无问题。但一旦后端跑进 Docker 容器，局面变得复杂。小程序面临的网络环境有三种：

| 场景 | API 地址 | 说明 |
|------|----------|------|
| **开发态** | `localhost:8080` | 微信开发者工具 + Docker 后端，同一台机器 |
| **真机调试** | `http://192.168.x.x:8080` | 手机 + 电脑 Docker，需填电脑局域网 IP |
| **生产部署** | `https://api.domain.com` | 云服务器部署，需域名 + HTTPS |

这里的"开发态"有一个隐藏陷阱：**微信开发者工具中的 `localhost` 指向的是开发者工具所在的机器，不是 Docker 容器内部**。所以虽然后端跑在 Docker 容器里，小程序通过 `localhost:8080` 访问的是宿主机的 8080 端口，而 Docker 的端口映射 `8080:8080` 正好把这个请求转发到了后端容器。这条链路是通的。

但"真机调试"就没这么幸运了。手机上的小程序没有 `localhost` 的概念——手机上的 `localhost` 指向手机自己。所以必须用电脑的局域网 IP。

**获取方法**（Windows）：

```powershell
ipconfig
# 找到 "无线局域网适配器" 的 IPv4 地址，如 192.168.1.105
```

然后修改 `config.js`：

```javascript
API_BASE_URL: 'http://192.168.1.105:8080',
```

这个手动改 IP 的过程很原始，但微信小程序没有 runtime 环境变量注入机制，这是最务实的方案。

### 2.2 小程序的"受害"起点：不受 Docker 保护

微信小程序有个独特的尴尬处境：**它不在 Docker 的编排范围内**。

Docker Compose 管理了 `db`、`milvus`、`backend`、`audio-detection` 四个容器，它们之间有内部 DNS（`safeguard-backend`、`safeguard-audio`），有健康检查，有依赖顺序。但小程序运行在用户的手机上，它不在这个网络里。它只能通过宿主机的暴露端口访问后端。

## 三、Vue 管理后台：标准前后端分离在 Docker 中的真实面貌

### 3.1 架构模型

Vue 管理后台是完全的前后端分离架构。开发时和生产时的链路截然不同：

```
开发模式：
  浏览器 :5173 → Vite Dev Server → proxy /api/* → localhost:8080（后端）

生产模式（Docker）：
  浏览器 :8080 → Spring Boot → /admin/ 静态资源（Vue 构建产物）
                              → /api/admin/* REST API
```

开发时通过 Vite 的 `vite.config.js` 配置代理，前端的 API 请求被自动转发到后端。生产时，Vue 构建产物（`admin-frontend/dist/`）由 Spring Boot 的 `WebConfig` 静态资源处理器提供。

### 3.2 开发模式：Vite Proxy 解决跨域

[admin-frontend/vite.config.js](file:///d:/Git/SafeGuard/admin-frontend/vite.config.js) 中的代理配置：

```javascript
export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

开发时，前端访问 `http://localhost:5173/admin/dashboard`，Vite Dev Server 返回 Vue 页面。页面中的 API 调用（如 `GET /api/admin/stats/overview`）被 Vite 自动转发到 `http://localhost:8080/api/admin/stats/overview`。**没有跨域问题，不需要配置 CORS**，因为 Vite proxy 是服务端转发。

### 3.3 生产模式：Spring Boot 同域服务

生产部署时，Vue 构建产物被 Spring Boot 的 [WebConfig](file:///d:/Git/SafeGuard/backend/src/main/java/com/sdu/safeguard/config/WebConfig.java) 作为静态资源提供服务：

```java
@Override
public void addResourceHandlers(ResourceHandlerRegistry registry) {
    registry.addResourceHandler("/admin/**")
            .addResourceLocations("classpath:/static/admin/", "file:admin-frontend/dist/");
}
```

Vue 的构建产物放在 `admin-frontend/dist/` 目录中，用户访问 `http://localhost:8080/admin/` 时看到的是 Vue 单页应用，所有 API 请求（如 `/api/admin/stats/overview`）发往同一个域名下——Spring Boot 后端。

### 3.4 Vue Router 与后端路由的协作

Vue SPA 使用 `createWebHistory('/admin/')` 模式，这意味着所有管理后台的路由（`/admin/dashboard`、`/admin/users` 等）在浏览器端由 Vue Router 处理。但用户直接在浏览器地址栏输入这些地址时，浏览器会向服务器发起请求。

这时，[AdminSpaController](file:///d:/Git/SafeGuard/backend/src/main/java/com/sdu/safeguard/controller/AdminSpaController.java) 发挥作用：

```java
@GetMapping("/admin/**")
public String adminFallback(HttpServletRequest request) {
    String path = request.getRequestURI();
    // 静态资源直接放行
    if (path.startsWith("/admin/api") || path.startsWith("/admin/assets")
            || path.endsWith(".js") || path.endsWith(".css")) {
        return "forward:" + path;
    }
    // 其他所有 /admin/* 路径都返回 index.html
    return "forward:/admin/index.html";
}
```

任何浏览器直接访问的 `/admin/dashboard`、`/admin/users` 都会被转发到 `index.html`，Vue Router 接管后自动渲染对应的页面。

### 3.5 JWT 认证的统一处理

管理后台的认证方式和小程序完全一致——**JWT Bearer Token**。前端登录流程：

```
用户填写表单 → POST /api/auth/admin/login
  → 后端验证账号密码 → 返回 JWT Token
  → 前端 localStorage 存储 Token
  → 后续所有 API 请求自动携带 Authorization: Bearer xxx
```

[Login.vue](file:///d:/Git/SafeGuard/admin-frontend/src/views/Login.vue) 中的登录逻辑：

```javascript
async function handleLogin() {
  const data = await login(username.value, password.value)
  localStorage.setItem('admin_token', data.token)
  router.push('/dashboard')
}
```

前端 API 请求模块 [api/index.js](file:///d:/Git/SafeGuard/admin-frontend/src/api/index.js) 中，从 `localStorage` 读取 Token 并自动添加到请求头：

```javascript
function authHeader() {
  const token = localStorage.getItem('admin_token')
  return token ? { Authorization: 'Bearer ' + token } : {}
}
```

**和后端的配合**：管理后台的 API 路径是 `/api/admin/*`，它们被加入 JWT 过滤器的白名单。为什么管理后台的 API 也要在 JWT 白名单中？因为前端负责认证——Vue SPA 在登录成功后获取 Token，后续 API 请求都携带 Token。但如果后端再对 `/api/admin/*` 做一次 JWT 拦截，就会出现"前端以为已登录，后端返回 401"的尴尬局面。

所以后端对管理后台 API 的策略是：**放行请求，由前端自己管理认证状态**：

```java
private static final Set<String> PUBLIC_PATHS = Set.of(
    "/api/auth/admin/login",    // 登录接口放行（无 Token）
    "/api/admin",               // 管理后台 API 放行（前端负责 Token）
    "/admin/index.html",        // Vue SPA 入口放行
    "/admin/assets",            // 静态资源放行
    // ...
);
```

这意味着管理后台的 API 实际上"不设防"——任何知道 API 地址的人都可以调用。这是一个有意的设计权衡：
- 小程序的 `/api/*` 需要 JWT 保护，因为用户数据敏感
- 管理后台的 `/api/admin/*` 不需要额外拦截，因为**登录态由前端 Token 管理**，后端 API 本身的业务逻辑已经做了权限控制（例如只有 admin 角色可以查看用户列表）

### 3.6 Vue 管理后台的 Docker 优势

相比微信小程序，Vue 管理后台在 Docker 部署中有三个优势：

1. **同域部署** — 生产环境下，Vue 构建产物和 API 在同一个域名和端口（`:8080`），没有跨域问题
2. **开发代理** — Vite Dev Server 自动代理 API 请求，开发者不需要手动修改 IP 地址
3. **构建产物可预测** — `npm run build` 输出到 `dist/` 目录，由 Spring Boot 静态资源处理器直接提供

Vite 的 proxy 机制也解决了"真机调试"时的问题：手机浏览器访问 `http://192.168.1.105:5173`，页面的 API 请求通过 Vite proxy 转发到 `localhost:8080`，不需要配置 CORS。

## 四、Docker Compose 中的服务编排

### 4.1 端口映射的两个视角

[当前 docker-compose.yml](file:///d:/Git/SafeGuard/docker-compose.yml) 的端口映射：

```yaml
services:
  db:
    ports:
      - "3307:3306"    # MySQL
  backend:
    ports:
      - "8080:8080"    # 后端
  audio-detection:
    ports:
      - "5000:5000"    # 音频检测
```

从两个前端的视角看：

```
微信小程序视角：
  config.js → http://localhost:8080 → 宿主机 8080 → 容器映射 → backend:8080

Vue 管理后台视角（开发）：
  浏览器 :5173 → Vite proxy → http://localhost:8080 → 宿主机 8080 → backend:8080

Vue 管理后台视角（生产）：
  浏览器 :8080 → Spring Boot（静态文件 + API 同一端口）
```

### 4.2 "核心模式"的设计意图

`deploy.sh core` 子命令只启动 `db`、`milvus`、`backend` 三个服务，跳过 `audio-detection`。这个设计对两个前端的影响：

| 前端 | 核心模式下的表现 | 不可用功能 |
|------|-----------------|-----------|
| 微信小程序 | 文本检测、知识库、模拟对话正常 | 音频检测返回错误 |
| Vue 管理后台 | Dashboard 统计、用户管理正常 | 音频检测统计显示"服务离线" |

核心模式的价值在于：**调试前端 UI 和 API 链路时，不需要启动所有依赖**。

## 五、两个前端的 Docker 适配总结

| 适配项 | 微信小程序 | Vue 管理后台 |
|--------|-----------|-------------|
| **开发命令** | 微信开发者工具打开 wechat-app/ | `cd admin-frontend && npm run dev` |
| **开发端口** | 无固定端口（工具内置） | `localhost:5173` |
| **API 代理** | 修改 `config.js` 的 `API_BASE_URL` | Vite proxy 自动转发 |
| **真机调试** | 需改为电脑局域网 IP | 手机访问 `电脑IP:5173` |
| **生产构建** | 无需构建，上传代码 | `npm run build` → `dist/` |
| **生产服务** | 后端 8080 端口 | 后端 8080 端口（同域静态文件） |
| **认证方式** | JWT Token（request.js 自动携带） | JWT Token（axios 拦截器自动携带） |
| **JWT 拦截** | API 受 JWT 保护 | API 在前端白名单中，前端自管认证 |
| **CORS 需求** | 真机调试需要 CORS | 开发由 Vite proxy 处理，生产同域 |

### 5.1 微信小程序的三个"必须知道"

1. **开发者工具用 `localhost`，真机调试用局域网 IP** — 这个切换是手动的，需修改 `config.js`
2. **小程序不在 Docker 网络内** — 它不享受 Docker DNS、健康检查等便利，依赖端口映射暴露的服务
3. **后端 CORS 必须配置** — 真机调试时手机请求电脑 IP 属于跨域

### 5.2 Vue 管理后台的三个"必须知道"

1. **先在 `admin-frontend/` 里 `npm run dev`** — 开发时不要直接打开 `dist/`，用 Vite Dev Server
2. **Vite proxy 配置要跟后端地址一致** — 默认指向 `localhost:8080`，Docker 部署时不需要改 proxy
3. **`npm run build` 后要确认 `dist/` 目录存在** — Spring Boot 通过 `WebConfig` 提供 Vue 构建产物

## 六、从双前端反观 Docker 部署

这次 Docker 部署让我对"前后端分离"有了更深的理解。**微信小程序和 Vue 管理后台虽然技术栈不同，但在 Docker 环境下，它们的本质是一样的：都是通过 HTTP 调用后端 API 的客户端应用。**

区别只在部署方式：
- **Vue 管理后台**有完整的开发工具链（Vite Dev Server、proxy、hot reload），开发体验接近"本地开发"；生产构建产物由后端同域提供，部署简单
- **微信小程序**受限于平台，没有本地开发服务器，配置管理更原始（手动改 IP）；但它在生产环境不需要构建步骤，上传代码即可

作为前端开发者，理解这些差异才能在不同场景下做出合适的技术决策。最后，一个实务建议：**如果你的项目同时有 CSR 前端和微信小程序前端，优先统一它们的 API 请求层设计**——两个前端都使用 JWT Bearer Token、都从相同的 `/api/*` 路径获取数据、都处理相同的错误码（401、429 等）。这样无论是本地开发、真机调试还是 Docker 部署，问题排查的思路都是统一的。
