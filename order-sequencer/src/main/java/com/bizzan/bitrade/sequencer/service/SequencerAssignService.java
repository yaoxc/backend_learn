package com.bizzan.bitrade.sequencer.service;

import com.bizzan.bitrade.sequencer.config.SequencerProperties;
import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.domain.SequencerInstruction;
import com.bizzan.bitrade.sequencer.domain.SequencerSymbolState;
import com.bizzan.bitrade.sequencer.repo.SequencerInstructionRepository;
import com.bizzan.bitrade.sequencer.repo.SequencerSymbolStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 负责：在「当前 symbol 不存在未发布的 SEQUENCED 批次」前提下，拉取 PENDING、排序、分配连续 sequenceId 并落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequencerAssignService {

    private final SequencerInstructionRepository instructionRepository;
    private final SequencerSymbolStateRepository symbolStateRepository;
    private final SequencerProperties properties;

    /**
     * 关键（多进程）：若库中仍有 SEQUENCED 状态的指令，说明上一批尚未完成 Kafka 确认/撮合 ACK，禁止再分配新序号，
     * 否则会出现两批并行下发、破坏「一批结束再发下一批」的语义。
     */
    @Transactional(rollbackFor = Exception.class)
    public Optional<String> assignNextBatch(String symbol) {
        if (instructionRepository.countBySymbolAndStatus(symbol, InstructionStatus.SEQUENCED) > 0) {
            return Optional.empty();
        }

        // 关键：对 symbol_state 行加悲观写锁，使多实例竞争时同一时刻只有一个事务能推进 nextSeq
        SequencerSymbolState state = symbolStateRepository.findBySymbolForUpdate(symbol).orElse(null);
        if (state == null) {
            SequencerSymbolState created = new SequencerSymbolState();
            created.setSymbol(symbol);
            created.setNextSeq(1L);
            symbolStateRepository.save(created);
            state = symbolStateRepository.findBySymbolForUpdate(symbol)
                    .orElseThrow(() -> new IllegalStateException("symbol state missing after create: " + symbol));
        }

        // 二次检查（持锁后）：防止与 publish 临界竞态
        if (instructionRepository.countBySymbolAndStatus(symbol, InstructionStatus.SEQUENCED) > 0) {
            return Optional.empty();
        }

        List<SequencerInstruction> pool = new ArrayList<>(instructionRepository
                .findBySymbolAndStatus(symbol, InstructionStatus.PENDING, PageRequest.of(0, properties.getPendingFetchSize()))
                .getContent());
        if (pool.isEmpty()) {
            return Optional.empty();
        }

        // 关键：时间优先 → 价格优先（买高卖低）→ id 稳定排序
        InstructionSorter.sortInPlace(pool);

        int take = Math.min(properties.getBatchSize(), pool.size());
        List<SequencerInstruction> batch = pool.subList(0, take);
        String batchId = UUID.randomUUID().toString();

        long seq = state.getNextSeq() != null ? state.getNextSeq() : 1L;
        for (SequencerInstruction i : batch) {
            i.setSequenceId(seq++);
            i.setBatchId(batchId);
            i.setStatus(InstructionStatus.SEQUENCED);
        }
        state.setNextSeq(seq);
        instructionRepository.saveAll(batch);
        symbolStateRepository.save(state);
        log.info("sequencer assigned batch symbol={} batchId={} size={} nextSeqAfter={}", symbol, batchId, batch.size(), seq);
        return Optional.of(batchId);
    }
}
