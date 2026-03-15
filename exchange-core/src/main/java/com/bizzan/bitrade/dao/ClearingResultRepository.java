package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.ClearingResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClearingResultRepository extends JpaRepository<ClearingResult, Long> {

    boolean existsByMessageId(String messageId);

    List<ClearingResult> findByStatusInOrderByIdAsc(List<ClearingResult.Status> statuses);
}
