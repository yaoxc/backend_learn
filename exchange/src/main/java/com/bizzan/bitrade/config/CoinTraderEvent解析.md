# CoinTraderEvent
一句话概括  
**`CoinTraderEvent` 是撮合引擎的“灾后重建”启动器**：Spring 容器刷新完成`（所有 Bean 已实例化、依赖已注入）`后，它会把数据库里**所有交易对尚未成交的订单**重新加载回对应的 `CoinTrader` 内存队列，并把**已成交的订单**补发一条完成通知，最后把 `CoinTrader` 标记为“准备就绪”，让撮合真正开始工作。

---

### ✅ 一、触发时机
实现 `ApplicationListener<ContextRefreshedEvent>`  
→ 当 **Spring 上下文刷新完成**（所有 Bean 已实例化、依赖已注入）后自动执行一次，**仅执行一次**。

---

### ✅ 二、执行步骤（逐行对应）

| 代码块 | 作用 |
|--------|------|
| `getTraderMap()` | 取出配置阶段已经建好的所有 `CoinTrader`（每个交易对一个） |
| `findAllTradingOrderBySymbol(symbol)` | 把 **该交易对下所有未完全成交** 的订单从 DB 捞出来 |
| `findAllByOrderId(orderId)` | 把每笔订单的 **成交明细** 查出来，重新计算 `tradedAmount` 和 `turnover` |
| `!order.isCompleted()` | 如果还有剩余量，放回内存队列，等待继续撮合 |
| `trader.trade(tradingOrders)` | **批量重新进入撮合引擎**（恢复停机前的深度） |
| `completedOrders` | 如果 DB 里已有“已完成”订单，补发一条 Kafka 消息，让下游（清算、推送、日志）能再次感知 |
| `trader.setReady(true)` | 把该交易对的“准备就绪”标志置为 `true`，**正式允许对外接受新订单** |

---

### ✅ 三、为什么需要这个类？

1. **防止内存丢失**  
   撮合引擎是纯内存结构，应用重启后队列是空的，必须从 DB 恢复。

2. **保证数据一致性**  
   通过 `ExchangeOrderDetail` 重新计算已成交量，确保内存状态与数据库完全一致。

3. **通知下游系统**  
   已成交订单补发一条 `exchange-order-completed`，让 **清算、风控、WebSocket 推送** 等消费者能重新处理。

4. **优雅启动**  
   先 `setReady(false)`（在配置类里 `stopTrading()`），等恢复完成再 `setReady(true)`，避免**脏数据**或**重复成交**。

---

### ✅ 四、时序图（简化）

```text
Spring 启动
   ↓
CoinTraderConfig 建立所有 CoinTrader（暂停状态）
   ↓
ContextRefreshedEvent 发布
   ↓
CoinTraderEvent.onApplicationEvent
   ├─ 遍历每个交易对
   ├─ 查 DB 订单 + 成交明细
   ├─ 未完成的 → 重新 push 进内存队列
   ├─ 已完成的 → 补发 Kafka 消息
   └─ setReady(true)  开放交易
```

---

### ✅ 五、一句话总结

> `CoinTraderEvent` 就是撮合引擎的“**灾难恢复按钮**”：应用重启后，它把数据库里尚未成交的订单重新塞进内存，把已成交的订单再通知一遍，然后正式打开交易大门。


# 如果项目中有多个类实现ApplicationListener<ContextRefreshedEvent>，会有冲突或异常么
不会。

Spring 对 `ApplicationListener<ContextRefreshedEvent>` 的支持是**多播**（multicast）机制：  
**任意数量的监听器都可以同时监听同一个事件**，执行顺序默认是**无序**的（除非额外指定），但彼此完全独立，**互不干扰、不会抛异常、也不会冲突**。

---

### ✅ 1. 底层原理
- `ApplicationEventMulticaster` 负责把事件**顺序地**（默认）或**异步地**（可配置）派发给所有匹配的监听器。
- 每个监听器在自己的 `onApplicationEvent(...)` 方法里执行，**异常只影响自己**，不会传播给其他监听器。
- 因此即使某个监听器抛了 RuntimeException，Spring 也会 catch 住并打日志，其余监听器仍会继续执行。

---

### ✅ 2. 如果想控制执行顺序
实现 `Ordered` 接口或使用 `@Order(value = ...)` 注解即可：

```java
@Component
@Order(1)
public class FirstJob implements ApplicationListener<ContextRefreshedEvent> { ... }

@Component
@Order(2)
public class SecondJob implements ApplicationListener<ContextRefreshedEvent> { ... }
```

数值越小，越早执行。

---

### ✅ 3. 如果想异步执行
把监听器方法（或类）标 `@Async`，并开启 `@EnableAsync`，即可让不同监听器并行跑，互不阻塞。

---

### ✅ 4. 总结一句话
> **多个类同时监听 `ContextRefreshedEvent` 完全合法、无冲突、无异常；Spring 会按顺序（或异步）把它们全部执行一遍。**




