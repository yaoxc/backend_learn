# 类功能概述

`MonitorController` 是 **撮合引擎的“运维控制台”**。  
它只读 `/monitor/**` 暴露一堆 HTTP 接口，用来：

1. **实时查看**每个交易对的盘口、队列、深度、订单；
2. **手动启停**某个交易对的撮合引擎（热启/热停/重建）；
3. **故障恢复**——重置引擎、重载未成交订单、补发完成消息；
4. **集群运维**——一次查询全部符号、全部引擎状态。

---

### 一、接口功能速查表

| URL | 作用 | 返回内容 |
|---|---|---|
| `/monitor/overview?symbol=BTC/USDT` | 仪表盘 | 买卖队列计数、盘口深度 |
| `/monitor/trader-detail` | 调试神器 | 完整内存队列（限价+市价）JSON 输出 |
| `/monitor/plate` | 前端深度组件 | 买卖档 `List<TradePlateItem>` |
| `/monitor/plate-mini` | 移动端 24 档 | 精简深度 |
| `/monitor/plate-full` | 后台 100 档 | 完整深度 |
| `/monitor/symbols` | 集群视角 | 当前存活的所有交易对名称 |
| `/monitor/engines` | 健康检查 | 每个 symbol 的状态码：1 运行 / 2 暂停 |
| `/monitor/order` | 单查 | 根据 `orderId+方向+类型` 从内存捞出订单 |
| `/monitor/reset-trader` | **危险但救命** | 销毁旧引擎 → 重建 → 重载未成交订单 → 设为 ready |
| `/monitor/start-trader` | 启动/恢复 | 若不存在则创建；已暂停则 resume |
| `/monitor/stop-trader` | 优雅停机 | 置 `tradingHalt=true`，不再接受新订单，但内存数据保留 |

---

### 二、关键实现细节

#### 1. 只读接口——直接拿内存
```java
trader.getTradePlate(ExchangeOrderDirection.SELL).getItems()
trader.getBuyLimitPriceQueue()   // TreeMap 原样返回
trader.getLimitPriceOrderCount(...)
```
> **无数据库查询**，毫秒级响应，适合监控大盘。

#### 2. 启停接口——线程安全
- 先判断 `factory.containsTrader(symbol)` 防止重复创建。
- 创建前强制 `!isTradingHalt()`（已停才能重建/重置）。
- 新建 `CoinTrader` 后**复用 Spring 已注入的** `kafkaTemplate`、`exchangeOrderService` 等，**不重新注入**。
- 最后统一 `setReady(true)` 打开闸门。

#### 3. 重置/启动——**“灾备三件套”**
① **捞订单**
```java
List<ExchangeOrder> orders = exchangeOrderService.findAllTradingOrderBySymbol(symbol);
```
② **重算已成交**
```java
for (ExchangeOrderDetail od : details) {
    tradedAmount += od.getAmount();
    turnover     += od.getAmount() * od.getPrice();
}
```
③ **推回内存**
```java
newTrader.trade(tradingOrders);   // 批量进入队列
```
④ **补发 Kafka**
```java
kafkaTemplate.send("exchange-order-completed", completedOrders);
```
> 保证**重启后深度、已成交量、下游清算**三者一致。

#### 4. 集群视角
- `/symbols` 列出当前 Factory 里所有 key。
- `/engines` 用 `Map<symbol, 1|2>` 一次性返回全部引擎健康状态，方便 **Prometheus/Grafana** 拉取。

---

### 三、使用场景举例

| 场景 | 调用接口 |
|---|---|
| 运维值班发现盘口倒挂、深度异常 | `reset-trader` 重建引擎 |
| 版本上线前优雅停撮合 | `stop-trader` |
| 上线后恢复 | `start-trader` |
| 客服帮用户查单 | `/monitor/order` |
| 前端深度组件刷新 | `/monitor/plate-mini` |

---

### 四、一句话总结

> `MonitorController` 是撮合引擎的**实时仪表板 + 运维遥控器**：既能秒级拉取内存状态，也能在秒级完成“启停重建、订单重载、深度修复”，是整个交易系统**线上运维最核心的 HTTP 入口**。





















