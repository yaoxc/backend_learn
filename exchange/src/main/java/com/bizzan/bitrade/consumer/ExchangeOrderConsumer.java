package com.bizzan.bitrade.consumer;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.ExchangeOrder;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ExchangeOrderConsumer {

    @Autowired
    private PartitionCheckpointStore partitionCheckpointStore;
    @Autowired
    private MatchingOrderPipeline matchingOrderPipeline;
    @Autowired(required = false)
    private MatchingOrderDisruptor matchingOrderDisruptor;

    @KafkaListener(topics = "exchange-order",containerFactory = "kafkaListenerContainerFactory",groupId = "${exchange.kafka.group.order:service-exchange-order}")
    public void onOrderSubmitted(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        if (matchingOrderDisruptor != null) {
            matchingOrderDisruptor.publishSubmitBatch(records, ack);
            return;
        }
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("接收订单>>topic={},value={},size={}",record.topic(),record.value(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }
            matchingOrderPipeline.onOrderSubmit(record, order);
        }
        if (ack != null) {
            ack.acknowledge();
        }
        partitionCheckpointStore.persistFromConsumerRecords(records);
    }

    @KafkaListener(topics = "exchange-order-cancel",containerFactory = "kafkaListenerContainerFactory", groupId = "${exchange.kafka.group.cancel:service-exchange-order-cancel}")
    public void onOrderCancel(List<ConsumerRecord<String,String>> records, Acknowledgment ack){
        if (matchingOrderDisruptor != null) {
            matchingOrderDisruptor.publishCancelBatch(records, ack);
            return;
        }
        for (int i = 0; i < records.size(); i++) {
            ConsumerRecord<String,String> record  = records.get(i);
            log.info("取消订单topic={},key={},size={}",record.topic(),record.key(),records.size());
            ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
            if(order == null){
                return ;
            }
            matchingOrderPipeline.onOrderCancel(record, order);
        }
        if (ack != null) {
            ack.acknowledge();
        }
        partitionCheckpointStore.persistFromConsumerRecords(records);
    }
}
