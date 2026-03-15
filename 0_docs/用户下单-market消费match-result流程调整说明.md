# 用户下单：market 消费 exchange-match-result 流程调整说明

本文档说明 market 消费 `exchange-match-result` 的**改造原则**、**具体改动**与**配置**。改造后的定位是：**market 只做行情与盘口（K 线、24h、最新成交等）+ 订单状态与成交明细，不碰资金**；资金（钱包、流水、退冻结）全部由 **清算 → 结算 → 资金服务** 完成。

---

## 一、改造原则与目标

### 1.1 market 的职责（改造后）

| 做 | 不做 |
|----|------|
| **行情与盘口**：K 线、24h 统计、盘口、最新成交（CoinProcessor、pushJob、MarketHandler 等） | **不碰资金**：不写 member_wallet、不写 member_transaction、不执行 increaseBalance / decreaseFrozen / thawBalance |
| **订单状态与成交明细**：exchange_order 状态（COMPLETED）、tradedAmount、turnover、completedTime；exchange_order_detail、order_detail_aggregation | 不返佣（promoteReward 由资金侧或单独任务处理） |
| **推送**：WebSocket、Netty 推送订单部分成交、订单完成（依赖已落库的订单状态） | 不退冻结（由资金服务执行 REFUND 指令） |
| — | **不在此处触发清算**：清算由独立消费者 **ClearingMatchResultConsumer** 监听 `exchange-match-result` 完成 |

即：**market = 行情与盘口 + 订单域（状态/明细）+ 推送；清算由独立消费者监听同一 topic 完成，资金域全部交给资金服务。**

### 1.2 为什么这么改造

- **职责清晰**  
  market 负责「交易与行情」：成交流水来了，更新行情、更新订单与成交记录、推给用户；**资金**属于另一域，由清算算、结算出指令、资金服务执行。这样 market 与资金服务边界清楚，便于维护和扩展。

- **订单状态/明细留在 market 的原因**  
  - **领域归属**：订单、成交明细属于交易/订单域（exchange_order、exchange_order_detail），与行情、盘口同属「交易侧」，放在 market 更自然；资金服务专注 member_wallet、member_transaction。  
  - **时延与体验**：market 先消费 match-result，可先落订单状态与成交明细，用户很快看到「订单已成交」；若等资金服务再改订单，完成态会滞后。  
  - **故障隔离**：资金服务异常时，订单「已成交」仍可先展示，余额稍后更新；若订单完成也等资金服务，会出现「撮合已成交但订单一直显示交易中」。

- **不碰资金的原因**  
  - 同一批 match-result 会走 **清算 → 结算 → 资金指令 → 资金服务执行**；若 market 也改钱包，会**重复加减款**。  
  - 资金变更集中到资金服务，**单一入口**，便于对账、审计与风控。

---

## 二、为何要调整（原问题）

- **原流程**：market 消费 `exchange-match-result` 后调用 **processMatchResultIdempotent**，在同一事务内完成：成交明细、订单状态、**钱包变动**、**资金流水**、返佣、退冻结。
- **现有流水线**：同一批 match-result 还会触发 **清算 → 结算 → 资金指令 → 资金服务执行**。
- **问题**：两处都改钱包会导致**重复加减款**。因此必须二选一：market 不碰资金，或资金服务不执行本流水线指令。

本改造采用 **market 不碰资金**：market 只做行情与盘口 + 订单状态与成交明细，资金全部由清算→结算→资金服务完成。

---

## 三、调整内容概览

| 层级 | 改动 |
|------|------|
| **exchange-core** | 新增「仅订单状态与明细、不碰资金」的处理：processMatchResultIdempotentOrderOnly、processMatchResultOrderOnly、processExchangeTradeOrderOnly、processOrderOrderOnly、tradeCompletedOrderOnly；不写 member_wallet、member_transaction、不返佣、不退冻结。 |
| **market** | 默认走 **match.result.use.fund.pipeline=true**：handleMatchResult 调用 processMatchResultIdempotentOrderOnly，只落订单状态与成交明细，并做行情、盘口、推送；**不在此处触发清算**。清算由 **ClearingMatchResultConsumer**（group=market-clearing-group）独立监听 `exchange-match-result` 并调用 clearingService.processAndPublish。保留 **false** 仅作兼容旧逻辑回退。 |
| **配置** | match.result.use.fund.pipeline=true（推荐）；false = 回退为 market 直接改钱包。 |

---

## 四、exchange-core 改动详情

