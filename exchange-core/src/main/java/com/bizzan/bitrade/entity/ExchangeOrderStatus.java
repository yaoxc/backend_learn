package com.bizzan.bitrade.entity;

/**
 * 委托订单状态。状态
 * 
 * 0已取消
 * 1未成交
 * 2部分成交
 * 3完全成交'
 */
public enum ExchangeOrderStatus {

    /** 0 - 已取消：用户或系统撤单（包括超时撤单等），不再参与撮合。 */
    CANCELED(0),

    /** 1 - 未成交：已挂单但尚无任何成交明细。 */
    TRADING(1),

    /** 2 - 部分成交：已有部分成交但仍有剩余待成交。 */
    PARTIAL_FILLED(2),

    /** 3 - 完全成交：数量已经全部成交完成。 */
    COMPLETED(3);

    private final int status;

    ExchangeOrderStatus(int status) {
        this.status = status;
    }

    public int getStatus() {
        return status;
    }
}
