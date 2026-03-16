## clearing 模块 Kafka 消费与调试说明

### 1. 背景：清算消费 exchange-match-result

clearing 服务通过 `ClearingMatchResultConsumer` 监听撮合结果 topic：

- **topic**：`exchange-match-result`
- **作用**：从撮合结果中提取 `trades`、`completedOrders`，计算清算结果并落库，然后发清算结果到 `exchange-clearing-result`。
- **幂等**：按 `messageId` / 业务主键在 `ClearingService` 内实现幂等，容忍 Kafka 层面的“至少一次”投递。

### 2. 手动提交 offset（AckMode.MANUAL）

为保证“业务成功才提交位点”，clearing 使用手动 ack：

- **配置文件**：`KafkaConsumerConfiguration`
  - 使用批量监听 + 手动提交：

```java
factory.setBatchListener(true);
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
```

- **监听方法**：`ClearingMatchResultConsumer.handleMatchResult(...)`

```java
@KafkaListener(
        topics = "exchange-match-result",
        containerFactory = "kafkaListenerContainerFactory",
        groupId = "market-clearing-group"
)
public void handleMatchResult(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
    try {
        for (ConsumerRecord<String, String> record : records) {
            // 解析 JSON，调用 clearingService.processAndPublish(...)
        }
        // 整个 batch 成功后才提交 offset
        if (ack != null) {
            ack.acknowledge();
        }
    } catch (Exception e) {
        // 抛异常，不 ack，本批次 offset 不提交，后续由容器重试；幂等由业务保证
        throw e;
    }
}
```

**关键点：**

- 容器开启 `MANUAL` ack 模式后，Spring 才会把 `Acknowledgment` 参数注入到监听方法中。
- **不调用 `ack.acknowledge()`：**
  - 当前进程内，这一批消息只会被处理一次（不会在同一次 run 循环里重新投递）；
  - 但因为 offset 没提交到 Kafka，**进程重启 / rebalance 后会从上一次提交的位置重新拉消息**，实现 Kafka 层的“至少一次”。

### 3. 调试场景：本地多次消费同一批消息

本地单机调试时，经常需要“反复吃同一批 `exchange-match-result` 消息”来验证清算逻辑。可以用以下几种方式。

#### 3.1 最简单：切换调试用 groupId（推荐）

监听注解中指定了 consumer group：

```java
@KafkaListener(
        topics = "exchange-match-result",
        containerFactory = "kafkaListenerContainerFactory",
        groupId = "market-clearing-group"
)
```

本地调试时，可以临时改成新的 groupId，例如：

```java
@KafkaListener(
        topics = "exchange-match-result",
        containerFactory = "kafkaListenerContainerFactory",
        groupId = "market-clearing-group-debug"
)
```

配合配置：

```properties
spring.kafka.consumer.auto-offset-reset=earliest
```

效果：

- 新 group 在 Kafka 中没有历史 offset 记录；
- 第一次启动时会从 `earliest` 开始拉取 **所有还在 broker 上的历史消息**；
- 不影响正式 group（`market-clearing-group`）的消费进度；
- 需要再次“从头调试”时，可以：
  - 再换一个新 groupId（`*-debug-2`），或者
  - 删除本地 Kafka 数据 / 用 reset-offsets 命令重置。

#### 3.2 利用“不 ack 不提交 offset”的特性

在 `AckMode.MANUAL` + `enable.auto.commit=false` 的前提下：

- 若业务处理完 **不调用** `ack.acknowledge()`：
  - Kafka 端 offset 不会前进；
  - 当前进程内，这一批消息只消费一次；
  - 清算服务 **重启后会从上一次提交的 offset（或 earliest）位置重新拉消息**，从而再次消费同一批数据。

这种方式适合：本地想通过“反复重启 clearing 服务”来回放同一批消息。

### 4. consumer 并发与 clientId（为什么看到 client 1/2/3）

本地虽然是“单机单进程”，但 clearing 默认配置了 **多并发 consumer**：

```java
@Value("${spring.kafka.consumer.concurrency:3}")
private int concurrency;

factory.setConcurrency(concurrency);
```

因此 Spring-Kafka 在一个 JVM 进程中创建了 3 个独立的 Kafka consumer 客户端，日志中会看到类似：

- `consumer-market-clearing-group-1`
- `consumer-market-clearing-group-2`
- `consumer-market-clearing-group-3`

这些都是同一个 groupId（`market-clearing-group`）下的三个 client 实例，用于并行消费分区。

**如果本地调试希望严格“单线程单 consumer”**，可以在 `application-dev.properties` 中配置：

```properties
spring.kafka.consumer.concurrency=1
```

或者直接改默认值为 1，这样日志里就只会有 `consumer-market-clearing-group-1` 一个 client。

### 5. 小结

- **生产语义**：clearing 使用 `AckMode.MANUAL`，做到“业务成功才提交 offset”，容忍 Kafka 至少一次投递，通过业务幂等保证资金正确。
- **本地调试**：
  - 最推荐的方式是改 `groupId` 为 `*-debug`，配合 `auto-offset-reset=earliest`，从头回放 topic 历史消息；
  - 也可以利用“不 ack 不提交 offset”的特性 + 重启服务，实现多次消费同一批数据。
- **并发与 clientId**：`concurrency` 决定了同一进程内的 consumer 数量，clientId 后缀 `-1/-2/-3` 是正常现象，不是多机器。调试时可以把并发改为 1，方便观察和排障。

