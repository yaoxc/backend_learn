## CEX 交易所项目 · 模拟面试归档（撮合 + 钱包）

> 基于简历（多链托管钱包 + Bitop 交易系统）与当前项目（backend_learn：钱包 / 充值 / 提币 / 撮合 / 行情），整理的一份**高频问答 + 流程案例**归档，用于准备「CEX 交易所核心后端」相关面试。

---

## 一、开场：自我介绍 & 项目定位

### 1. 自我介绍（1–2 分钟）

**参考要点**：11 年经验 → Bitop 区块链后端 → 交易撮合引擎 + 托管钱包双线；简历中的「百万级日订单」「多链 EVM+Solana」「nonce 管理」「三态确认」一句话带出。

**参考范文**：

> 您好，我是 Felix，有 11 年后端开发经验，本科毕业于中南民族大学计算机专业。目前在上海 Bitop 担任区块链后端工程师，主要负责两块：一是**交易撮合引擎**，基于内存订单簿做限价、市价撮合，配合 Kafka 做状态持久化和恢复，支撑百万级日订单；二是**多链托管钱包**，覆盖 EVM 和 Solana，从构造、签名、广播到链上确认和重组回滚的整条链路，包括热钱包 nonce 管理、三态确认和跨服务幂等设计，保证资金安全和高可用。
>
> 技术栈上，后端以 **Java** 和 **Go** 为主，熟悉 Spring Cloud、Redis、MySQL、Kafka，区块链侧用过 Web3j、go-ethereum 和 Solidity。之前在地产、教育、游戏等行业做过中台和业务系统，近一年专注在 CEX 交易所的核心模块。希望能在贵司继续深耕交易所或钱包方向，把现有经验用起来，谢谢。

---

## 二、订单创建 → 撮合 → 行情推送 → 资金入账：完整闭环

### 2. 总览：一条链路一句话

- **从端到端**：  
  **用户下单 → 校验 & 冻结资金 + 写订单(MySQL) → 写入 outbox → Kafka `exchange-order-ingress` → relay 转发到 `exchange-order` → 撮合引擎 `CoinTrader` → 统一产出撮合结果 Kafka `exchange-match-result`（trades + completedOrders）→**
  **market 消费：成交明细落库 + 行情/K线/推送；clearing 消费：清算落库并发布 `exchange-clearing-result`；settlement 消费：生成资金指令并发布 `exchange-fund-instruction`；fund 消费：执行钱包入账/扣减并落资金流水。**

---

### 3. 详细链路（对应代码与类）

#### 3.1 用户下单 & 冻结资金

- **入口**：`OrderController.addOrder()`（`exchange-api`）
- **关键步骤**：
  - 参数、风控、精度校验。
  - **冻结资金**：
    - 买单：冻结计价币（如 BTC/USDT 中的 USDT）。
    - 卖单：冻结标的币（如 BTC）。
    - 账户表里典型有 `balance`（可用）、`frozen`（冻结）两个字段：
      - 下单时：`balance -= 需要金额；frozen += 需要金额`，放在一个事务里。
  - 落订单库 `ExchangeOrder`（状态 `TRADING`）。
  - **可靠投递（Outbox）**：
    - 本项目采用 “落库 + outbox” 的方式保证事件可靠投递：订单写入 MySQL 后，同时写一条 outbox 记录；
    - 后台再把 outbox 投递到 Kafka（下单入口 topic：`exchange-order-ingress`），避免“订单落库成功但消息丢失”。

> **面试可说**：  
> 「我们下单是‘先冻结后入撮合’，并且用 outbox 保证可靠投递。以限价买单为例，会在同一个事务里校验余额并从 `balance` 扣到 `frozen`，插入订单记录（TRADING）+ 写 outbox，随后异步投递到 Kafka 的下单入口 topic，保证‘订单落库’与‘进入撮合队列’最终一致。」

---

#### 3.2 Kafka → 撮合入口（订单进 CoinTrader）

- **入口 topic 与内部 topic 分离**：
  - 外部服务写入 `exchange-order-ingress`（下单入口 topic）。
  - `exchange-relay` 消费入口 topic 后转发到撮合内部 topic `exchange-order`（按 symbol 作为 key，保证同交易对落同分区）。
