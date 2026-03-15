package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;

/**
 * 已处理的 exchange-match-result 消息 id，用于消费端幂等。
 * 同一 messageId 只落库一次，重复消费时跳过。
 */
@Data
@Entity
@Table(name = "processed_match_result_message", indexes = @Index(unique = true, columnList = "message_id"))
public class ProcessedMatchResultMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    /** 全局唯一消息 id，与 MatchResult.messageId 对应 */
    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;
}
