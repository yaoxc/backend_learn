package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.MatchResultEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Date;
import java.util.List;

public interface MatchResultEventRepository extends JpaRepository<MatchResultEvent, Long> {

    boolean existsByMessageId(String messageId);

    List<MatchResultEvent> findByCreatedAtAfterOrderByIdAsc(Date createdAt);
}

