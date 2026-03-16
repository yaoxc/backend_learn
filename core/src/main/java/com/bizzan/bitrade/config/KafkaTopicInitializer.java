package com.bizzan.bitrade.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 改造说明：Kafka topic 自动创建并打日志。
 * 配置 kafka.topics.ensure 时，应用就绪后检查并创建 topic：
 * - 已有则打日志「Kafka topic [xxx] 已有，不需要创建」
 * - 没有则创建并打日志「Kafka topic [xxx] 没有，已自动创建」
 * 可选：kafka.topics.partitions、kafka.topics.replication-factor（默认 1/1）。
 */
@Component
@ConditionalOnBean(KafkaAdmin.class)
@ConditionalOnProperty(name = "kafka.topics.ensure")
public class KafkaTopicInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicInitializer.class);

    private final KafkaAdmin kafkaAdmin;
    private final List<String> topicsToEnsure;
    private final int partitions;
    private final short replicationFactor;

    public KafkaTopicInitializer(KafkaAdmin kafkaAdmin,
                                 @org.springframework.beans.factory.annotation.Value("${kafka.topics.ensure:}") String topicsEnsure,
                                 @org.springframework.beans.factory.annotation.Value("${kafka.topics.partitions:1}") int partitions,
                                 @org.springframework.beans.factory.annotation.Value("${kafka.topics.replication-factor:1}") short replicationFactor) {
        this.kafkaAdmin = kafkaAdmin;
        this.topicsToEnsure = parseTopics(topicsEnsure);
        this.partitions = partitions;
        this.replicationFactor = replicationFactor;
    }

    private static List<String> parseTopics(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    @Override
    public void run(ApplicationArguments args) {
        if (topicsToEnsure.isEmpty()) {
            return;
        }
        log.info("开始检查/创建 Kafka topic，列表: {}（已有则不打创建，没有则自动创建）", topicsToEnsure);
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> existing = admin.listTopics().names().get();
            for (String topic : topicsToEnsure) {
                if (existing.contains(topic)) {
                    log.info("Kafka topic [{}] 已有，不需要创建", topic);
                } else {
                    admin.createTopics(Collections.singletonList(
                            new NewTopic(topic, partitions, replicationFactor))).all().get();
                    log.info("Kafka topic [{}] 没有，已自动创建", topic);
                }
            }
        } catch (Exception e) {
            log.warn("Kafka topic 检查/创建失败: {}", e.getMessage());
        }
        log.info("Kafka topic 检查/创建完成");
    }
}
