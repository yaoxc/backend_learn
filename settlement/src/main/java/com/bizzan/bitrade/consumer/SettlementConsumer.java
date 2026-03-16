package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.service.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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

    /**
     * 使用手动提交 offset 的方式消费 exchange-clearing-result。
     * <ul>
     *   <li>KafkaListener 容器配置为 MANUAL ack 模式（见 KafkaConsumerConfiguration）。</li>
     *   <li>整个 batch 处理成功后调用 {@code ack.acknowledge()} 提交位点；中途异常则不提交，由容器重试。</li>
     *   <li>业务按 messageId 幂等，容忍 Kafka 的「至少一次」重投。</li>
     * </ul>
     */
    @KafkaListener(topics = "exchange-clearing-result", containerFactory = "kafkaListenerContainerFactory")
    public void handleClearingResult(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, String> record : records) {
                String messageId = record.key();
                String payload = record.value();
                log.info("结算服务处理 offset:{} , partition:{} , value:{}", record.offset(), record.partition(), payload);
                if (payload == null || payload.isEmpty()) {
                    continue;
                }
                try {
                    settlementService.processAndPublish(messageId, payload);
                } catch (Exception e) {
                    log.error("settlement handle clearing result error, topic={}, partition={}, offset={}, messageId={}",
                            record.topic(), record.partition(), record.offset(), messageId, e);
                    // 抛出让整个 batch 不提交 offset，后续重试；幂等由 SettlementService 保证。
                    throw e;
                }
            }
            if (ack != null) {
                ack.acknowledge();
            }
        } catch (Exception e) {
            // 不调用 ack 即表示不提交 offset；交由容器按配置重试。
            throw e;
        }
    }
}
