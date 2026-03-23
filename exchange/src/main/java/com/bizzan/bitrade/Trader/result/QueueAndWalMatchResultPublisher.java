package com.bizzan.bitrade.Trader.result;

import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 【改造范围】方案 A 实现：内存队列 + 本地 WAL + 后台 Sender。
 * - 撮合线程只调用 publish() 入队，立即返回，不碰 Kafka。
 * - Writer 线程：从队列取 MatchResult，顺序追加到 WAL 文件。
 * - Sender 线程：从 WAL 按偏移读取，发 Kafka（exchange-match-result），成功后推进偏移。
 * 宕机重启后 Sender 从未发位置继续发，保证消息可靠；单条消息包含 trades+completedOrders，下游单事务处理避免部分成功。
 */
public class QueueAndWalMatchResultPublisher implements MatchResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(QueueAndWalMatchResultPublisher.class);

    /** Kafka topic：单条消息原子（trades + completedOrders） */
    public static final String TOPIC_EXCHANGE_MATCH_RESULT = "exchange-match-result";

    /** 队列默认容量；可配置 match.queue.capacity，建议 1 万～10 万，按单 symbol 峰值与内存权衡，过大则故障时积压多、恢复慢 */
    private static final int DEFAULT_QUEUE_CAPACITY = 20000;
    private static final int SENDER_RETRY_MAX = 5;
    private static final long SENDER_RETRY_DELAY_MS = 500;

    private final String symbol;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String walBasePath;
    private final BlockingQueue<MatchResult> queue;
    /** 队列容量，用于监控暴露，与 queue 实际容量一致 */
    private final int queueCapacity;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread writerThread;
    private Thread senderThread;

    /** WAL 文件路径（symbol 中 / 替换为 -） */
    private final Path walPath;
    /** 已发送偏移文件路径 */
    private final Path offsetPath;

    /**
     * 使用默认队列容量 {@value #DEFAULT_QUEUE_CAPACITY}。
     */
    public QueueAndWalMatchResultPublisher(String symbol, KafkaTemplate<String, String> kafkaTemplate, String walBasePath) {
        this(symbol, kafkaTemplate, walBasePath, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * @param queueCapacity 内存队列容量；≤0 时用默认 {@value #DEFAULT_QUEUE_CAPACITY}。建议 1 万～10 万，过大则故障时积压多、内存占用高。
     */
    public QueueAndWalMatchResultPublisher(String symbol, KafkaTemplate<String, String> kafkaTemplate, String walBasePath, int queueCapacity) {
        this.symbol = symbol;
        this.kafkaTemplate = kafkaTemplate;
        this.walBasePath = walBasePath == null || walBasePath.isEmpty() ? "data/wal" : walBasePath;
        int cap = queueCapacity > 0 ? queueCapacity : DEFAULT_QUEUE_CAPACITY;
        this.queueCapacity = cap;
        this.queue = new ArrayBlockingQueue<>(cap);
        String safeSymbol = symbol.replace("/", "-");
        this.walPath = Paths.get(this.walBasePath, "match-" + safeSymbol + ".wal");
        this.offsetPath = Paths.get(this.walBasePath, "match-" + safeSymbol + ".offset");
    }

    /**
     * 撮合结果入队，供 Writer 线程写 WAL。撮合线程调用后不写 Kafka，仅入队。
     * <ul>
     *   <li>无成交且无完成订单时跳过写入，减少无效 WAL 与 Kafka 消息。</li>
     *   <li>队列满时：使用 {@code put(result)} 阻塞入队，直到 Writer 消费掉一条腾出空位再放入，
     *       保证不丢失任何一条 MatchResult（若丢弃会导致未写 WAL、未发 Kafka，订单/资金不一致）。</li>
     *   <li>代价：若 Writer 或 Sender 很慢或卡住，撮合线程会在 put 上阻塞，形成背压，撮合变慢甚至停住。
     *       一般更可接受撮合慢一点而非丢撮合结果。</li>
     *   <li>若需「宁可丢也不阻塞」：可改回 {@code offer(result, timeout, unit)}，超时丢弃并打 ERROR，
     *       注释中需写明会丢数据、会导致订单/资金不一致。</li>
     * </ul>
     */
    @Override
    public void publish(MatchResult result) {
        if (result == null) {
            return;
        }
        // 无成交且无完成订单时可不写入，减少无效 WAL 记录与 Kafka 消息
        if ((result.getTrades() == null || result.getTrades().isEmpty())
                && (result.getCompletedOrders() == null || result.getCompletedOrders().isEmpty())) {
            return;
        }
        try {
            // 阻塞入队，队列满时撮合线程在此等待，避免丢弃导致订单/资金不一致
            queue.put(result);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[{}] publish interrupted", symbol, e);
        }
    }

    /**
     * 当前队列中待写 WAL 的 MatchResult 数量（供监控）。
     */
    public int getMatchQueueSize() {
        return queue.size();
    }

    /**
     * 队列容量上限（供监控）；可与 getMatchQueueSize 一起计算使用率或剩余空间。
     */
    public int getMatchQueueCapacity() {
        return queueCapacity;
    }

    /**
     * 启动 Writer 与 Sender 后台线程。由 CoinTraderConfig 在创建 trader 后调用。
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(Paths.get(walBasePath));
        } catch (IOException e) {
            log.error("[{}] create wal dir failed: {}", symbol, walBasePath, e);
            throw new RuntimeException("create wal dir failed: " + walBasePath, e);
        }
        writerThread = new Thread(this::runWriter, "match-wal-writer-" + symbol.replace("/", "-"));
        writerThread.setDaemon(false);
        writerThread.start();
        senderThread = new Thread(this::runSender, "match-kafka-sender-" + symbol.replace("/", "-"));
        senderThread.setDaemon(false);
        senderThread.start();
        log.info("[{}] QueueAndWalMatchResultPublisher started, wal={}", symbol, walPath);
    }

    /**
     * Writer 线程：从队列取 MatchResult，追加写 WAL（一行 JSON）。
     */
    private void runWriter() {
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(walPath.toFile(), true), StandardCharsets.UTF_8))) {
            while (running.get()) {
                try {
                    MatchResult result = queue.poll(1, TimeUnit.SECONDS);
                    if (result == null) {
                        continue;
                    }
                    String line = JSON.toJSONString(result) + "\n";
                    writer.write(line);
                    writer.flush();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (IOException e) {
                    log.error("[{}] wal write error", symbol, e);
                }
            }
        } catch (IOException e) {
            log.error("[{}] open wal file error", symbol, e);
        }
    }

    /**
     * Sender 线程：从 WAL 按偏移读取，逐行发 Kafka，成功后更新偏移文件。
     * 重启后从 offset 继续，不会「重新撮合」或「重复写 WAL」；同一 WAL 行若宕机发生在写 offset 之前，可能被再次发送到 Kafka（下游按 messageId 幂等）。
     */
    private void runSender() {
        long offset = readOffset();
        while (running.get()) {
            try {
                if (!Files.exists(walPath)) {
                    sleep(1000);
                    continue;
                }
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
                        offset += line.getBytes(StandardCharsets.UTF_8).length + 1;
                        boolean sent = sendWithRetry(line);
                        if (sent) {
                            // 发一条写一次偏移：宕机重启后从未发位置继续，最多重发本条；若改为批量写则恢复时可能多重发一批，下游需幂等。
                            // 性能：单次为小文件覆盖写且未 fsync，常见撮合量下通常非主瓶颈；若单 symbol 数千条/秒可考虑按 N 条或间隔批量写偏移。
                            // 业界：高吞吐流水线常见做法为按 N 条/按间隔批量写 checkpoint，依赖下游幂等；本实现采用逐条写，偏保守、恢复语义更简单。
                            writeOffset(offset);
                        } else {
                            offset = lineStartOffset;
                            sleep(SENDER_RETRY_DELAY_MS);
                            break;
                        }
                    }
                }
                sleep(50);
            } catch (IOException e) {
                log.error("[{}] sender read wal error", symbol, e);
                sleep(1000);
            }
        }
    }

    private boolean sendWithRetry(String payload) {
        Exception lastEx = null;
        for (int i = 0; i < SENDER_RETRY_MAX; i++) {
            try {
                // 关键：给 exchange-match-result 也设置 key=symbol
                //   - 目的：确保同一交易对（symbol）产生的多批 MatchResult 永远进入同一个 partition，
                //     从而在 consumer 侧保持“同 symbol 的跨批顺序”（避免默认分区策略导致的乱序）。
                //   - 这与撮合输入端 ExchangeOrderRelayConsumer 的 key=symbol 分区保序语义对齐。
                kafkaTemplate.send(TOPIC_EXCHANGE_MATCH_RESULT, symbol, payload).get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                lastEx = e;
                if (i < SENDER_RETRY_MAX - 1) {
                    sleep(SENDER_RETRY_DELAY_MS * (i + 1));
                }
            }
        }
        log.error("[{}] kafka send failed after {} retries: {}", symbol, SENDER_RETRY_MAX, lastEx != null ? lastEx.getMessage() : "");
        return false;
    }

    private long readOffset() {
        if (!Files.exists(offsetPath)) {
            return 0L;
        }
        try (BufferedReader r = Files.newBufferedReader(offsetPath, StandardCharsets.UTF_8)) {
            String s = r.readLine();
            return s != null && !s.isEmpty() ? Long.parseLong(s.trim()) : 0L;
        } catch (IOException | NumberFormatException e) {
            log.warn("[{}] read offset failed, use 0: {}", symbol, e.getMessage());
            return 0L;
        }
    }

    private void writeOffset(long offset) {
        try {
            Files.write(offsetPath, String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.error("[{}] write offset failed", symbol, e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        running.set(false);
        if (writerThread != null) {
            writerThread.interrupt();
        }
        if (senderThread != null) {
            senderThread.interrupt();
        }
    }
}
