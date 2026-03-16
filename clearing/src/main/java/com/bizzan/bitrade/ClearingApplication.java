package com.bizzan.bitrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 清算服务启动类：独立消费 exchange-match-result（group=market-clearing-group），
 * 计算清算结果落库并发 exchange-clearing-result，供结算/资金服务消费。
 * 修改说明：移除 MongoAutoConfiguration 排除，core 内 Mongo 仓库需 mongoTemplate，dev 配置中已加 spring.data.mongodb.uri。
 */
@EnableScheduling
@EntityScan({"com.bizzan.bitrade.entity", "com.bizzan.bitrade.dto"})
@EnableJpaRepositories("com.bizzan.bitrade.dao")
@ComponentScan(basePackages = "com.bizzan.bitrade")
@SpringBootApplication
public class ClearingApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClearingApplication.class, args);
    }
}
