# CoinTrader 撮合引擎分析与撮合成功落库流程

## 一、整体架构与职责

- **CoinTrader**（exchange 模块）：**纯内存撮合引擎**，按交易对（symbol）一个实例，只做订单簿维护与撮合逻辑，**不落库**。
- **落库与资金**：由 **market 模块** 消费 Kafka 消息后，调用 **exchange-core** 的 `ExchangeOrderService` 完成成交明细、订单状态、钱包、流水的持久化。

---

## 二、CoinTrader 核心数据结构

| 结构 | 类型 | 说明 |
|------|------|------|
| `buyLimitPriceQueue` | TreeMap&lt;BigDecimal, MergeOrder&gt; | 买单限价队列，**价格从高到低**（reverseOrder），保证最优买价优先 |
| `sellLimitPriceQueue` | TreeMap&lt;BigDecimal, MergeOrder&gt; | 卖单限价队列，**价格从低到高**（naturalOrder），保证最优卖价优先 |
| `buyMarketQueue` / `sellMarketQueue` | LinkedList&lt;ExchangeOrder&gt; | 市价单队列，**按时间 FIFO** |
| `buyTradePlate` / `sellTradePlate` | TradePlate | 盘口深度，用于推送给前端的买一卖一档等 |

- **MergeOrder**：同一价格下的多笔订单合并为一个节点，内部按时间排序，便于价格优先 + 时间优先（PTTF）。

**为什么限价队列选 TreeMap（红黑树）？**

- **价格天然有序**：TreeMap 基于红黑树实现，按价格自动排序，能在 O(log N) 时间拿到当前最优买价/卖价（firstKey/lastKey），非常贴合撮合里“总是先吃最优价”的需求。
- **插入/删除复杂度可控**：挂单、撤单本质是对某个价格档插入或删除节点，红黑树能保证这些操作始终是 O(log N)，维护大规模盘口时性能稳定。
- **支持价区间遍历**：需要在某个价格区间内扫描或统计时，可直接利用 TreeMap 的有序特性（如迭代子区间），不必额外维护排序结构。

---

## 三、trade() 方法流程说明

### 3.1 入口与前置校验

1. **tradingHalt**：为 true 时直接 return，不撮合（运维可暂停交易）。
2. **symbol 校验**：当前引擎只处理本交易对，否则忽略。
3. **金额校验**：`amount <= 0` 或 `(amount - tradedAmount) <= 0` 表示无剩余可成交量，直接 return。

### 3.2 对手盘队列选择

- **当前订单是买单** → 对手盘：卖限价 `sellLimitPriceQueue`、卖市价 `sellMarketQueue`。
- **当前订单是卖单** → 对手盘：买限价 `buyLimitPriceQueue`、买市价 `buyMarketQueue`。

这样保证「买对卖、卖对买」。

### 3.3 市价单路径

- 只和**限价对手盘**撮合：`matchMarketPriceWithLPList(limitPriceOrderList, exchangeOrder)`。
- 市价单不挂盘口，要么当场吃单成交，要么有剩余再放回市价队列（见 3.5）。

### 3.4 限价单路径

1. **价格校验**：限价单 `price <= 0` 直接 return。
2. **分摊模式特殊逻辑**（FENTAN）：仅当该交易对是「分摊」且当前是**卖单**且在清盘时间前，走 `matchLimitPriceWithLPListByFENTAN`，按比例分摊成交，不进入普通限价队列。
3. **先和限价对手盘撮合**：`matchLimitPriceWithLPList(limitPriceOrderList, exchangeOrder, false)`。
4. **若未完全成交**：再和市价对手盘撮合 `matchLimitPriceWithMPList(marketPriceOrderList, exchangeOrder)`。
5. **若仍有剩余**：通过 `addLimitPriceOrder(focusedOrder)` 把当前订单挂到本方限价队列并更新盘口。

顺序含义：**价格优先**（先吃限价盘最优价），再吃市价盘，最后未成交部分挂单。

### 3.5 撮合过程（以 matchLimitPriceWithLPList 为例）

