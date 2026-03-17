package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.FundInstructionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FundInstructionRecordRepository extends JpaRepository<FundInstructionRecord, Long> {

    Optional<FundInstructionRecord> findByMessageId(String messageId);

    boolean existsByMessageId(String messageId);

    List<FundInstructionRecord> findByStatusInOrderByIdAsc(List<FundInstructionRecord.Status> statuses);
}

