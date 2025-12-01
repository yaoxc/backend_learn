package com.bizzan.bitrade.config;          // 当前配置类所在包

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig; // Kafka 生产者配置键常量
import org.apache.kafka.common.serialization.StringSerializer; // 字符串序列化器
import org.springframework.beans.factory.annotation.Value; // 读取 application.yml 中的值
import org.springframework.context.annotation.Bean; // 声明这是一个 Spring Bean 生成方法
import org.springframework.context.annotation.Configuration; // 声明这是一个配置类
import org.springframework.kafka.annotation.EnableKafka; // 启用 Kafka 注解（@KafkaListener 等）
import org.springframework.kafka.core.DefaultKafkaProducerFactory; // 默认生产者工厂实现
import org.springframework.kafka.core.KafkaTemplate; // Spring 提供的 Kafka 生产者模板
import org.springframework.kafka.core.ProducerFactory; // 生产者工厂接口

@Configuration // 告诉 Spring 这是一个配置类，启动时加载
@EnableKafka   // 开启 Kafka 注解功能（如 @KafkaListener）
public class KafkaProducerConfiguration {

    // ↓↓↓ 使用 @Value 把 application.yml 里的配置读进来，方便不同环境换值
    @Value("${spring.kafka.bootstrap-servers}")
    private String servers; // Kafka broker 地址列表，如 192.168.1.101:9092

    @Value("${spring.kafka.producer.retries}")
    private int retries; // 发送失败时的重试次数（0 表示不重试）

    @Value("${spring.kafka.producer.batch.size}")
    private int batchSize; // 一个批次最多攒多少字节再发（单位：字节）

    @Value("${spring.kafka.producer.linger}")
    private int linger; // 批次等待时间（毫秒），超时即使未满也发

    @Value("${spring.kafka.producer.buffer.memory}")
    private int bufferMemory; // 客户端本地缓冲区总大小（单位：字节）

    /**
     * 组装 Kafka 生产者的所有配置项
     */
    public Map<String, Object> producerConfigs() {
        Map<String, Object> props = new HashMap<>();
        // broker 列表（必填）
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, servers);
        // 发送失败时重试次数（网络抖动时自动重发）
        props.put(ProducerConfig.RETRIES_CONFIG, retries);
        // 批次大小：攒够多少字节再发，提高吞吐
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize);
        // 批次等待时间：即使没攒够也超时发送，降低延迟
        props.put(ProducerConfig.LINGER_MS_CONFIG, linger);
        // 客户端总缓冲区大小（字节）
        props.put(ProducerConfig.BUFFER_MEMORY_CONFIG, bufferMemory);
        // 自定义分区器（当前注释掉，用默认轮询）
        // props.put(ProducerConfig.PARTITIONER_CLASS_CONFIG, "com.bizzan.bitrade.kafka.kafkaPartitioner");
        // key 序列化器：字符串 → 字节数组
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // value 序列化器：字符串 → 字节数组（JSON 消息）
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return props;
    }

    /**
     * 创建生产者工厂（Spring Kafka 需要）
     */
    public ProducerFactory<String, String> producerFactory() {
        // 把上面配置传进去，工厂就能生产出 KafkaProducer 实例
        return new DefaultKafkaProducerFactory<>(producerConfigs());
    }

    /**
     * 向 Spring 容器注册 KafkaTemplate Bean
     * 业务代码里直接 @Autowired KafkaTemplate 即可发送消息
     */
    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<String, String>(producerFactory());
    }
}