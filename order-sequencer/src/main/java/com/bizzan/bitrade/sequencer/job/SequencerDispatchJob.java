package com.bizzan.bitrade.sequencer.job;

import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.repo.SequencerInstructionRepository;
import com.bizzan.bitrade.sequencer.service.SequencerOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 定时扫描「仍有 PENDING 或 SEQUENCED」的交易对，逐个调用编排器。
 * 多实例会并发跑本 Job，但 {@link com.bizzan.bitrade.sequencer.service.SequencerAssignService} 内悲观锁保证同 symbol 互斥。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sequencer.dispatch.enabled", havingValue = "true", matchIfMissing = true)
public class SequencerDispatchJob {

    private final SequencerInstructionRepository instructionRepository;
    private final SequencerOrchestrator orchestrator;

    @Scheduled(fixedDelayString = "${sequencer.poll-fixed-delay-ms:200}")
    public void tick() {
        List<String> symbols = instructionRepository.findDistinctSymbolsByStatusIn(
                Arrays.asList(InstructionStatus.PENDING, InstructionStatus.SEQUENCED));
        for (String symbol : symbols) {
            try {
                orchestrator.processSymbol(symbol);
            } catch (Exception e) {
                log.error("sequencer tick failed symbol={}", symbol, e);
            }
        }
    }
}
