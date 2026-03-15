package com.bizzan.bitrade.Trader.result;

/**
 * 【改造范围】撮合结果发布接口。
 * 撮合引擎只调用 publish，不直接发 Kafka，保证热路径不碰网络；实现类负责队列/WAL + 后台发送。
 */
public interface MatchResultPublisher {

    /**
     * 发布一次撮合结果（非阻塞）。实现类将结果放入内存队列或 WAL，立即返回。
     * 若 trades 与 completedOrders 均为空，实现类可选择不写入。
     *
     * @param result 本批 trades + completedOrders
     */
    void publish(MatchResult result);
}
