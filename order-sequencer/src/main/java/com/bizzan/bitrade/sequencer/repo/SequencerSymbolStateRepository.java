package com.bizzan.bitrade.sequencer.repo;

import com.bizzan.bitrade.sequencer.domain.SequencerSymbolState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import javax.persistence.LockModeType;
import java.util.Optional;

public interface SequencerSymbolStateRepository extends JpaRepository<SequencerSymbolState, String> {

    /**
     * 关键：FOR UPDATE 保证多进程下同一 symbol 的 nextSeq 单调分配不重复。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SequencerSymbolState s WHERE s.symbol = :symbol")
    Optional<SequencerSymbolState> findBySymbolForUpdate(@Param("symbol") String symbol);
}
