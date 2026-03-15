package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.SettlementResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementResultRepository extends JpaRepository<SettlementResult, Long> {

    boolean existsByMessageId(String messageId);

    List<SettlementResult> findByStatusInOrderByIdAsc(List<SettlementResult.Status> statuses);
}
