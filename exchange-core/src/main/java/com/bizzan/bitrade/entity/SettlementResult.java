package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 结算结果主表。消费清算结果生成资金指令，先落库再发 Kafka，未发成功可定时重试。
 */
@Data
@Entity
@Table(name = "settlement_result", indexes = @Index(unique = true, columnList = "message_id"))
public class SettlementResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 与清算 messageId 一致，幂等 */
    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;
    private String symbol;
    private Long ts;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status = Status.PENDING;
    @Column(name = "payload", columnDefinition = "text")
    private String payload;
    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;
    @Column(name = "published_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date publishedAt;
}
