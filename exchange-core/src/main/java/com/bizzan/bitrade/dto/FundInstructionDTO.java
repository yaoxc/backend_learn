package com.bizzan.bitrade.dto;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.core.config.plugins.validation.constraints.Required;

/**
 * 结算/业务产出的资金指令 DTO，用于落库 payload 与发 Kafka；资金服务据此执行加减款、解冻等。
 * <p>
 * 通过 refType + refId 区分业务来源，便于流水分类与平账：
 * ORDER=币币下单, DEPOSIT=存款, WITHDRAW=提款, SWEEP=归集, REBALANCE=冷热再平衡。
 * 不传时兼容老数据，按 ORDER 处理。
 */
@Data
public class FundInstructionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 与清算/业务 messageId 一致，幂等与溯源 */
    private String messageId;
    private String symbol;
    private Long ts;

    /**
     * 业务类型：ORDER/DEPOSIT/WITHDRAW/SWEEP/REBALANCE，与 MemberTransaction.refType 一致。
     * 为空时资金服务按 ORDER 处理，兼容旧 payload。
     */ 
    private String refType;
    /**
     * 业务主键：订单号/存款单号/提现单号/归集批次ID/再平衡批次ID 等，与 MemberTransaction.refId 一致。
     * 可为空，资金服务会回填 messageId 或 item.orderId（下单场景）。
     */
    private String refId;

    /** 资金指令明细：每笔对应一个币种一个方向（收入/支出/手续费/退冻/平台手续费收入） */
    private List<FundInstructionItem> instructions = new ArrayList<>();

    /** INCOME=用户收入, OUTCOME=用户支出, FEE=用户支付手续费, REFUND=退冻结, FEE_REVENUE=平台手续费收入 */
    public enum InstructionType { INCOME, OUTCOME, FEE, REFUND, FEE_REVENUE }

    @Data
    public static class FundInstructionItem implements Serializable {
        private Long memberId;
        /** 订单号等，下单场景下会写入流水 refId，与 refType=ORDER 配合 */
        private String orderId;
        private String symbol;
        private BigDecimal amount;
        /**
         * 指令类型：收入/支出/手续费/退冻/平台手续费收入。
         * 为兼容旧版 payload，这里通过 @JSONField(name = "type") 接收 JSON 中的 \"type\" 字段。
         */
        private InstructionType instructionType;
        /** 可选：本条指令的业务主键，覆盖 DTO 级 refId 时使用 */
        private String itemRefId;
    }
}