- **撮合消费者**：`ExchangeOrderConsumer.onOrderSubmitted()`（`exchange`）
  - 从 Kafka 读取内部 topic `exchange-order`。
  - 反序列化为 `ExchangeOrder`。
  - `CoinTrader trader = traderFactory.getTrader(order.getSymbol())` 获取当前交易对撮合实例。
  - 若 `trader.isTradingHalt()` 或 `!trader.getReady()`：
    - 说明交易对停牌或未就绪 → 直接发送 `exchange-order-cancel-success`，撤回订单。
  - 否则调用：
    - `trader.trade(order)` 作为唯一撮合入口。

> **面试可说**：  
> 「撮合入口是 `CoinTrader.trade(ExchangeOrder)`，外部任何线程下单最终都要走到这里；中间通过 Kafka 做缓冲和顺序控制。」

---

#### 3.3 撮合核心：订单簿、撮合逻辑、生成 ExchangeTrade

- **核心类**：`CoinTrader`（`exchange/Trader`）
- **订单簿结构**：
  - 买盘：`TreeMap<BigDecimal, MergeOrder> buyLimitPriceQueue`（降序）。
  - 卖盘：`TreeMap<BigDecimal, MergeOrder> sellLimitPriceQueue`（升序）。
  - 市价单：按时间排队的列表。
- **撮合过程**（简化）：
  - `trade(order)` 根据方向 + 类型路由到：
    - `matchLimitPriceWithLPList(...)`（限价 vs 限价）
    - `matchMarketPriceWithLPList(...)`（市价 vs 限价）
  - 满足价格条件时，进入 `processMatch(...)`：
    - 计算成交量 `tradedAmount = min(买剩余, 卖剩余)`。
    - 以挂单价格为成交价（具体实现看代码，主流是以挂单价为准）。
    - 构建 `ExchangeTrade`：
      - `symbol`, `amount`, `price`
      - `buyOrderId`, `sellOrderId`
      - 成交额、时间戳等。
    - 更新订单本身的已成交数量和状态。
- **统一出口（方案 A）**：
  - 撮合引擎把“成交明细 + 完全成交订单”合并为一条消息，发送到 `exchange-match-result`：
    - `trades[]`：本次撮合产生的成交明细
    - `completedOrders[]`：本次撮合完成的订单
    - `messageId`：用于幂等去重/防重复处理
  - 这样下游只需要监听一个 topic，减少多 topic 的一致性问题。

---

#### 3.4 下游消费：成交入库、行情 K 线与盘口

- **market（行情/成交明细/推送）**：`ExchangeTradeConsumer` 消费 `exchange-match-result`
  - 幂等处理（按 `messageId` 做去重）：订单状态落库 + 成交明细落库（必要时写 Mongo 聚合）；
  - `CoinProcessor` 更新 K 线与 24h 行情；
  - WebSocket/Netty 推送成交与订单变更。

- **clearing（清算）**：`ClearingMatchResultConsumer` 消费 `exchange-match-result`
  - 计算清算结果落库，发布 `exchange-clearing-result`（幂等按 `messageId`）。

- **settlement（结算）**：`SettlementConsumer` 消费 `exchange-clearing-result`
  - 生成资金指令落库并发布 `exchange-fund-instruction`（幂等按 `messageId`）。

- **fund（资金执行）**：`FundInstructionConsumer` 消费 `exchange-fund-instruction`
  - 执行钱包扣减/入账与流水落库（幂等按 `messageId`），最终资金以资金服务落库为准。

> **面试要点**：market **不直接改钱包**（走清算→结算→资金指令流水线时），避免把“行情与撮合结果处理”绑定到资金一致性上。

---

### 4. 冻结资金机制（重点讲解版）

#### 4.1 为什么要冻结资金

- 防止同一笔钱被重复下单：先锁住，再给撮合用。
- 保證撮合一定有对应资金支撑，避免撮合成功后发现余额不够。

#### 4.2 数据结构与基本操作

- 账户/钱包表里通常有：
  - `balance`：可用余额。
  - `frozen`：冻结余额（和未完成订单绑定）。

