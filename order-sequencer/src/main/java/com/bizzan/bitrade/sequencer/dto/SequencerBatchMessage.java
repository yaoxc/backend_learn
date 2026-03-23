package com.bizzan.bitrade.sequencer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SequencerBatchMessage {

    private String batchId;
    private String symbol;
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        private Long instructionId;
        private String orderId;
        /** 本交易对连续递增序号，撮合去重主键之一 */
        private Long sequenceId;
        private String direction;
        private BigDecimal price;
        private Long receivedAt;
        private String payload;
    }
}
