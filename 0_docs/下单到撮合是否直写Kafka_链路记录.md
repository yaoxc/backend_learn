# 下单到撮合是否“直写 Kafka”——链路记录

结论：**是的**。在本项目中，`exchange-api` 的下单接口 **直接写 Kafka topic `exchange-order`**；`exchange` 服务通过 `@KafkaListener` **直接消费 `exchange-order`**，并调用撮合引擎 `CoinTrader.trade(order)` 进入撮合。撮合完成后，`CoinTrader` 也会 **直接写 Kafka**，把成交/订单完成/盘口变化广播给后续消费者（行情、落库、推送等）。

---

## 1. 下单端（exchange-api）直写 Kafka

文件：`exchange-api/src/main/java/com/bizzan/bitrade/controller/OrderController.java`

关键点：
- 注入：`KafkaTemplate<String, String> kafkaTemplate`
- 下单成功后：`kafkaTemplate.send("exchange-order", JSON.toJSONString(order));`

源码片段：

```java
@Autowired
private KafkaTemplate<String, String> kafkaTemplate;

// ...
MessageResult mr = orderService.addOrder(member.getId(), order);
if (mr.getCode() != 0) {
    return MessageResult.error(500, "提交订单失败:" + mr.getMessage());
}
// 发送消息至Exchange系统
kafkaTemplate.send("exchange-order", JSON.toJSONString(order));
```

补充：撤单也走 Kafka（`exchange-order-cancel`）。

```java
kafkaTemplate.send("exchange-order-cancel", JSON.toJSONString(order));
```

---

## 2. 撮合入口（exchange）直消费 Kafka

文件：`exchange/src/main/java/com/bizzan/bitrade/consumer/ExchangeOrderConsumer.java`

关键点：
- `@KafkaListener(topics = "exchange-order")`：消费下单 topic
- `traderFactory.getTrader(order.getSymbol())`：按交易对路由到对应 `CoinTrader`
- `trader.trade(order)`：进入撮合引擎唯一入口之一（本项目撮合入口）

源码片段：

```java
@KafkaListener(topics = "exchange-order", containerFactory = "kafkaListenerContainerFactory")
public void onOrderSubmitted(List<ConsumerRecord<String,String>> records){
    for (ConsumerRecord<String,String> record : records) {
        ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
        if(order == null){
            return;
        }
        CoinTrader trader = traderFactory.getTrader(order.getSymbol());
        if (trader.isTradingHalt() || !trader.getReady()) {
            kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
        } else {
            trader.trade(order);
        }
    }
}
```

撤单消费：

```java
@KafkaListener(topics = "exchange-order-cancel", containerFactory = "kafkaListenerContainerFactory")
public void onOrderCancel(List<ConsumerRecord<String,String>> records){
    // ... trader.cancelOrder(order) ...
}
```

---

## 3. 撮合完成后（CoinTrader）继续直写 Kafka（给后续服务消费）

文件：`exchange/src/main/java/com/bizzan/bitrade/Trader/CoinTrader.java`

撮合引擎产生的关键输出 topic（从代码可见）：
- `exchange-trade`：成交列表（可能分片发送，最多 1000 条一批）
- `exchange-order-completed`：订单完成通知（可能分片发送）
- `exchange-trade-plate`：盘口变化（发送 `TradePlate`）

源码片段（节选）：

```java
// 成交广播
kafkaTemplate.send("exchange-trade", JSON.toJSONString(trades));

// 订单完成广播
kafkaTemplate.send("exchange-order-completed", JSON.toJSONString(orders));

// 盘口变化广播
kafkaTemplate.send("exchange-trade-plate", JSON.toJSONString(plate));
```

---

## 4. 一句话总结这条链路

用户下单（`exchange-api`）  
→ **Kafka** `exchange-order`（Producer：`OrderController`）  
→ 撮合服务（`exchange`）消费 `exchange-order`（Consumer：`ExchangeOrderConsumer`）  
→ `CoinTrader.trade(order)` 撮合  
→ **Kafka** `exchange-trade` / `exchange-order-completed` / `exchange-trade-plate`（Producer：`CoinTrader`）  
→ 行情/落库/推送/清算等下游消费者异步处理

