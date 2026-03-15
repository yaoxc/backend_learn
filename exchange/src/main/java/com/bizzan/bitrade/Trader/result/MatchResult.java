package com.bizzan.bitrade.Trader.result;

import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 【改造范围】方案 A：单条消息原子。
 * 一次撮合产生的「成交明细 + 已完全成交订单」打成一条 Kafka 消息，下游单事务处理，避免部分成功。
 * messageId 用于消费端幂等：同一 messageId 只处理一次。
 */
@Data
public class MatchResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全局唯一消息 id，发送时生成，消费端据此幂等去重（可选，旧消息无此字段时为 null） */
    private String messageId;
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
        this(null, symbol, ts, trades, completedOrders);
    }

    /** 含 messageId 的构造，用于幂等；messageId 为 null 时消费端不按幂等处理。 */
    public MatchResult(String messageId, String symbol, Long ts, List<ExchangeTrade> trades, List<ExchangeOrder> completedOrders) {
        this.messageId = messageId;
        this.symbol = symbol;
        this.ts = ts != null ? ts : System.currentTimeMillis();
        this.trades = trades;
        this.completedOrders = completedOrders;
    }
}
