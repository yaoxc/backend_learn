package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.util.Date;

/**
 * 本地消息表（订单/撤单 Kafka 投递）：与业务同库同事务写入，再异步发 Kafka，保证投递成功。
 * 用于 OrderController 下单、撤单时「先落库再发 Kafka」，发送失败由定时任务重试。
 */
@Data
@Entity
@Table(name = "exchange_order_kafka_outbox", indexes = {
        @Index(name = "idx_outbox_status", columnList = "status"),
        @Index(name = "idx_outbox_topic_key", columnList = "topic, message_key")
})
public class OrderKafkaOutbox {

    public enum Status {
        PENDING,
        SENT,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 64)
    private String messageKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt = new Date();

    @Column(name = "sent_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date sentAt;
}
