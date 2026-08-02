# SafeGuard JMeter 性能测试

## 1. 测试范围

| 脚本 | 验证指标 | 默认负载 |
|---|---|---|
| `01-async-video-submit.jmx` | 唯一视频任务提交吞吐、P95/P99、最终状态 | 50 线程 × 4 次，5 秒升压（过载发现） |
| `02-hotspot-singleflight.jmx` | 200 个相同请求是否只生成 1 个 `taskId`，新建/复用/失败数 | 200 线程同时释放 |
| `03-llm-cache.jmx` | 一次冷请求预热后，重复报告请求的 P95 和错误率 | 20 线程 × 5 次 |

JMeter 用于测接口吞吐和延迟。`Recall@5`、多轮追问准确率属于离线质量评测，必须使用带人工标注的数据集计算，不能从 JMeter 延迟报告推导。

异步视频接口的 HTTP 2xx 只代表任务已持久化并进入生命周期，不代表模型推理最终完成。必须结合任务表统计 `completed`、`failed`、`processing`，并将“提交延迟”和“最终成功率”分开报告。

异步提交脚本会额外生成 `task-records.tsv`，记录每个样本的 `taskId`、提交耗时、HTTP 状态和断言结果；压测结束后应逐个查询这些 `taskId`，确认最终状态。

## 2. 环境要求

1. JDK 17，Apache JMeter 5.6.3。
2. Java 后端、MySQL、Redis、RocketMQ、MinIO、音视频模型服务按待测配置启动。
   视频异步链路必须确认 RocketMQ 的 `safeguard-inference-work` Topic、
   `safeguard-inference-worker` 消费组和 `VideoInference` Tag 已正常注册。
3. 缓存测试必须配置真实 `LLM_API_KEY`；若响应是降级文案，预热断言会失败。
4. 只在隔离压测环境设置 `TRUST_FORWARDED_HEADERS=true`。脚本为每个线程生成不同 `X-Forwarded-For`，避免本机单 IP 的 15/60 次每分钟限流掩盖系统真实容量。
5. 压测期间不要运行其他业务请求，数据库和 Token 日账本应使用独立测试库。

生产环境不能直接信任任意客户端传入的转发头，必须由受信任网关覆盖并清洗该请求头。

## 3. 推荐执行方式

先将 JMeter 的 `bin` 加入 `PATH`，然后在项目根目录执行：

```powershell
# 测异步视频任务提交
./performance/jmeter/run-jmeter.ps1 -Scenario submit

# 测 200 个相同视频瞬时并发
./performance/jmeter/run-jmeter.ps1 -Scenario hotspot

# 测 LLM 精确缓存
./performance/jmeter/run-jmeter.ps1 -Scenario cache
```

JMeter 未加入 `PATH` 时显式指定：

```powershell
./performance/jmeter/run-jmeter.ps1 `
  -Scenario hotspot `
  -JMeter "D:/apache-jmeter-5.6.3/bin/jmeter.bat"
```

带管理员 JWT 时，参数必须包含完整前缀：

```powershell
-Authorization "Bearer eyJ..."
```

## 4. 参数填写

| PowerShell 参数 | JMeter `-J` 参数 | 含义 | 建议值 |
|---|---|---|---|
| `-HostName` | `host` | 后端地址，不带协议 | `127.0.0.1` |
| `-Port` | `port` | 后端端口 | `8080` |
| `-Protocol` | `protocol` | HTTP 协议 | `http` |
| `-VideoFile` | `video_file` | MP4 绝对路径 | 5～20 MB、时长固定的视频 |
| `-Threads` | `threads` | 并发线程数 | submit=50，hotspot=200，cache=20 |
| `-Loops` | `loops` | 每线程请求次数 | submit=4，hotspot=1，cache=5 |
| `-RampSeconds` | `ramp_seconds` | 线程启动时间 | 1～5 秒 |
| `-ClientIpPrefix` | `client_ip_prefix` | 隔离环境模拟客户端 IP 前缀 | 每轮使用新的私网段 |
| 无需手填 | `run_id` | 每轮唯一标识，防止复用上轮数据 | 启动脚本自动生成 |
| 无需手填 | `sync_size` | 热点场景同步释放数量 | 必须等于 `threads` |

直接调用 JMeter 时示例：

```powershell
jmeter -n `
  -t performance/jmeter/02-hotspot-singleflight.jmx `
  -Jhost=127.0.0.1 -Jport=8080 `
  -Jthreads=200 -Jsync_size=200 -Jrun_id=hotspot-20260731-01 `
  -Jvideo_file="D:/Git/Job Search Preparation/SafeGuard/test_data/video/f6699295ad7d66734aa1d1d63004f7fb.mp4" `
  -Jclient_ip_prefix=10.82.1. `
  -Jresult_dir=performance/jmeter/results/hotspot-20260731-01 `
  -l performance/jmeter/results/hotspot-20260731-01.jtl
