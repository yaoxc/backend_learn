# 撮合流程与方案 A 当前实现说明

本文档仅描述当前业务实现：撮合流程、防 Kafka 失败（方案 A）、幂等（含 messageId 改造），不做扩展设计。

---

## 一、撮合流程

### 1.1 整体链路

```
订单入口(Kafka: exchange-order) → ExchangeOrderConsumer → CoinTrader.trade(订单)
    → 内存订单簿撮合 → flushMatchResult（生成 messageId）→ [方案A] MatchResultPublisher.publish
    → Kafka(exchange-match-result) → Market: ExchangeTradeConsumer.handleMatchResult
    → processMatchResultIdempotent(messageId, …) → 按 messageId 幂等落库 + 推送
```

- **exchange 模块**：消费 `exchange-order`，按 symbol 取对应 `CoinTrader`，调用 `trader.trade(order)`。
- **CoinTrader**：单交易对、内存订单簿；撮合结果通过 `flushMatchResult` 统一出口；**每条 MatchResult 生成全局 messageId（UUID）**，供消费端幂等。
- **方案 A 下**：`flushMatchResult` 只调用 `matchResultPublisher.publish(MatchResult)`，不发 `exchange-trade` / `exchange-order-completed`。
- **market 模块**：消费 `exchange-match-result`，解析出 messageId、trades、completedOrders；调用 **processMatchResultIdempotent**，同一 messageId 只落库一次，重复消费跳过；再推送行情与订单通知。

### 1.2 CoinTrader 撮合入口与分支（当前实现）

- **入口**：`CoinTrader.trade(ExchangeOrder)`。
- **前置校验**：未暂停、symbol 匹配、订单有剩余可成交量（含市价买 amount 为金额时的判断）。
- **对手盘选择**：买单对卖盘（限价+市价），卖单对买盘。
- **市价单**：只和限价对手盘撮合（`matchMarketPriceWithLPList`），不挂本方盘口；有剩余再放回市价队列。
- **限价单**：
  - 分摊模式（FENTAN）卖单在清盘时间前：按比例与买单分摊（`matchLimitPriceWithLPListByFENTAN`），不挂卖盘。
  - 普通限价：先与限价对手盘撮（`matchLimitPriceWithLPList`），再与市价对手盘撮（`matchLimitPriceWithMPList`），未成交部分挂本方限价队列。
- **结果输出**：每轮撮合产生 `List<ExchangeTrade>`、`List<ExchangeOrder>`（已完全成交订单），由 `flushMatchResult(trades, completedOrders)` 发出。

### 1.3 撮合结果统一出口（flushMatchResult）

- **有 MatchResultPublisher（当前配置）**：  
  每条结果生成全局 **messageId = UUID.randomUUID().toString()**，再  
  `matchResultPublisher.publish(new MatchResult(messageId, symbol, ts, trades, completedOrders))`；撮合线程不直接发 Kafka，立即返回。messageId 随消息写入 WAL 并发往 Kafka，供 market 幂等去重。
- **无 MatchResultPublisher（回退）**：  
  同步发 `exchange-trade`（成交明细）和 `exchange-order-completed`（已完全成交订单），带有限次数重试；失败打 ERROR 日志。

---

## 二、防 Kafka 失败（方案 A）

### 2.1 目标

撮合结果先持久化到本地，再异步发 Kafka；避免因 Kafka 不可用或超时导致结果丢失或阻塞撮合热路径。

### 2.2 实现组件

- **QueueAndWalMatchResultPublisher**（exchange 模块）：每个交易对一个实例，由 CoinTraderConfig 创建并注入对应 CoinTrader。
- **配置**：WAL 根目录 `match.wal.path`，默认 `data/wal`。

### 2.3 三线程职责（当前实现）

| 角色       | 线程         | 职责 |
|------------|--------------|------|
| 撮合线程   | 调用方       | 调用 `publish(MatchResult)`，将结果放入内存队列（`put` 阻塞入队，队列满时等待，保证不丢）。 |
| Writer 线程 | match-wal-writer-{symbol} | 从队列 poll(1s)，取到则把 `MatchResult` 序列化为一行 JSON + 换行，追加写 WAL 文件并 `flush()`。 |
| Sender 线程 | match-kafka-sender-{symbol} | 从 WAL 按偏移读行，每行发 Kafka topic `exchange-match-result`，发送成功后把当前字节偏移写入 offset 文件；失败则保持偏移不变，等待下一轮重试。 |

### 2.4 文件与格式

