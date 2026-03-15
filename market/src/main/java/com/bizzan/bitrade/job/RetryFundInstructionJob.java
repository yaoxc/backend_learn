package com.bizzan.bitrade.job;

import com.bizzan.bitrade.service.FundInstructionExecuteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时重试未成功执行的资金指令（status 为 PENDING/FAILED 的 processed_fund_instruction 记录）。
 */
@Component
@Slf4j
public class RetryFundInstructionJob {

    @Autowired
    private FundInstructionExecuteService fundInstructionExecuteService;

    @Scheduled(fixedDelayString = "${fund.instruction.retry.interval:30000}")
    public void retryExecute() {
        int done = fundInstructionExecuteService.retryExecutePending();
        if (done > 0) {
            log.info("fund instruction retry executed {} record(s)", done);
        }
    }
}
