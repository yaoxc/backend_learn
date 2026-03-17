package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 结算产出的资金指令凭证表：记录“应执行”的资金指令消息（通常一条 messageId 对应一批指令）。
 * 用于对账：证明结算层确实生成了哪些指令，以及是否已成功发布到 Kafka。
 */
@Data
@Entity
@Table(name = "fund_instruction_record", indexes = {
        @Index(name = "uk_fund_instruction_record_message_id", unique = true, columnList = "message_id")
})
public class FundInstructionRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Status { PENDING, PUBLISHED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Column(name = "symbol", length = 32)
    private String symbol;

    @Column(name = "ts")
    private Long ts;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
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

