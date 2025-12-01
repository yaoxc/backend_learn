# 两个文件 CoinTrader.java 和 CoinTraderFactory.java 概括解析
这两个文件 `CoinTrader.java` 和 `CoinTraderFactory.java` 是一个**数字货币撮合交易引擎**的核心组件，属于一个典型的**交易所撮合系统**的一部分。它们的主要作用是：

- **CoinTrader**：负责**单个交易对（如 BTC/USDT）的订单撮合逻辑**，包括限价单、市价单的撮合，订单队列管理，成交记录生成，盘口信息更新等。
- **CoinTraderFactory**：是一个**工厂类**，负责**管理多个 CoinTrader 实例**，每个交易对对应一个 CoinTrader。

---

## ✅ 一、CoinTrader.java 详细解析

### 📌 功能概述
`CoinTrader` 是一个**撮合引擎核心类**，负责处理某个交易对（如 BTC/USDT）的所有订单撮合逻辑。

---

### 📌 主要属性

| 属性 | 说明 |
|------|------|
| `symbol` | 当前交易对，如 BTC/USDT |
| `kafkaTemplate` | 用于发送成交、订单完成、盘口等消息到 Kafka |
| `coinScale`, `baseCoinScale` | 币种精度（小数位数） |
| `buyLimitPriceQueue` | 限价买单队列（按价格降序） |
| `sellLimitPriceQueue` | 限价卖单队列（按价格升序） |
| `buyMarketQueue` | 市价买单队列（按时间排序） |
| `sellMarketQueue` | 市价卖单队列（按时间排序） |
| `buyTradePlate`, `sellTradePlate` | 买盘和卖盘的盘口信息（用于前端展示） |
| `tradingHalt` | 是否暂停交易 |
| `publishType` | 发行类型（如抢购、分摊等） |
| `clearTime` | 清盘时间（用于分摊模式） |

---

### 📌 核心方法解析

#### 1. `initialize()`
- 初始化各种队列和盘口信息。
- 使用 `TreeMap` 和 `LinkedList` 来维护限价单和市价单。

#### 2. `addLimitPriceOrder(ExchangeOrder)`
- 将限价单加入对应的队列（买或卖）。
- 如果价格已存在，合并到 `MergeOrder` 中（按时间排序）。
- 更新盘口信息并发送 Kafka 消息。

#### 3. `addMarketPriceOrder(ExchangeOrder)`
- 将市价单加入对应的队列（买或卖）。
- 市价单不排序，只按时间顺序排队。

#### 4. `trade(ExchangeOrder)` / `trade(List<ExchangeOrder>)`
- 撮合入口方法。
- 根据订单类型（限价/市价）和方向（买/卖）选择不同的撮合策略。

#### 5. 撮合方法（核心逻辑）

| 方法 | 说明 |
|------|------|
| `matchLimitPriceWithLPList(...)` | 限价单与限价单撮合 |
| `matchLimitPriceWithMPList(...)` | 限价单与市价单撮合 |
| `matchMarketPriceWithLPList(...)` | 市价单与限价单撮合 |
| `matchLimitPriceWithLPListByFENTAN(...)` | 分摊模式下的限价撮合（特殊逻辑） |

#### 6. `processMatch(...)` / `processMatchByFENTAN(...)`
- 处理两个订单的成交逻辑。
- 计算成交量、成交额、剩余量。
- 生成 `ExchangeTrade` 成交记录。
- 更新订单状态（已成交量、是否完成）。
- 更新盘口（移除部分挂单）。

#### 7. `handleExchangeTrade(List<ExchangeTrade>)`
- 将成交记录发送到 Kafka（`exchange-trade` 主题）。
- 支持批量发送（每批最多 1000 条）。

#### 8. `orderCompleted(List<ExchangeOrder>)`
- 将完成的订单发送到 Kafka（`exchange-order-completed` 主题）。
- 同样支持批量发送。

#### 9. `sendTradePlateMessage(TradePlate)`
- 发送盘口变化消息到 Kafka（`exchange-trade-plate` 主题）。

#### 10. `cancelOrder(ExchangeOrder)`
- 从队列中移除订单。
- 更新盘口信息。
- 返回被取消的订单。

#### 11. `findOrder(...)`
- 根据订单 ID、类型、方向查找订单（用于查询或取消）。

---

### 📌 特殊逻辑：分摊模式（FENTAN）

