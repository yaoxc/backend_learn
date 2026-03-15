package com.bizzan.bitrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 结算服务启动类：独立消费 exchange-clearing-result，生成资金指令落库并发送 exchange-fund-instruction，
 * 供资金服务（market 或独立资金服务）消费执行钱包操作。
 */
@EnableScheduling
@EntityScan("com.bizzan.bitrade.entity")
@EnableJpaRepositories("com.bizzan.bitrade.dao")
@ComponentScan(basePackages = "com.bizzan.bitrade")
@SpringBootApplication(exclude = { MongoAutoConfiguration.class })
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
