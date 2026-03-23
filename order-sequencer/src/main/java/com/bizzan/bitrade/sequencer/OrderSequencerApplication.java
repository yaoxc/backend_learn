package com.bizzan.bitrade.sequencer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定序服务入口：可多实例部署；同一交易对的全序与「批与批之间串行」由 DB 行锁 + 未发布批次门槛保证。
 */
@SpringBootApplication
@EnableScheduling
public class OrderSequencerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderSequencerApplication.class, args);
    }
}
