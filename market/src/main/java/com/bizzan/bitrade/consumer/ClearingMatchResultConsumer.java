package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import com.bizzan.bitrade.service.ClearingService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 独立监听 exchange-match-result，只做清算：计算清算结果并落库、发 Kafka（exchange-clearing-result）。
 * 与 ExchangeTradeConsumer 使用不同 consumer group，同一条 match-result 会分别被 market（订单+行情+推送）与清算各消费一次。
 * 不碰资金；幂等按 messageId。
 */
@Component
@Slf4j
public class ClearingMatchResultConsumer {

    @Autowired
    private ClearingService clearingService;

    @KafkaListener(topics = "exchange-match-result", containerFactory = "kafkaListenerContainerFactory", group = "market-clearing-group")
    public void handleMatchResult(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            String value = record.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            try {
                JSONObject obj = JSONObject.parseObject(value);
                String messageId = obj.getString("messageId");
                String symbol = obj.getString("symbol");
                Long ts = obj.getLong("ts");
                JSONArray tradesArr = obj.getJSONArray("trades");
                JSONArray completedArr = obj.getJSONArray("completedOrders");
                List<ExchangeTrade> trades = tradesArr != null ? JSON.parseArray(tradesArr.toJSONString(), ExchangeTrade.class) : Collections.emptyList();
                List<ExchangeOrder> completedOrders = completedArr != null ? JSON.parseArray(completedArr.toJSONString(), ExchangeOrder.class) : Collections.emptyList();
                if (trades.isEmpty() && completedOrders.isEmpty()) {
                    continue;
                }
                if (symbol == null && !trades.isEmpty()) {
                    symbol = trades.get(0).getSymbol();
                }
                clearingService.processAndPublish(messageId, symbol, ts, trades, completedOrders);
            } catch (Exception e) {
                log.error("clearing handle match-result error", e);
                throw e;
            }
        }
    }
}
