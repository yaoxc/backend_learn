package com.bizzan.bitrade.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 清算结果 DTO，用于落库 payload 与发 Kafka；结算/资金服务可据此生成资金指令。
 */
@Data
public class ClearingResultDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String messageId;
    private String symbol;
    private Long ts;
    /** 每笔成交对应的买卖双方清算明细（手续费、应收应付） */
    private List<TradeClearingItem> tradeClearingList = new ArrayList<>();
    /** 已完全成交订单的退冻结明细 */
    private List<OrderRefundItem> orderRefundList = new ArrayList<>();

    @Data
    public static class TradeClearingItem implements Serializable {
        private String orderId;
        private String direction; // BUY / SELL
        private BigDecimal fee;
        private String incomeSymbol;
        private BigDecimal incomeAmount;
        private String outcomeSymbol;
        private BigDecimal outcomeAmount;
    }

    @Data
    public static class OrderRefundItem implements Serializable {
        private String orderId;
        private String coinSymbol;
        private BigDecimal refundAmount;
    }
}
