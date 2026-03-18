package com.bizzan.bitrade.util;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 成交ID生成器（Snowflake）。
 *
 * 说明：
 * - tradeId 必须由撮合服务在“产生成交”时生成，才能随撮合结果下发并在全链路对账中保持稳定。
 * - workerId/datacenterId 建议在部署时按实例配置，避免多实例并发产生冲突。
 */
public final class TradeIdGenerator {

    private static final AtomicReference<IdWorkByTwitter> WORKER = new AtomicReference<>();

    private TradeIdGenerator() {}

    public static void init(long workerId, long datacenterId) {
        WORKER.set(new IdWorkByTwitter(workerId, datacenterId));
    }

    public static String next() {
        IdWorkByTwitter w = WORKER.get();
        if (w == null) {
            // 默认兜底：单机开发环境不配也能跑；生产应显式 init
            w = new IdWorkByTwitter(0, 0);
            WORKER.compareAndSet(null, w);
        }
        return String.valueOf(w.nextId());
    }
}

