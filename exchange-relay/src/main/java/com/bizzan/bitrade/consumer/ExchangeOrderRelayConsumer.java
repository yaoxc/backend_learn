package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.ExchangeOrder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据中转服务：负责把“对外入口 topic”的订单事件，标准化后转发到撮合内部 topic。
 *
 * 设计目标（最小可落地版）：
 * - 入口 topic 与撮合内部 topic 分离（便于治理、灰度、权限隔离）
 * - 统一设置 message key（按 symbol），保证同交易对有序消费与分区稳定
 * - 保持 payload 与旧链路一致（ExchangeOrder JSON），尽量不改撮合消费者
 */
@Slf4j
@Component
public class ExchangeOrderRelayConsumer {

    @Value("${relay.order.ingress-topic:exchange-order-ingress}")
    private String orderIngressTopic;

    @Value("${relay.order.cancel-ingress-topic:exchange-order-cancel-ingress}")
    private String cancelIngressTopic;

    @Value("${relay.order.internal-topic:exchange-order}")
    private String internalOrderTopic;

    @Value("${relay.order.internal-cancel-topic:exchange-order-cancel}")
    private String internalCancelTopic;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @KafkaListener(topics = "${relay.order.ingress-topic:exchange-order-ingress}", containerFactory = "kafkaListenerContainerFactory")
    public void onOrderIngress(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("数据中转服务 ----- Kafka消费者 ----- onOrderIngress records: {}", records);
        for (ConsumerRecord<String, String> record : records) {
            relayOne(record, orderIngressTopic, internalOrderTopic);
        }
        // 【改动】使用手动提交模式，只有当本批次消息全部成功中转后才提交 offset。
        // 【目的】避免在转发失败时仍然提交位点，保证至少处理一次语义，便于问题排查和重试。
        if (ack != null) {
            ack.acknowledge();
        }
    }

    @KafkaListener(topics = "${relay.order.cancel-ingress-topic:exchange-order-cancel-ingress}", containerFactory = "kafkaListenerContainerFactory")
    public void onCancelIngress(List<ConsumerRecord<String, String>> records, Acknowledgment ack) {
        log.info("数据中转服务 ----- Kafka消费者 ----- onCancelIngress records: {}", records);
        for (ConsumerRecord<String, String> record : records) {
            relayOne(record, cancelIngressTopic, internalCancelTopic);
        }
        if (ack != null) {
            ack.acknowledge();
        }
    }

    private void relayOne(ConsumerRecord<String, String> record, String fromTopic, String toTopic) {
        String raw = record.value();
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }

        ExchangeOrder order;
        try {
            order = JSON.parseObject(raw, ExchangeOrder.class);
        } catch (Exception e) {
            log.error("relay parse failed, fromTopic={}, partition={}, offset={}, raw={}", record.topic(), record.partition(), record.offset(), raw, e);
            return;
        }

        if (order == null || order.getSymbol() == null || order.getSymbol().trim().isEmpty()) {
            log.error("relay invalid order, missing symbol, fromTopic={}, partition={}, offset={}, order={}", record.topic(), record.partition(), record.offset(), raw);
            return;
        }

        // 统一分区键：同 symbol 的订单进入同一分区，撮合端同分区顺序消费更稳定
        String key = order.getSymbol().trim();
        kafkaTemplate.send(toTopic, key, raw);

        if (log.isDebugEnabled()) {
            log.debug("relayed message, {} -> {}, key={}, orderId={}", fromTopic, toTopic, key, order.getOrderId());
        }
    }
}

