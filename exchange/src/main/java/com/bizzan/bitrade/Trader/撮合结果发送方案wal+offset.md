## 撮合结果发送方案：WAL + offset

### 1. 问题记录

- **问题**：`offset` 是“已经成功发完到哪一行/哪一字节了”？怎么保证发送的是一整行，而不是一半行？

### 2. 代码背景（QueueAndWalMatchResultPublisher）

撮合结果发送实现类为 `QueueAndWalMatchResultPublisher`，核心思路是：

- **Writer 线程**：从内存队列取 `MatchResult`，以“**一条结果 = 一行 JSON + 换行符**”的形式顺序写入 WAL 文件 `match-<symbol>.wal`。
- **Sender 线程**：根据 `match-<symbol>.offset` 中记录的 **字节偏移量 offset**，从 WAL 文件中读取尚未发送的行，逐行发 Kafka，成功后推进 offset 并写回 offset 文件。

这里的 offset 是“文件内的**字节偏移量**”，而不是“第几行”的逻辑编号。

### 3. Writer：保证 WAL 按“整行”写入

Writer 侧逻辑（简化）：

```java
String line = JSON.toJSONString(result) + "\n";
writer.write(line);
writer.flush();
```

- 每个 `MatchResult` 被序列化为一行 JSON，然后 **追加一个换行符 `\n`**。
- `flush()` 保证“这一整行”尽量完整落盘（除非遇到极端宕机场景）。

因此 WAL 文件是一个 **按行分隔的 JSON 日志文件**：每一行就是一个完整的撮合结果。

### 4. Sender：offset 如何与“整行”挂钩

Sender 侧逻辑（关键片段）：

```java
long offset = readOffset();

try (FileInputStream fis = new FileInputStream(walPath.toFile());
     BufferedReader reader = new BufferedReader(
             new InputStreamReader(fis, StandardCharsets.UTF_8))) {
    long skipped = fis.skip(offset);
    if (skipped < offset) {
        log.warn("[{}] wal file truncated? offset={}, skipped={}", symbol, offset, skipped);
        offset = skipped;
    }
    String line;
    while (running.get() && (line = reader.readLine()) != null) {
        if (line.isEmpty()) {
            continue;
        }
        long lineStartOffset = offset;
        offset += line.getBytes(StandardCharsets.UTF_8).length + 1; // +1 是换行符
        boolean sent = sendWithRetry(line);
        if (sent) {
            writeOffset(offset);
        } else {
            offset = lineStartOffset;
            sleep(SENDER_RETRY_DELAY_MS);
            break;
        }
    }
}
```

关键点：

- **按字节跳过已发送部分**：`fis.skip(offset)`，从已确认发送成功的偏移之后开始读。
- **按行读取**：`reader.readLine()` 一次读出一整行（直到 `\n` 或文件结束）。
- **发送前记录本行起始 offset**：`long lineStartOffset = offset;`
- **计算“本行字节长度 + 换行符”后再推进 offset**：
  - `offset += line.getBytes(StandardCharsets.UTF_8).length + 1;`
- **只有发送成功才持久化新的 offset**：
  - `if (sent) { writeOffset(offset); }`
- **发送失败则回退 offset 到本行开始位置**：
  - `offset = lineStartOffset;`

因此：

- offset 始终指向“**最后一条已成功发送的完整行之后的字节位置**”；
- 如果某一行发送失败，offset 会被回滚到这行开始，下次重试时会重新读这整行；
- offset **不会**停在某一行的中间字节位置。

### 5. 极端场景：半行写入怎么办？

极端情况下（例如 OS 层面突然宕机），可能会出现“半行”：

- Writer 已经写出部分 JSON，但还没写完这一行，也没写入换行符；
- 这时 Sender 之前的 offset 只会停在“**上一次成功发送的完整行末尾**”：
  - 新的半行数据因为没有被完整读出、也没有被发送成功，所以 **不会驱动 offset 前进**。

重启后 Sender 的行为：

- 重新从 offset 开始，`skip(offset)` 到上次完整行末尾；
- 如果文件尾部只有半行且没有换行符，`readLine()` 读不到这一半行，就不会当作有效记录发送；
- 若某行已完整写入但宕机发生在“发送成功但尚未来得及写 offset”之间，则：
  - 重启后会从旧 offset 重读这行；
  - 这条会 **至少被发送一次，可能被发送两次**，下游需要基于 `messageId` / 业务主键做幂等。

总结：

- **offset 的推进条件**：必须“读到完整一行”且“发送成功”；
- 因此 offset 不会指向半行中间，也不会把半行当作“已成功发送的数据”。

### 6. 设计取舍与语义

- **可靠性优先**：
  - 每成功发送一行就立刻覆盖写 offset 文件；
  - 宕机最多重发“最后一行”，不会漏掉已经写入且成功发送过的 WAL 行。
- **幂等依赖**：
  - WAL + offset 方案在故障边界处是“**至少一次（at-least-once）发送**”，
  - 下游必须按 `messageId` 或业务键实现幂等，保证重复消息不会导致资金/状态错误。
- **性能权衡**：
  - 当前实现是“**逐行更新 offset**”，语义简单，恢复行为直观；
  - 高吞吐场景可以改成“每 N 行或每 T 毫秒批量更新 offset”，
    但那样重启后可能重放一批行，下游幂等要求更高。

