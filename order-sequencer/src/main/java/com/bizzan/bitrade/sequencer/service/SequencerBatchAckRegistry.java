package com.bizzan.bitrade.sequencer.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 端到端等待：撮合 ACK 到达后完成对应 batch 的 Future。
 * 与 {@link com.bizzan.bitrade.sequencer.config.SequencerProperties.WaitMode#END_TO_END_MATCHER_ACK} 配合使用。
 */
@Component
public class SequencerBatchAckRegistry {

    private final ConcurrentHashMap<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    /**
     * 在发送 Kafka 之前注册，避免撮合极快回 ACK 时丢失通知。
     */
    public CompletableFuture<Void> register(String batchId) {
        return pending.computeIfAbsent(batchId, k -> new CompletableFuture<>());
    }

    public void complete(String batchId) {
        CompletableFuture<Void> f = pending.remove(batchId);
        if (f != null) {
            f.complete(null);
        }
    }

    /** 发送失败或超时：摘掉未完成的 Future，避免泄漏 */
    public void cancel(String batchId) {
        CompletableFuture<Void> f = pending.remove(batchId);
        if (f != null && !f.isDone()) {
            f.cancel(true);
        }
    }
}
