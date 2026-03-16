package com.bizzan.bitrade.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 修改说明：exchange-api 启动时不会自动创建 Kafka topic，依赖服务端 auto.create.topics.enable 或手动创建。
 * 本配置通过 NewTopic Bean 在启动时由 KafkaAdmin 自动创建所需 topic，避免本地/测试环境未预先建 topic 导致发送失败。
 */
@Configuration
public class KafkaTopicConfig {

    @Value("${exchange.relay.order.ingress-topic:exchange-order-ingress}")
    private String orderIngressTopic;
    @Value("${exchange.relay.order.cancel-ingress-topic:exchange-order-cancel-ingress}")
    private String cancelIngressTopic;

    @Bean
    public NewTopic orderIngressTopic() {
        return TopicBuilder.name(orderIngressTopic).partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic cancelIngressTopic() {
        return TopicBuilder.name(cancelIngressTopic).partitions(1).replicas(1).build();
    }

    /** OrderUpdateJob 使用的取消订单 topic，与 exchange-api 代码中硬编码名一致 */
    @Bean
    public NewTopic exchangeOrderCancelTopic() {
        return TopicBuilder.name("exchange-order-cancel").partitions(1).replicas(1).build();
    }
}