**创建买单时（例：买 0.5 BTC @ 30000，需冻结 15000 USDT）**：

1. 校验：

```java
need = price * amount  // 15000
assert balance >= need
```

2. 同一个事务里：

```java
balance = balance - need
frozen  = frozen + need
insert ExchangeOrder(...)
```

> 面试时可以补一句：  
> 「实际实现里会用行锁/乐观锁或条件更新，比如 `UPDATE account SET balance=balance-?, frozen=frozen+? WHERE id=? AND balance>=?`，更新条数为 0 就表示余额不够，天然抗并发。」

**成交/撤单时**：

- 成交：
  - 买单：`frozen -= 成交额`；对手币种 `balance += 成交数量`。
  - 卖单：`frozen -= 成交数量`；对手币种 `balance += 成交额`。
- 撤单：
  - 把未成交对应的冻结金额从 `frozen` 退回 `balance`。

#### 4.3 成交时 frozen 与流水：新增流水、不修改原冻结（含部分成交）

- **原则**：流水表（资金变动记录）**只追加、不修改**，便于审计和对账；成交时**不修改**当初下单产生的那条「冻结」流水，而是**为本次成交新增流水**。

**常见流水类型示例**：

| 类型 | 含义 | 方向 |
|------|------|------|
| FROZEN | 下单冻结 | 可用 → 冻结 |
| UNFROZEN | 撤单解冻 | 冻结 → 可用 |
| TRADE_FROZEN_OUT | 成交扣减冻结（支出） | 冻结减少，不回到可用 |
| TRADE_CREDIT | 成交入账（对手币） | 可用增加 |

**成交时具体做法（全量/部分同理）**：

- **买家（USDT 买 BTC）**  
  - 本次成交额 = price × amount（例：30000 × 0.3 = 9000 USDT）。  
  - **新增两条流水**：  
    1. **TRADE_FROZEN_OUT**：币种 USDT，金额 = 成交额，扣减冻结；关联 order_id、trade_id。  
    2. **TRADE_CREDIT**：币种 BTC，数量 = 成交数量，可用增加；关联 order_id、trade_id。  
  - 汇总表（若有）：USDT `frozen -= 成交额`，BTC `balance += 成交数量`。

- **卖家（卖 BTC 收 USDT）**  
  - **新增两条流水**：  
    1. **TRADE_FROZEN_OUT**：币种 BTC，数量 = 成交数量，扣减冻结。  
    2. **TRADE_CREDIT**：币种 USDT，金额 = 成交额，可用增加。  
  - 汇总表：BTC `frozen -= 成交数量`，USDT `balance += 成交额`。

- **订单表**：更新该订单的 `traded_amount`（累加本次成交数量），全部成交时 `status = COMPLETED`。

**部分成交时**：

- **每次**一笔成交（一个 `ExchangeTrade`），只针对**本笔**的 amount/price 新增上述流水，不删、不改当初的 FROZEN 流水。
- **剩余冻结**：业务上用「订单初始冻结 − 已成交占用」计算，或对流水按 order_id 汇总 TRADE_FROZEN_OUT 得到已消耗冻结。
- **撤单**：对「未成交部分」新增 **UNFROZEN** 流水（冻结减、可用加），不修改原 FROZEN 流水。

**小结（面试可答）**：

| 问题 | 做法 |
|------|------|
| 成交时 frozen 怎么动？ | 不改原冻结流水，**新增流水**：一条「冻结扣减/支出」（TRADE_FROZEN_OUT），一条「对手币入账」（TRADE_CREDIT）。 |
| 部分成交呢？ | 每次成交只对本笔写这两类流水，订单累加 `traded_amount`；剩余冻结由「初始冻结 − 已成交占用」或流水汇总得到，撤单时对未成交部分写 UNFROZEN。 |
| 为什么用新增流水？ | 流水表 append-only，便于审计和对账；改历史流水会破坏可追溯性，高并发下也容易产生锁和一致性问题。 |

---

### 5. 完整数值案例（BTC/USDT）

#### 5.1 初始状态

- 交易对：`BTC/USDT`
- 用户 A（买家）：
  - USDT：`balance = 20000, frozen = 0`
  - BTC：`balance = 0, frozen = 0`
