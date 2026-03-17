package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.MatchResultEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MatchResultEventRepository extends JpaRepository<MatchResultEvent, Long> {

    boolean existsByMessageId(String messageId);
}