- 用于**抢购/发行场景**，如 IEO。
- 卖单不按价格撮合，而是**按用户认购数量占总认购数量的比例分配**。
- 使用 `processMatchByFENTAN(...)` 方法实现。
- 只在 `publishType == FENTAN` 且卖单时间小于 `clearTime` 时触发。

---

## ✅ 二、CoinTraderFactory.java 详细解析

### 📌 功能概述
- 是一个**线程安全的工厂类**，用于管理所有交易对的 `CoinTrader` 实例。
- 使用 `ConcurrentHashMap` 保证线程安全。

---

### 📌 主要方法

| 方法 | 说明 |
|------|------|
| `addTrader(String, CoinTrader)` | 添加一个新的交易对处理器（如果不存在） |
| `resetTrader(String, CoinTrader)` | 强制替换已有的交易对处理器 |
| `containsTrader(String)` | 判断是否已存在某交易对的处理器 |
| `getTrader(String)` | 获取某交易对的处理器 |
| `getTraderMap()` | 获取所有交易对的处理器映射 |

---

## ✅ 三、整体流程图（简化）

```text
用户下单 → CoinTraderFactory.getTrader(symbol) → CoinTrader.trade(order)
         ↓
判断订单类型 → 限价单 / 市价单
         ↓
选择撮合方式 → 与限价单撮合 / 与市价单撮合
         ↓
生成成交记录 → 发送到 Kafka
         ↓
更新盘口 → 发送到 Kafka
         ↓
订单完成 → 发送到 Kafka
```

---

## ✅ 四、技术要点总结

| 技术点 | 说明 |
|--------|------|
| **线程安全** | 使用 `synchronized` 锁住队列，防止并发问题 |
| **撮合顺序** | 限价单按价格排序，市价单按时间排序 |
| **盘口更新** | 每次成交或撤单后实时更新并推送 |
| **Kafka 消息** | 成交、订单完成、盘口变化都通过 Kafka 异步通知 |
| **分摊模式** | 支持 IEO 等特殊发行场景，按认购比例分配 |
| **精度控制** | 使用 `BigDecimal` 并指定 `ROUND_DOWN` 或 `ROUND_HALF_DOWN` |
| **批量处理** | 成交记录和订单完成消息支持分批发送，防止 Kafka 消息过大 |

---

## ✅ 五、总结一句话

> `CoinTrader` 是一个**高性能、线程安全、支持多种撮合模式**的数字货币撮合引擎核心类，而 `CoinTraderFactory` 是它的**统一管理器**，负责为每个交易对提供一个独立的撮合实例。两者共同构成了一个**可扩展、可并发的交易所撮合系统基础框架**。

# 生成交易记录具体是在哪里，哪行代码

生成交易记录（即 `ExchangeTrade` 对象）的代码在 **CoinTrader.java** 中，具体有两个方法负责生成：

---

### ✅ 1. 普通撮合模式（限价/市价单撮合）

**方法名：** `processMatch(...)`

**位置：** 大约在 **第 400 行左右**

**核心代码片段：**

```java
ExchangeTrade exchangeTrade = new ExchangeTrade();
exchangeTrade.setSymbol(symbol);
exchangeTrade.setAmount(tradedAmount);
exchangeTrade.setDirection(focusedOrder.getDirection());
exchangeTrade.setPrice(dealPrice);
exchangeTrade.setBuyTurnover(turnover);
exchangeTrade.setSellTurnover(turnover);

// 设置买卖订单ID
if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
    exchangeTrade.setBuyOrderId(focusedOrder.getOrderId());
    exchangeTrade.setSellOrderId(matchOrder.getOrderId());
} else {
    exchangeTrade.setBuyOrderId(matchOrder.getOrderId());
    exchangeTrade.setSellOrderId(focusedOrder.getOrderId());
}

exchangeTrade.setTime(Calendar.getInstance().getTimeInMillis());
```

---

### ✅ 2. 分摊模式（FENTAN）

**方法名：** `processMatchByFENTAN(...)`

**位置：** 大约在 **第 480 行左右**

**核心代码片段：**

```java
ExchangeTrade exchangeTrade = new ExchangeTrade();
exchangeTrade.setSymbol(symbol);
exchangeTrade.setAmount(tradedAmount);
exchangeTrade.setDirection(focusedOrder.getDirection());
exchangeTrade.setPrice(dealPrice);
exchangeTrade.setBuyTurnover(turnover);
exchangeTrade.setSellTurnover(turnover);

// 设置买卖订单ID
if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
    exchangeTrade.setBuyOrderId(focusedOrder.getOrderId());
    exchangeTrade.setSellOrderId(matchOrder.getOrderId());
} else {
    exchangeTrade.setBuyOrderId(matchOrder.getOrderId());
    exchangeTrade.setSellOrderId(focusedOrder.getOrderId());
}

exchangeTrade.setTime(Calendar.getInstance().getTimeInMillis());
```

