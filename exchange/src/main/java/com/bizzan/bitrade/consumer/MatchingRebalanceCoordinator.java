package com.bizzan.bitrade.consumer;

import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Kafka 分区再均衡协调器：
 * 1) onPartitionsRevoked：记录当前分区 position 作为 checkpoint（最佳努力）；
 * 2) onPartitionsAssigned：接管新分区后，重建该分区涉及 symbol 的内存订单簿。
 * <p>
 * 说明：
 * - 目前无法直接从 partition 反查 symbol，因此通过“运行期观测到的 symbol->partition”做近似映射；
 * - 若映射为空（例如实例刚启动就接管），为保证正确性，回退为“重放全部 trader”。
 */
@Component
public class MatchingRebalanceCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MatchingRebalanceCoordinator.class);

    private final CoinTraderFactory traderFactory;
    private final PartitionCheckpointStore checkpointStore;
    /** 运行期观测到的 symbol -> partition 映射（来自 ExchangeOrderConsumer 处理记录） */
    private final Map<String, Integer> symbolPartitionMap = new ConcurrentHashMap<>();

    public MatchingRebalanceCoordinator(CoinTraderFactory traderFactory, PartitionCheckpointStore checkpointStore) {
        this.traderFactory = traderFactory;
        this.checkpointStore = checkpointStore;
    }

    public void onOrderObserved(String symbol, int partition) {
        if (symbol == null || symbol.trim().isEmpty()) {
            return;
        }
        symbolPartitionMap.put(symbol.trim(), partition);
    }

    public void onPartitionsRevoked(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
        if (consumer == null || partitions == null || partitions.isEmpty()) {
            return;
        }
        Map<TopicPartition, Long> offsets = new HashMap<>();
        for (TopicPartition tp : partitions) {
            try {
                offsets.put(tp, consumer.position(tp));
            } catch (Exception e) {
                log.warn("read position on revoke failed, topic={}, partition={}", tp.topic(), tp.partition(), e);
            }
        }
        checkpointStore.persist(offsets);
        log.info("partitions revoked, checkpoint persisted, size={}", offsets.size());
    }

    public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
        if (partitions == null || partitions.isEmpty()) {
            return;
        }
        Set<Integer> assigned = partitions.stream().map(TopicPartition::partition).collect(Collectors.toSet());
        Set<String> symbols = symbolPartitionMap.entrySet().stream()
                .filter(e -> assigned.contains(e.getValue()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());

        // 若映射未知（新实例/刚接管），回退重放全部，确保不因遗漏 symbol 导致脏订单簿。
        if (symbols.isEmpty()) {
            symbols = traderFactory.getTraderMap().keySet();
            log.info("assigned partitions={}, symbol mapping empty, fallback replay all symbols, count={}",
                    assigned, symbols.size());
        } else {
            log.info("assigned partitions={}, replay symbols={}", assigned, symbols);
        }

        for (String symbol : symbols) {
            CoinTrader trader = traderFactory.getTrader(symbol);
            if (trader == null) {
                continue;
            }
            rebuildTraderSafely(symbol, trader);
        }
    }

    private void rebuildTraderSafely(String symbol, CoinTrader trader) {
        try {
            // 关键：接管期间先置为未就绪并暂停，防止边重放边接单。
            trader.stopTrading();
            trader.setReady(false);
            trader.replayOrderLog();
            trader.setReady(true);
            trader.resumeTrading();
            log.info("rebalance rebuild trader done, symbol={}", symbol);
        } catch (Exception e) {
            log.error("rebalance rebuild trader failed, symbol={}", symbol, e);
        }
    }
}

