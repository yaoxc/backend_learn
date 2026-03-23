package com.bizzan.bitrade.sequencer.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 定序模块可调参数：批大小、拉取窗口、Kafka topic、阻塞等待语义。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sequencer")
public class SequencerProperties {

    /** 每批最多包含的指令条数 */
    private int batchSize = 50;

    /** 从 PENDING 池拉取参与排序的上限（先拉再内存排序，避免大表全表扫） */
    private int pendingFetchSize = 500;

    /** 调度间隔：上一轮全部 symbol 处理完后再隔 fixedDelayMs */
    private long pollFixedDelayMs = 200L;

    private String outputTopic = "exchange-order-sequenced";

    /** 撮合处理完一批后回 ACK 的 topic（仅 waitMode=END_TO_END_MATCHER_ACK 时使用） */
    private String matcherAckTopic = "exchange-sequencer-batch-ack";

    public enum WaitMode {
        /** 仅等待 Kafka broker 对 produce 的确认；不等待下游撮合 */
        KAFKA_BROKER_ACK,
        /** 额外等待撮合发回 batchId ACK（需实现消费者） */
        END_TO_END_MATCHER_ACK
    }

    private WaitMode waitMode = WaitMode.KAFKA_BROKER_ACK;

    private long kafkaSendTimeoutMs = 30_000L;

    private long endToEndTimeoutMs = 60_000L;
}
