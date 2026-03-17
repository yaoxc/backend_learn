package com.bizzan.bitrade.constant;

import com.bizzan.bitrade.core.BaseEnum;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Getter;

@Getter
public enum TransactionType implements BaseEnum {
    // ===================== 充值类 =====================
    RECHARGE(0, "充值"),
    RECHARGE_CHAIN(21, "链上充值"),       // 扫链/区块确认入账
    RECHARGE_C2C(22, "C2C充值"),         // C2C 买币入账，与 OTC_BUY 区分时可选用
    ADMIN_RECHARGE(10, "人工充值"),

    // ===================== 提现类 =====================
    WITHDRAW(1, "提现"),
    WITHDRAW_FREEZE(18, "提现冻结"),      // 提现申请时可用→冻结
    WITHDRAW_UNFREEZE(23, "提现解冻"),    // 提现取消或拒绝后冻结→可用

    // ===================== 转账 =====================
    TRANSFER_ACCOUNTS(2, "转账"),

    // ===================== 币币交易 =====================
    EXCHANGE(3, "币币交易"),
    EXCHANGE_FREEZE(17, "下单冻结"),      // 币币下单时可用→冻结

    // ===================== 法币/C2C 买卖 =====================
    OTC_BUY(4, "法币买入"),
    OTC_SELL(5, "法币卖出"),

    // ===================== CTC =====================
    CTC_BUY(13, "CTC买入"),
    CTC_SELL(14, "CTC卖出"),

    // ===================== 活动/营销/奖励 =====================
    ACTIVITY_AWARD(6, "活动奖励"),
    PROMOTION_AWARD(7, "推广奖励"),
    DIVIDEND(8, "分红"),
    VOTE(9, "投票"),
    MATCH(11, "配对"),
    ACTIVITY_BUY(12, "活动兑换"),

    // ===================== 红包 =====================
    RED_OUT(15, "红包发出"),
    RED_IN(16, "红包领取"),

    // ===================== 归集与再平衡 =====================
    SWEEP_FREEZE(19, "归集冻结"),        // 归集时可用→冻结
    SWEEP_UNFREEZE(24, "归集解冻"),       // 归集流程中冻结释放/到账
    REBALANCE(20, "冷热再平衡");         // 冷热钱包调配

    private final int ordinal;
    private final String cnName;

    TransactionType(int ordinal, String cnName) {
        this.ordinal = ordinal;
        this.cnName = cnName;
    }

    @Override
    @JsonValue
    public int getOrdinal() {
        return this.ordinal;
    }

    public static TransactionType valueOfOrdinal(int ordinal) {
        switch (ordinal) {
            case 0:  return RECHARGE;
            case 1:  return WITHDRAW;
            case 2:  return TRANSFER_ACCOUNTS;
            case 3:  return EXCHANGE;
            case 4:  return OTC_BUY;
            case 5:  return OTC_SELL;
            case 6:  return ACTIVITY_AWARD;
            case 7:  return PROMOTION_AWARD;
            case 8:  return DIVIDEND;
            case 9:  return VOTE;
            case 10: return ADMIN_RECHARGE;
            case 11: return MATCH;
            case 12: return ACTIVITY_BUY;
            case 13: return CTC_BUY;
            case 14: return CTC_SELL;
            case 15: return RED_OUT;
            case 16: return RED_IN;
            case 17: return EXCHANGE_FREEZE;
            case 18: return WITHDRAW_FREEZE;
            case 19: return SWEEP_FREEZE;
            case 20: return REBALANCE;
            case 21: return RECHARGE_CHAIN;
            case 22: return RECHARGE_C2C;
            case 23: return WITHDRAW_UNFREEZE;
            case 24: return SWEEP_UNFREEZE;
            default: return null;
        }
    }

    public static int parseOrdinal(TransactionType type) {
        return type != null ? type.getOrdinal() : 24;
    }
}
