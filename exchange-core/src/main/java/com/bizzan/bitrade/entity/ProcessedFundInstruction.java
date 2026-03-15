package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 已处理的资金指令记录，用于幂等与重试；先落库再执行钱包操作，保证不丢、可恢复。
 */
@Data
@Entity
@Table(name = "processed_fund_instruction", indexes = @Index(unique = true, columnList = "message_id"))
public class ProcessedFundInstruction implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { PENDING, PROCESSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Status status = Status.PENDING;
    @Column(name = "payload", columnDefinition = "text")
    private String payload;
    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;
    @Column(name = "processed_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date processedAt;
}