- 用户 B（卖家）：
  - BTC：`balance = 1.0, frozen = 0`
  - USDT：`balance = 0, frozen = 0`

#### 5.2 A 下买单：买 0.5 BTC @ 30000

- 需要 USDT：`0.5 * 30000 = 15000`
- 冻结后：
  - A USDT：`balance = 5000, frozen = 15000`
- 生成订单 `O_A_1`，发 Kafka `exchange-order`。

#### 5.3 B 下卖单：卖 0.5 BTC @ 29900

- 冻结：
  - B BTC：`balance = 0.5, frozen = 0.5`
- 生成订单 `O_B_1`，发 Kafka `exchange-order`。

#### 5.4 撮合引擎撮合

- 撮合发现：卖价 29900 ≤ 买盘最优价 30000 → 可成交。
- 假设成交价取买盘价 30000：
  - 成交量：`0.5`
  - 成交额：`15000`
- 生成成交记录 `T_1`：
  - `symbol = BTC/USDT`
  - `amount = 0.5`
  - `price = 30000`
  - `buyOrderId = O_A_1`
  - `sellOrderId = O_B_1`
- 修改订单：
  - `O_A_1.status = COMPLETED, tradedAmount = 0.5`
  - `O_B_1.status = COMPLETED, tradedAmount = 0.5`
- 发送事件（统一出口）：
  - `exchange-match-result`：包含 `messageId` + `trades=[T_1]` + `completedOrders=[O_A_1, O_B_1]`

#### 5.5 成交落库 & 行情（market）

- `exchange-match-result` 被 `market` 消费：
  - 明细表插入 `T_1`，订单状态更新为 `COMPLETED`。
  - `DefaultCoinProcessor` 更新 1m K 线、24h 行情。
  - WebSocket/Netty 推送最新成交与订单变更。

#### 5.6 资金最终入账（clearing → settlement → fund）

- `exchange-match-result` → 清算发布 `exchange-clearing-result` → 结算发布 `exchange-fund-instruction` → 资金服务执行入账：

**买家 A：**

- 初始：USDT `balance=5000, frozen=15000`。
- 成交后：
  - USDT：`frozen = 15000 - 15000 = 0`，`balance` 保持 5000。
  - BTC：`balance = 0 + 0.5 = 0.5`。

**卖家 B：**

- 初始：BTC `balance=0.5, frozen=0.5`。
- 成交后：
  - BTC：`frozen = 0.5 - 0.5 = 0`，`balance` 仍 0.5（未卖出的那半个）。
  - USDT：`balance = 0 + 15000 = 15000`。

**最终结果**：

- A：`USDT 可用 5000, BTC 可用 0.5`
- B：`USDT 可用 15000, BTC 可用 0.5`

---

## 三、Kafka 分区与顺序：symbol / symbol+side

### 6. 分区 key 设计

- 目标：**保证同一交易对的订单严格有序进入同一个撮合实例**。
- 实现方式：
  - Kafka Producer 发送时设置 key，Kafka 以 key 做分区路由。

**常见 key 方案**：

- **方案 1：只用交易对 symbol（推荐简化版）**

```text
key = "BTCUSDT"
key = "ETHUSDT"
```

- **方案 2：用 symbol+side（更细粒度，可将买卖拆到不同线程）**

```text
key = "BTCUSDT-BUY"
key = "BTCUSDT-SELL"
key = "ETHUSDT-BUY"
key = "ETHUSDT-SELL"
```

> 面试可说：  
> 「我们是按 symbol 做分区 key 的，比如 `BTCUSDT`，这样同一交易对的订单都在一个分区、一个撮合线程里，天然保证顺序；如果要进一步拆分，也可以用 `symbol+side` 作为 key，把买卖撮合线程拆开做并行。」

---

## 四、钱包 & 多链托管钱包高频问答（提纲）

### 7. 多链托管钱包（结合简历项目 1）

- **确认终态**：如何用 `confirmed / safe / finalized` + 重组检测判断终态。
- **nonce 管理**：
  - 热钱包维度集中管理 nonce。
  - PendingNonceAt 同步链上值 + 本地原子递增。
  - 解决提现与归集并发导致的「nonce too low」。
