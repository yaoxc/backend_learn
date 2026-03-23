package com.bizzan.bitrade.sequencer.domain;

/**
 * 指令生命周期：PENDING → SEQUENCED（已赋连续序号、待推送）→ PUBLISHED（本批已得到 Kafka 确认，可选再等撮合 ACK）
 */
public enum InstructionStatus {
    PENDING,
    SEQUENCED,
    PUBLISHED
}
