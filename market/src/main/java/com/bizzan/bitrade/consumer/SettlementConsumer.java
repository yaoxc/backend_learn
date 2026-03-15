package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.service.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消费清算结果（exchange-clearing-result），生成资金指令并落库、发 Kafka。
 * 按 messageId 幂等，发送失败由 RetrySettlementPublishJob 重试。
 */
@Component
@Slf4j
public class SettlementConsumer {

    @Autowired
    private SettlementService settlementService;

    @KafkaListener(topics = "exchange-clearing-result", containerFactory = "kafkaListenerContainerFactory")
    public void handleClearingResult(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            String messageId = record.key();
            String payload = record.value();
            if (payload == null || payload.isEmpty()) {
                continue;
            }
            try {
                settlementService.processAndPublish(messageId, payload);
            } catch (Exception e) {
                log.error("settlement handle clearing result error, messageId={}", messageId, e);
                throw e;
            }
        }
    }
}
