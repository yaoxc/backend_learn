package com.bizzan.bitrade.config; // 当前配置类所在包

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig; // Kafka 消费者配置键常量
import org.apache.kafka.common.serialization.StringDeserializer; // 字符串反序列化器
import org.springframework.beans.factory.annotation.Value; // 读取 application.yml 中的值
import org.springframework.context.annotation.Bean; // 声明这是一个 Spring Bean 生成方法
import org.springframework.context.annotation.Configuration; // 声明这是一个配置类
import org.springframework.kafka.annotation.EnableKafka; // 开启 Kafka 注解（@KafkaListener 等）
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory; // 并发监听器容器工厂
import org.springframework.kafka.config.KafkaListenerContainerFactory; // 监听器容器工厂父接口
import org.springframework.kafka.core.ConsumerFactory; // 消费者工厂接口
import org.springframework.kafka.core.DefaultKafkaConsumerFactory; // 默认消费者工厂实现
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer; // 并发消息监听器容器

@Configuration // 告诉 Spring 这是一个配置类，启动时加载
@EnableKafka   // 启用 Kafka 注解（@KafkaListener、@KafkaHandler 等）
public class KafkaConsumerConfiguration {

    // ↓↓↓ 下面使用 @Value 把 application.yml 里的配置读进来，方便不同环境换值
    @Value("${spring.kafka.bootstrap-servers}")
    private String servers; // Kafka broker 地址，如 192.168.1.101:9092

    @Value("${spring.kafka.consumer.enable.auto.commit}")
    private boolean enableAutoCommit; // 是否自动提交 offset，一般 false 由业务手动提交

    @Value("${spring.kafka.consumer.session.timeout}")
    private String sessionTimeout; // 消费者与 broker 的会话超时时间（毫秒）

    @Value("${spring.kafka.consumer.auto.commit.interval}")
    private String autoCommitInterval; // 自动提交间隔（毫秒），仅当 enable.auto.commit=true 时生效

    @Value("${spring.kafka.consumer.group.id}")
    private String groupId; // 消费者组 ID，同一组内负载均衡

    @Value("${spring.kafka.consumer.auto.offset.reset}")
    private String autoOffsetReset; // 无初始偏移量时从何处开始消费（earliest/latest）

    @Value("${spring.kafka.consumer.concurrency}")
    private int concurrency; // 并发度：一个 @KafkaListener 将启动多少个线程/消费者

    @Value("${spring.kafka.consumer.maxPollRecordsConfig}")
    private int maxPollRecordsConfig; // 每次 poll() 最多拉取多少条记录，批处理关键参数

    /**
     * 组装 Kafka 消费者的所有配置项
     */
    public Map<String, Object> consumerConfigs() {
        Map<String, Object> propsMap = new HashMap<>();
        // broker 列表
        propsMap.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        // 是否自动提交（false：业务代码处理完再手动提交，更安全）
        propsMap.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, enableAutoCommit);
        // 自动提交间隔（毫秒）
        propsMap.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, autoCommitInterval);
        // 会话超时（毫秒）
        propsMap.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, sessionTimeout);
        // key 反序列化器：字符串
        propsMap.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // value 反序列化器：字符串（JSON 消息）
        propsMap.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 消费者组 ID
        propsMap.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        // 无初始偏移量时从最早开始（避免消息丢失）
        propsMap.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        // 一次 poll 最大记录数：批处理吞吐关键
        propsMap.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecordsConfig);
        return propsMap;
    }

    /**
     * 创建消费者工厂（Spring Kafka 需要）
     */
    public ConsumerFactory<String, String> consumerFactory() {
        // 把上面配置传进去，工厂就能生产出 KafkaConsumer 实例
        return new DefaultKafkaConsumerFactory<>(consumerConfigs());
    }

    /**
     * 生成并发监听器容器工厂 Bean
     * 所有 @KafkaListener 只要 containerFactory="kafkaListenerContainerFactory"
     * 就会拿到这个高并发、可批处理的配置
     */
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, String>> kafkaListenerContainerFactory() {
        // 具体实现类：支持并发
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 注入消费者工厂（知道怎么创建 KafkaConsumer）
        factory.setConsumerFactory(consumerFactory());
        // 并发度：一个 Listener 启动 N 个线程/消费者
        factory.setConcurrency(concurrency);
        // 无消息时最长阻塞 1.5 秒（避免 CPU 空转）
        factory.getContainerProperties().setPollTimeout(1500);
        // 开启批处理：一次把 max.poll.records 条记录当成 List 传入业务方法
        factory.setBatchListener(true);
        return factory;
    }
}