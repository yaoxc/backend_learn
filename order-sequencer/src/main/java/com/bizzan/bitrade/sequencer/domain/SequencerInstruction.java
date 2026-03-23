package com.bizzan.bitrade.sequencer.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.math.BigDecimal;

/**
 * 待定序的一条业务指令（如新单、撤单）；定序后写入 {@link #sequenceId} 供撮合去重/全序消费。
 */
@Getter
@Setter
@Entity
@Table(name = "sequencer_instruction", indexes = {
        @Index(name = "idx_seq_instr_symbol_status", columnList = "symbol,status"),
        @Index(name = "idx_seq_instr_batch", columnList = "batchId")
})
public class SequencerInstruction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 交易对，如 BTC/USDT；与 Kafka key、撮合分片一致 */
    @Column(nullable = false, length = 32)
    private String symbol;

    /** 业务侧订单号或指令唯一键，撮合可用 (symbol, orderId, sequenceId) 做幂等 */
    @Column(nullable = false, length = 64)
    private String orderId;

    /** BUY / SELL；价格优先次序依赖方向 */
    @Column(nullable = false, length = 8)
    private String direction;

    @Column(nullable = false, precision = 36, scale = 18)
    private BigDecimal price;

    /**
     * 到达定序模块的时间（毫秒），**时间优先**：越小越靠前。
     */
    @Column(nullable = false)
    private Long receivedAt;

    /** 透传给撮合的原始 JSON（或简化载荷） */
    @Column(columnDefinition = "TEXT")
    private String payload;

    /**
     * 本交易对上**连续递增**的全序编号；仅 SEQUENCED/PUBLISHED 后有值。
     * 撮合引擎可用「已处理最大 sequenceId」或「(orderId+sequenceId) 唯一」做去重。
     */
    private Long sequenceId;

    /** 同一批推送 Kafka 的批次号 */
    @Column(length = 64)
    private String batchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InstructionStatus status = InstructionStatus.PENDING;
}
