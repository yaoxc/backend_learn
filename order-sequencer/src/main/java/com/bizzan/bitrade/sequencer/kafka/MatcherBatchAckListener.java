package com.bizzan.bitrade.sequencer.kafka;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.sequencer.dto.MatcherBatchAckMessage;
import com.bizzan.bitrade.sequencer.service.SequencerBatchAckRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 撮合引擎处理完一批定序消息后，向 {@code sequencer.matcher-ack-topic} 发送 ACK，释放定序侧端到端等待。
 * 仅当 {@code sequencer.wait-mode=END_TO_END_MATCHER_ACK} 时 {@link SequencerPublishService} 会阻塞等待本 ACK。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sequencer.wait-mode", havingValue = "END_TO_END_MATCHER_ACK")
public class MatcherBatchAckListener {

    private final SequencerBatchAckRegistry batchAckRegistry;

    @KafkaListener(
            topics = "${sequencer.matcher-ack-topic:exchange-sequencer-batch-ack}",
            groupId = "${spring.kafka.consumer.group-id:order-sequencer}-matcher-ack")
    public void onAck(ConsumerRecord<String, String> record) {
        try {
            MatcherBatchAckMessage msg = JSON.parseObject(record.value(), MatcherBatchAckMessage.class);
            if (msg != null && msg.getBatchId() != null) {
                // 关键：与发送前 register 的 batchId 对齐，唤醒 publishBatchAndAwait
                batchAckRegistry.complete(msg.getBatchId());
                log.debug("matcher ack batchId={} symbol={}", msg.getBatchId(), msg.getSymbol());
            }
        } catch (Exception e) {
            log.warn("matcher ack parse failed: {}", record.value(), e);
        }
    }
}
