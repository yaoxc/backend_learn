package com.bizzan.bitrade.sequencer.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.sequencer.config.SequencerProperties;
import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.domain.SequencerInstruction;
import com.bizzan.bitrade.sequencer.dto.SequencerBatchMessage;
import com.bizzan.bitrade.sequencer.repo.SequencerInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 将一批 SEQUENCED 指令推送到 Kafka；仅在 broker（及可选撮合）确认后，才把状态改为 PUBLISHED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SequencerPublishService {

    private final SequencerInstructionRepository instructionRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SequencerProperties properties;
    private final SequencerBatchAckRegistry batchAckRegistry;
    private final SequencerStateService sequencerStateService;

    /**
     * 按 sequenceId 顺序发布该 symbol 下所有未发布的 SEQUENCED 批次（用于宕机恢复或排队积压）。
     */
    public void publishAllSequencedBatchesInOrder(String symbol) {
        List<SequencerInstruction> all = instructionRepository.findBySymbolAndStatus(symbol, InstructionStatus.SEQUENCED);
        if (all.isEmpty()) {
            return;
        }
        List<String> batchIds = all.stream()
                .collect(Collectors.groupingBy(SequencerInstruction::getBatchId))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(
                        minSeq(a.getValue()),
                        minSeq(b.getValue())))
                .map(java.util.Map.Entry::getKey)
                .collect(Collectors.toList());
        for (String batchId : batchIds) {
            // 关键：严格串行 await，上一批整批确认完成后才会进入下一批 publish
            publishBatchAndAwait(batchId);
        }
    }

    private static long minSeq(List<SequencerInstruction> list) {
        return list.stream().mapToLong(SequencerInstruction::getSequenceId).min().orElse(0L);
    }

    /**
     * 发送单批到 Kafka，等待 broker ACK；若配置端到端，再等待撮合回执 batchId。
     */
    public void publishBatchAndAwait(String batchId) {
        List<SequencerInstruction> items = instructionRepository.findByBatchIdAndStatus(batchId, InstructionStatus.SEQUENCED);
        if (items.isEmpty()) {
            return;
        }
        String symbol = items.get(0).getSymbol();
        SequencerBatchMessage msg = toMessage(batchId, symbol, items);
        String json = JSON.toJSONString(msg);

        CompletableFuture<Void> matcherWait = null;
        if (properties.getWaitMode() == SequencerProperties.WaitMode.END_TO_END_MATCHER_ACK) {
            // 关键：先发后等时，必须先注册，避免撮合回 ACK 早于 register 导致漏信号
            matcherWait = batchAckRegistry.register(batchId);
        }

        try {
            // 关键：key=symbol 保证单交易对在分区内有序，便于撮合单线程消费
            ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(
                    properties.getOutputTopic(), symbol, json);
            future.get(properties.getKafkaSendTimeoutMs(), TimeUnit.MILLISECONDS);

            if (properties.getWaitMode() == SequencerProperties.WaitMode.END_TO_END_MATCHER_ACK && matcherWait != null) {
                matcherWait.get(properties.getEndToEndTimeoutMs(), TimeUnit.MILLISECONDS);
            }

            // 关键：仅在整批「发送 +（可选）撮合确认」全部成功后，才把状态改为 PUBLISHED，下一轮才能继续 assign
            sequencerStateService.markBatchPublished(batchId);
            log.info("sequencer published batch batchId={} symbol={} size={}", batchId, symbol, items.size());
        } catch (TimeoutException te) {
            batchAckRegistry.cancel(batchId);
            throw new RuntimeException("sequencer publish/ack timeout batchId=" + batchId, te);
        } catch (Exception e) {
            batchAckRegistry.cancel(batchId);
            throw new RuntimeException("sequencer publish failed batchId=" + batchId, e);
        }
    }

    private static SequencerBatchMessage toMessage(String batchId, String symbol, List<SequencerInstruction> items) {
        List<SequencerBatchMessage.Item> parts = items.stream()
                .sorted((a, b) -> Long.compare(a.getSequenceId(), b.getSequenceId()))
                .map(i -> new SequencerBatchMessage.Item(
                        i.getId(),
                        i.getOrderId(),
                        i.getSequenceId(),
                        i.getDirection(),
                        i.getPrice(),
                        i.getReceivedAt(),
                        i.getPayload()))
                .collect(Collectors.toList());
        return new SequencerBatchMessage(batchId, symbol, parts);
    }
}
