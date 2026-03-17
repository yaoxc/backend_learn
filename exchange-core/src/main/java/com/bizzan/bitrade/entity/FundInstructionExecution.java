package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 资金指令执行结果凭证：记录每条资金指令（messageId）的最终执行状态与错误信息，用于对账/排障。
 */
@Data
@Entity
@Table(name = "fund_instruction_execution", indexes = {
        @Index(name = "uk_fund_instruction_execution_message_id", unique = true, columnList = "message_id")
})
public class FundInstructionExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { PENDING, PROCESSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    private Status status = Status.PENDING;

    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "error_msg", columnDefinition = "text")
    private String errorMsg;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "processed_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date processedAt;
}

