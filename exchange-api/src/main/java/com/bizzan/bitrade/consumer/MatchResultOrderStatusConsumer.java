package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import com.bizzan.bitrade.service.ExchangeOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 消费撮合结果，仅更新订单状态（MySQL），不写成交明细（不写 MongoDB），不碰资金、不推行情。
 * <p>
 * 用途：当未部署 market 或 market 未消费到 exchange-match-result 时，由 exchange-api 保证订单状态能从未完成→已完成，
 * 与「清算→结算→资金」流水线配合，避免出现「资金已入账但订单仍为 TRADING」。
 * 成交明细（exchange_order_detail）由 market 消费同一条 match-result 时写入；exchange-api 不依赖 MongoDB。
 * <p>
 * 与 market 的 ExchangeTradeConsumer 使用不同 groupId，同一条 match-result 可被两边各消费一次；
 * 通过 messageId 幂等（processed_match_result_message），先处理的一方落库，后处理的一方跳过。
 * <p>
 * 可通过配置关闭：exchange.order.status.sync.enabled=false。
 * <p>
 * 使用手动提交 offset：处理成功整批后调用 ack.acknowledge()；异常则不提交，由容器重试。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "exchange.order.status.sync.enabled", havingValue = "true", matchIfMissing = true)
public class MatchResultOrderStatusConsumer {

    @Autowired
    private ExchangeOrderService exchangeOrderService;

    @KafkaListener(topics = "exchange-match-result", 
    containerFactory = "kafkaListenerContainerFactory", 
    groupId = "exchange-api-order-status-group")
    public void onMatchResult(List<ConsumerRecord<String, String>> records, Acknowledgment ack) throws Exception {
        for (ConsumerRecord<String, String> record : records) {
            String value = record.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            log.info("根据撮合结果,更新订单状态: offset:{}, value: {}", record.offset(), value);
            JSONObject obj = JSON.parseObject(value);
            String messageId = obj.getString("messageId");
            JSONArray tradesArr = obj.getJSONArray("trades");
            JSONArray completedArr = obj.getJSONArray("completedOrders");
            List<ExchangeTrade> trades = tradesArr != null ? JSON.parseArray(tradesArr.toJSONString(), ExchangeTrade.class) : Collections.emptyList();
            List<ExchangeOrder> completedOrders = completedArr != null ? JSON.parseArray(completedArr.toJSONString(), ExchangeOrder.class) : Collections.emptyList();
            if (trades.isEmpty() && completedOrders.isEmpty()) {
                continue;
            }
            
            boolean processed = exchangeOrderService.processMatchResultIdempotentOrderStatusOnly(messageId, trades, completedOrders);
            if (processed) {
                log.info("match-result order status synced, messageId={} trades={} completedOrders={}",
                        messageId, trades.size(), completedOrders.size());
            }
        }
        if (ack != null) {
            ack.acknowledge();
        }
    }
}
