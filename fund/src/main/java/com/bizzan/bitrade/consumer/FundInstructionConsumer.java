package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.service.FundInstructionExecuteService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
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

    /**
     * 使用手动提交 offset 的方式消费资金指令（exchange-fund-instruction）。
     * <ul>
     *   <li>KafkaListener 容器使用 MANUAL ack 模式（见 KafkaConsumerConfiguration）。</li>
     *   <li>本方法处理完整个 batch 后调用 {@code ack.acknowledge()} 提交位点；若中途抛异常则不提交，由容器重试。</li>
     *   <li>资金指令执行幂等由 FundInstructionExecuteService 按 messageId/业务键保证。</li>
     * </ul>
     */
    @KafkaListener(topics = "exchange-fund-instruction", containerFactory = "kafkaListenerContainerFactory")
    public void handleFundInstruction(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        try {
            for (ConsumerRecord<String, String> record : records) {
                String messageId = record.key();
                String payload = record.value();
                log.info("资金服务处理资金指令 offset:{} , partition:{} , value:{}", record.offset(), record.partition(), payload);
                if (payload == null || payload.isEmpty()) {
                    continue;
                }
                try {
                    fundInstructionExecuteService.processAndExecute(messageId, payload);
                } catch (Exception e) {
                    log.error("fund instruction execute error, topic={}, partition={}, offset={}, messageId={}",
                            record.topic(), record.partition(), record.offset(), messageId, e);
                    // 抛出让整个 batch 不提交 offset，由容器重试；资金幂等由执行服务保证。
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
