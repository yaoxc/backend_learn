package com.bizzan.bitrade.sequencer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 单交易对上的流水线：先发完所有已赋序未发批次，再尝试从 PENDING 切新批并立即发送。
 * 多进程下依赖 DB 行锁 +「存在 SEQUENCED 则不再 assign」保证批与批串行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequencerOrchestrator {

    private final SequencerPublishService publishService;
    private final SequencerAssignService assignService;

    public void processSymbol(String symbol) {
        // 关键：恢复场景下可能残留多条 SEQUENCED，按 sequence 顺序逐批发，每批 await 结束才进入下一批
        publishService.publishAllSequencedBatchesInOrder(symbol);
        // 仅当不存在 SEQUENCED 时 assign 才会成功；新批创建后立刻 publish，避免中间状态被其他实例误判
        assignService.assignNextBatch(symbol).ifPresent(publishService::publishBatchAndAwait);
    }
}
