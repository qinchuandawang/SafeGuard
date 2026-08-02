# SafeGuard 技术选型与架构演进说明

## 1. 架构定位

SafeGuard 的核心不是普通 CRUD，也不是单纯模型训练，而是一个多模态 AI 反诈检测平台。当前最契合的架构是：

```text
模块化单体 Spring Boot 主后端
        +
独立 Python 音频/视频推理服务
        +
MySQL / Redis / RocketMQ / Qdrant 基础设施
```

主后端负责用户、任务、记录、模型管理、智能体编排和管理后台 API；Python 服务负责音频、视频模型推理。这个边界按“业务编排”和“计算密集型推理”拆分，比过早把 Java 后端拆成多个微服务更稳。

当前不直接引入完整微服务体系。原因是业务仍处于单团队、单主库、强编排阶段，硬拆会提前引入注册发现、网关、配置中心、链路追踪和分布式事务。后续当检测任务、报告生成、通知审计出现独立扩缩容需求时，再拆分 Detection Service、Report Service、Audit Service 和 Model Inference Service。

## 2. 后端主技术栈

| 组件 | 当前选择 | 选择理由 |
|------|----------|----------|
| Web 框架 | Spring Boot | 国内后端岗位主流，生态成熟，适合承载业务编排和工程治理 |
| ORM | MyBatis-Plus | 任务、记录、模型和 MQ 本地事务记录等表结构明确，SQL 可控性比 JPA 更契合 |
| 主存储 | MySQL | 用户、任务、记录、模型配置和事务状态均为结构化数据 |
| 缓存 | Caffeine + Redis | Caffeine 承载进程内高频读，Redis 承载跨实例快照与共享状态 |
| Redis 客户端 | Redisson | 提供可过期计数信号量、看门狗锁和原子对象，适合模型容量预占与分布式协调 |
| 消息队列 | RocketMQ | 任务事件属于高可靠业务消息，适合重试、死信、延迟、事务消息演进 |
| AI 框架 | Spring AI | 与 Spring Boot 配置、Bean 生命周期和 Function Calling 接入方式更一致 |
| 流控熔断 | Sentinel | 对 AI 慢调用按资源实施线程数流控、慢调用/异常比例熔断，并提供治理控制台 |
| 向量库 | Qdrant | 适合本地部署和中小规模 RAG，内存 fallback 能保证演示可用 |

## 3. Spring AI 与 LangChain4j 的取舍

当前选择 Spring AI，不是因为 LangChain4j 不好，而是因为项目已有自研 `AgentOrchestrator`、`ToolRegistry`、`ReActService`、`CoTService` 和 `RAGService`。如果再引入 LangChain4j，容易出现两套 Agent / Tool / Memory / RAG 抽象并存。

Spring AI 在当前阶段承担的是模型接入层和 Function Calling 标准化能力，不替代业务智能体编排。这样既保留自研 Agent 的可解释性，也避免大规模重构。

## 4. Function Calling 与手写工具调用

主链路使用 Function Calling。原因是工具 schema 明确、参数结构化、工具执行可审计，避免手写 ReAct 文本解析中常见的格式漂移问题。

旧 ReAct 文本解析链路已经移除。模型不可用时只降级为 RAG/规则结果，不再通过解析 `Action:` 文本手写调用工具，避免两套工具协议并存。

## 5. RocketMQ 与其他 MQ 的取舍

SafeGuard 的消息不是海量日志流，而是检测任务生命周期事件：

```text
task.created
task.progress
task.completed
task.failed
report.generate.requested
audit.created
```

这些消息强调可靠投递、消费重试、死信处理、消息轨迹和事务消息，因此选择 RocketMQ。

| MQ | 更适合的场景 | 当前是否选择 |
|----|--------------|--------------|
| RocketMQ | 国内大厂业务消息、事务消息、延迟消息、顺序消息、重试和死信 | 当前选择 |
| RabbitMQ | 中小型业务队列、复杂路由、轻量演示 | 不作为最终选型 |
| Kafka | 高吞吐日志流、埋点、行为流、实时特征流 | 后续有日志流分析再考虑 |
| Pulsar | 多租户、冷热分层、跨地域消息 | 当前过重 |

