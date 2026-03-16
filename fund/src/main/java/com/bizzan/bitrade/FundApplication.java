package com.bizzan.bitrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 资金服务启动类：独立消费 exchange-fund-instruction，落库 processed_fund_instruction 后
 * 按条执行钱包操作（加减可用、扣减冻结、解冻等），与 market 解耦部署。
 * 修改说明：移除 MongoAutoConfiguration 排除，core 内 Mongo 仓库需 mongoTemplate，dev 配置中已加 spring.data.mongodb.uri。
 */
@EnableScheduling
@EntityScan({"com.bizzan.bitrade.entity", "com.bizzan.bitrade.dto"})
@EnableJpaRepositories("com.bizzan.bitrade.dao")
@ComponentScan(basePackages = "com.bizzan.bitrade")
@SpringBootApplication
public class FundApplication {

    public static void main(String[] args) {
        SpringApplication.run(FundApplication.class, args);
    }
}
