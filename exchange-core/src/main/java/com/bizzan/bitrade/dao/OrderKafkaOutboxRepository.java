package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.OrderKafkaOutbox;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 本地消息表：订单/撤单 Kafka 投递 outbox。
 */
public interface OrderKafkaOutboxRepository extends JpaRepository<OrderKafkaOutbox, Long> {

    List<OrderKafkaOutbox> findByStatusInOrderByIdAsc(List<OrderKafkaOutbox.Status> statuses);
}