需要注意：RocketMQ 事务消息解决的是生产侧数据库事务与消息提交的一致性，不能替代消费幂等、重试、死信和业务补偿。

当前链路先发送半消息，再由 `TransactionListener` 执行任务状态更新和 `mq_transaction_record` 写入；两者在同一本地事务中提交。若生产者在本地事务提交后、向 Broker 返回提交状态前异常，Broker 根据业务消息 ID 回查本地事务记录并决定提交或回滚。消费者将幂等占位、业务处理和完成标记放在同一事务中，并以 `consumer_group + message_id` 唯一索引兜底。

## 6. 缓存一致性与 Canal

当前采用职责明确的多级缓存，而不是把所有数据都放进 Redis：

```text
Caffeine 保存单实例热点结果（Embedding / RAG / 知识上下文 / 健康状态）
Redis 保存跨实例任务快照、活动模型和推理容量状态
Redisson 提供可过期信号量、分布式锁、原子预占与 CAS 释放
MySQL 保存最终事实和唯一性约束
RocketMQ 发布状态事件
查询 miss 后回源 MySQL
```

Caffeine 只缓存可重建、允许短时不一致的数据，并设置最大容量和 TTL；Redis 不作为幂等事实源，MySQL 唯一索引仍承担最终去重。生产环境可通过 `COORDINATION_REDIS_*` 将 Redisson 锁、信号量、幂等和 Token 预算放到独立 `noeviction` Redis，普通缓存 Redis 才允许使用淘汰策略。

当前不引入 Canal。Canal 更适合 MySQL binlog 同步到 ES、Redis 或多系统审计场景。如果后续引入 Elasticsearch 做检测报告全文检索，可以演进为：

```text
MySQL binlog -> Canal -> RocketMQ -> ES / Redis / Audit
```

## 7. SSE 与 WebSocket

当前通信策略是 REST + SSE 为主，WebSocket 只用于真正双向实时场景。任务 SSE 支持多订阅者、连接建立时回放当前快照，并按 `taskId` 共享刷新任务从 Redis/MySQL 读取事实状态，因此断线重连或负载均衡到其他实例后仍可恢复，读取压力也与活跃任务数而非连接数相关。

| 场景 | 推荐协议 | 原因 |
|------|----------|------|
| 普通 API | REST | 简单、清晰、易测试 |
| AI 助手流式输出 | SSE | LLM token stream 是服务端单向推送，SSE 更轻 |
| 检测任务进度 | SSE 或短轮询 | 任务状态是服务端单向更新 |
| 模拟诈骗训练 | WebSocket | 需要双向实时交互时再使用 |

不把所有实时能力都做成 WebSocket，是为了避免过早承担连接管理、心跳、断线重连、水平扩展和鉴权续期复杂度。

## 8. Sentinel 流控与熔断策略

AI 应用的主要风险是推理延迟高、资源占用大以及故障时请求堆积。请求先通过 Redisson `RPermitExpirableSemaphore` 按模型预占集群推理槽位，再进入 Sentinel 的 `audioDetection`、`videoDetection` 资源统计。这样许可等待不会被误判为下游慢调用，音频与视频也能使用独立的容量、租约和熔断阈值。

推理许可是“固定 N 个任务并行”的计数资源，不使用互斥锁模拟。许可租约高于对应 HTTP 读取超时，业务进程存活期间按租约的三分之一周期续租，正常完成主动释放，实例崩溃后由租约兜底回收。Redis 明确关闭时使用 JVM 公平信号量支持单机开发；集群配置已开启 Redis 但协调失败时直接返回 503，禁止降级为每实例本地限额，否则会突破全局容量。

Sentinel 拒绝时立即失败，不进入重试；只有已经进入下游且发生瞬时异常的调用才执行一次有限重试。HTTP 客户端不再叠加自动重试，避免故障期间产生乘法级流量放大。检测线程池继续使用有界队列和拒绝策略，形成“入口任务削峰、线程池资源隔离、Sentinel 下游保护”三层边界。

后续建议覆盖：

