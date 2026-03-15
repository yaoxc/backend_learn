package com.bizzan.bitrade.job;

import com.bizzan.bitrade.service.SettlementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时重试未成功发送至 Kafka 的结算结果（资金指令），status 为 PENDING/FAILED 的记录。
 */
@Component
@Slf4j
public class RetrySettlementPublishJob {

    @Autowired
    private SettlementService settlementService;

    @Scheduled(fixedDelayString = "${settlement.retry.interval:30000}")
    public void retryPublish() {
        int sent = settlementService.retryPublishPending();
        if (sent > 0) {
            log.info("settlement retry published {} record(s)", sent);
        }
    }
}
