# 前后端联调实战：从零打通反诈检测系统的全栈链路

## 前言

前五篇博客分别介绍了"诈骗克星"小程序的页面构建、检测能力、模拟对话、知识库和结果展示。然而这些前端能力一直依赖本地 Mock 数据运行——就像建好了精美的仪表盘，却没有接入真正的传感器。本文记录我们首次将前端与 Java Spring Boot 后端（SafeGuard）打通的全过程，涵盖 17 个 API 的对齐、三大核心技术决策，以及联调中暴露的真实 Bug 修复。

## 一、联调全景：从 0 到 17 个 API

前后端分离项目的第一次联调，本质上是一次**契约对齐**。前端期望的数据结构和后端实际返回的 JSON 往往存在差异，我们通过一份接口文档逐条核对，最终梳理出 17 个 API 的完整映射。

改造分三个阶段推进：

| 阶段 | 目标 | 新增/改造 |
|------|------|-----------|
| Phase A | 最小可行联调 | 新增 8 个 API，前后端路由对齐 |
| Phase B | 视频检测异步化 | 任务管理 + SSE 进度推送 |
| Phase C | LLM 流式输出 | DeepSeek SSE 透传至小程序 |

全阶段新增后端文件 4 个、修改文件 10 个、新增接口 8 个；前端修改文件 9 个，共计约 1150 行改动。

## 二、技术决策：为什么用 ConcurrentHashMap 而不是 Caffeine？

Phase B 需要一个内存级的任务管理器来跟踪异步检测任务的状态。常见方案有两种——`ConcurrentHashMap` 和 Caffeine 缓存。我们最终选择了前者，原因涉及三个层面的考量。

### 生命周期语义不匹配

Caffeine 的核心抽象是**缓存**——数据写入后自动过期、按策略驱逐，调用者只关心"存"和"取"，不关心条目何时消失。但我们的任务管理器管理的是**有状态工作流**：

```
create → updateProgress → complete/fail → cleanup
```

每个任务持有一个 `SseEmitter` 长连接实例，需要在其生命周期内主动推送事件。如果交给 Caffeine 的 TTL 机制自动驱逐，`SseEmitter` 会被静默关闭，前端收到的是无预警的连接中断。

### Caffeine 的能力过剩

异步任务的并发量极低（1-5 个同时运行），Caffeine 引以为傲的 LRU 淘汰、软引用、命中率统计等能力完全用不上。引入 Caffeine 意味着多一个强制依赖（`com.github.ben-manes.caffeine`），对于这个需求来说过于沉重。

### 显式清理更可控

`ConcurrentHashMap` 配合 `ScheduledExecutorService` 实现了精确的 30 秒延迟清理——任务完成后才开始倒计时，而非从创建时计算 TTL。这确保了用户查看结果期间数据始终可用：

```java
// 30 秒后清理（从 complete/fail 开始计时）
private void scheduleCleanup(String taskId) {
    cleanupScheduler.schedule(() -> {
        tasks.remove(taskId);
    }, 30_000L, TimeUnit.MILLISECONDS);
}
```

如果用 Caffeine，需要组合 `expireAfterWrite` 和 `expireAfter` 自定义策略才能模拟类似行为，代码复杂度反而更高。

### 适用场景速览

| 维度 | ConcurrentHashMap + 手动清理 | Caffeine |
|------|------|------|
| 生命周期 | 显式 create→complete→remove | 自动过期/驱逐 |
| 并发度 | 低（1-5 个任务） | 高（数千条目） |
| 状态耦合 | 持有 SseEmitter 等资源 | 纯数据缓存 |
| 清理时机 | 精确控制（完成后再计时） | TTL 从创建/访问算起 |
| 依赖开销 | JDK 内置，零依赖 | 需引入第三方包 |

**结论**：Caffeine 是优秀的本地缓存，但任务管理器需要的是状态机而非缓存。`ConcurrentHashMap` + 手动清理的简单组合，更符合这个场景的精确需求。

## 三、前端双模监控：SSE 优先 + Polling 回退

（SSE 细节前篇已述，本文只讲核心设计）

视频检测涉及逐帧推理，单次请求耗时数十秒。前端 `TaskWatcher` 采用双模策略：优先建立 SSE 长连接接收实时进度，若不可用则自动降级为轮询，每 1.5 秒查询一次任务状态。两种模式共享同一套 `onProgress`/`onDone`/`onError` 回调接口，对业务代码完全透明：

