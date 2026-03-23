package com.bizzan.bitrade.consumer;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地 checkpoint 存储（topic-partition -> nextOffset）。
 * <p>
 * 作用：
 * 1) 记录“本实例最近处理到哪里”，用于运维排查与故障恢复参考；
 * 2) onPartitionsRevoked 时做一次“接管前快照”；
 * 3) 结合批次处理后的 checkpoint 持久化，降低“仅依赖 revoke 回调”的风险。
 * <p>
 * 注意：该文件是“辅助恢复信息”，Kafka 的 committed offset 仍是消费接续主依据。
 */
@Component
public class PartitionCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(PartitionCheckpointStore.class);

    private final Path checkpointPath;

    public PartitionCheckpointStore(@Value("${match.wal.path:data/wal}") String walBasePath) {
        String base = walBasePath == null || walBasePath.isEmpty() ? "data/wal" : walBasePath;
        this.checkpointPath = Paths.get(base, "consumer-partition-checkpoint.log");
    }

    public synchronized void persist(Map<TopicPartition, Long> offsets) {
        if (offsets == null || offsets.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(checkpointPath.getParent());
            StringBuilder sb = new StringBuilder();
            long ts = System.currentTimeMillis();
            for (Map.Entry<TopicPartition, Long> e : offsets.entrySet()) {
                TopicPartition tp = e.getKey();
                Long nextOffset = e.getValue();
                if (tp == null || nextOffset == null) {
                    continue;
                }
                sb.append(tp.topic())
                        .append('\t')
                        .append(tp.partition())
                        .append('\t')
                        .append(nextOffset)
                        .append('\t')
                        .append(ts)
                        .append('\n');
            }
            if (sb.length() > 0) {
                Files.write(checkpointPath, sb.toString().getBytes(StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.error("persist consumer checkpoint failed, path={}", checkpointPath, e);
        }
    }

    public synchronized Map<TopicPartition, Long> loadLatest() {
        Map<TopicPartition, Long> latest = new ConcurrentHashMap<>();
        if (!Files.exists(checkpointPath)) {
            return latest;
        }
        try {
            for (String line : Files.readAllLines(checkpointPath, StandardCharsets.UTF_8)) {
                if (line == null || line.trim().isEmpty()) {
                    continue;
                }
                String[] arr = line.split("\t");
                if (arr.length < 3) {
                    continue;
                }
                String topic = arr[0];
                int partition = Integer.parseInt(arr[1]);
                long nextOffset = Long.parseLong(arr[2]);
                latest.put(new TopicPartition(topic, partition), nextOffset);
            }
        } catch (Exception e) {
            log.warn("load consumer checkpoint failed, path={}", checkpointPath, e);
        }
        return latest;
    }
}