- **跨服务幂等与双签**：
  - operation_id 贯穿 wallet / db_gateway / signer。
  - 幂等表或唯一键避免重复广播与重复记账。

（详细问题可参考之前问答章节 Q1–Q3，不再赘述。）

---

## 五、总结 & 使用方式

- **使用方式**：
  - 面试前按顺序过一遍：  
    1）自我介绍  
    2）「下单 → 撮合 → 行情 → 资金」整条链路 + BTCUSDT 数值案例  
    3）冻结资金 & 并发安全说法  
    4）Kafka 分区 key 的设计理由  
    5）多链托管钱包的亮点（交易全生命周期、nonce、重组回滚）。
- **对照代码/文档**：
  - `market/委托单提交到推送前端流程.md`  
  - `exchange/Trader/CoinTrader解析.md`  
  - `Z_ReadMe/CEX下单到成交.md`  
  - `wallet/eth-support/EthService.java`、`wallet/eth/EthWatcher.java` 等。

**一句话**：面试时围绕「撮合引擎 + 钱包资金安全 + 事件驱动架构」这三条主线，把上面的链路和案例讲顺，就能非常完整地呈现你在 CEX 交易所方向的经验。**

---

## 六、Kafka / 消息队列专项高频问答（含参考回答提纲）

> 这一节是结合当前项目中 Kafka 的实际用法 + 两份 Kafka/消息队列面试资料，总结出的问答提纲，适合初级/中级开发背诵使用。

### 1. 为什么要用 Kafka？在你们项目里用在了哪些地方？（高频）

- **答题关键词**：解耦、异步、削峰。
- **参考答法**：
  - 在我们交易所项目里，Kafka 主要解决三个问题：**解耦、异步、削峰**。
  - **解耦**：下单、撮合、清算、结算、资金、行情、推送之间通过 Topic 通信。撮合统一出口 `exchange-match-result`，清算/结算/资金分别订阅自己的 topic，不依赖彼此的 RPC 地址与实现。
  - **异步**：撮合线程只负责撮合并发出 `exchange-match-result`；行情计算、推送、清算、资金执行都在下游异步完成，不阻塞撮合。
  - **削峰**：行情剧烈波动或活动高峰时，下单和成交量会瞬间冲高。我们先把订单写入 Kafka，让流量“排队缓冲”，撮合和清算服务按自己的处理能力持续消费，避免数据库和撮合服务被瞬时打爆。

---

### 2. Kafka 中 Topic / Partition / Producer / Consumer 这些概念怎么理解？结合你们项目说说。（高频）

- **答题关键词**：主题、分区有序、生产者、消费组。
- **参考答法**：
  - **Topic（主题）**：按业务分类的消息集合，比如：
    - `exchange-order-ingress`：下单入口（外部服务写入，relay 转发）；
    - `exchange-order`：撮合内部下单队列；
    - `exchange-match-result`：撮合统一出口（trades + completedOrders）；
    - `exchange-clearing-result`：清算结果；
    - `exchange-fund-instruction`：资金指令。
  - **Partition（分区）**：Topic 物理上拆成多个分区，**每个分区内消息天然有序**，分区之间不保证顺序。
  - **Producer（生产者）**：往 Topic 写消息的服务，比如下单服务写 `exchange-order-ingress`，撮合服务写 `exchange-match-result`，清算写 `exchange-clearing-result`，结算写 `exchange-fund-instruction`。
  - **Consumer / Consumer Group（消费者/消费组）**：
    - relay 消费 `exchange-order-ingress` 并转发到 `exchange-order`；
    - 撮合服务 `exchange` 消费 `exchange-order`；
    - market/clearing 都消费 `exchange-match-result`（不同 group，各自职责）；
    - settlement 消费 `exchange-clearing-result`，fund 消费 `exchange-fund-instruction`。
    - 同一个 Group 内多个实例会自动“分摊”不同分区的消息，实现水平扩展。

---

### 3. 你们如何保证同一个交易对的订单是有序进入撮合引擎的？（高频）

