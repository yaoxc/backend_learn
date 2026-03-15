package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.service.FundInstructionExecuteService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 消费结算产出的资金指令（exchange-fund-instruction），落库后执行钱包操作。
 * 按 messageId 幂等，执行失败由 RetryFundInstructionJob 重试。
 */
@Component
@Slf4j
public class FundInstructionConsumer {

    @Autowired
    private FundInstructionExecuteService fundInstructionExecuteService;

    @KafkaListener(topics = "exchange-fund-instruction", containerFactory = "kafkaListenerContainerFactory")
    public void handleFundInstruction(List<ConsumerRecord<String, String>> records) {
        for (ConsumerRecord<String, String> record : records) {
            String messageId = record.key();
            String payload = record.value();
            if (payload == null || payload.isEmpty()) {
                continue;
            }
            try {
                fundInstructionExecuteService.processAndExecute(messageId, payload);
            } catch (Exception e) {
                log.error("fund instruction execute error, messageId={}", messageId, e);
                throw e;
            }
        }
    }
}
