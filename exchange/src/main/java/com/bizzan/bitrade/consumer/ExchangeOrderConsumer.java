package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeOrder;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ExchangeOrderConsumer {

    @Autowired
    private CoinTraderFactory traderFactory;
    
    @Autowired
    private KafkaTemplate<String,String> kafkaTemplate;
    @Autowired
    private MatchingRebalanceCoordinator matchingRebalanceCoordinator;
    @Autowired
    private PartitionCheckpointStore partitionCheckpointStore;

    @KafkaListener(topics = "exchange-order",containerFactory = "kafkaListenerContainerFactory",groupId = "${exchange.kafka.group.order:service-exchange-order}")
    public void onOrderSubmitted(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("接收订单>>topic={},value={},size={}",record.topic(),record.value(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }
            // 记录 symbol->partition 运行期映射，供 rebalance 接管时快速定位需要重建的 trader。
            matchingRebalanceCoordinator.onOrderObserved(order.getSymbol(), record.partition());

            // 订单消息可能会被多次投递，所以这里需要做幂等，保证同一条订单，只进一次撮合引擎的队列
            CoinTrader trader = traderFactory.getTrader(order.getSymbol());
            // 如果当前币种交易暂停会自动取消订单
            // halt: 暂停 ready: 完成
            if (trader.isTradingHalt() || !trader.getReady()) {
                // 撮合器未准备完成，撤回当前等待的订单
                log.error("撮合器未准备完成，撤回当前等待的订单: orderId: {} --- halt: {} --- ready: {}", order.getOrderId(), trader.isTradingHalt(), trader.getReady());
                kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
            } else {
                log.info("撮合器准备完成，开始撮合订单: {}", order.getOrderId());
                try {
                    long startTick = System.currentTimeMillis();
                    trader.trade(order);
                    log.info("complete trade,{}ms used!", System.currentTimeMillis() - startTick);
                } catch (Exception e) {
                    log.error("交易出错 error: {}，退回订单: {}, ", e.getMessage(), order.getOrderId());
                    kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(order));
                }
            }
        }
        // 手动提交当前批次 offset，确保订单已成功写入撮合引擎后再提交
        if (ack != null) {
            ack.acknowledge();
        }
        // 除了在 onPartitionsRevoked 记录 checkpoint，这里每批也落一次，避免进程直接宕机导致 revoke 回调来不及执行。
        persistBatchCheckpoint(records);
    }

    @KafkaListener(topics = "exchange-order-cancel",containerFactory = "kafkaListenerContainerFactory", groupId = "${exchange.kafka.group.cancel:service-exchange-order-cancel}")
    public void onOrderCancel(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("取消订单topic={},key={},size={}",record.topic(),record.key(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }
            matchingRebalanceCoordinator.onOrderObserved(order.getSymbol(), record.partition());
            CoinTrader trader = traderFactory.getTrader(order.getSymbol());
            if(trader.getReady()) {
                try {
                    ExchangeOrder result = trader.cancelOrder(order);
                    if (result != null) {
                        kafkaTemplate.send("exchange-order-cancel-success", JSON.toJSONString(result));
                    }
                }catch (Exception e){
                    log.info("====取消订单出错===",e);
                    e.printStackTrace();
                }
            }
        }
        if (ack != null) {
            ack.acknowledge();
        }
        persistBatchCheckpoint(records);
    }

    /**
     * 记录“本批处理后每个分区的 nextOffset”到本地 checkpoint。
     * 说明：Kafka committed offset 仍是消费主依据；本地 checkpoint 用于运维与重建辅助。
     */
    private void persistBatchCheckpoint(List<ConsumerRecord<String, String>> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        Map<TopicPartition, Long> offsets = new HashMap<>();
        Map<String, Integer> symbolPartitions = new HashMap<>();
        for (ConsumerRecord<String, String> r : records) {
            TopicPartition tp = new TopicPartition(r.topic(), r.partition());
            long nextOffset = r.offset() + 1;
            Long old = offsets.get(tp);
            if (old == null || nextOffset > old) {
                offsets.put(tp, nextOffset);
            }
            try {
                ExchangeOrder order = JSON.parseObject(r.value(), ExchangeOrder.class);
                if (order != null && order.getSymbol() != null && !order.getSymbol().trim().isEmpty()) {
                    symbolPartitions.put(order.getSymbol().trim(), r.partition());
                }
            } catch (Exception ignore) {
                // checkpoint 辅助逻辑，解析失败不影响主流程
            }
        }
        partitionCheckpointStore.persist(offsets);
        // 持久化 symbol->partition 映射，供接管分区时仅重放对应 symbol。
        // 说明：
        // 1) 同一分区可能出现多个 symbol，这里会把本批观察到的全部 symbol 都记录下来；
        // 2) 同一 symbol 后续若路由到新分区（如扩分区后hash变化），新值会覆盖旧值；
        // 3) 该映射是“最近观测值”，与 Kafka committed offset 一起使用，作为重建定位辅助。
        partitionCheckpointStore.persistSymbolPartitions(symbolPartitions);
    }
}