```

## 5. 指标读取

### 异步任务提交

在 HTML Dashboard 中只查看 `POST /api/detection/video - 创建异步任务`：

- `95th pct`：任务提交 P95。
- `99th pct`：任务提交 P99。
- `Error %`：HTTP/业务断言失败率，目标应为 0；它不等于后台任务最终失败率。
- `Throughput`：接口提交能力，不等于模型推理吞吐。

改造前单机真实结果：50 个唯一视频任务均成功受理，但最终为 `completed=4`、`failed=39`、`processing=5`，说明旧实现的推理许可等待窗口不足以承载该过载负载，不应将这轮写成“50 个任务成功”。容量内 4 并发基线为提交平均 63.75ms、最大 97ms，最终 `completed=4`。

改造后的工作链路为：任务落库为 `queued` -> RocketMQ 工作消息 -> 消费者条件更新为 `processing` -> 消费者调用模型 -> `completed`/`failed`。消费者在推理许可不足时将状态恢复为 `queued` 并返回 `RECONSUME_LATER`，消息发送失败时接口返回 `503`，不会把未投递的任务伪装成已执行。

正式性能指标应使用容量内配置进行多轮测试，例如 `-Threads 4 -Loops 5 -RampSeconds 5`，并等待所有任务进入终态后再统计最终成功率。50 并发场景保留用于验证背压、预期 `503` 拒绝和过载保护。

当前 RocketMQ 工作队列改造后的容量内实测基线：4 并发、每批 4 次、5 秒升压，分 13 批共 208 个有效样本；提交平均 70.18ms，P50 64ms，P95 106ms，P99 167.23ms，最大 253ms，208/208 个任务最终 `completed`。本轮关闭外部 `LLM_API_KEY` 并使用规则化降级报告，隔离外部 LLM 网络耗时；文件上传、MySQL 持久化、Redis 快照、RocketMQ 投递、视频模型推理、任务状态机和最终完成状态均保留在测试链路中。测试结束后逐个查询 208 个 `taskId` 均为 `completed`，数据库核验视频 `queued/processing` 活跃任务数为 0。

测试过程中发现并修复提交阶段的边界问题：队列满时任务已落库但投递失败会形成无消息的 `queued` 僵尸任务。当前队列拒绝路径会将未受理任务收敛为 `failed` 后返回 503；消费者因模型许可不足重试时仍保持 `queued`，两种状态语义分离。

“1.8s → 45ms”必须分别在改造前基线版本和当前版本运行同一 JMX、同一文件、同一硬件后计算，不能用同步音频接口与异步视频接口进行横向对比。

### 热点请求合并

脚本会生成 `hotspot-summary.json`。验收条件为：

```json
{
  "uniqueTaskIds": 1,
  "created": 1,
  "reused": 199,
  "errors": 0
}
```

再对比压测前后的 Prometheus 指标：

```text
safeguard_inference_capacity_acquire_total{category="video",result="acquired"}
```

增量为 1 才能说明只进入一次实际推理。仅有一个 `taskId` 只能证明任务合并，不能单独证明模型服务只被调用一次。

### LLM 缓存与 Token

脚本会生成 `cache-summary.json`，HTML 报告中分别查看：

- `cache-warmup`：冷请求耗时，不计入缓存命中 P95。
- `cache-hit`：重复请求耗时，用于计算缓存 P95。

使用下列 SQL 核对 Token，时间范围替换为本轮测试时间：

```sql
SELECT cache_hit,
       COUNT(*) AS requests,
       AVG(total_tokens) AS avg_tokens,
       SUM(total_tokens) AS total_tokens
FROM llm_usage_record
WHERE scene = 'multimodal'
  AND created_at >= '2026-07-31 10:00:00'
  AND created_at <  '2026-07-31 10:10:00'
GROUP BY cache_hit;
```

预期只有预热请求 `cache_hit=0` 且产生 Token，后续请求 `cache_hit=1`、`total_tokens=0`。简历中的“1850 降至 0”应使用首次请求的实际 `total_tokens` 替换 1850。

## 6. RAG 质量指标

准备至少 500 条人工标注记录，每条包含：查询、相关 `chunkId` 集合、是否为多轮问题、期望答案关键词。逐条请求：

```http
GET /api/rag/query?q={query}
```

计算口径：

```text
Recall@5 = 前 5 条结果命中至少一个相关 chunkId 的查询数 / 总查询数
多轮追问准确率 = 满足人工答案或关键词判定的多轮问题数 / 多轮问题总数
```

基线必须关闭关键词、规则和 Rerank，仅保留 Qdrant 单路向量召回；优化组开启完整混合 RAG。两组必须使用同一知识库快照、Embedding 模型和 500 条测试集。

## 7. 执行纪律

- 正式压测使用 JMeter CLI，GUI 只用于编辑和小流量调试。
- 每个场景至少预热 1 轮、正式执行 5 轮，报告中使用中位轮次或汇总结果。
- 保存 `.jtl`、HTML Dashboard、应用日志、Prometheus 快照、SQL 结果和测试环境信息。
- 不删除失败轮次；说明限流、队列拒绝、外部模型抖动等异常原因。
