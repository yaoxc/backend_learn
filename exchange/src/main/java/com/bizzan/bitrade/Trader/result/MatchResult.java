package com.bizzan.bitrade.Trader.result;

import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 【改造范围】方案 A：单条消息原子。
 * 一次撮合产生的「成交明细 + 已完全成交订单」打成一条 Kafka 消息，下游单事务处理，避免部分成功。
 */
@Data
public class MatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 交易对，如 BTC/USDT */
    private String symbol;
    /** 时间戳（毫秒） */
    private Long ts;
    /** 本批成交明细 */
    private List<ExchangeTrade> trades;
    /** 本批已完全成交的订单（需更新状态 + 退剩余冻结） */
    private List<ExchangeOrder> completedOrders;

    public MatchResult() {
    }

    public MatchResult(String symbol, Long ts, List<ExchangeTrade> trades, List<ExchangeOrder> completedOrders) {
        this.symbol = symbol;
        this.ts = ts != null ? ts : System.currentTimeMillis();
        this.trades = trades;
        this.completedOrders = completedOrders;
    }
}
