# SafeGuard 高可用与故障演练手册

## 1. 拓扑与边界

`docker-compose-ha.yml` 提供 Redis 1 主 2 从 3 Sentinel，以及 MySQL 1 主 1 从。应用使用 `prod,ha` Profile 时通过 Sentinel 发现 Redis 主节点，Redisson 复用同一 Sentinel 拓扑执行分布式锁和原子对象操作；MySQL 默认数据源始终连接主库，`readReplicaJdbcTemplate` 仅供明确标记的报表、历史和归档读取使用。

Redis 是加速层，不是幂等事实源。Sentinel 切换期间可能丢失尚未复制的少量写入，MySQL 的 `uk_idempotency_key` 仍负责最终去重。MySQL 复制是异步的，所有写后读、任务状态、MQ 本地事务记录、权限与模型切换均禁止访问从库。

## 2. 启动与检查

```bash
docker compose -f docker-compose-ha.yml up -d
docker compose -f docker-compose-ha.yml ps
docker exec safeguard-sentinel-1 redis-cli -p 26379 SENTINEL master safeguard-master
docker exec safeguard-mysql-replica mysql -uroot -p -e "SHOW REPLICA STATUS\\G"
```

演示凭据仅用于本地 Compose，正式环境必须替换数据库、Redis 和复制用户密码。首次建立 MySQL 主从后应确认 `Replica_IO_Running` 与 `Replica_SQL_Running` 都为 `Yes`，并记录 `Seconds_Behind_Source`。

## 3. Redis 故障转移演练

1. 连续请求同一视频并确认返回相同 `taskId`。
2. 停止 `safeguard-redis-master`，观察 Sentinel 达到 quorum 后选举新主。
3. 再次提交相同请求，确认即使 Redis 最近键丢失，MySQL 唯一索引仍返回原任务。
4. 恢复旧主，确认其以副本身份加入；检查线程池拒绝数和接口错误率没有异常突增。
5. 在归档执行期间切换 Redis 主节点，确认 Redisson 完成重连，归档锁未发生双持有且事务提交后正常释放。

```bash
docker stop safeguard-redis-master
docker exec safeguard-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name safeguard-master
docker start safeguard-redis-master
```

## 4. RocketMQ 事务消息演练

停止 Broker 后创建检测任务，确认半消息发送失败且本地任务事务没有提交。恢复 Broker 后重新提交任务，确认任务状态与 `mq_transaction_record` 同时提交，并能消费任务事件。

```text
SELECT message_id, task_id, event_type, transaction_status
FROM mq_transaction_record
ORDER BY id DESC LIMIT 20;
```

进一步演练可在本地事务提交后、生产者返回提交状态前终止应用，恢复生产者后观察 Broker 发起事务回查；存在 `COMMITTED` 记录时提交消息，不存在时回滚。事务回查数据库异常时返回 `UNKNOW`，由 Broker 后续继续回查。

消费者以唯一键去重；消费异常返回 `RECONSUME_LATER`，超过 5 次由 RocketMQ 投递到 `%DLQ%safeguard-task-event-audit`。当前消费者只做任务事件审计，不承担不可逆业务副作用。

## 5. 冷热归档演练

```text
POST /api/admin/reliability/archives/run
GET  /api/admin/reliability/archives/stats
```

归档按主键游标读取 30 天前数据，每批默认 500 条。流程为复制到 `detection_record_archive`、校验副本、逻辑删除热表；任一步异常都会回滚。媒体文件写入 MinIO 后，任务仍保持热状态，`object_key` 仅表示媒体存储位置。

## 6. 验收指标

压测至少记录 P50/P95/P99、吞吐量、错误率、线程池队列、拒绝数、数据库连接池、Redis 命中率、事务回查次数、MQ 重试数和推理服务并发。故障注入期间不得出现请求线程执行视频推理、任务终态反复覆盖、进度回退或同一幂等键生成多个任务。

当前开发机未安装 Docker CLI，因此 Compose 已完成静态配置但未在本机实际拉起验证；部署到有 Docker 的环境后必须执行上述演练并保存结果，不能把静态配置等同于生产高可用验证。