```javascript
class TaskWatcher {
  watch(taskId, callbacks = {}) {
    // SSE 优先，失败则自动回退轮询
    if (!this._startSSE(taskId, callbacks)) {
      this._startPolling(taskId, callbacks);
    }
  }
}
```

**关键点不在 SSE 本身，而在于双模设计**——小程序环境下 `wx.request` 的 `enableChunked` 支持因基础库版本而异，双模方案确保了兼容性零妥协。

## 四、零缓冲透传 + 本地打字机回退

（SSE 流式透传细节前篇已述，本文聚焦降级策略）

后端收到 DeepSeek 流式响应后透传至前端，若 SSE 链路受阻，前端自动降级为**本地打字机效果**。这不是简单的直接显示完整文本，而是按字符逐字吐出——中文字符间隔 40ms、英文 30ms、标点停顿 120ms，最大化保留流式体验：

```javascript
async streamContent(messageId, fullContent) {
  for (let i = 0; i <= fullContent.length; i++) {
    this.updateStreamingMessage(messageId, fullContent.slice(0, i));
    const char = fullContent[i - 1];
    let delay = 30;
    if (char && /[一-龥]/.test(char)) delay = 40;
    else if (/[.,!?;:，。！？；：]/.test(char)) delay = 120;
    await this.sleep(delay);
  }
}
```

**核心思路**：不把网络降级视为失败，而是视为"传输协议切换"。用户在任何网络条件下都能获得一致的逐字展示体验。

## 五、PromptLoader 复合占位符解析

多模态检测需要将音频、视频的分析结果注入 LLM 提示词模板。传统方案使用 `String.format()` 或简单键值替换，但遇到 `{audio.fakeProbability}` 这样的**点分隔复合占位符**就无能为力了。

我们实现了一个支持反射的提示词加载器：通过正则匹配 `{对象.属性}` 格式的占位符，运行时通过反射递归获取嵌套属性值：

```java
public class PromptLoader {
    public String loadPrompt(String templateName, Map<String, Object> params) {
        String template = loadTemplate(templateName);
        Pattern pattern = Pattern.compile("\\{([\\w.]+)\\}");
        Matcher matcher = pattern.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String[] parts = placeholder.split("\\.");
            Object value = resolveValue(params, parts);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                value != null ? value.toString() : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private Object resolveValue(Object obj, String[] parts) {
        Object current = obj;
        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<?, ?>) current).get(part);
            } else {
                current = invokeGetter(current, part); // 反射 getter
            }
        }
        return current;
    }
}
```

这使得提示词模板可以直接引用嵌套字段：`"音频伪造概率为 {audio.fakeProbability}，视频帧分析结果为 {video.frameAnalysis[0].label}"`，大幅提升了模板的表达力和复用性。

## 六、联调修复：真实环境暴露的 6 个 Bug

前后端首次联调，6 个真实 Bug 浮出水面：

| 问题 | 根因 | 修复 |
|------|------|------|
| 结果圆环只显示半圈 | CSS `border` 方案只能画 180° | 改用 `conic-gradient` 360° 渐变 |
| 检测按钮始终可用 | `canDetect` 是方法而非 `data` 字段 | 新增 `data` 字段 + 输入变化时刷新 |
| 文本检测置信度溢出 100% | 公式 `riskProb + 0.1` 导致 1.05 | 改为 `Math.max(real, fake)` |
| 知识库搜索不命中 | 单向匹配"公检法" vs "冒充公检法" | 双向匹配 + 分类名对齐 |
| 首页跳转失败 | 知识库是 TabBar 页却用 `navigateTo` | 改为 `switchTab` |
| 视频选择语法错误 | try-catch 缺闭合括号 | 补全括号 |

这些 Bug 大部分在 Mock 环境下无法暴露，只有真实数据流经完整链路时才会触发——这正是联调不可替代的价值。

## 七、总结与数据

最终验证结果：

- **后端**：17 个 API 全部就绪，`mvn test` 24/24 通过
- **前端**：知识库、模拟对话、文本检测三页联调通过
- **代码**：全阶段 1150 行改动，新增 4 个后端文件、修改 19 个文件

前后端联调不是简单的接口拼接，而是**契约的验证、异常的处理、体验的打磨**。用 `ConcurrentHashMap` 而非 Caffeine 管理任务生命周期、双模监控保障兼容性、本地打字机作为 SSE 降级方案、反射式占位符提升模板表达力——这些技术决策加上真实联调中修复的 6 个 Bug，让"诈骗克星"从一套精美的 Mock 原型，蜕变为真正可用的反诈检测系统。