- **WAL 文件**：`{match.wal.path}/match-{symbol}.wal`（symbol 中 `/` 替换为 `-`），追加写入，每行一条 `MatchResult` 的 JSON（含 messageId、symbol、ts、trades、completedOrders）。
- **偏移文件**：`{match.wal.path}/match-{symbol}.offset`，存已成功发送的 WAL 字节偏移（纯数字）。
- **Kafka topic**：`exchange-match-result`，单条消息即一条 MatchResult（含 **messageId**、symbol、ts、trades、completedOrders）。

### 2.5 故障与恢复（当前行为）

- **Writer 写 WAL 失败**：打 ERROR，该条 MatchResult 可能只进队列未落盘；进程存活时队列中后续仍会写。
- **Sender 发 Kafka 失败**：最多重试 5 次，仍失败则本行不推进 offset，下次 Sender 循环从同一行再发。
- **进程宕机**：重启后 Sender 从 offset 文件读取上次已发位置，从 WAL 该位置继续读行发送，避免已落盘未发 Kafka 的数据丢失。
- **无成交且无完成订单**：`publish` 时直接不入队，不写 WAL。

---

## 三、幂等

### 3.1 为何需要幂等

- **方案 A 下**：撮合只发 `exchange-match-result`，market 只消费该 topic 时，同一批结果只会被处理一次。
- **若 market 同时消费三个 topic**（`exchange-match-result`、`exchange-trade`、`exchange-order-completed`），例如兼容旧版或灰度，同一批撮合结果可能既在 `exchange-match-result` 里出现，又在旧 topic 出现，导致同一笔成交/同一笔订单完成被处理两次，必须幂等防重复落库与重复推送。

### 3.2 messageId 幂等改造（当前实现）

- **发送端**：`CoinTrader.flushMatchResult` 在构造 MatchResult 前生成 `messageId = UUID.randomUUID().toString()`，并写入 MatchResult；该 messageId 随 WAL 与 Kafka 消息一起下发。
- **消息体**：MatchResult 含字段 **messageId**（String，可选；旧消息无此字段时为 null）。
- **消费端**：market 的 `handleMatchResult` 解析出 `messageId`，调用 **processMatchResultIdempotent(messageId, trades, completedOrders, secondReferrerAward)**：
  - **messageId 为 null 或空**：直接调用 processMatchResult，兼容旧消息。
  - **已处理过**：若表 `processed_match_result_message` 中已存在该 messageId，返回 false，不落库、不推送，实现幂等跳过。
  - **未处理过**：先 insert 该 messageId，再调用 processMatchResult，同一事务；返回 true，继续后续推送与行情。
- **表**：`processed_match_result_message`（id, message_id 唯一），用于记录已处理的 messageId，避免重复落库与重复推送。

### 3.3 其他幂等相关行为

- **订单完成 tradeCompleted(orderId, ...)**（exchange-core ExchangeOrderService）：  
  若订单状态已非 `TRADING`（例如已是 COMPLETED），直接返回错误，不更新订单、不执行退冻结。  
  → 同一订单完成被重复调用时，第二次不会重复改库与退币，但会返回错误码。

- **成交明细 processExchangeTrade**（exchange-core ExchangeOrderService）：  
  在「按 messageId 只执行一次 processMatchResult」的前提下，单条消息内不会重复调用；若存在其他重复来源，仍需按 tradeId 等做明细级幂等。

### 3.4 建议（与当前实现一致）

- **仅消费 exchange-match-result**：当前已通过 messageId + processed_match_result_message 做幂等；不订阅或不再处理 `exchange-trade` / `exchange-order-completed` 时，无需额外幂等逻辑。
- **同时消费三个 topic**：  
  - exchange-match-result 已按 messageId 幂等。  
  - 订单完成 / 成交明细若从旧 topic 再次收到，tradeCompleted 对非 TRADING 会返回错误；成交明细仍建议按 tradeId 去重。

---

## 四、订单状态在撮合前后的修改位置（当前实现）

当前枚举 **ExchangeOrderStatus** 仅有：`TRADING`（交易中）、`COMPLETED`（已完成）、`CANCELED`（已取消）、`OVERTIMED`（超时）。没有“NEW”；下单落库时即为 TRADING，可理解为“新单→交易中”；完全成交或取消后变为 COMPLETED/CANCELED，即“撮合结束”。

### 4.1 谁改、在哪改

