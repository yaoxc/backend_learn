package com.bizzan.bitrade.sequencer.repo;

import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.domain.SequencerInstruction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SequencerInstructionRepository extends JpaRepository<SequencerInstruction, Long> {

    long countBySymbolAndStatus(String symbol, InstructionStatus status);

    List<SequencerInstruction> findBySymbolAndStatus(String symbol, InstructionStatus status);

    Page<SequencerInstruction> findBySymbolAndStatus(String symbol, InstructionStatus status, Pageable pageable);

    @Query("SELECT DISTINCT i.symbol FROM SequencerInstruction i WHERE i.status IN :statuses")
    List<String> findDistinctSymbolsByStatusIn(@Param("statuses") Collection<InstructionStatus> statuses);

    List<SequencerInstruction> findByBatchIdAndStatus(String batchId, InstructionStatus status);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE SequencerInstruction i SET i.status = :newStatus WHERE i.batchId = :batchId AND i.status = :oldStatus")
    int markBatchPublished(@Param("batchId") String batchId,
                          @Param("oldStatus") InstructionStatus oldStatus,
                          @Param("newStatus") InstructionStatus newStatus);
}
