package com.bizzan.bitrade.job;

import com.bizzan.bitrade.service.OrderOutboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 本地消息表：定时重试未发送成功的订单/撤单 Kafka 消息。
 */
@Slf4j
@Component
public class RetryOrderOutboxJob {

    @Autowired
    private OrderOutboxService orderOutboxService;

    @Value("${exchange.outbox.retry.interval:30000}")
    private long retryIntervalMs;

    @Scheduled(fixedDelayString = "${exchange.outbox.retry.interval:30000}")
    public void retryOrderOutbox() {
        int sent = orderOutboxService.retryPending();
        if (sent > 0) {
            log.info("retry order outbox sent count={}", sent);
        }
    }
}