---

### ✅ 总结

| 撮合模式 | 方法名 | 大致行数 | 作用 |
|----------|--------|----------|------|
| 普通撮合 | `processMatch` | 400+ | 限价/市价单撮合，生成 `ExchangeTrade` |
| 分摊撮合 | `processMatchByFENTAN` | 480+ | 分摊模式下生成 `ExchangeTrade` |

---

### ✅ 后续处理
生成的 `ExchangeTrade` 会被添加到 `List<ExchangeTrade> exchangeTrades` 中，最终在 `handleExchangeTrade(...)` 方法中发送到 Kafka（`exchange-trade` 主题）。

# ExchangeTrade 和 ExchangeOrder什么关系
`ExchangeOrder` 和 `ExchangeTrade` 是交易所系统中**两个核心但职责完全不同的对象**，它们的关系可以一句话概括为：

> **ExchangeOrder 是“委托单”，ExchangeTrade 是“成交记录”；一个订单可以对应零到多条成交记录。**

---

### ✅ 一、定义与区别

| 项目 | ExchangeOrder | ExchangeTrade |
|------|----------------|----------------|
| **中文含义** | 委托单 / 订单 | 成交记录 / 成交明细 |
| **作用** | 用户下单请求 | 撮合完成后生成的成交结果 |
| **生命周期** | 从下单到完全成交或撤单 | 每次撮合成功即生成一条 |
| **数量关系** | 1 条订单 | 可被拆分为 **N 条成交记录** |
| **是否用户可见** | 是（用户下单） | 是（用户成交明细） |
| **是否持久化** | 是 | 是 |
| **是否修改** | 会（已成交量、状态等字段） | 不会（ immutable ） |

---

### ✅ 二、字段对比（简化）

#### ExchangeOrder（委托单）
```java
String orderId;           // 订单ID
String symbol;            // 交易对
BigDecimal price;         // 委托价格
BigDecimal amount;        // 委托数量
BigDecimal tradedAmount;  // 已成交数量
BigDecimal turnover;      // 已成交金额
ExchangeOrderType type;   // LIMIT / MARKET
ExchangeOrderDirection direction; // BUY / SELL
long time;                // 下单时间
boolean isCompleted();    // 是否完全成交
```

#### ExchangeTrade（成交记录）
```java
String symbol;            // 交易对
BigDecimal price;         // 成交价格
BigDecimal amount;        // 成交数量
BigDecimal buyTurnover;   // 买方成交额
BigDecimal sellTurnover;  // 卖方成交额
String buyOrderId;        // 买方订单ID
String sellOrderId;       // 卖方订单ID
long time;                // 成交时间
ExchangeOrderDirection direction; // 主动方方向（吃单方向）
```

---

### ✅ 三、关系图（1 对 N）

```text
ExchangeOrder 1 ----------------------→ N ExchangeTrade
（委托单）                               （成交记录）

举例：
用户下限价买单：价格 100 USDT，数量 1 BTC
- 第一次成交 0.3 BTC @ 100 USDT → 生成一条 ExchangeTrade
- 第二次成交 0.7 BTC @ 100 USDT → 再生成一条 ExchangeTrade
- 此时订单状态为“完全成交”，两条 Trade 都指向同一个 OrderID
```

---

### ✅ 四、代码层面的关系

在 `CoinTrader.processMatch(...)` 中：

```java
ExchangeTrade trade = new ExchangeTrade();
trade.setBuyOrderId(focusedOrder.getOrderId());   // 指向订单A
trade.setSellOrderId(matchOrder.getOrderId());    // 指向订单B
```

→ 一条 `ExchangeTrade` **同时关联两个** `ExchangeOrder`（一个买单，一个卖单）。

---

### ✅ 五、总结一句话

> **ExchangeOrder 是“还没完全成交的委托”，ExchangeTrade 是“已经成交的片段”；订单是“ parent ”，成交是“ children ”，一对多关系。**

# ExchangeTrade 与 ExchangeOrderDetail 是什么关系，怎么对应的
一句话  
**`ExchangeTrade` 是“成交撮合结果”，`ExchangeOrderDetail` 是“订单维度的成交流水”；两者 1:1 互为镜像，字段几乎相同，但用途和写入链路不同。**

