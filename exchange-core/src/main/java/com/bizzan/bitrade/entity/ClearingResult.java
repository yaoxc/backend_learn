package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 清算结果主表。先落库再发 Kafka，未发成功可定时重试；落库保证不丢、可恢复。
 */
@Data
@Entity
@Table(name = "clearing_result", indexes = @Index(unique = true, columnList = "message_id"))
public class ClearingResult implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 与 MatchResult.messageId 一致，幂等 */
    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;
    private String symbol;
    private Long ts;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status = Status.PENDING;
    /** 清算结果 JSON，含 tradeClearingList、orderRefundList，供下发 Kafka 与重试 */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;
    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;
    @Column(name = "published_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date publishedAt;
}