1. **synchronized (lpList)**：对对手盘限价队列加锁，保证同一交易对撮合串行，避免并发修改订单簿。
2. **遍历对手盘价格档**：买单从「最低卖价」起、卖单从「最高买价」起（TreeMap 迭代顺序保证）。
3. **价格可匹配条件**：
   - 买单：`mergeOrder.getPrice() <= focusedOrder.getPrice()` 才能成交，否则 break（后面价格更高更不可能成交）。
   - 卖单：`mergeOrder.getPrice() >= focusedOrder.getPrice()` 才能成交，否则 break。
4. **同价位内按时间**：对 MergeOrder 内订单顺序迭代，调用 `processMatch(focusedOrder, matchOrder)` 生成一笔 **ExchangeTrade**，并更新双方 `tradedAmount`、`turnover`。
5. **完成订单移除**：若某笔订单 `isCompleted()`（tradedAmount >= amount），从 MergeOrder 中 remove，并加入 `completedOrders`。
6. **焦点订单完成**：若当前订单已完全成交，加入 completedOrders 并 exitLoop 退出。
7. **空档位清理**：若某价格档 MergeOrder 为空，从 TreeMap 中 remove。
8. **未完全成交**：若 `canEnterList==true` 且仍有剩余，调用 `addLimitPriceOrder(focusedOrder)` 挂到本方限价队列。
9. **统一后续处理**：  
   - `handleExchangeTrade(exchangeTrades)` → 发 Kafka **exchange-trade**；  
   - `orderCompleted(completedOrders)` → 发 Kafka **exchange-order-completed**；  
   - 若有完成订单，推送盘口变更 `sendTradePlateMessage(plate)`。

### 3.6 processMatch：单笔成交逻辑

- **成交价**：对手方是限价单则以对手价，否则以焦点单价格（市价单场景）。
- **成交量**：`min(焦点单剩余可成交量, 对手单剩余可成交量)`；市价买单的「剩余」按剩余金额/价格换算数量。
- **更新双方**：`tradedAmount += tradedAmount`，`turnover += turnover`。
- **构造 ExchangeTrade**：symbol、price、amount、buyOrderId、sellOrderId、direction、time 等，用于下游落库与推送。
- **盘口**：若对手方是限价单，从对应 TradePlate 中 remove 该订单（或部分数量），保证盘口与订单簿一致。

---

## 四、撮合成功如何落库（不发生在 CoinTrader 内）

CoinTrader **只发 Kafka**，落库全部在 **market 模块** 的消费者中完成。

### 4.1 消息与消费者对应关系

| Kafka Topic | 发送位置 | 消费者 | 作用 |
|-------------|----------|--------|------|
| **exchange-trade** | `handleExchangeTrade(trades)` | market 的 `ExchangeTradeConsumer#handleTrade` | 每笔成交明细 → 落库 + 钱包 + 流水 |
| **exchange-order-completed** | `orderCompleted(orders)` | market 的 `ExchangeTradeConsumer#handleOrderCompleted` | 订单完全成交 → 更新订单状态 + 剩余冻结退款 |

### 4.2 exchange-trade 落库流程（每笔成交）

1. **ExchangeTradeConsumer** 收到 `exchange-trade`，解析为 `List<ExchangeTrade>`，提交到线程池 **HandleTradeThread**。
2. 每条 **ExchangeTrade** 调用：  
   `exchangeOrderService.processExchangeTrade(trade, secondReferrerAward)`。
3. **processExchangeTrade** 内（exchange-core）：
   - 根据 `buyOrderId`、`sellOrderId` 查库得到 `ExchangeOrder` 买卖双方订单；
   - 按 **memberId 对 member_wallet 加锁**（`for update`），避免死锁与并发错误；
   - 对**买方订单**、**卖方订单**各调用一次 **processOrder**：
     - 写 **ExchangeOrderDetail**（成交明细：orderId、price、amount、turnover、fee）；
     - 写 **OrderDetailAggregation**（Mongo，用于统计/报表）；
     - **手续费**：买收交易币、卖收基准币，按配置费率计算；
     - **买方**：增加「交易币」可用、扣减「基准币」冻结；**卖方**：增加「基准币」可用、扣减「交易币」冻结；
     - 写两条 **MemberTransaction**（一进一出）；
     - 若开启推广返佣，则 **promoteReward**（一级/二级推荐人）。