| 状态变化 | 修改位置（模块.类.方法） | 触发时机 |
|----------|--------------------------|----------|
| → **TRADING**（新单进入“交易中”） | **exchange-core**：`ExchangeOrderService.addOrder`、`ExchangeOrderService.addOrderForApi` | 用户下单：exchange-api 的 `OrderController.addOrder` 等调用 `orderService.addOrder`，订单首次落库时 `order.setStatus(ExchangeOrderStatus.TRADING)`。 |
| → **COMPLETED**（完全成交，撮合结束） | **exchange-core**：`ExchangeOrderService.tradeCompleted` | market 消费 `exchange-match-result` 后调用 `processMatchResult`，对每条已完全成交订单调用 `tradeCompleted(orderId, tradedAmount, turnover)`，其内 `order.setStatus(ExchangeOrderStatus.COMPLETED)` 并退剩余冻结。 |
| → **CANCELED**（已取消） | **exchange-core**：`ExchangeOrderService.cancelOrder`、`forceCancelOrder` | 用户撤单或强制取消时，订单状态改为 CANCELED，并退未成交冻结。 |

### 4.2 撮合引擎是否改库

- **CoinTrader（exchange 模块）** 只维护内存订单簿，**不写数据库**，也**不修改订单的 status 字段**。
- 订单从 Kafka（exchange-order）进入时，库里已是 TRADING；撮合完成后，由 market 消费 exchange-match-result 后调 `tradeCompleted` 把库里该订单改为 COMPLETED。

即：**“new → matching-finished”在当前实现中 = 下单时设为 TRADING（exchange-core addOrder/addOrderForApi）→ 完全成交后设为 COMPLETED（exchange-core tradeCompleted，由 market 消费 exchange-match-result 触发）。**

---

## 五、关键类与配置一览（当前实现）

| 模块    | 类/配置 | 说明 |
|---------|---------|------|
| exchange | CoinTrader | 单交易对撮合引擎；flushMatchResult 调用 MatchResultPublisher.publish。 |
| exchange | QueueAndWalMatchResultPublisher | 方案 A：队列 + WAL 写 + 按偏移发 Kafka。 |
| exchange | CoinTraderConfig | 为每个启用交易对创建 CoinTrader 与 QueueAndWalMatchResultPublisher，并 start()。 |
| exchange-core | MatchResult | 单条消息体：**messageId**（发送时生成）、symbol, ts, trades, completedOrders。 |
| exchange-core | ExchangeOrderService.processMatchResult | 单事务：先处理本批 trades，再处理本批 completedOrders（tradeCompleted）。 |
| exchange-core | ExchangeOrderService.processMatchResultIdempotent | 按 messageId 幂等：已存在则返回 false；否则 insert messageId 后 processMatchResult，同一事务。 |
| exchange-core | ExchangeOrderService.tradeCompleted | 订单状态非 TRADING 则返回错误，不重复更新与退冻结。 |
| exchange-core | ProcessedMatchResultMessage / ProcessedMatchResultMessageRepository | 表 processed_match_result_message（message_id 唯一），记录已处理的 messageId。 |
| market | ExchangeTradeConsumer.handleMatchResult | 消费 exchange-match-result，解析 messageId 后调用 processMatchResultIdempotent；若返回 false 则跳过推送，否则继续推送行情与订单通知。 |
| exchange-core | ExchangeOrderService.addOrder / addOrderForApi | 下单落库，设置 status=TRADING。 |
| exchange-core | ExchangeOrderService.tradeCompleted | 完全成交时设置 status=COMPLETED 并退冻结。 |
| 配置项 | match.wal.path | WAL 根目录，默认 data/wal。 |

---

## 六、与典型 CEX 架构的差异

主流 CEX 常采用「撮合 → 清算 → 结算 → 资金」分层：撮合只产出交易流水，推给**清算系统**；清算系统做清算（手续费、分摊、强平等），**结算系统**按清算结果生成资金指令，**资金系统**再执行划转、落账。即：**撮合只推流水，不直接驱动资金变动**。

本项目中为简化实现，**未单独拆出清算、结算、资金系统**：

- 撮合结果（exchange-match-result）直接推给 **market** 模块。
- market 内 `ExchangeOrderService.processMatchResult` **一次完成**：落成交明细、更新订单状态、**直接改钱包余额/冻结**、记流水、返佣等，相当于把「清算 + 结算 + 资金处理」合并在同一套落库与资金逻辑中。

若后续要贴近典型 CEX 分层，可考虑：撮合只推送**交易流水**至清算服务，由清算产出清算结果，再经结算生成资金指令，由独立资金系统执行变动；当前实现可作为简化版对照。

---

*文档仅描述当前代码实现，不包含未实现的扩展方案。*
