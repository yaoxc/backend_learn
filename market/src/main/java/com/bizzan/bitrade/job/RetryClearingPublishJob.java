package com.bizzan.bitrade.job;

import com.bizzan.bitrade.service.ClearingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时重试未成功发送至 Kafka 的清算结果（status 为 PENDING/FAILED 的记录）。
 */
@Component
@Slf4j
public class RetryClearingPublishJob {

    @Autowired
    private ClearingService clearingService;

    @Scheduled(fixedDelayString = "${clearing.retry.interval:30000}")
    public void retryPublish() {
        int sent = clearingService.retryPublishPending();
        if (sent > 0) {
            log.info("clearing retry published {} record(s)", sent);
        }
    }
}
