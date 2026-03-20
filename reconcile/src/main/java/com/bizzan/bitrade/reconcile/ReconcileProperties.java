package com.bizzan.bitrade.reconcile;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 对账任务参数（对应 application.yml 中 {@code reconcile.*}）。
 * <ul>
 *   <li>{@link Scan#lookbackMinutes}：每次扫描多久的撮合凭证，避免全表扫。</li>
 *   <li>{@link Tolerance#stageTimeoutSeconds}：撮合凭证落库后等待多久再判「下游缺失」，减少瞬时延迟误报。</li>
 * </ul>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "reconcile")
public class ReconcileProperties {
    private Scan scan = new Scan();
    private Tolerance tolerance = new Tolerance();

    @Data
    public static class Scan {
        /** 扫描最近多少分钟内的 messageId 进行对账 */
        private int lookbackMinutes = 60;
    }

    @Data
    public static class Tolerance {
        /** messageId 从撮合到资金执行的各阶段容忍超时（秒） */
        private int stageTimeoutSeconds = 120;
    }
}