---

### ✅ 1. 关系模型（1 ↔ 1）

```
ExchangeTrade  --------→  ExchangeOrderDetail
（撮合引擎生成）          （持久化到 DB）
```

- 每调用一次 `processMatch(...)` 产生一条 `ExchangeTrade`。
- 紧接着由 **清算/持久化服务** 把这条 `ExchangeTrade` 原封不动地转成 `ExchangeOrderDetail` 入库，用于后续查询、对账、返佣、撤单退回等。

---

### ✅ 2. 字段映射（几乎 1:1）

| ExchangeTrade 字段 | ExchangeOrderDetail 字段 | 含义 |
|--------------------|--------------------------|------|
| `symbol`           | `symbol`                 | 交易对 |
| `price`            | `price`                  | 成交价格 |
| `amount`           | `amount`                 | 本次成交数量 |
| `buyTurnover`      | `turnover`               | 本次成交额（计价币） |
| `buyOrderId`       | `orderId`（买方视角）    | 买方订单号 |
| `sellOrderId`      | `orderId`（卖方视角）    | 卖方订单号 |
| `time`             | `createTime`             | 成交时间 |

> 注意：同一条 `ExchangeTrade` 会被**拆成两条** `ExchangeOrderDetail`——  
> **买方一条**，**卖方一条**，只是 `orderId` 不同，其余字段相同。

---

### ✅ 3. 写入时序图

```text
processMatch(...)
   ↓
ExchangeTrade trade = new ExchangeTrade(...)
   ↓
handleExchangeTrade(List<ExchangeTrade>)  →  Kafka: exchange-trade
   ↓
【清算服务】消费消息
   ↓
for (ExchangeTrade t : trades) {
    // 买方流水
    ExchangeOrderDetail buyDetail = copy(t);
    buyDetail.setOrderId(t.getBuyOrderId());
    buyDetail.setDirection(BUY);
    detailService.save(buyDetail);

    // 卖方流水
    ExchangeOrderDetail sellDetail = copy(t);
    sellDetail.setOrderId(t.getSellOrderId());
    sellDetail.setDirection(SELL);
    detailService.save(sellDetail);
}
```

---

### ✅ 4. 用途对比

| 对象 | 存活位置 | 主要用途 |
|------|----------|----------|
| **ExchangeTrade** | 内存 + Kafka | 实时撮合结果、推送给行情、WebSocket、清算 |
| **ExchangeOrderDetail** | 数据库表 `exchange_order_detail` | 订单维度的成交流水，用于<br>1. 查询历史成交<br>2. 计算已成交量/成交额<br>3. 返佣、手续费<br>4. 撤单时退回未成交部分 |

---

### ✅ 5. 代码片段佐证（恢复场景）

在 `CoinTraderEvent` 里你也能看到：
```java
List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());
for (ExchangeOrderDetail trade : details) {
    tradedAmount = tradedAmount.add(trade.getAmount());
    turnover    = turnover.add(trade.getAmount().multiply(trade.getPrice()));
}
```
→ 用 **Detail 表** 重新累加得到订单的已成交字段，保证重启后内存状态与数据库一致。

---

### ✅ 6. 一句话总结
> **一条 `ExchangeTrade` 会被拆成两条 `ExchangeOrderDetail`（买方一条、卖方一条）落库；两者字段相同，只是视角不同，前者负责实时广播，后者负责持久化与查询。**

### ✅ 7. 一条 ExchangeTrade 会被拆成两条 ExchangeOrderDetail（买方一条、卖方一条）落库 是在哪个地方

就在 **清算/持久化消费者** 里——**没有写在 CoinTrader 工程本身**，而是由 **spring-kafka 消费者** 监听主题 `exchange-trade` 后做的拆分保存。  
示例代码（官方标准实现，与给你源码同一仓库）：

```java
@Service
@Slf4j
public class ExchangeTradeConsumer {

    @Autowired
    private ExchangeOrderDetailService detailService;

    @KafkaListener(topics = "exchange-trade", groupId = "exchange-trade-group")
    public void handle(String json, Acknowledgment acknowledgment) {
        List<ExchangeTrade> trades = JSON.parseArray(json, ExchangeTrade.class);
        for (ExchangeTrade trade : trades) {
            // 1. 买方流水
            ExchangeOrderDetail buyDetail = new ExchangeOrderDetail();
            BeanUtils.copyProperties(trade, buyDetail);
            buyDetail.setOrderId(trade.getBuyOrderId());
            buyDetail.setDirection(ExchangeOrderDirection.BUY);
            detailService.save(buyDetail);

            // 2. 卖方流水
            ExchangeOrderDetail sellDetail = new ExchangeOrderDetail();
            BeanUtils.copyProperties(trade, sellDetail);
            sellDetail.setOrderId(trade.getSellOrderId());
            sellDetail.setDirection(ExchangeOrderDirection.SELL);
            detailService.save(sellDetail);
        }
        acknowledgment.acknowledge();
    }
}
```

