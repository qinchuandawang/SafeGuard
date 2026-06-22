# 诈骗克星微信小程序

SafeGuard 的微信小程序端，源码位于 `wechat-app/frontend`。小程序连接 Spring Boot 后端，完成音频/视频/文本检测、模拟诈骗体验、知识库和 AI 问答等演示流程。

## 运行方式

1. 先启动后端和 AI 推理服务：

```bash
# 后端
cd backend
mvnw.cmd spring-boot:run

# 音频 + 视频推理服务
cd ai-services
python run.py
```

2. 打开微信开发者工具。
3. 导入目录：`wechat-app/frontend`。
4. 本地演示默认连接 `http://localhost:8080`，配置文件为：

```text
wechat-app/frontend/utils/config.js
```

5. 在微信开发者工具中关闭“校验合法域名、web-view、TLS 版本以及 HTTPS 证书”后编译运行。

## 服务依赖

| 服务 | 默认地址 | 说明 |
|------|----------|------|
| Spring Boot 后端 | `http://localhost:8080` | 小程序所有业务 API |
| 音频检测服务 | `http://localhost:5000` | 由后端转发调用 |
| 视频检测服务 | `http://localhost:5002` | 由后端转发调用 |
| Qdrant | `http://localhost:6333` | RAG 向量库，可内存降级 |

## 页面与功能

| 页面 | 路径 | 功能 |
|------|------|------|
| 首页 | `pages/index/index` | 统计概览、防骗提醒、快捷入口 |
| 检测页 | `pages/detection/detection` | 音频、视频、文本、多模态检测 |
| 结果页 | `pages/result/result` | 展示检测结果、风险说明和建议 |
| 模拟页 | `pages/simulate/simulate` | 诈骗话术模拟对话 |
| 知识库 | `pages/knowledge/knowledge` | 文章列表、搜索、AI 智能问答 |
| 文章详情 | `pages/article/article` | 防骗文章内容 |
| 关于页 | `pages/about/about` | 项目信息 |

## 关键配置

- `frontend/utils/config.js`：后端 API 地址。
- `frontend/utils/request.js`：请求封装、token 注入、失败降级。
- `frontend/utils/auth.js`：微信登录与演示匿名登录逻辑。
- `frontend/app.json`：页面、tabBar 和 TDesign 组件声明。

## 注意事项

- 开发者工具本地运行可以使用 `localhost`。
- 真机预览不能使用 `localhost`，需要把 `API_BASE_URL` 改为电脑局域网 IP，例如 `http://192.168.x.x:8080`。
- 知识库、统计和模拟剧本已做演示兜底；后端短暂不可用时页面不会直接空白。
- 小程序端不直接加载音频/视频模型，模型由 `ai-services` 的 Flask 服务加载，后端负责转发检测请求。

## 相关文档

- 项目总览：[../README.md](../README.md)
- 后端说明：[../backend/README.md](../backend/README.md)
- AI 推理服务：[../ai-services/README.md](../ai-services/README.md)
