package com.bizzan.bitrade.service;

import com.bizzan.bitrade.dao.ExchangeOrderRepository;
import com.bizzan.bitrade.dto.ClearingResultDTO;
import com.bizzan.bitrade.entity.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 清算计算：根据成交流水与已完成订单，计算手续费、应收应付、退冻结金额。
 * 不写钱包、不写订单状态，仅产出结构化清算结果供落库与下发 Kafka。
 */
@Slf4j
@Service
public class ClearingComputeService {

    @Autowired
    private ExchangeOrderRepository exchangeOrderRepository;
    @Autowired
    private ExchangeCoinService exchangeCoinService;

    /**
     * 计算清算结果 DTO，与 processOrder/orderRefund 逻辑一致（仅算不算钱）。
     */
    public ClearingResultDTO compute(String messageId, String symbol, Long ts,
                                     List<ExchangeTrade> trades, List<ExchangeOrder> completedOrders) {
        ClearingResultDTO dto = new ClearingResultDTO();
        dto.setMessageId(messageId);
        dto.setSymbol(symbol);
        dto.setTs(ts != null ? ts : System.currentTimeMillis());

        ExchangeCoin coin = exchangeCoinService.findBySymbol(symbol);
        if (coin == null) {
            log.warn("clearing compute: coin not found, symbol={}", symbol);
            return dto;
        }

        if (trades != null) {
            for (ExchangeTrade trade : trades) {
                ExchangeOrder buyOrder = exchangeOrderRepository.findByOrderId(trade.getBuyOrderId());
                ExchangeOrder sellOrder = exchangeOrderRepository.findByOrderId(trade.getSellOrderId());
                if (buyOrder != null) {
                    dto.getTradeClearingList().add(computeTradeItem(buyOrder, trade, coin));
                }
                if (sellOrder != null) {
                    dto.getTradeClearingList().add(computeTradeItem(sellOrder, trade, coin));
                }
            }
        }

        if (completedOrders != null) {
            for (ExchangeOrder order : completedOrders) {
                ClearingResultDTO.OrderRefundItem item = computeRefundItem(order);
                if (item != null && item.getRefundAmount().compareTo(BigDecimal.ZERO) > 0) {
                    dto.getOrderRefundList().add(item);
                }
            }
        }

        return dto;
    }

    private ClearingResultDTO.TradeClearingItem computeTradeItem(ExchangeOrder order, ExchangeTrade trade, ExchangeCoin coin) {
        ClearingResultDTO.TradeClearingItem item = new ClearingResultDTO.TradeClearingItem();
        item.setMemberId(order.getMemberId());
        item.setOrderId(order.getOrderId());
        item.setDirection(order.getDirection() != null ? order.getDirection().name() : null);

        BigDecimal turnover = order.getDirection() == ExchangeOrderDirection.BUY ? trade.getBuyTurnover() : trade.getSellTurnover();
        BigDecimal fee;
        // fee 来自 exchange_coin.fee，买单用 成交数量 * 费率，卖单用 成交额 * 费率。
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            fee = trade.getAmount().multiply(coin.getFee());
        } else {
            fee = turnover.multiply(coin.getFee());
        }
        
        // ID为1的用户默认为机器人，此处当订单用户ID为机器人时，不收取手续费
        // ID为10001的用户默认为超级管理员，此处当订单用户ID为机器人时，不收取手续费
        if (order.getMemberId() != null && (order.getMemberId() == 1L || order.getMemberId() == 10001L)) {
            fee = BigDecimal.ZERO;
        }
        item.setFee(fee);

        BigDecimal incomeAmount;
        BigDecimal outcomeAmount;
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            incomeAmount = trade.getAmount().subtract(fee);
            item.setIncomeSymbol(order.getCoinSymbol());
            item.setOutcomeSymbol(order.getBaseSymbol());
            outcomeAmount = turnover;
        } else {
            incomeAmount = turnover.subtract(fee);
            item.setIncomeSymbol(order.getBaseSymbol());
            item.setOutcomeSymbol(order.getCoinSymbol());
            outcomeAmount = trade.getAmount();
        }
        item.setIncomeAmount(incomeAmount);
        item.setOutcomeAmount(outcomeAmount);
        return item;
    }

    private ClearingResultDTO.OrderRefundItem computeRefundItem(ExchangeOrder order) {
        BigDecimal frozenBalance, dealBalance;
        if (order.getDirection() == ExchangeOrderDirection.BUY) {
            if (order.getType() == ExchangeOrderType.LIMIT_PRICE) {
                frozenBalance = order.getAmount().multiply(order.getPrice());
            } else {
                frozenBalance = order.getAmount();
            }
            dealBalance = order.getTurnover() != null ? order.getTurnover() : BigDecimal.ZERO;
        } else {
            frozenBalance = order.getAmount();
            dealBalance = order.getTradedAmount() != null ? order.getTradedAmount() : BigDecimal.ZERO;
        }
        String coinSymbol = order.getDirection() == ExchangeOrderDirection.BUY ? order.getBaseSymbol() : order.getCoinSymbol();
        BigDecimal refundAmount = frozenBalance.subtract(dealBalance);

        ClearingResultDTO.OrderRefundItem item = new ClearingResultDTO.OrderRefundItem();
        item.setMemberId(order.getMemberId());
        item.setOrderId(order.getOrderId());
        item.setCoinSymbol(coinSymbol);
        item.setRefundAmount(refundAmount);
        return item;
    }
}
