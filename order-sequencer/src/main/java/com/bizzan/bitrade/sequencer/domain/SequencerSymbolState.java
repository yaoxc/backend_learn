package com.bizzan.bitrade.sequencer.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * 每个交易对下一条将要分配的 sequenceId（行级锁载体，多进程互斥赋序）。
 */
@Getter
@Setter
@Entity
@Table(name = "sequencer_symbol_state")
public class SequencerSymbolState {

    @Id
    private String symbol;

    /**
     * 下一条待分配序号；分配 n 条后应递增 n。
     */
    private Long nextSeq = 1L;
}