- **答题关键词**：分区内有序、按 symbol/symbol+side 选 key、单线程消费。
- **参考答法**：
  - Kafka 的规则是“**一个分区内的消息是有序的**”，所以要保证同一个交易对有序，只要确保它们都落在同一个分区即可。
  - 我们生产订单消息时，会把交易对作为 **分区 key**，比如：
    - `key = "BTCUSDT"`，或者更细一点 `key = "BTCUSDT-BUY"` / `key = "BTCUSDT-SELL"`。
  - 这样 Kafka 会把同一 key 的消息固定分配到同一个分区；撮合端的 `ExchangeOrderConsumer` 对每个分区是**单线程顺序消费**，然后统一调用 `CoinTrader.trade(order)`。
  - 所以，从“写入分区的顺序 + 分区内单线程消费”这一组合上，就保证了**同一交易对订单的撮合顺序**。

---

### 4. Kafka 的“至少一次”语义是什么？你们怎么做幂等处理？（高频）

- **答题关键词**：至少一次、重复消费、业务幂等键。
- **参考答法**：
  - Kafka 默认提供的是“**至少一次（At-Least-Once）**”语义：为了保证可靠性，在网络抖动或超时时可能会重试发送或重放消息，这样同一条业务消息有可能被消费多次。
  - 在我们项目里，**接受这个前提，在业务层做幂等**，大致做法是：
    - 给每种关键操作设计一个**业务唯一键**：
      - 充值：`txid + address`；
      - 订单/结算：`orderId` 或 `operationId`；
    - 落库前先用唯一键做“查重 / 唯一索引”：
      - 已存在 → 说明这条消息处理过，直接跳过；
      - 不存在 → 正常插入，并把唯一键记录下来。
  - 总结一句：**中间件负责“至少一次投递”，我们负责“幂等处理”，最终达到“效果上恰好一次”的目的。**

---

### 5. 消息队列的三大作用是什么？你们各自用在了哪些场景？（高频）

- **答题关键词**：解耦、异步、削峰 + 具体例子。
- **参考答法**：
  - 一般来说消息队列有三个典型用途：**解耦、异步、削峰**。
  - 在我们项目里的对应关系：
    - **解耦**：  
      下单服务只管发 `exchange-order-ingress`，撮合统一出口 `exchange-match-result`；以后要加“审计/风控”服务，只要订阅对应 topic 即可，不需要改撮合和资金服务。
    - **异步**：  
      撮合引擎只发 `exchange-match-result`，成交入库、K 线计算、推送、清算、资金执行全部异步完成，不拖慢撮合。
    - **削峰**：  
      行情极端波动或者活动时，下单和成交量暴涨。Kafka 起到“水库”的作用，先接住流量，撮合和清算服务可以稍后均匀消费，避免数据库和内存被瞬时打满。

---

### 6. 冻结资金是怎么实现的？为什么要冻结？（高频，和 MQ/撮合一起问）

- **答题关键词**：balance/frozen 字段、下单扣可用加冻结、成交或撤单时回滚。
- **参考答法**：
  - **为什么要冻结**：防止用户用一笔钱下多笔单，保证撮合成功后一定有对应资金可以结算，避免出现“成交成功但余额不够”的情况。
  - **实现方式**（账户/钱包表都有 `balance` 和 `frozen` 两个字段）：
    - 下单时（以限价买单举例）：
      - 计算所需金额，例如买 0.5 BTC @ 30000，需要 15000 USDT；
      - 在一个数据库事务内：
        - `balance -= 15000`，`frozen += 15000`；
        - 插入一条订单记录 `ExchangeOrder`；
      - 事务提交成功表示：订单创建成功+资金冻结成功。
    - 成交时：
      - 买家：从 `frozen` 扣成交流出金额（不再回到可用），对手币种（BTC）可用余额增加成交数量；
      - 卖家：从 `frozen` 扣已卖出的 BTC，对手币种（USDT）可用余额增加成交额。
    - 撤单时：
      - 将该订单未成交部分对应的冻结金额从 `frozen` 退回 `balance`。
  - **结合 MQ**：订单创建成功并冻结资金后，才会发 `exchange-order` 到 Kafka；后续的成交、撤单事件会驱动账户服务根据消息更新 `balance/frozen`。

---

