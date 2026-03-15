package com.bizzan.bitrade.config;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeOrderDetail;
import com.bizzan.bitrade.service.ExchangeOrderDetailService;
import com.bizzan.bitrade.service.ExchangeOrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class CoinTraderEvent implements ApplicationListener<ContextRefreshedEvent> {
    private Logger log = LoggerFactory.getLogger(CoinTraderEvent.class);
    @Autowired
    CoinTraderFactory coinTraderFactory;
    @Autowired
    private ExchangeOrderService exchangeOrderService;
    @Autowired
    private ExchangeOrderDetailService exchangeOrderDetailService;
    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;

    /** 形态一：true=从订单事件日志回放恢复订单簿（推荐）；false=从 DB 加载 TRADING 订单恢复（存在重复撮合风险） */
    @Value("${match.restore.from.order.log:true}")
    private boolean matchRestoreFromOrderLog;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        log.info("======initialize coinTrader======");
        Map<String, CoinTrader> traders = coinTraderFactory.getTraderMap();
        traders.forEach((symbol, trader) -> {
            log.info("======CoinTrader Process: " + symbol + "======");
            if (matchRestoreFromOrderLog) {
                // 形态一：从订单事件日志回放恢复订单簿，避免已撮合未下发的订单从 DB 加载后再次撮合
                trader.replayOrderLog();
            } else {
                // 回退：从 DB 加载 TRADING 订单并 trade，与旧逻辑一致（存在重启重复撮合风险）
                List<ExchangeOrder> orders = exchangeOrderService.findAllTradingOrderBySymbol(symbol);
                log.info("Initialize: find all trading orders, total count( " + orders.size() + ")");
                List<ExchangeOrder> tradingOrders = new ArrayList<>();
                List<ExchangeOrder> completedOrders = new ArrayList<>();
                orders.forEach(order -> {
                    BigDecimal tradedAmount = BigDecimal.ZERO;
                    BigDecimal turnover = BigDecimal.ZERO;
                    List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());
                    for (ExchangeOrderDetail tradeDetail : details) {
                        tradedAmount = tradedAmount.add(tradeDetail.getAmount());
                        turnover = turnover.add(tradeDetail.getAmount().multiply(tradeDetail.getPrice()));
                    }
                    order.setTradedAmount(tradedAmount);
                    order.setTurnover(turnover);
                    if (!order.isCompleted()) {
                        tradingOrders.add(order);
                    } else {
                        completedOrders.add(order);
                    }
                });
                log.info("Initialize: tradingOrders total count( " + tradingOrders.size() + ")");
                try {
                    trader.trade(tradingOrders);
                } catch (ParseException e) {
                    log.warn("trader.trade(tradingOrders) error", e);
                }
                if (completedOrders.size() > 0) {
                    log.info("Initialize: completedOrders total count( " + completedOrders.size() + ")");
                    kafkaTemplate.send("exchange-order-completed", JSON.toJSONString(completedOrders));
                }
            }
            trader.setReady(true);
        });
    }

}