### 4.1 新增方法（ExchangeOrderService）

- **processMatchResultIdempotentOrderOnly(messageId, trades, completedOrders)**  
  幂等逻辑同 processMatchResultIdempotent（processed_match_result_message），但内部只做订单侧处理，**不写钱包、不写 member_transaction、不返佣、不退冻结**。

- **processMatchResultOrderOnly(trades, completedOrders)**  
  对每笔成交调用 processExchangeTradeOrderOnly，对每个已完成订单调用 tradeCompletedOrderOnly。

- **processExchangeTradeOrderOnly(trade)**  
  加载买卖订单与交易对，只调 processOrderOrderOnly（买卖各一次），不锁 member_wallet、不写钱包与流水。

- **processOrderOrderOnly(order, trade, coin)**  
  仅：写 **exchange_order_detail**、写 **order_detail_aggregation**（MongoDB）。不调用 increaseBalance、decreaseFrozen、member_transaction、promoteReward。

- **tradeCompletedOrderOnly(orderId, tradedAmount, turnover)**  
  仅：更新 **exchange_order** 的 status=COMPLETED、tradedAmount、turnover、completedTime 并保存。不调用 orderRefund；退冻结由资金服务执行 REFUND 指令。

### 4.2 与原有方法的关系

- **processMatchResult / processMatchResultIdempotent**：仍包含钱包、流水、返佣、退冻结，仅在 **match.result.use.fund.pipeline=false** 时使用（兼容回退）。
- **processOrder / processExchangeTrade / tradeCompleted**：不变，仍被旧路径及取消订单等场景使用。

---

## 五、market 改动详情

### 5.1 ExchangeTradeConsumer.handleMatchResult

- 配置 **match.result.use.fund.pipeline**（默认 **true**）。
- **useFundPipeline == true（推荐）**：调用 **processMatchResultIdempotentOrderOnly**，只落订单状态与成交明细，不碰资金；然后照常执行：行情（CoinProcessor、pushJob）、推送（WebSocket、Netty）。**清算**由独立消费者 **ClearingMatchResultConsumer** 监听同一 topic `exchange-match-result`（group=market-clearing-group）完成，此处不再调用 clearingService。
- **useFundPipeline == false**：调用 **processMatchResultIdempotent**（原逻辑，market 直接改钱包），仅用于回退或未启用资金服务时。

### 5.2 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| **match.result.use.fund.pipeline** | **true** | true = market 只做行情与盘口 + 订单状态/明细，不碰资金，资金由资金服务执行；false = 兼容旧逻辑，market 直接改钱包、写流水、返佣、退冻结。 |

---

## 六、数据流对比（推荐 vs 回退）

| 步骤 | match.result.use.fund.pipeline = **true**（推荐） | match.result.use.fund.pipeline = **false**（回退） |
|------|---------------------------------------------------|----------------------------------------------------|
| 1. 消费 exchange-match-result | 同左 | 同左 |
| 2. 幂等落库 | 仅 **订单明细 + 订单状态**（不碰资金） | 订单明细 + 订单状态 + **钱包 + 流水 + 返佣 + 退冻结** |
| 3. 行情 / 盘口 / 推送 | market 执行 | market 执行 |
| 3'. 清算 | **ClearingMatchResultConsumer** 独立消费同一条 match-result（group=market-clearing-group），调用 clearingService.processAndPublish | 同左（或旧逻辑下 market 内调用） |
| 4. 清算 → 结算 → 资金指令 | 执行，**资金服务**按指令改钱包、写流水、退冻结 | 若资金服务也执行会重复改钱包，需关闭或置 false |

**建议**：在已启用清算、结算、资金服务的前提下，使用 **match.result.use.fund.pipeline=true**，符合「market 只做行情与盘口 + 订单状态/明细，不碰资金」的改造目标。

---

## 七、注意事项与后续可做

- **返佣**：OrderOnly 路径下不执行 promoteReward；若需要，可在资金服务执行 FEE 相关指令时补写返佣，或由单独返佣任务基于清算/结算结果处理。
- **资金流水（member_transaction）**：OrderOnly 路径不写；可在资金服务执行 INCOME/OUTCOME/FEE/REFUND 时同步写入 member_transaction。
- **兼容**：同一 messageId 只处理一次（processed_match_result_message）；切换为 false 时需避免对同一批 match-result 重复消费。

以上为「market 只做行情与盘口 + 订单状态/明细，不碰资金」的改造说明与修改记录。
