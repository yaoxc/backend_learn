package com.bizzan.bitrade.config;

import org.springframework.context.annotation.Configuration;

/**
 * Kafka 相关配置。
 * 改造说明：原 NewTopic Bean 已移除，topic 自动创建及「已有/没有」日志统一由 core 模块
 * {@link com.bizzan.bitrade.config.KafkaTopicInitializer} 处理，需在 application 中配置
 * kafka.topics.ensure=xxx,yyy（如 exchange-api 的 application-dev 中已配置）。
 */
@Configuration
public class KafkaTopicConfig {
}
