package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeOrder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka 订单进入撮合的纯业务逻辑，供 {@link ExchangeOrderConsumer} 与 Disruptor 单线程处理器共用。
 */
@Slf4j
@Component
public class MatchingOrderPipeline {

    @Autowired
    private CoinTraderFactory traderFactory;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private MatchingRebalanceCoordinator matchingRebalanceCoordinator;

    public void onOrderSubmit(ConsumerRecord<String, String> record, ExchangeOrder order) {
        if (order == null) {
            return;
        }
        log.info("接收订单>>topic={},value={}", record.topic(), record.value());
        matchingRebalanceCoordinator.onOrderObserved(order.getSymbol(), record.partition());
        CoinTrader trader = traderFactory.getTrader(order.getSymbol());
        if (trader.isTradingHalt() || !trader.getReady()) {
            log.error("撮合器未准备完成，撤回当前等待的订单: orderId: {} --- halt: {} --- ready: {}", order.getOrderId(), trader.isTradingHalt(), trader.getReady());
            kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
            return;
        }
        log.info("撮合器准备完成，开始撮合订单: {}", order.getOrderId());
        try {
            long startTick = System.currentTimeMillis();
            trader.trade(order);
            log.info("complete trade,{}ms used!", System.currentTimeMillis() - startTick);
        } catch (Exception e) {
            log.error("交易出错 error: {}，退回订单: {}, ", e.getMessage(), order.getOrderId());
            kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
        }
    }

    public void onOrderCancel(ConsumerRecord<String, String> record, ExchangeOrder order) {
        if (order == null) {
            return;
        }
        log.info("取消订单topic={},key={}", record.topic(), record.key());
        matchingRebalanceCoordinator.onOrderObserved(order.getSymbol(), record.partition());
        CoinTrader trader = traderFactory.getTrader(order.getSymbol());
        if (trader.getReady()) {
            try {
                ExchangeOrder result = trader.cancelOrder(order);
                if (result != null) {
                    kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(result));
                }
            } catch (Exception e) {
                log.info("====取消订单出错===", e);
            }
        }
    }
}