4. 推送：WebSocket / Netty 推送给对应用户「订单成交」、并更新 K 线（CoinProcessor.process）。

### 4.3 exchange-order-completed 落库流程（订单完全成交）

1. **ExchangeTradeConsumer#handleOrderCompleted** 收到 `exchange-order-completed`，解析为 `List<ExchangeOrder>`。
2. 对每个订单调用：  
   `exchangeOrderService.tradeCompleted(order.getOrderId(), order.getTradedAmount(), order.getTurnover())`。
3. **tradeCompleted** 内：
   - 校验订单状态为 TRADING；
   - 更新订单：`tradedAmount`、`turnover`、`status=COMPLETED`、`completedTime`，**saveAndFlush**；
   - **orderRefund**：计算「下单时冻结」与「实际成交占用」的差额，将**剩余冻结**解冻回可用（thawBalance）。

因此：**成交明细与资金变动**由 **exchange-trade** 驱动逐笔落库；**订单状态变为已完成 + 剩余冻结退回**由 **exchange-order-completed** 驱动一次落库。

---

## 五、小结

- **CoinTrader**：内存订单簿 + 价格/时间优先撮合，产出 **ExchangeTrade** 与已完成 **ExchangeOrder**，通过 **exchange-trade**、**exchange-order-completed** 发往 Kafka。
- **落库**：由 **market** 的 **ExchangeTradeConsumer** 消费上述两个 topic，调用 **ExchangeOrderService.processExchangeTrade**（每笔成交写明细、改钱包、写流水、返佣）和 **tradeCompleted**（订单完成、退剩余冻结），实现撮合结果与资金、订单状态的一致性持久化。

---

## 六、Kafka 发送失败怎么办？

### 6.1 当前风险

- **exchange-trade**：`kafkaTemplate.send(...)` 为**异步、无回调**，发送失败时不会有任何重试或记录，**撮合结果丢失** → 下游永远不落库，资金与订单状态不一致。
- **exchange-order-completed**：有 `ListenableFuture` + `onFailure` 回调，但**仅打日志**，不重试、不落库，失败后订单在 DB 端一直处于 TRADING，剩余冻结不会退回。
- **exchange-trade-plate**：盘口推送失败只影响行情展示，不影响资金，但同样无重试。

一旦 Kafka 不可用或网络抖动，**已发生的撮合无法被下游感知**，属于严重一致性问题。

### 6.2 方案一：同步发送 + 有限重试 + 失败告警（可落实现有 exchange 模块）

思路：在**不引入 DB** 的前提下，把关键消息改为**同步等待发送结果**，失败则重试若干次（如 3 次、带退避），仍失败则：

- 打 **ERROR 日志**，并带上完整 payload（便于人工或脚本补发）；
- 接入**告警**（钉钉/邮件/监控），确保有人处理；
- 若有简单持久化能力（如本地文件、或 exchange 模块后续接入 DB），可把失败内容写入「失败表/文件」，由定时任务或运维脚本二次投递。

**优点**：改动小，能显著降低“发了但不知道失败”的概率。  
**缺点**：同步发送会拉长撮合线程的 RT，Kafka 慢或不可用时会影响吞吐；失败后仍需人工或脚本补发，无法自动恢复。

### 6.3 方案二：Outbox 模式（需架构调整）

思路：**撮合结果先落库，再异步发 Kafka**，保证“只要业务库有记录，消息最终可发出”。

1. **谁落库**：exchange 模块当前无 DB，需要二选一：  
   - 在 **exchange** 引入轻量 DB（或复用现有库），在调用 `trader.trade(order)` 的**上层**（如消费 `exchange-order` 的 Consumer）里，在**同一事务**中：写入「成交明细 + 完成订单」到业务表或 Outbox 表；  
   - 或由 **market** 模块在消费某条“原始订单/事件”后，先写本库的 Outbox，再发 Kafka（此时需保证“撮合结果”能先落到某处，例如 exchange 通过 HTTP 回调 market 写 Outbox，再由 market 发 Kafka）。
