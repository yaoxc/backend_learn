## 一、一句话总结  
`ProcessorConfig` 是 **“行情处理器工厂一次性初始化”** 的配置类：  
把数据库里所有 **已启用交易对** 逐个包装成 `DefaultCoinProcessor`，并**共用同一套 Handler 实例**（MongoDB、WebSocket、Netty）注册到 `CoinProcessorFactory`，后续任何成交/K线/盘口都通过 **同一个处理器链** 落库 + 推送给前端。

---

## 二、Handler 是不是同一个对象？

**是，全部都是同一个 Spring Bean。**  
Spring 默认单例注入：

| Handler 类型 | Bean 名称 | 实例数量 |
|--------------|-----------|----------|
| `MongoMarketHandler` | `mongoMarketHandler` | 1 |
| `WebsocketMarketHandler` | `wsHandler` | 1 |
| `NettyHandler` | `nettyHandler` | 1 |

> 因此 **BTC/USDT、ETH/USDT … 所有交易对的 processor 共用同一套 Handler**，  
> 既节省内存，又保证「写库」和「推送」逻辑完全一致。

---

## 三、这样做的好处

1. **内存友好**  
   几十个交易对共用 3 个 Handler，避免重复 new。

2. **逻辑一致**  
   无论哪个 symbol 成交，都走 **同一套落库 + 同一套推送** 代码，方便维护。

3. **启动即就绪**  
   配置类在 Spring 启动阶段就完成注册，后续 **KafkaConsumer** 直接 `factory.getProcessor(symbol).process(trade)` 即可。

4. **可扩展**  
   想加新 Handler（例如 **Redis 缓存**、**指标统计**）只需 **单例实现 → 注入 → addHandler**，全 symbol 立即生效。

---

## 四、一句话总结

> `ProcessorConfig` 在应用启动时 **一次性** 为每个启用交易对 new 一个 `DefaultCoinProcessor`，但 **所有处理器共享同一个 Spring Handler 实例链**（Mongo/WebSocket/Netty），注册到 `CoinProcessorFactory` 后，任何成交/K线/盘口都通过这条链 **统一落库 + 统一推送给前端**，既省内存又保一致性。



# 为什么推行情要用两个handler，netty和websocket
因为 **Netty** 与 **WebSocket（STOMP/SockJS）** 面对的是**两套完全不同的长连接通道**：

| 维度 | NettyHandler | WebsocketMarketHandler（STOMP/SockJS） |
|------|--------------|----------------------------------------|
| **协议/端口** | 原生 TCP 私有协议（或自定义二进制） | HTTP/WebSocket 标准协议（80/443） |
| **客户端** | 手机 APP、桌面行情终端、第三方量化系统 | H5 网页、管理后台、小程序 |
| **连接数** | 十万~百万级 | 万级 |
| **报文格式** | Protobuf/自定义字节（体积小、解析快） | JSON/Text（易读、调试方便） |
| **穿透/防火墙** | 需开放额外端口 | 走 80/443，防火墙友好 |
| **Spring 集成** | 自己写编解码，与 Spring 无关 | 直接 `@MessageMapping` 即可 |

---

## 一、业务场景决定“双通道”

1. **APP 用户** → **Netty**
    - 需要 **高并发、低延迟**（毫秒级）
    - 能接受 **私有协议**、**Protobuf**
    - 连接稳定，心跳保活

2. **Web 用户** → **WebSocket(STOMP)**
    - 浏览器只能走 **HTTP/WebSocket**
    - 需要 **快速开发、易调试**（JSON 一眼看懂）
    - 连接随页面关闭而断开，**生命周期短**

> 若只保留一条通道，要么 **APP 性能掉档**，要么 **Web 端无法接入**。

---

## 二、代码级视角

```java
processor.addHandler(nettyHandler);      // 推给 APP / 量化终端
processor.addHandler(wsHandler);         // 推给浏览器
```

同一笔 `ExchangeTrade` 会顺序调用：
1. `nettyHandler.handleTrade(...)` → **Protobuf 字节** → **Netty ChannelGroup**
2. `wsHandler.handleTrade(...)` → **JSON 字符串** → **SimpMessagingTemplate** → **/topic/market/xxx**

---

## 三、能否合并成一个 Handler？

可以，但会引入：
- **协议转换开销**（JSON ↔ Protobuf）
- **端口/证书/防火墙** 额外配置
- **前端/APP 同时改造**

交易所行情对 **延迟** 极其敏感，**分通道各自最优**是最经济方案。

---

## 四、一句话总结

> **Netty 给 APP/量化终端提供“极致性能”私有通道；WebSocket 给网页提供“标准易用”HTTP 通道；**  
> 两个 Handler 不是重复，而是 **面向不同客户端的最优解**，因此“一份数据，两套推送”。



