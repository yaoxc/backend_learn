# 功能说明

下面把 `WebSocketConfig` 的**每一行代码**加上中文注释，并详细说明它在整个交易所行情推送链路里的**作用、原理、使用方式**。


## 一、类的作用一句话

> **它是交易所“WebSocket 行情推送”的握手与路由配置**：  
> 告诉 Spring **“谁来连、怎么连、连完能订阅哪些频道”**。

---

## 二、配置项详解

| 配置 | 含义 | 客户端使用示例 |
|------|------|----------------|
| `enableSimpleBroker("/topic")` | 内存广播代理，**服务器→客户端**推送 | `stompClient.subscribe('/topic/market/trade/BTC_USDT', callback)` |
| `setApplicationDestinationPrefixes("/app")` | 客户端→服务器消息前缀 | `stompClient.send('/app/send', {}, JSON)` |
| `addEndpoint("/market-ws")` | WebSocket 握手入口 URL | `new SockJS('/market-ws')` 或 `ws://ip:port/market-ws` |
| `setAllowedOrigins("*")` | 允许跨域，方便 H5/小程序接入 | 可改为具体域名增强安全 |
| `withSockJS()` | 自动降级：不支持 WebSocket 的浏览器用 **XHR 轮询**模拟 |

---

## 三、在整个行情链路中的位置

```
撮合引擎 CoinTrader
   ↓ Kafka
ExchangeTradeConsumer
   ↓ 调用
SimpMessagingTemplate.convertAndSend("/topic/market/trade/BTC_USDT", trades)
   ↓ 经过本配置
WebSocketConfig 代理 → /topic/market/trade/BTC_USDT
   ↓ 推送到
前端/APP WebSocket 客户端
```

> 没有 `WebSocketConfig`，**Spring 不知道要开哪个端口、走什么协议**，客户端就**连不进来**。

---

## 四、使用步骤（前端视角）

1. 引入 SockJS + STOMP 客户端库。
2. 建立连接：
   ```javascript
   const socket = new SockJS('/market-ws');
   const stomp = Stomp.over(socket);
   stomp.connect({}, function (frame) {
       // 订阅成交
       stomp.subscribe('/topic/market/trade/BTC_USDT', function (msg) {
           console.log(JSON.parse(msg.body));
       });
   });
   ```
3. 服务器端（Java）只需：
   ```java
   messagingTemplate.convertAndSend("/topic/market/trade/BTC_USDT", tradeList);
   ```
   前端即可实时收到。

---

## 五、一句话总结

> `WebSocketConfig` 就是 **“行情推送的门票”**：  
> 它告诉 Spring **“打开 /market-ws 端口，用 STOMP 协议，客户端可以订阅 /topic 开头的任何频道”**；  
> 没有它，**浏览器、APP 都收不到实时成交、盘口、K 线**。

# 这个配置和 SimpMessagingTemplate 有什么关系

一句话关系  
`WebSocketConfig` 负责 **“开通道、定规则”**，  
`SimpMessagingTemplate` 负责 **“往通道里发消息”**；  
前者把 `/topic` 路径注册成 **STOMP 广播代理**，后者直接把对象 **推到这条路径**，客户端就能实时收到。

---

## 一、角色对比

| 组件 | 所属层级 | 作用 | 典型代码 |
|------|----------|------|----------|
| **WebSocketConfig** | Spring 配置层 | 建立 **WebSocket 握手端点** 并声明 **消息代理规则** | `config.enableSimpleBroker("/topic")` |
| **SimpMessagingTemplate** | Spring Bean 层 | 向 **已注册的消息代理** 发送消息（广播 or 点对点） | `template.convertAndSend("/topic/market/trade", trade)` |

---

## 二、配合时序（启动 → 运行）

1. **启动阶段**  
   `WebSocketConfig` 被 Spring 扫描 →  
   注册 **端点 `/market-ws`** 并启用 **内存代理 `/topic`**。

2. **运行阶段**  
   业务代码：
   ```java
   @Autowired
   private SimpMessagingTemplate template;

   template.convertAndSend("/topic/market/trade/BTC_USDT", tradeList);
   ```
   内部流程：
   ```
   SimpMessagingTemplate
   → 找到 **SimpleBroker**（由 WebSocketConfig 注册）
   → 把 tradeList 序列化
   → 通过 **WebSocket 会话** 推送给所有订阅了 `/topic/market/trade/BTC_USDT` 的客户端
   ```

3. **客户端视角**
   ```javascript
   stompClient.subscribe('/topic/market/trade/BTC_USDT', function (msg) {
       console.log(JSON.parse(msg.body));
   });
   ```
   即可实时收到服务器发出的 `tradeList`。

---

## 三、没有 WebSocketConfig 会怎样？

- **端点不存在** → 客户端 **连不进来**（404）。
- **未启用 `/topic` 代理** → `SimpMessagingTemplate` 报错：
  ```
  No broker found for destination: /topic/market/trade/...
  ```
  消息发不出去。

---

## 四、类比记忆

| 现实生活 | 对应组件 |
|----------|----------|
| **修建高速公路**（车道、入口、指示牌） | **WebSocketConfig** |
| **货车把货物倒进高速**（广播） | **SimpMessagingTemplate** |
| **收费站把货物分发给沿途车辆**（订阅者） | **SimpleBroker**（由配置启用） |

---

## 五、一句话总结

> `WebSocketConfig` 负责 **“修高速、立路牌”**（端口、协议、代理路径）；  
> `SimpMessagingTemplate` 负责 **“把货倒进去”**（发消息）；  
> 二者配合，才能让 **浏览器、APP 通过 WebSocket 实时收到行情推送**。








