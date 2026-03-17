## Kafka 历史消息与 CEX 保留/归档策略（建议稿）

### 1. Kafka 历史消息的基本事实（先统一认知）

#### 1.1 “保留 7–30 天”是什么意思？
- **含义**：Kafka 会把 topic 的日志数据按策略保留一段时间（例如 30 天），超过保留期的旧 segment 会被清理。
- **不是含义**：Kafka 并不承诺“永久可回放”，也不承诺“精确到 30 天整都还在”。

#### 1.2 我开一个新 consumer（新 group），能从 30 天前开始消费吗？
结论：**在“理想且配置正确”的前提下，大概率可以回放到接近 30 天前；但 30 天是上限，不是硬保证。**

满足以下条件时，新组（`auto.offset.reset=earliest`）通常能回放到“当前仍存在的最早 offset”，该最早 offset 通常接近 30 天前：
- topic 使用 `cleanup.policy=delete`（流水型事件）
- `retention.ms≈30天` 且磁盘容量足够
- 未被 `retention.bytes` 或磁盘压力提前清理

常见导致“回不到 30 天前”的原因：
- **空间优先**：`retention.bytes` 或 broker 磁盘压力触发更早删除
- **压缩策略**：`cleanup.policy=compact`（只保 key 的最新值，历史流水会被压缩掉）
- **segment 粒度**：按 segment 删除，最早可读时间会在“30 天附近”波动（小时/天级误差）

#### 1.3 Kafka 消息都在内存里吗？
- **不是**。Kafka 的数据主要是 **磁盘上的顺序日志（log segments）**。
- 内存主要用于 **OS page cache** 等加速读取，热点数据可能命中内存，但**源头仍是磁盘日志**。

---

### 2. CEX 业务下的历史消息处理：主流架构建议

#### 2.1 总体原则
- Kafka：负责 **实时解耦 + 短期可重放（天/周级）**
- DB/数据湖/数仓：负责 **长期存证/审计/对账/离线重算（月/年级）**
- 核心链路默认按 **“至少一次”** 设计：手动提交 offset + 幂等

> 经验法则：**Kafka 不做最终事实来源**；最终事实应落在可审计的存储（MySQL/湖仓/数仓）。

---

### 3. 按 Topic 类型落地策略（CEX 常见三类）

#### A）流水型（订单/撮合/清算/结算/资金指令）
典型：`exchange-match-result`、`exchange-clearing-result`、`exchange-fund-instruction` 等

- **Kafka 策略**：`cleanup.policy=delete`
- **保留期建议**：**7–30 天**（核心资金链路通常倾向更长，如 14/30 天）
- **目的**：
  - 支持短期回放（故障恢复、补数、对账窗口）
  - 降低 Kafka 存储成本与运维复杂度
- **长期留存**（推荐）：
  - 关键结果必须落库（订单最终态、资金流水/指令执行结果、幂等记录）
  - 同步归档到对象存储/湖仓/数仓（审计、离线重算、合规）
- **回放策略**：
  - **保留期内**：新 group 回放/重置 offset 回放
  - **超过保留期**：从归档回灌（或离线重算再回写 DB）
- **必须配套**：幂等键（messageId/orderId/instructionId）+ 去重表/唯一约束

#### B）高频实时型（行情/推送/非关键通知）
典型：ticker、depth、Kline 推送、撮合成交推送等

- **Kafka 策略**：通常 `delete`
- **保留期建议**：**小时级～1/3 天**（按业务可丢程度与磁盘成本）
- **长期留存**：若业务需要历史行情，建议进入行情库/OLAP（如 ClickHouse），不要用 Kafka 存一年流水。

#### C）状态型（配置/字典/开关）
典型：币种/交易对配置变更、字典表刷新通知等

- **Kafka 策略**：推荐 `cleanup.policy=compact`（或 `compact,delete`）
- **Key 设计**：必须稳定（如 `pair:BTC/USDT`、`dict:xxx`），确保压缩语义成立
- **目的**：新 consumer 启动能快速“追上最新状态”，无需保留完整历史流水

---

### 4. 生产治理清单（建议做成制度）

#### 4.1 为每个 topic 建立“生命周期配置表”
建议至少包含：
- topic 类型（流水/高频/状态）
- `cleanup.policy`
- `retention.ms` / `retention.bytes`
- 是否需要归档、归档落地位置、保留周期
- 回放预案（谁能回放、回放范围/速率、如何隔离）

#### 4.2 毒丸消息与重试策略
在“手动 ack + 至少一次”模式下：
- 异常不 ack → 会反复重试（同进程下一轮 poll / rebalance / 重启后）
- 若单条永远失败（毒丸），会拖住分区消费

建议：
- 配置错误处理器退避（backoff）
- 必要时引入 DLQ（死信主题）/隔离主题
- 关键链路必须幂等（否则重试=重复扣款/重复入账）

#### 4.3 变更 groupId 的风险提示
- **改 groupId 等于新消费者组**：没有历史 offset
- `auto.offset.reset=earliest` 可能触发大量回放
- 上线前需评估：是否允许回放、是否具备幂等、是否需要手工设置 offset

---

### 5. 针对本项目链路的直接建议（可作为默认）

- `exchange-match-result`：流水型 → `delete` + **14/30 天**；长期靠落库与归档
- `exchange-clearing-result`：流水型 → `delete` + **30 天**（对账窗口更长）
- `exchange-fund-instruction`：资金链路 → `delete` + **30 天**；执行与幂等结果必须落库
- `data-dictionary-save-update`：更偏状态/通知 → 优先评估 `compact`（只需要最新状态时）

