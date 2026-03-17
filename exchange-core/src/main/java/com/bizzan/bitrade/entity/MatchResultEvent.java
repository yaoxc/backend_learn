package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 撮合结果凭证表：落库原始 exchange-match-result 事件，用于对账/追溯/补偿定位。
 */
@Data
@Entity
@Table(name = "match_result_event", indexes = {
        @Index(name = "uk_match_result_event_message_id", unique = true, columnList = "message_id"),
        @Index(name = "idx_match_result_event_symbol_ts", columnList = "symbol,ts")
})
public class MatchResultEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false, unique = true, length = 64)
    private String messageId;

    @Column(name = "symbol", length = 32)
    private String symbol;

    /**
     * 撮合结果事件时间戳（来自消息 ts 字段）
     */
    @Column(name = "ts")
    private Long ts;

    @Column(name = "trades_count")
    private Integer tradesCount;

    @Column(name = "completed_orders_count")
    private Integer completedOrdersCount;

    /**
     * 原始 payload（JSON），保留以便对账追溯；生产可考虑压缩/脱敏/保留期治理。
     */
    @Column(name = "payload", columnDefinition = "text")
    private String payload;

    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;
}