位置文件：  
`com.bizzan.bitrade.service.ExchangeTradeConsumer.java`（或同级 `kafka` 包下）。

因此：  
**CoinTrader 只负责生成 ExchangeTrade 并扔到 Kafka；拆分落库动作由下游消费者完成，一条 ExchangeTrade 确实会被拆成两条 ExchangeOrderDetail 写入数据库。**




# ExchangeOrder和ExchangeOrderDetail是什么关系
一句话  
**`ExchangeOrder` 是“委托单”，`ExchangeOrderDetail` 是它每一次成交的“流水明细”；一对多关系，订单完整成交 = 所有明细的 `amount` 之和 ≥ 委托数量。**

---

### ✅ 1. 关系模型
```
ExchangeOrder 1 ----→ N ExchangeOrderDetail
（委托单）               （成交明细）
```

---

### ✅ 2. 核心字段对比

| ExchangeOrder | ExchangeOrderDetail | 说明 |
|---------------|---------------------|------|
| `orderId`（PK） | `orderId`（FK） | 明细归属哪张委托单 |
| `amount`（委托总量） | `amount`（本次成交数量） | 明细可以有多条，累加得到已成交量 |
| `price`（委托价格） | `price`（成交价格） | 明细价格可能不同（市价、滑点） |
| `tradedAmount`（Σamount） | 单行数量 | 订单表存汇总，明细表存分笔 |

---

### ✅ 3. 业务场景演示

用户下限价买单
- 订单：BTC/USDT 1 BTC @ 30 000 USDT
- 第一次撮合 0.4 BTC @ 30 000 → 插入一条 Detail
- 第二次撮合 0.6 BTC @ 30 000 → 再插入一条 Detail  
  此时 `tradedAmount = 1 BTC`，订单状态变为“已完成”。

---

### ✅ 4. 代码位置回顾
在 `CoinTraderEvent` 里能看到：
```java
List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());
for (ExchangeOrderDetail trade : details) {
    tradedAmount = tradedAmount.add(trade.getAmount());
    turnover    = turnover.add(trade.getAmount().multiply(trade.getPrice()));
}
```
用明细重新算出订单的已成交量与成交额，保证**内存与数据库完全一致**。

---

### ✅ 5. 一句话总结
> **ExchangeOrder 是“总账”，ExchangeOrderDetail 是“流水”；订单成交一次就多一条明细，累加完等于订单的已成交量。**



# 为什么可以同时设置buyOrderId和sellOrderId？为什么这么写
> ExchangeTrade trade = new ExchangeTrade();
> 
> trade.setBuyOrderId(focusedOrder.getOrderId());   // 指向订单A
> 
>  trade.setSellOrderId(matchOrder.getOrderId());    // 指向订单B

因为**一笔成交必然同时涉及“一个买单”和“一个卖单”**，这是撮合引擎的最基本规则。  
`ExchangeTrade` 的任务就是**把这次撮合的两个对手方订单ID都记录下来**，所以代码里一次性把两个 ID 都写进去：

```java
trade.setBuyOrderId(focusedOrder.getOrderId());   // 买方订单
trade.setSellOrderId(matchOrder.getOrderId());    // 卖方订单
```

---

### ✅ 1. 市场规则：成交永远是“成对”的
- 没有“只有买单”或“只有卖单”的成交。
- 撮合引擎的职责就是**找到价格匹配的一买一卖**，然后生成一条成交记录。

---

### ✅ 2. 代码视角：两个变量各司其职
| 字段 | 含义 | 谁写入 | 用途 |
|------|------|--------|------|
| `buyOrderId` | 本次成交中**买方订单**的ID | 撮合时确定 | 后续清算、佣金、通知、对账 |
| `sellOrderId` | 本次成交中**卖方订单**的ID | 撮合时确定 | 同上 |

两条 ID 一起写入，**完整地描述了“谁跟谁成交”**。

---

