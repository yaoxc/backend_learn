package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.FundInstructionExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FundInstructionExecutionRepository extends JpaRepository<FundInstructionExecution, Long> {

    Optional<FundInstructionExecution> findByMessageId(String messageId);
}