### 7. 当出现消息积压时，你会如何处理？结合你们的下单/撮合场景说一下。（中频）

- **答题关键词**：生产太快消费太慢、扩容消费端、优化消费逻辑。
- **参考答法**：
  - 消息积压说明整体是“**生产太快，消费太慢**”，需要分情况看：
    - 如果只是短时间的流量尖峰（比如几分钟内暴涨），通常 Kafka 顶得住，后面消费者加速消费一阵就能追上；
    - 如果积压成为常态，说明当前消费能力不够，需要扩容或优化。
  - 我们项目里的处理思路：
    - **增加消费能力**：多部署几实例的撮合/行情服务，或者增加 Topic 分区数，让更多实例并行消费。
    - **优化消费逻辑**：  
      把耗时操作（复杂查询、外部 HTTP 调用、链上 RPC）从“消费主流程”里拆出去，改成异步或批量处理，让消费主循环尽可能轻量。
  - 总结一句：**先判断是“短时峰值”还是“长期产能不足”，短时可以忍，长期要扩容 + 优化消费过程。**

---

### 8. Kafka 为什么快？简单说几个关键点。（中频）

- **答题关键词**：顺序写、零拷贝、批量/压缩。
- **参考答法**：
  - **顺序写磁盘**：Kafka 的日志文件是 append-only，只追加不修改，磁盘 I/O 以顺序写为主，哪怕用机械盘也能有很高吞吐。
  - **零拷贝**：基于 Linux 的 `mmap` 和 `sendfile`，减少用户态和内核态之间的数据复制次数，提高网络和磁盘的传输效率。
  - **批量发送 + 压缩**：Producer 和 Broker 都支持批量发送和压缩，一次把多条消息写出去，减少系统调用和网络包数量。

---

### 9. 分区数量是不是越多越好？分区过多有什么问题？（低频/大厂题）

- **答题关键词**：并行度 vs 资源开销，Producer/Consumer/Broker 三端影响。
- **参考答法**：
  - 分区多可以提高并行度，但**不是越多越好**，分区过多会带来：
    - **Producer 端**：维护更多分区的批量缓冲，占内存，失败重试时丢批的风险增大。
    - **Consumer 端**：需要开更多线程/实例才能消费完所有分区，否则某些分区长期积压。
    - **Broker 端**：单机持有太多分区，会占用大量文件句柄和元数据，顺序写退化为多文件间的“伪随机写”，宕机恢复时间也会变长。
  - 实际上，我们一般会通过压测来大致确定**单分区能承受多少 TPS**，再根据业务总量反推需要多少分区，而不是盲目增加。

---

### 10. Rebalance 是什么？什么时候会发生？有什么影响？（低频/大厂题）

- **答题关键词**：重新分配分区、触发时机、短暂停顿和重复消费风险。
- **参考答法**：
  - **Rebalance 本质**：对一个 Consumer Group 来说，是“**把组里所有消费者与分区的对应关系重新算一遍**”的过程。
  - **触发时机**：
    - 有新的 Consumer 实例加入 Group；
    - 有 Consumer 实例退出或心跳超时；
    - Topic 分区数量发生变化（比如扩容分区）。
  - **影响**：
    - Rebalance 期间，相关分区上的消息暂时无法被消费，吞吐会有一个小波动。
    - 如果某个实例已经消费了消息但还没提交 offset，在 Rebalance 后可能被其他实例再次消费，需要靠业务幂等来兜底。

---

### 11. 如何保证消费幂等？（总结版，可与“至少一次”一起答）

- **答题关键词**：业务唯一键、唯一索引/去重表、重复消息直接跳过。
- **参考答法**：
  - Kafka 无法从中间件层面完全避免重复投递，我们的做法是**在消费侧做幂等**：
    - 设计一个业务唯一键，比如充值用 `txid+address`，订单用 `orderId`，提现用 `operationId`。
    - 消费时先基于这个键查一次（或插入到一张幂等表/唯一索引表）：
      - 如果已经存在，说明之前处理过，直接返回，不再改变任何状态；
      - 如果不存在，则正常处理本次业务逻辑，并记录这次处理结果。
  - 思想就是：**允许重复消息，但结果不重复生效**。
