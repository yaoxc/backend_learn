package com.bizzan.bitrade.entity;

/**
 * 委托订单状态。
 */
public enum ExchangeOrderStatus {
    /** 交易中：已挂单，未完全成交、未撤单、未超时 */
    TRADING,
    /** 已完成：已完全成交 */
    COMPLETED,
    /** 已撤销：用户或系统撤单 */
    CANCELED,
    /** 已超时：超过最大挂单时间未完全成交被系统取消 */
    OVERTIMED
}
