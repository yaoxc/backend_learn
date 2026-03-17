package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeOrder;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ExchangeOrderConsumer {

    @Autowired
    private CoinTraderFactory traderFactory;
    
    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;

    @KafkaListener(topics = "exchange-order",containerFactory = "kafkaListenerContainerFactory",groupId = "${exchange.kafka.group.order:service-exchange-order}")
    public void onOrderSubmitted(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("接收订单>>topic={},value={},size={}",record.topic(),record.value(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }

            // 订单消息可能会被多次投递，所以这里需要做幂等，保证同一条订单，只进一次撮合引擎的队列
            CoinTrader trader = traderFactory.getTrader(order.getSymbol());
            // 如果当前币种交易暂停会自动取消订单
            // halt: 暂停 ready: 完成
            if (trader.isTradingHalt() || !trader.getReady()) {
                // 撮合器未准备完成，撤回当前等待的订单
                log.error("撮合器未准备完成，撤回当前等待的订单: orderId: {} --- halt: {} --- ready: {}", order.getOrderId(), trader.isTradingHalt(), trader.getReady());
                kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
            } else {
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
        }
        // 手动提交当前批次 offset，确保订单已成功写入撮合引擎后再提交
        if (ack != null) {
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = "exchange-order-cancel",containerFactory = "kafkaListenerContainerFactory", groupId = "${exchange.kafka.group.cancel:service-exchange-order-cancel}")
    public void onOrderCancel(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("取消订单topic={},key={},size={}",record.topic(),record.key(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }
            CoinTrader trader = traderFactory.getTrader(order.getSymbol());
            if(trader.getReady()) {
                try {
                    ExchangeOrder result = trader.cancelOrder(order);
                    if (result != null) {
                        kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(result));
                    }
                }catch (Exception e){
                    log.info("====取消订单出错===",e);
                    e.printStackTrace();
                }
            }
        }
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
