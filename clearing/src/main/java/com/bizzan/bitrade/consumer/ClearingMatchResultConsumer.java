package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import com.bizzan.bitrade.service.ClearingService;

import lombok.val;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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

    /**
     * 使用手动提交 offset 的方式消费 exchange-match-result。
     * <ul>
     *   <li>KafkaListener 容器需配置为 MANUAL/MANUAL_IMMEDIATE（参见 kafkaListenerContainerFactory）。</li>
     *   <li>本方法处理完成整个 batch 后调用 {@code ack.acknowledge()} 提交位点；若中途抛异常则不提交，保留消息供重试。</li>
     *   <li>幂等由 ClearingService 按 messageId/业务键保证，容忍 Kafka 层面的「至少一次」重投。</li>
     * </ul>
     */
    @KafkaListener(topics = "exchange-match-result", containerFactory = "kafkaListenerContainerFactory", groupId = "market-clearing-group")
    public void handleMatchResult(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, String> record : records) {
                String value = record.value();
                if (value == null || value.isEmpty()) {
                    continue;
                }
                log.info("清算服务处理 offset:{} , partition:{} , match-result record, value: {}", record.offset(), record.partition(), value);
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
                    // 单条异常打印后抛出，让整个 batch 不提交 offset，后续重试；幂等由业务保证。
                    log.error("clearing handle match-result record error, topic={}, partition={}, offset={}",
                            record.topic(), record.partition(), record.offset(), e);
                    throw e;
                }
            }
            // 整个 batch 处理成功后，提交消费位点
            if (ack != null) {
                // 先不提交，保证多次调试
                 ack.acknowledge();
            }
        } catch (Exception e) {
            // 保持异常向上抛出，由容器按配置重试；不调用 ack 即表示不提交 offset。
            throw e;
        }
    }
}
