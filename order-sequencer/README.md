# order-sequencer（定序模块）

## 职责

- 接收待定序指令（HTTP 写入 `sequencer_instruction`）。
- **时间优先、价格优先**（二遍：买单价高、卖单价低）排序后，为同一 `symbol` 分配**连续递增**的 `sequenceId`。
- 将 `sequenceId` 写回指令行；按批发送到 Kafka topic（默认 `exchange-order-sequenced`）。
- **上一批在 Kafka（及可选撮合 ACK）完成后**，才允许分配/发送下一批，多进程依赖 DB 悲观锁 + `SEQUENCED` 状态门槛保证。

## 运行

```bash
# 需 MySQL、Kafka；按需修改 application.yml
mvn -pl order-sequencer spring-boot:run
```

## 接入

`POST /api/sequencer/instructions`

```json
{
  "symbol": "BTC/USDT",
  "orderId": "E123",
  "direction": "BUY",
  "price": 50000,
  "receivedAt": 1730000000000,
  "payload": "{\"type\":\"NEW_ORDER\",...}"
}
```

## 撮合消费

消费 `exchange-order-sequenced`，按 `items[].sequenceId` 顺序处理；可用 `(symbol, orderId, sequenceId)` 或「已处理最大 sequenceId」做去重。

若配置 `sequencer.wait-mode=END_TO_END_MATCHER_ACK`，处理完一批后向 `sequencer.matcher-ack-topic` 发送：

```json
{"batchId":"...","symbol":"BTC/USDT","ts":1730000000000}
```

## 表

- `sequencer_instruction`：指令与 `sequenceId` / `batchId` / 状态。
- `sequencer_symbol_state`：每 symbol 下一个 `nextSeq`，行锁载体。

详见代码内注释。
