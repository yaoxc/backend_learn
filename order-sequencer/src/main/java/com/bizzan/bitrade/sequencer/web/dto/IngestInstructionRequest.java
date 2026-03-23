package com.bizzan.bitrade.sequencer.web.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class IngestInstructionRequest {
    private String symbol;
    private String orderId;
    /** BUY / SELL */
    private String direction;
    private BigDecimal price;
    /** 可选；不传则用服务端当前时间，保证时间优先语义 */
    private Long receivedAt;
    /** 透传撮合的 JSON 字符串 */
    private String payload;
}