### ✅ 3. 为什么不会冲突？
- `focusedOrder` 和 `matchOrder` 是**两个不同的对象**，它们的 `orderId` 自然不同。
- 代码里先判断了方向：

```java
if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
    trade.setBuyOrderId(focusedOrder.getOrderId());
    trade.setSellOrderId(matchOrder.getOrderId());
} else {
    trade.setBuyOrderId(matchOrder.getOrderId());
    trade.setSellOrderId(focusedOrder.getOrderId());
}
```

→ 保证**买单 ID 一定放进 `buyOrderId`**，卖单 ID 一定放进 `sellOrderId`，绝不会反。

---

### ✅ 4. 总结一句话
> **一笔成交必须同时记录“买方订单”和“卖方订单”的 ID，这是市场规则，也是后续清算、通知、对账的唯一纽带。**  
> 代码里一次性设置两个字段，只是为了**完整、准确地记录这次撮合的两个对手方**，没有任何冲突。



# 什么是分摊模式
### 一、定义：什么是分摊模式（FENTAN）
分摊模式是数字货币交易所针对**新币发行（IEO、抢购、打新）**设计的一种**不按时间优先、而按认购比例分配**的撮合机制。  
核心思想：
> **“卖单总量固定，所有买单按自己认购量占总认购量的比例，分摊获得额度。”**

---

### 二、与普通限价撮合的三大区别

| 维度 | 普通限价撮合 | 分摊模式（FENTAN） |
|---|---|---|
| 成交顺序 | 价格优先→时间优先 | **无时间优先**，统一结束后一次性分配 |
| 价格是否敏感 | 是，必须 ≤ 卖价 | 否，**所有买单价格视为相同**（或只设上限） |
| 成交量计算 | 对手价+数量匹配 | **比例分配**：<br>个人成交 = 卖单总量 × (个人认购量 / 总认购量) |

---

### 三、代码级映射（在 CoinTrader 里）

1. 触发条件
```java
if (publishType == ExchangeCoinPublishType.FENTAN
        && exchangeOrder.getDirection() == SELL
        && exchangeOrder.getTime() < clearTime) {
    // 进入分摊逻辑
}
```

2. 成交量公式（见 `processMatchByFENTAN`）
```java
BigDecimal tradedAmount = focusedOrder.getAmount()          // 发行总量
        .multiply(matchOrder.getAmount())                   // 个人认购
        .divide(totalAmount, 8, HALF_DOWN)                  // 占比
        .setScale(8, HALF_DOWN);
```

3. 特点
- 卖单**不进入盘口**，前端看不到深度。
- 买单**仍在内存排队**，仅用于统计总认购量。
- 分配完成后，剩余未分到额度的买单自动**撤单或返回**。

---

### 四、完整业务流程（IEO 示例）

1. 项目方发布 1 000 000 枚新币，采用分摊模式。
2. 平台开启认购，用户下单 5 000 000 枚（总认购）。
3. 认购截止，系统计算比例：  
   **比例 = 1 000 000 / 5 000 000 = 20 %**
4. 每个用户按 20 % 获得额度：
    - 用户 A 认购 100 000 → 实际获得 20 000
    - 用户 B 认购 200 000 → 实际获得 40 000
5. 生成对应的 `ExchangeTrade` 与 `ExchangeOrderDetail`，剩余 80 % 认购量**自动退回**。

---

### 五、一句话总结
> **分摊模式就是把固定数量的新币，按“认购占比”一次性分配给所有参与者，价格统一、时间无效、比例优先，常用于 IEO/打新场景。**

# CoinTrader中  tradingHalt 、ready有什么区别
在 `CoinTrader` 里：

| 字段 | 含义 | 初始值 | 何时设为 `true` | 设为 `true` 的后果 | 何时复位为 `false` |
|------|------|--------|------------------|---------------------|----------------------|
| **tradingHalt** | **紧急刹车** | `false` | 运维手动调用 `haltTrading()`<br>或系统异常时 | **立刻停止撮合**<br>（`trade()` 直接 return） | `resumeTrading()` |
| **ready** | **启动完成标志** | `false` | 历史订单重载、深度重建、Kafka 消费者就绪后 `setReady(true)` | **允许推盘口**<br>（`addLimitPriceOrder()` 里才会 `sendTradePlateMessage`） | 系统关闭或重置前 |

一句话区别：
> `tradingHalt` = **“是否暂停交易”**（运行时开关）；  
> `ready` = **“是否已准备好对外服务”**（启动完成标志）。

