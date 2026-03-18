package com.bizzan.bitrade.constant;

import com.bizzan.bitrade.core.BaseEnum;
import com.fasterxml.jackson.annotation.JsonValue;

import lombok.Getter;

@Getter
public enum TransactionType implements BaseEnum {
    // ===================== 充值类 =====================
    RECHARGE(101, "充值"),
    RECHARGE_CHAIN(102, "链上充值"),       // 扫链/区块确认入账
    RECHARGE_C2C(103, "C2C充值"),          // C2C 买币入账，与 OTC_BUY 区分时可选用
    ADMIN_RECHARGE(104, "人工充值"),

    // ===================== 提现类 =====================
    WITHDRAW(201, "提现"),
    WITHDRAW_FREEZE(202, "提现冻结"),      // 提现申请时可用→冻结
    WITHDRAW_UNFREEZE(203, "提现解冻"),    // 提现取消或拒绝后冻结→可用

    // ===================== 转账 =====================
    TRANSFER_ACCOUNTS(301, "转账"),

    // ===================== 币币交易 =====================
    EXCHANGE(401, "币币交易"),
    EXCHANGE_FREEZE(402, "下单冻结"),      // 币币下单时可用→冻结

    // ===================== 法币/C2C 买卖 =====================
    OTC_BUY(501, "法币买入"),
    OTC_SELL(502, "法币卖出"),

    // ===================== CTC =====================
    CTC_BUY(601, "CTC买入"),
    CTC_SELL(602, "CTC卖出"),

    // ===================== 活动/营销/奖励 =====================
    ACTIVITY_AWARD(701, "活动奖励"),
    PROMOTION_AWARD(702, "推广奖励"),
    DIVIDEND(703, "分红"),
    VOTE(704, "投票"),
    MATCH(705, "配对"),
    ACTIVITY_BUY(706, "活动兑换"),

    // ===================== 红包 =====================
    RED_OUT(801, "红包发出"),
    RED_IN(802, "红包领取"),

    // ===================== 归集与再平衡 =====================
    SWEEP_FREEZE(901, "归集冻结"),         // 归集时可用→冻结
    SWEEP_UNFREEZE(902, "归集解冻"),       // 归集流程中冻结释放/到账
    REBALANCE(903, "冷热再平衡");         // 冷热钱包调配

    /**
     * 交易类型编码（推荐落库/对账使用）：按业务大类分段，具备“浅醉编码”含义。
     *
     * 约定示例：
     * - 1xx 充值类：RECHARGE=101、RECHARGE_CHAIN=102 ...
     * - 2xx 提现类：WITHDRAW=201、WITHDRAW_FREEZE=202 ...
     * - 4xx 币币：EXCHANGE=401、EXCHANGE_FREEZE=402 ...
     */
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
            case 101: return RECHARGE;
            case 102: return RECHARGE_CHAIN;
            case 103: return RECHARGE_C2C;
            case 104: return ADMIN_RECHARGE;

            case 201: return WITHDRAW;
            case 202: return WITHDRAW_FREEZE;
            case 203: return WITHDRAW_UNFREEZE;

            case 301: return TRANSFER_ACCOUNTS;

            case 401: return EXCHANGE;
            case 402: return EXCHANGE_FREEZE;

            case 501: return OTC_BUY;
            case 502: return OTC_SELL;

            case 601: return CTC_BUY;
            case 602: return CTC_SELL;

            case 701: return ACTIVITY_AWARD;
            case 702: return PROMOTION_AWARD;
            case 703: return DIVIDEND;
            case 704: return VOTE;
            case 705: return MATCH;
            case 706: return ACTIVITY_BUY;

            case 801: return RED_OUT;
            case 802: return RED_IN;

            case 901: return SWEEP_FREEZE;
            case 902: return SWEEP_UNFREEZE;
            case 903: return REBALANCE;
            default:  return null;
        }
    }

    public static int parseOrdinal(TransactionType type) {
        return type != null ? type.getOrdinal() : 0;
    }
}
