package com.bizzan.bitrade.sequencer.dto;

import lombok.Data;

/**
 * 撮合处理完一批后写回此结构到 {@code sequencer.matcher-ack-topic}，用于端到端「一批结束再发下一批」。
 */
@Data
public class MatcherBatchAckMessage {
    private String batchId;
    private String symbol;
    private Long ts;
}
