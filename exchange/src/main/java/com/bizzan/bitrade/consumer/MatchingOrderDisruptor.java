package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

/**
 * 将 Kafka 监听线程与撮合执行线程解耦：监听线程只负责 publish，单线程 EventHandler 顺序执行撮合。
 * <p>
 * <b>重要</b>：当前实现为<strong>全局单环 + 单消费者</strong>。若 Spring Kafka
 * {@code concurrency &gt; 1}，多个监听线程会向同一 RingBuffer 交错写入，
 * 会<strong>破坏「按 partition 保序」</strong>语义。启用时请将该 container 的
 * {@code spring.kafka.consumer.concurrency} 设为 1，或后续改为「每消费线程独立 Disruptor」。
 * <p>
 * 启用：{@code match.disruptor.enabled=true}
 */
@Component
@ConditionalOnProperty(name = "match.disruptor.enabled", havingValue = "true")
public class MatchingOrderDisruptor {

    private static final int DEFAULT_BUFFER_SIZE = 8192;

    private final int bufferSize;
    private Disruptor<MatchingOrderRingEvent> disruptor;
    private RingBuffer<MatchingOrderRingEvent> ringBuffer;

    @Autowired
    private MatchingOrderPipeline pipeline;
    @Autowired
    private PartitionCheckpointStore partitionCheckpointStore;

    public MatchingOrderDisruptor(@Value("${match.disruptor.buffer-size:8192}") int bufferSize) {
        int n = bufferSize <= 0 ? DEFAULT_BUFFER_SIZE : bufferSize;
        int hb = Integer.highestOneBit(n);
        this.bufferSize = hb < n ? hb << 1 : hb;
    }

    @PostConstruct
    public void start() {
        EventFactory<MatchingOrderRingEvent> factory = MatchingOrderRingEvent::new;
        disruptor = new Disruptor<>(factory, bufferSize, DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new BlockingWaitStrategy());
        disruptor.handleEventsWith((EventHandler<MatchingOrderRingEvent>) (event, sequence, endOfBatch) -> {
            if (event.getKind() == MatchingOrderRingEvent.Kind.BATCH_END) {
                partitionCheckpointStore.persistFromConsumerRecords(event.getBatchRecords());
                Acknowledgment ack = event.getAcknowledgment();
                if (ack != null) {
                    ack.acknowledge();
                }
                event.clear();
                return;
            }
            if (event.getKind() == MatchingOrderRingEvent.Kind.CANCEL) {
                pipeline.onOrderCancel(event.getRecord(), event.getOrder());
            } else {
                pipeline.onOrderSubmit(event.getRecord(), event.getOrder());
            }
            event.clear();
        });
        disruptor.start();
        ringBuffer = disruptor.getRingBuffer();
    }

    @PreDestroy
    public void stop() {
        if (disruptor != null) {
            disruptor.shutdown();
        }
    }

    /**
     * 发布一批下单事件，最后发布 BATCH_END 以在同一顺序边界内提交 offset 与 checkpoint。
     */
    public void publishSubmitBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        publishOrderBatch(records, ack, MatchingOrderRingEvent.Kind.SUBMIT);
    }

    public void publishCancelBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        publishOrderBatch(records, ack, MatchingOrderRingEvent.Kind.CANCEL);
    }

    private void publishOrderBatch(List<ConsumerRecord<String, String>> records, Acknowledgment ack, MatchingOrderRingEvent.Kind kind) {
        if (records == null || records.isEmpty()) {
            if (ack != null) {
                ack.acknowledge();
            }
            return;
        }
        List<ExchangeOrder> orders = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> r : records) {
            ExchangeOrder order = JSON.parseObject(r.value(), ExchangeOrder.class);
            if (order == null) {
                return;
            }
            orders.add(order);
        }
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String, String> r = records.get(i);
            ExchangeOrder order = orders.get(i);
            long seq = ringBuffer.next();
            try {
                MatchingOrderRingEvent e = ringBuffer.get(seq);
                e.loadOrder(kind, r, order);
            } finally {
                ringBuffer.publish(seq);
            }
        }
        long endSeq = ringBuffer.next();
        try {
            MatchingOrderRingEvent e = ringBuffer.get(endSeq);
            e.loadBatchEnd(ack, records);
        } finally {
            ringBuffer.publish(endSeq);
        }
    }
}
