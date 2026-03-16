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
 * 数据中转服务：负责把“对外入口 topic”的订单/撤单事件，标准化后转发到撮合内部 topic。
 *
 * 设计关键点与约束说明：
 * 1）入口 topic 与撮合内部 topic 分离：
 *    - 外部服务（exchange-api、自动撤单 Job 等）只向 ingress topic 写入：
 *         - 下单：relay.order.ingress-topic（默认 exchange-order-ingress）
 *         - 撤单：relay.order.cancel-ingress-topic（默认 exchange-order-cancel-ingress）
 *    - 撮合内部真正消费的是 internal topic：
 *         - 新单：relay.order.internal-topic（默认 exchange-order）
 *         - 撤单：relay.order.internal-cancel-topic（默认 exchange-order-cancel）
 *    - 这样方便做灰度、限流、权限隔离，业务服务不直接依赖撮合内部 topic。
 *
 * 2）分区与顺序保障（但不是“只消费一次”保障）：
 *    - 统一使用 symbol 作为 Kafka key（kafkaTemplate.send(toTopic, key, raw)）；
 *    - 同一个交易对的订单/撤单必然落在同一个 partition，撮合消费端能按分区顺序处理；
 *    - 这是“有序 + 至少一次”语义，并不保证“只消费一次”，真正的“只进一次撮合簿”要靠撮合服务对 orderId 做幂等控制。
 *
 * 3）手动提交 offset，避免“转发失败但位点已提交”：
 *    - 监听方法签名中引入 Acknowledgment，处理完当前批次 records 后再 ack.acknowledge()；
 *    - 如果中途转发失败抛异常，不会提交 offset，下次会重放这一批（至少一次语义）；
 *    - 这要求撮合/清算服务以 orderId 为幂等键处理消息，抵御重放。
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
        // 使用手动提交模式，只有当本批次消息全部成功中转后才提交 offset。
        // 避免在转发失败时仍然提交位点，保证至少处理一次语义，便于问题排查和重试。
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

        // 可能会投送多次，所以消费端要做幂等
        kafkaTemplate.send(toTopic, key, raw);

        if (log.isDebugEnabled()) {
            log.debug("relayed message, {} -> {}, key={}, orderId={}", fromTopic, toTopic, key, order.getOrderId());
        }
    }
}

