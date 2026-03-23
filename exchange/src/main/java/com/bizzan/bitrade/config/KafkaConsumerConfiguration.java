package com.bizzan.bitrade.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import com.bizzan.bitrade.consumer.MatchingRebalanceCoordinator;

@Configuration
@EnableKafka
public class KafkaConsumerConfiguration {

	@Value("${spring.kafka.bootstrap-servers}")
	private String servers;
	@Value("${spring.kafka.consumer.enable.auto.commit:false}")
	private boolean enableAutoCommit;
	@Value("${spring.kafka.consumer.session.timeout}")
	private String sessionTimeout;
	@Value("${spring.kafka.consumer.auto.commit.interval}")
	private String autoCommitInterval;
	@Value("${spring.kafka.consumer.group.id}")
	private String groupId;
	@Value("${spring.kafka.consumer.auto.offset.reset}")
	private String autoOffsetReset;
	@Value("${spring.kafka.consumer.concurrency}")
	private int concurrency;
	@Value("${spring.kafka.consumer.maxPollRecordsConfig}")
	private int maxPollRecordsConfig;
	@Autowired
	private MatchingRebalanceCoordinator matchingRebalanceCoordinator;

	public Map<String, Object> consumerConfigs() {
		Map<String, Object> propsMap = new HashMap<>();
		propsMap.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
		propsMap.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
		propsMap.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, autoCommitInterval);
		propsMap.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeout);
		propsMap.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		propsMap.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		propsMap.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		propsMap.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
		propsMap.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecordsConfig);// 每个批次获取数
		return propsMap;
	}

	public ConsumerFactory<String, String> consumerFactory() {
		return new DefaultKafkaConsumerFactory<>(consumerConfigs());
	}

	@Bean
	public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory() {
		ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory());
		factory.setConcurrency(concurrency);
		factory.getContainerProperties().setPollTimeout(1500);
		// 再均衡回调：撤销分区时记录 checkpoint；接管分区时触发订单簿重放恢复。
		factory.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
			@Override
			public void onPartitionsRevokedBeforeCommit(org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
														java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
				matchingRebalanceCoordinator.onPartitionsRevoked(consumer, partitions);
			}

			@Override
			public void onPartitionsAssigned(org.apache.kafka.clients.consumer.Consumer<?, ?> consumer,
											 java.util.Collection<org.apache.kafka.common.TopicPartition> partitions) {
				matchingRebalanceCoordinator.onPartitionsAssigned(partitions);
			}
		});
		// 使用批量监听 + 手动提交 offset，配合监听方法入参 Acknowledgment。
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
		factory.setBatchListener(true);
		return factory;
	}

}