2. **发送**：独立进程或定时任务从 Outbox 表扫「待发送」记录，发 Kafka，成功则标记已发送，失败则重试。
3. **幂等**：下游消费时按 `orderId + tradeId` 等做幂等，避免重复落库。

**优点**：与“本地事务 + 消息”一致，不丢消息，Kafka 宕机也不影响业务落库。  
**缺点**：需要 exchange 或 market 有 DB、以及调用链/职责拆分（例如撮合结果由谁先落库、谁写 Outbox）。

### 6.4 建议

- **短期**：对 **exchange-trade**、**exchange-order-completed** 做**同步发送 + 有限重试 + 失败 ERROR 日志与告警**，必要时把失败 payload 写文件或简单表，便于补发。  
- **中期**：若对一致性要求高，规划 **Outbox**：让撮合结果在“发 Kafka”前先进入可持久化存储，再由独立任务发送，并配合下游幂等。

---

## 七、撮合性能与落库：会影响吗？业界主流 CEX 方案

### 7.1 撮合服务里同步 DB 落库对性能的影响

**会明显影响。** 若在撮合热路径上每笔成交都同步写 DB：

| 因素 | 影响 |
|------|------|
| **延迟** | 单次 DB 往返通常 1～10ms+，撮合延迟会直接叠加上去，P99 明显变差。 |
| **吞吐** | 单机 DB TPS 有限（几千～几万），撮合可达几十万 TPS，同步写库很快成为瓶颈。 |
| **锁与事务** | 频繁写订单/成交/账户表，锁竞争与事务提交会进一步拉高延迟、限制并发。 |

因此：**不在撮合核心循环里做「每笔成交同步落库」。**

### 7.2 业界主流 CEX 的做法：撮合与落库解耦

- **撮合引擎**：**纯内存**，只维护订单簿与撮合逻辑，不直接写业务 DB。
- **结果输出**：撮合只做「写出结果」到**本地 WAL / 内存队列 / Kafka** 等，不等待 DB。
- **落库**：由**独立进程/服务**消费上述事件流，**异步、可批量**写 DB（成交明细、订单状态、账户、流水等）。

即：**DB 不在撮合热路径上**，落库延迟与 DB 抖动不会拖慢撮合。

### 7.3 常见形态

1. **撮合 → Kafka → 下游消费者落库**（与当前项目一致）  
   撮合发 Kafka，market 等模块消费后写 DB、改钱包、推行情。只要 Kafka 发送可靠（同步重试 + 告警），即符合主流做法。

2. **撮合 → 本地 WAL / 队列 → 独立 Persistence 服务**  
   撮合先写本地日志或队列（保证可恢复），再由专门服务批量读、批量写 DB，进一步减轻 DB 压力。

3. **批量落库**  
   下游按「每 N 条」或「每 T 毫秒」攒批再写 DB，降低写次数、提高吞吐。

### 7.4 若必须在撮合侧“触达”DB 时如何减损

若架构上必须由撮合服务写 DB，应避免在**撮合线程**里同步等 DB：

- **异步写**：撮合只投递到内存队列，由**单独线程/线程池**消费队列写 DB。
- **Outbox**：先写本地 Outbox 表或文件，再由定时任务/线程发 Kafka 或写 DB，撮合线程不阻塞。

本质都是：**不让「等 DB」发生在撮合关键路径上。**

### 7.5 小结（可作面试/文档结论）

- **撮合服务里同步 DB 落库会显著影响性能**，一般不采用。
- **主流 CEX 方案**：撮合**只做内存 + 事件输出**（Kafka / WAL / 队列），**落库由下游服务异步完成**，可配合批量、Outbox、重试。
- 当前「CoinTrader 发 Kafka → market 消费落库」即属该模式；加强 Kafka 发送可靠性（同步重试 + 失败告警/补发）即可，无需在撮合进程内加 DB 落库。
