# 引擎入口函数 `trade()` 触发流程

### 一、trade() 触发调用路径（文字版）

1. **OrderController.addOrder()**  
   → 订单校验、落库  
   → `kafkaTemplate.send("exchange-order", orderJson)`

2. **ExchangeOrderConsumer.onOrderSubmitted()**  
   → 监听 topic `exchange-order`  
   → 反序列化得到 `ExchangeOrder`  
   → 检查 `!trader.isTradingHalt() && trader.getReady()`  
   → `trader.trade(order)`  // ← 进入 CoinTrader.trade()

3. **CoinTrader.trade(List<ExchangeOrder>) / trade(ExchangeOrder)**  
   → 判断 `tradingHalt`/`symbol`/`amount`  
   → 按类型/方向路由到：
    - `matchLimitPriceWithLPList(...)`
    - `matchMarketPriceWithLPList(...)`
    - `matchLimitPriceWithLPListByFENTAN(...)`

4. **processMatch(...)**  
   → 生成 `ExchangeTrade`  
   → `handleExchangeTrade(...) → kafka("exchange-trade")`  
   → `orderCompleted(...) → kafka("exchange-order-completed")`  
   → 更新盘口 & 发送 `kafka("exchange-trade-plate")`

---

### 二、流程图（PlantUML 代码）

```
+------------------------------------------------------------------+
|  币币撮合 trade() 触发流程（文字图）                                |
+------------------------------------------------------------------+
    1. 前端/机器人 POST /order/add
       └─ OrderController.addOrder()
          ├─ 参数校验、风控、落库
          └─ kafkaTemplate.send("exchange-order", JSON订单)

    2. Kafka exchange-order 主题
       └─ ExchangeOrderConsumer.onOrderSubmitted()
          ├─ 反序列化 ExchangeOrder
          ├─ 检查 tradingHalt & ready
          ├─ 若未就绪 → send("exchange-order-cancel-success") 并返回
          └─ 若就绪 → coinTrader.trade(order)   【进入CoinTrader.java】

    3. CoinTrader.trade(ExchangeOrder)
       ├─ 批量循环 trade(List) 入口
       ├─ 判空、symbol、amount 初筛
       ├─ 根据 type/direction 路由：
       │   ├─ LIMIT_PRICE 且 方向=BUY/SELL
       │   │  ├─ 限价 vs 限价队列 matchLimitPriceWithLPList()
       │   │  └─ 若未吃完 → 限价 vs 市价队列 matchLimitPriceWithMPList()
       │   ├─ MARKET_PRICE
       │   │  └─ 市价 vs 限价队列 matchMarketPriceWithLPList()
       │   └─ FENTAN 分摊模式
       │      └─ matchLimitPriceWithLPListByFENTAN()
       ├─ 上述匹配循环内 → processMatch() 生成 ExchangeTrade
       ├─ handleExchangeTrade(trades) → Kafka "exchange-trade"
       ├─ orderCompleted(orders) → Kafka "exchange-order-completed"
       └─ sendTradePlateMessage() → Kafka "exchange-trade-plate"

    4. 下游消费
       ├─ exchange-trade → 清算、手续费、行情、K线
       ├─ exchange-order-completed → 推送、通知、资金解锁
       └─ exchange-trade-plate → WebSocket深度、前端深度组件

+------------------------------------------------------------------+
| 文件对照表                                                        |
+------------------------------------------------------------------+
OrderController.java          → 下单入口，写Kafka
ExchangeOrderConsumer.java  → 消费订单，调trader
CoinTrader.java             → 唯一trade()入口，全部撮合逻辑
CoinTraderFactory.java      → 存放单例CoinTrader
ExchangeTrade               → 成交结果（内存+Kafka）
ExchangeOrderDetail         → 持久化落库
```

### 三、关键文件名一览

| 步骤 | 类/文件 | 作用 |
|----|--------|------|
| 1 | `OrderController.java` | 接收前端下单，首写Kafka |
| 2 | `ExchangeOrderConsumer.java` | 消费"exchange-order"，调CoinTrader.trade() |
| 3 | `CoinTrader.java` | 唯一入口trade()，完成撮合全链路 |
| 4 | `CoinTraderFactory.java` | 存放各symbol单例CoinTrader |
| 5 | `ExchangeTrade`/`ExchangeOrderDetail` | 成交结果与持久化流 |








