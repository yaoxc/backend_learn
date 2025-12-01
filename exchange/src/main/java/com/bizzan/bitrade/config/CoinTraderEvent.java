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
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
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

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        log.info("======initialize coinTrader======");
        // coinTraderFactory.getTraderMap();
        // 1. 加载所有可交易的 代币对:CoinTrader Map
        Map<String,CoinTrader> traders = coinTraderFactory.getTraderMap();
        traders.forEach((symbol,trader) ->{
        	log.info("======CoinTrader Process: " + symbol + "======");
            // 2. 根据代币对符号，加载所有交易中的委托单
            List<ExchangeOrder> orders = exchangeOrderService.findAllTradingOrderBySymbol(symbol);
            log.info("Initialize: find all trading orders, total count( " + orders.size() + ")");
            List<ExchangeOrder> tradingOrders = new ArrayList<>();
            List<ExchangeOrder> completedOrders = new ArrayList<>();
            orders.forEach(order -> {
                BigDecimal tradedAmount = BigDecimal.ZERO; // 成交量
                BigDecimal turnover = BigDecimal.ZERO; // turnover : 成交额（成交金额）= 成交数量 × 成交价格

                // 根据orderId,加载订单详情
                List<ExchangeOrderDetail> details = exchangeOrderDetailService.findAllByOrderId(order.getOrderId());

                for(ExchangeOrderDetail tradeDetail : details) {
                    // orderId下  累加计算成交量
                    tradedAmount = tradedAmount.add(tradeDetail.getAmount());
                    // orderId下 累加计算成交金额
                    turnover = turnover.add(tradeDetail.getAmount().multiply(tradeDetail.getPrice()));
                }
                order.setTradedAmount(tradedAmount);
                order.setTurnover(turnover);
                if(!order.isCompleted()){ // 未完成的委托单
                    tradingOrders.add(order);
                }
                else{ // 已完成的委托单
                    completedOrders.add(order);
                }
            });
            log.info("Initialize: tradingOrders total count( " + tradingOrders.size() + ")");
            try {
                // 进 撮合入口方法
				trader.trade(tradingOrders);
			} catch (ParseException e) {
				e.printStackTrace();
				log.info("异常：trader.trade(tradingOrders);");
			}
            //判断已完成的订单发送消息通知
            if(completedOrders.size() > 0){
            	log.info("Initialize: completedOrders total count( " + tradingOrders.size() + ")");
                kafkaTemplate.send("exchange-order-completed", JSON.toJSONString(completedOrders));
            }
            trader.setReady(true);
        });
    }

}
