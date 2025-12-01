# CoinProcessor是一个**定时同步任务**，目的是：

> **每分钟**从 **撮合中心（CoinTrader服务）** 拉取当前 **所有交易对及其运行状态**，与自己（Market服务）的 **行情处理器（CoinProcessor）** 进行比对，确保：
> - 交易中心**新上线**的交易对，Market端**立即创建**对应的Processor并启动K线/行情计算；
> - 交易中心**暂停/恢复**的交易对，Market端**同步停止/重启**K线生成；
> - 交易中心**下架**的交易对，Market端**不再处理**（自然跳过）。

---

## 一、核心逻辑流程图（文字版）

```
每分钟 cron
   ↓
调用 /monitor/engines 获取交易中心所有 symbol 状态
   ↓
遍历每个 symbol：
   ├─ 已存在 Processor
   │   ├─ status=1（正常交易）→ 若当前停止K线 → 开启K线
   │   └─ status=2（暂停交易）→ 若当前运行K线 → 停止K线
   ├─ 不存在 Processor
   │   ├─ 数据库也无该币种 → continue 跳过
   │   └─ 数据库存在 → 新建 Processor，挂载 Handler，加入工厂
   └─ 继续下一个 symbol
```

---

## 二、关键字段对照表

| 交易中心返回值 | 含义 | Market端动作 |
|----------------|------|---------------|
| `status = 1` | 正常撮合 | 开启 K 线生成 |
| `status = 2` | 暂停撮合 | 停止 K 线生成 |
| 不存在该 symbol | 已下架 | 不再处理，自然跳过 |

---

## 三、代码目的拆解

| 段落 | 作用 |
|------|------|
| **定时器** `@Scheduled(cron = "0 */1 * * * ?")` | 每分钟执行一次，保证实时同步 |
| **远程调用** `restTemplate.getForEntity(.../monitor/engines)` | 拉取交易中心当前存活符号及状态 |
| **状态比对** `processorMap.containsKey(symbol)` | 判断 Market 自己是否已存在该币种的 Processor |
| **K 线开关** `setIsStopKLine(true/false)` | 根据交易中心暂停/恢复状态，**同步启停** K 线计算，节省 CPU |
| **新建 Processor** `new DefaultCoinProcessor(...)` | 交易中心新增交易对时，Market 端**立即创建**行情处理器，并挂载同一套 **Mongo/WebSocket/Netty Handler** |
| **数据库兜底** `coinService.findBySymbol(symbol)` | 防止交易中心“幽灵”币种（库无记录）被误创建 |
| **工厂注册** `processorFactory.addProcessor(...)` | 把新 Processor 放进工厂，后续 **KafkaConsumer** 即可 `factory.getProcessor(symbol).process(trade)` |

---

## 四、为什么用 `continue`

- 数据库不存在该币种 → **跳过当前循环**，避免空指针。
- 已存在 Processor 且已处理完 K 线启停 → **跳过剩余代码**，避免重复新建。

---

## 五、一句话总结

> **这段代码就是 Market 端的“交易对生命周期同步器”**：  
> 每分钟把 **撮合中心** 的在线/暂停/下架状态 **镜像** 到自己这边，  
> 保证 **新币立即有行情、暂停立即停 K 线、下架不再消耗资源**，实现 **双端状态一致**。