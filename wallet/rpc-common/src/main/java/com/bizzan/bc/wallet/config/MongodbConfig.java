package com.bizzan.bc.wallet.config;

import com.bizzan.bc.wallet.converter.BigDecimalToDecimal128Converter;
import com.bizzan.bc.wallet.converter.Decimal128ToBigDecimalConverter;
import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.CustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Mongo 配置，兼容 Spring Boot 2.7 / Spring Data MongoDB 3.x（不再使用已移除的 AbstractMongoConfiguration / SimpleMongoDbFactory）。
 */
@Configuration
@ConditionalOnProperty(name = "spring.data.mongodb.uri")
public class MongodbConfig {

    @Value("${spring.data.mongodb.uri}")
    private String uri;

    @Bean
    public MongoClient mongoClient() {
        return MongoClients.create(uri);
    }

    /** 从 URI 解析数据库名，缺省为 wallet（Spring Data 3.x 使用 ConnectionString 替代 MongoClientURI）。 */
    private String getDatabaseName() {
        return new ConnectionString(uri).getDatabase() != null ? new ConnectionString(uri).getDatabase() : "wallet";
    }

    /** Spring Data MongoDB 3.x：使用 SimpleMongoClientDatabaseFactory 替代已移除的 SimpleMongoDbFactory。 */
    @Bean
    public MongoDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        return new SimpleMongoClientDatabaseFactory(mongoClient, getDatabaseName());
    }

    @Bean
    public MongoMappingContext mongoMappingContext() {
        return new MongoMappingContext();
    }

    /** 自定义 BigDecimal↔Decimal128 转换器，与 Spring Data 3.x 的 CustomConversions 配合。 */
    @Bean
    public MappingMongoConverter mappingMongoConverter(MongoDatabaseFactory mongoDatabaseFactory,
                                                      MongoMappingContext mongoMappingContext) {
        DefaultDbRefResolver dbRefResolver = new DefaultDbRefResolver(mongoDatabaseFactory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mongoMappingContext);
        List<Object> list = new ArrayList<>();
        list.add(new BigDecimalToDecimal128Converter());
        list.add(new Decimal128ToBigDecimalConverter());
        converter.setCustomConversions(new CustomConversions(list));
        converter.afterPropertiesSet();
        return converter;
    }

    @Bean
    public MongoTemplate mongoTemplate(MongoDatabaseFactory mongoDatabaseFactory,
                                      MappingMongoConverter mappingMongoConverter) {
        return new MongoTemplate(mongoDatabaseFactory, mappingMongoConverter);
    }
}
