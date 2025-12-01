# ExchangeTradeConsumer类的作用

`ExchangeTradeConsumer` 是 **行情与清算系统的“中央分发器”**——  
它只干一件事：
> **监听 Kafka 里所有撮合相关事件**，  
> **异步批量处理后**，  
> **落库 + 推送给前端/APP + 更新行情缓存**。

---

## 一、职责总览（5 个 Kafka 主题）

| 监听主题 | 方法 | 作用 |
|----------|------|------|
| `exchange-trade` | `handleTrade()` | **成交明细** → 落库、个人成交推送、K 线聚合、WebSocket/Netty 广播 |
| `exchange-order-completed` | `handleOrderCompleted()` | **订单完全成交** → 更新订单状态、资金解锁、推“已完成”事件 |
| `exchange-trade-mocker` | `handleMockerTrade()` | **模拟盘成交** → 不走真实清算，只更新行情和深度 |
| `exchange-trade-plate` | `handleTradePlate()` | **盘口深度变化** → 交给 `ExchangePushJob` 批量推送给前端 |
| `exchange-order-cancel-success` | `handleOrderCanceled()` | **撤单成功** → 解锁资金、推“已取消”事件 |

---

## 二、核心设计亮点

1. **线程池异步**
   ```java
   private ExecutorService executor = new ThreadPoolExecutor(30, 100, ...)
   ```
    - `handleTrade()` 把每条记录包装为 `HandleTradeThread` 提交线程池，**防止 Kafka 消费线程被阻塞**。

2. **批量处理**
    - 一次 poll 200 条，**逐条落库 + 逐条推送**，吞吐量高。

3. **双通道推送**
    - **WebSocket**（浏览器）：`messagingTemplate.convertAndSend(...)`
    - **Netty**（APP/终端）：`nettyHandler.handleOrder(...)`  
      同一份数据，**两套客户端同时收到**。

4. **责任链解耦**
    - 只负责“**收 + 落库 + 推**”，不计算 K 线；
    - 把 `List<ExchangeTrade>` 交给 `CoinProcessor.process(trades)` → **行情服务**内部聚合 K 线、指标、缓存。

5. **异常隔离**
    - 每个 `Runnable` 内部 try-catch，**单条失败不影响后续消息**。

---

## 三、方法级拆解

### 1. `handleTrade()` → 真实成交

```java
@KafkaListener(topics = "exchange-trade")
public void handleTrade(List<ConsumerRecord<String, String>> records) {
    for (ConsumerRecord<String, String> record : records) {
        executor.submit(new HandleTradeThread(record)); // 异步线程处理
    }
}
```

**HandleTradeThread.run()** 内部：
- `exchangeOrderService.processExchangeTrade(trade)` → **写成交明细表 + 返佣 + 资金解冻**
- 推送 **买卖双方的个人成交** 到 `/topic/market/order-trade/{symbol}/{uid}`
- `coinProcessor.process(trades)` → **K 线、指标、缓存**
- `pushJob.addTrades(...)` → **深度、实时成交列表** 缓存

> **一条成交 = 落库 + 个人推送 + K 线 + 深度 + WebSocket/Netty 广播**

---

### 2. `handleOrderCompleted()` → 订单全部成交

- 更新订单状态 **TRADING → COMPLETED**
- 解锁剩余冻结资金
- 推送 **“订单已完成”** 事件给买卖双方

---

### 3. `handleMockerTrade()` → 模拟盘成交

- **不走真实清算**（不扣款、不落成交明细表）
- 只更新 **K 线、深度、实时成交列表** → 供前端演示/回测

---

### 4. `handleTradePlate()` → 盘口深度变化

- 仅把 `TradePlate` 扔进 `ExchangePushJob` 的内存队列
- 由 **ExchangePushJob** 每 2 秒批量推送给前端（减少网络次数）

---

### 5. `handleOrderCanceled()` → 撤单成功

- 更新订单状态 → CANCELED
- 解锁冻结资金
- 推送 **“订单已取消”** 事件

---

## 四、一句话总结

> `ExchangeTradeConsumer` 是 **行情+清算系统的“中央分发器”**：  
> 它只负责 **“收消息 → 落库 → 推送给用户”**，  
> 让 **成交、撤单、盘口、K 线** 在同一台服务里**高吞吐、低延迟**地到达 **WebSocket 浏览器** 和 **Netty APP**，  
> 而自身**不计算指标**，通过线程池与责任链保持**解耦与健壮**。