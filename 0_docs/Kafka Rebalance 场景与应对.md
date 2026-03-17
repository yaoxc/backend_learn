## Kafka Rebalance 场景与应对（简明版）

### 1) 什么是 Rebalance？
Rebalance（再均衡）是指：**同一个 consumer group 内，partition 重新分配给各个 consumer 实例**的过程。

结果通常包含两点：
- **短暂停顿**：分配期间该组对相关 partition 的消费会暂停一小段时间
- **可能重复消费**：若上一次处理未提交 offset，新接手的 consumer 会从“最后已提交 offset”继续，导致部分消息被再次处理（因此需要幂等）

---

### 2) 最常见触发 Rebalance 的场景（生产高频）

#### A. 组成员变化（最常见）
- **新增实例 / 扩容**：同组新增 consumer，触发重新分配
- **下线实例 / 缩容**：consumer 退出或被剔除，触发重新分配
- **应用重启/崩溃**：进程挂掉/重启导致成员变化

#### B. 心跳超时（被认为“挂了”）
当 consumer **长时间无法发送心跳**，超过 `session.timeout.ms`，协调器会把它踢出组，触发 rebalance。

常见原因：
- 长时间 STW / CPU 打满
- 网络抖动导致心跳失败
- 容器/虚拟机抖动

#### C. Poll 间隔超时（处理太慢）
当 consumer **两次 poll 之间的间隔**超过 `max.poll.interval.ms`，协调器会认为该 consumer “不再活跃”，触发 rebalance。

典型场景：
- 单条/单批消息处理时间过长（大事务、慢 SQL、外部 RPC 卡住）
- batch 太大（一次拉太多，处理不过来）
- 业务线程阻塞导致 poll 无法及时执行

> 交易/资金链路最容易踩这个：处理必须可控、可降级、可超时。

#### D. Topic/订阅变化
- consumer 订阅的 topic 列表发生变化（代码/配置更新）
- topic **新增 partition**（扩分区）也会触发组内重新分配

#### E. Coordinator/集群层面的抖动（低频但要知道）
- coordinator 迁移、broker 重启
- ACL/认证短暂失败导致 consumer 重连

---

### 3) Rebalance 对业务的直接影响

- **消费延迟抖动**：短暂停顿，积压上涨
- **重复消费变多**：未提交 offset 的消息会被重新投递
- **顺序性约束**：Kafka 只保证 partition 内顺序；rebalance 不改变这一点，但会改变“哪个实例”在处理该分区

结论：业务侧必须按 **“至少一次”** 设计：**幂等 + 手动提交 offset（处理成功再提交）**。

---

### 4) 工程应对建议（可操作）

#### A. 控制单次处理时长，避免 poll 间隔超时
- 限制 batch 大小：`max.poll.records`
- 给外部调用加超时（RPC/DB）
- 拆分大事务，避免单批处理时间不可控

#### B. 参数建议（方向性）
- `session.timeout.ms`：不要太小（太小容易抖动踢出）
- `max.poll.interval.ms`：确保 **大于** “最坏情况下单次处理耗时”

#### C. 降低 rebalance 频率（可选项）
- 稳定实例数，避免频繁扩缩容
- 对需要稳定分配的场景可考虑 **Static Membership**（`group.instance.id`）

#### D. 业务必须幂等
rebalance 是常态，不能靠“尽量不 rebalance”保证不重复。

---

### 5) 一句话总结

**Rebalance 发生在：组成员变化、心跳超时、处理太慢（poll 间隔超时）、订阅/分区变化、集群抖动。**
它带来的核心影响是 **短暂停顿 + 重复消费概率上升**，因此必须用 **幂等 + 成功后手动提交 offset** 来兜底。

