package com.bizzan.bitrade.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结算产出的资金指令 DTO，用于落库 payload 与发 Kafka；资金/钱包服务据此执行加减款、解冻等。
 */
@Data
public class FundInstructionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与清算 messageId 一致，幂等与溯源 */
    private String messageId;
    private String symbol;
    private Long ts;
    /** 资金指令明细：每笔对应一个币种一个方向（收入/支出/手续费/退冻/平台手续费收入） */
    private List<FundInstructionItem> instructions = new ArrayList<>();

    /** INCOME=用户收入, OUTCOME=用户支出, FEE=用户支付手续费, REFUND=退冻结, FEE_REVENUE=平台手续费收入 */
    public enum InstructionType { INCOME, OUTCOME, FEE, REFUND, FEE_REVENUE }

    @Data
    public static class FundInstructionItem implements Serializable {
        private Long memberId;
        private String orderId;
        private String symbol;
        private BigDecimal amount;
        private InstructionType type;
    }
}
