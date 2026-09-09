# SafeGuard 项目简历描述

## 项目介绍

面向多模态 AI 慢推理与高并发场景，构建文本、音频、视频反诈检测平台；通过 Function Calling、混合 RAG、异步任务和推理容量治理，实现检测编排、成本控制、可靠执行与故障恢复。

## 核心技术栈

Spring Boot + Spring AI + MyBatis-Plus + MySQL + Caffeine + Redis + Redisson + RocketMQ + Sentinel + Qdrant + MinIO + PyTorch + Vue 3

## 技术亮点

1. **多模态 AI 与智能编排**：构建文本、语音克隆、视频换脸及多模态融合检测链路，支持音视频各 3 个模型切换；基于 Spring AI Function Calling 自动编排知识检索和检测工具。

2. **混合 RAG 与长短期记忆**：结合 Qdrant 向量召回、关键词检索、规则过滤和 Rerank；短期记忆维护会话上下文，长期记忆沉淀用户风险特征。评测集按知识库 40 个主题节自动生成并扩充至 **562 条**（原 163 条 + 生成 399 条，ground truth 为主题节与 `anti_fraud_knowledge_N.txt` 的映射），知识库 111 chunks。检索侧：混合检索基线 Recall@5 **0.8468**、MRR 0.7407、nDCG@5 0.7864（n=555）。生成侧采用 **RAGAS** 体系（LLM-as-judge，150 条采样）：Faithfulness **0.9233**、Context Recall **0.9302**、Context Precision **0.8995**、Answer Relevancy **0.6826**。
   - **Agentic 反思补检相对基线未带来召回提升**（Recall@5 0.8468 → 0.8474，MRR 0.7407 → 0.7398）：562 条中仅 3 条触发补检（`RAG_AGENTIC_COVERAGE_THRESHOLD=0.6` 偏保守），这是当前的已知短板与迭代方向。
   - **Answer Relevancy 0.6826 是明确短板**：检索质量（Context Recall / Precision ≈ 0.9+）与答案忠实度（Faithfulness 0.92）健康，但回答切题度不足，下一步通过 query 改写与答案结构化优化。

3. **高并发推理治理**：将模型并发视为有限库存，通过 Redisson 分布式许可、热点请求合并、有界线程池和快速拒绝控制集群容量；200 并发提交相同视频时，最终仅创建 1 个任务、复用 199 次，实际推理许可仅获取 1 次，重复计算减少 **99.5%**。

4. **幂等任务与事务消息**：使用文件哈希和模型版本生成幂等业务键，以 Redis 原子预占、MySQL 唯一索引和条件状态机兜底；RocketMQ 事务消息通过半消息、本地事务和状态回查保证任务状态与事件一致，消费端使用幂等表处理重复投递。

5. **Token 与多级缓存治理**：LLM 调用前预占 Token 预算、调用后按 Usage 对账；使用 Caffeine + Redis 缓存确定性结果，缓存标识纳入模型、Prompt 版本和推理参数。2026-09-09 复测（见 `performance/rag/exact-cache-repeat-result.json`，Token 以 MySQL `llm_usage_record` 账本行级对账）：冷启动消耗 7266 Token（Agentic 反思构建知识上下文 2955 + 主分析 4311），随后 100 次重复请求全部命中缓存、账本 0 条记录（0 Token），命中延迟 P50 5ms / P95 28ms / max 29ms；模型 DeepSeek-V4-Flash-0731；本环境 `infra.redis.enabled=false`，命中由 Caffeine L1 承载，Redis L2 路径存在但未启用。

6. **异步任务与 SSE 推送**：MySQL 保存任务事实状态、Redis 保存实时快照，RocketMQ 工作队列异步消费慢推理任务，容量不足时延迟重试；SSE 支持事件 ID、断线重连和状态恢复。关闭外部 LLM 调用、保留视频模型推理的容量内基准测试中，4 并发分 13 批共 208 个提交样本，提交平均耗时 **70.18ms**、P50 **64ms**、P95 **106ms**、P99 **167.23ms**，208 个任务最终全部完成。

7. **冷热数据分离与数据库优化**：原始媒体存入 MinIO，近期检测记录保留在热表，历史记录按主键游标归档；结合唯一索引、联合索引、状态扫描索引和 HikariCP 控制查询与连接开销。

8. **系统稳定性与全栈闭环**：Sentinel 实现并发流控、慢调用及异常比例熔断，并按依赖重要性设计降级；管理端支持模型切换、任务追踪、Token 预算和检测结果管理。

## 指标说明

上述指标来自本机真实 JMeter 压测、MySQL 最终状态、Token 账本及 Prometheus 指标交叉核验。测试环境为单机部署，视频模型使用 CUDA 推理；异步场景关闭外部 LLM 调用以隔离网络波动，但保留文件上传、MySQL、Redis、RocketMQ、任务状态机和视频模型推理链路，因此不能将提交接口数据等同于完整 AI 报告耗时，也不能直接外推为生产集群吞吐。

**RAG 评测方法（2026-09-09）**：评测集 **562 条**，由原有 163 条人工用例与 399 条自动生成用例组成。自动生成基于知识库 40 个主题节，用 LLM 产出「答案可在该节中找到」的问题，ground truth 为主题节与切分后 `anti_fraud_knowledge_N.txt` 的映射关系。检索侧对每条用例分别调用 `/api/rag/query`（混合检索基线）与 `/api/rag/agentic-query`（反思补检），统计 hit@1/3/5、MRR、nDCG@5、precision@5，成功率 555/562。生成侧对齐 **RAGAS** 体系，由评测脚本自行编排「检索 → 基于上下文生成回答 → LLM-as-judge 打分」：Faithfulness（回答原子陈述受上下文支持比例）、Context Recall（标准答案要点被上下文覆盖比例）、Context Precision（上下文有用性的 Average Precision）、Answer Relevancy（由回答反推问题与原始问题的 embedding 余弦相似度）。因 LLM-as-judge 成本较高，生成侧在 150 条等距采样上进行；Faithfulness / Context Recall / Context Precision 的有效样本数低于采样数，是因为部分回答过短无法拆出可判定陈述，此类样本不计入均值。评测脚本与原始结果见 `performance/rag/`（`eval-ragas.py`、`gen-cases.py`、`ragas-eval-result.json`）。

改造前曾对 50 个唯一视频任务进行过载验证：50 个请求均在约 90ms 内完成接口受理，但由于推理许可和线程池阶段分离，最终 39 个失败、5 个处理中、4 个完成。该结果不作为简历性能指标，而是用于定位容量治理缺陷；当前已改为 RocketMQ 工作队列，排队容量超过阈值时返回 503，消费者容量不足时保持 `queued` 并延迟重试；提交阶段被拒绝的任务会收敛为 `failed`，避免未投递消息的僵尸任务占用队列容量。
