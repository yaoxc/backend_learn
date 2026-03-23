package com.bizzan.bitrade.sequencer.service;

import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.repo.SequencerInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 独立事务落库，避免 publish 方法内因自调用导致 @Transactional 不生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequencerStateService {

    private final SequencerInstructionRepository instructionRepository;

    @Transactional(rollbackFor = Exception.class)
    public void markBatchPublished(String batchId) {
        int updated = instructionRepository.markBatchPublished(
                batchId, InstructionStatus.SEQUENCED, InstructionStatus.PUBLISHED);
        if (updated <= 0) {
            log.warn("sequencer markBatchPublished updated 0 rows batchId={}", batchId);
        }
    }
}