| 依赖 | 保护策略 | 降级方案 |
|------|----------|----------|
| LLM | timeout、Sentinel 异常比例熔断 | 文本检测回退规则引擎 |
| 音频服务 | timeout、线程数流控、慢调用/异常比例熔断 | 任务进入可重试失败状态 |
| 视频服务 | 长超时、较低并发、慢调用/异常比例熔断 | 任务进入可重试失败状态 |
| Qdrant | timeout、Sentinel 熔断（待覆盖） | 回退关键词检索或内存索引 |
| Redis 普通缓存 | 短超时 | 失败时回源 MySQL |
| Redis 推理容量协调 | 短超时、可过期许可 | 失败关闭并返回 503，防止全局容量失控 |
| RocketMQ | 事务消息 | 半消息发送失败则不执行本地事务；提交结果不确定时由 Broker 回查 |

Sentinel Dashboard 是本项目的主要运行时治理入口，用于查看实时 QPS、拒绝数和熔断状态并调整规则。但它不保存长期监控数据，也不提供跨服务 Trace：Actuator/Prometheus 保留为长期趋势和告警接口，OpenTelemetry/Zipkin 降为可选的疑难链路排障能力，二者不参与核心业务正确性。

## 9. 当前不建议引入的组件

| 组件 | 当前不引入原因 | 何时引入 |
|------|----------------|----------|
| Nacos / Gateway | 未拆 Java 微服务，治理成本大于收益 | 服务拆分后 |
| MongoDB | 当前数据结构明确，MySQL 足够 | 报告文档 schema 高度动态时 |
| Elasticsearch | 普通记录查询 MySQL 足够 | 报告全文搜索、审计检索明确后 |
| Canal | 当前没有多系统 binlog 同步需求 | MySQL 同步 ES / Redis / 审计系统时 |
| Kubernetes | 本地和秋招展示过重 | 需要集群部署和弹性伸缩时 |

应用进程默认不创建、启动或停止 Qdrant/Docker；基础设施生命周期由 Docker Compose 或部署平台管理。仅在个人开发环境显式设置 `QDRANT_AUTO_MANAGE_CONTAINER=true` 时启用兼容管理器。

## 10. 后续演进优先级

1. 将模型切换审计持久化到 `model_switch_log`，记录操作者、原因和回滚版本。
2. 为正式数据集建立可复现训练流水线和模型评估报告，不把候选适配器描述成已训练模型。
3. 当报告全文搜索成为真实需求时，引入 ES，并消费 RocketMQ 任务事件异步构建索引；当前不为展示技术栈而引入 ES。

## 11. 高并发与数据分层设计

音视频推理入口先捕获活动模型 ID，再使用该版本完成容量预占和模型调用，避免切换期间“按 A 模型占位、向 B 模型请求”。Redisson 可过期信号量按模型限制集群实际任务数，许可耗尽快速失败；视频任务再以 `video:{sha256}:{modelId}` 合并热点素材请求，避免相同素材重复消耗 GPU/CPU 推理资源。

任务入口以客户端 `Idempotency-Key` 或上述视频业务键进行幂等控制。Redisson `RBucket.trySet` 负责快速原子预占，失败释放使用 CAS 避免误删其他实例的新值，MySQL 唯一索引才是最终一致性防线。任务状态仅允许 `queued -> processing -> completed/failed`，进度条件更新禁止回退，终态只能写入一次。

原始媒体与结构化数据分别分层：音视频写入 MinIO，任务表仍作为热状态数据；超过保留期的 `detection_record` 使用主键游标分页复制到归档表，确认归档副本数量后再逻辑删除热表记录。归档由 Redisson `RLock` 保护，看门狗在长事务期间自动续期，锁在数据库事务提交后按线程所有权释放；归档表主键和事务仍承担最终正确性。

Redis Sentinel 解决缓存节点故障转移，但 Redis 异步复制仍可能丢失极少量最近写入，所以幂等不能只依赖 Redis。MySQL 从库仅允许承载报表、历史列表和归档扫描；创建任务、幂等判断、状态更新、MQ 本地事务记录和模型切换都固定访问主库，并避免全局自动读写路由导致写后读不一致。
