package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.ProcessedMatchResultMessage;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 已处理的 match-result 消息 id，用于幂等判断。
 */
public interface ProcessedMatchResultMessageRepository extends JpaRepository<ProcessedMatchResultMessage, Long> {

    boolean existsByMessageId(String messageId);
}
