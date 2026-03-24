package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.entity.ExchangeOrder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;

/**
 * Disruptor 槽位事件：单笔订单或批次结束哨兵（携带 ack 与 checkpoint 用 records）。
 */
public class MatchingOrderRingEvent {

    public enum Kind {
        SUBMIT,
        CANCEL,
        BATCH_END
    }

    private Kind kind = Kind.SUBMIT;
    private ConsumerRecord<String, String> record;
    private ExchangeOrder order;
    private Acknowledgment acknowledgment;
    private List<ConsumerRecord<String, String>> batchRecords;

    public void clear() {
        kind = Kind.SUBMIT;
        record = null;
        order = null;
        acknowledgment = null;
        batchRecords = null;
    }

    public void loadOrder(Kind kind, ConsumerRecord<String, String> record, ExchangeOrder order) {
        this.kind = kind;
        this.record = record;
        this.order = order;
        this.acknowledgment = null;
        this.batchRecords = null;
    }

    public void loadBatchEnd(Acknowledgment ack, List<ConsumerRecord<String, String>> batchRecords) {
        this.kind = Kind.BATCH_END;
        this.record = null;
        this.order = null;
        this.acknowledgment = ack;
        this.batchRecords = batchRecords;
    }

    public Kind getKind() {
        return kind;
    }

    public ConsumerRecord<String, String> getRecord() {
        return record;
    }

    public ExchangeOrder getOrder() {
        return order;
    }

    public Acknowledgment getAcknowledgment() {
        return acknowledgment;
    }

    public List<ConsumerRecord<String, String>> getBatchRecords() {
        return batchRecords;
    }
}
