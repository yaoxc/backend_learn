package com.bizzan.bitrade.service;

import com.bizzan.bitrade.dao.OrderKafkaOutboxRepository;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.OrderKafkaOutbox;
import com.bizzan.bitrade.util.MessageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 本地消息表：下单/撤单与 outbox 同事务，再发 Kafka，保证投递成功；失败由定时任务重试。
 */
@Slf4j
@Service
public class OrderOutboxService {

    @Autowired
    private ExchangeOrderService orderService;
    @Autowired
    private OrderKafkaOutboxRepository outboxRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    /**
     * 下单并写入 outbox（同一事务）：先落单再写一条 PENDING 消息，返回后由调用方 trySend 或由定时任务重试。
     *
     * @return 成功时 result.getData() 为 OutboxId（Long），用于立即尝试发送
     */
    @Transactional(rollbackFor = Exception.class)
    public MessageResult addOrderAndSaveOutbox(Long memberId, ExchangeOrder order, String orderIngressTopic) {
        MessageResult mr = orderService.addOrder(memberId, order);
        if (mr.getCode() != 0) {
            return mr;
        }
        OrderKafkaOutbox outbox = new OrderKafkaOutbox();
        outbox.setTopic(orderIngressTopic);
        outbox.setMessageKey(order.getOrderId());
        outbox.setPayload(com.alibaba.fastjson.JSON.toJSONString(order));
        outbox.setStatus(OrderKafkaOutbox.Status.PENDING);
        outbox.setCreatedAt(new Date());
        outbox = outboxRepository.save(outbox);
        mr.setData(outbox.getId());
        return mr;
    }

    /**
     * 仅写入撤单 outbox（无订单落库），用于撤单请求。调用方随后 trySendById 或由定时任务重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveCancelOutbox(String cancelIngressTopic, String orderId, String payload) {
        OrderKafkaOutbox outbox = new OrderKafkaOutbox();
        outbox.setTopic(cancelIngressTopic);
        outbox.setMessageKey(orderId);
        outbox.setPayload(payload);
        outbox.setStatus(OrderKafkaOutbox.Status.PENDING);
        outbox.setCreatedAt(new Date());
        outbox = outboxRepository.save(outbox);
        return outbox.getId();
    }

    /**
     * 发送指定 outbox 到 Kafka，成功则置 SENT。
     *
     * @return true 发送成功并已更新状态，false 发送失败（状态不变或置 FAILED）
     */
    public boolean trySendById(Long outboxId) {
        OrderKafkaOutbox outbox = outboxRepository.findOne(outboxId);
        if (outbox == null || outbox.getStatus() == OrderKafkaOutbox.Status.SENT) {
            return true;
        }
        try {
            kafkaTemplate.send(outbox.getTopic(), outbox.getMessageKey(), outbox.getPayload()).get(10, java.util.concurrent.TimeUnit.SECONDS);
            outbox.setStatus(OrderKafkaOutbox.Status.SENT);
            outbox.setSentAt(new Date());
            outboxRepository.save(outbox);
            return true;
        } catch (Exception e) {
            log.warn("order outbox send failed, id={}, topic={}", outboxId, outbox.getTopic(), e);
            outbox.setStatus(OrderKafkaOutbox.Status.FAILED);
            outboxRepository.save(outbox);
            return false;
        }
    }

    /**
     * 定时任务：重试所有 PENDING/FAILED 的 outbox。
     */
    public int retryPending() {
        List<OrderKafkaOutbox> list = outboxRepository.findByStatusInOrderByIdAsc(
                java.util.Arrays.asList(OrderKafkaOutbox.Status.PENDING, OrderKafkaOutbox.Status.FAILED));
        int sent = 0;
        for (OrderKafkaOutbox outbox : list) {
            if (trySendById(outbox.getId())) {
                sent++;
            }
        }
        return sent;
    }
}
