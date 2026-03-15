package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.ProcessedFundInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProcessedFundInstructionRepository extends JpaRepository<ProcessedFundInstruction, Long> {

    boolean existsByMessageIdAndStatus(String messageId, ProcessedFundInstruction.Status status);

    Optional<ProcessedFundInstruction> findByMessageId(String messageId);

    List<ProcessedFundInstruction> findByStatusInOrderByIdAsc(List<ProcessedFundInstruction.Status> statuses);
}
