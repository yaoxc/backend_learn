package com.bizzan.bitrade.sequencer.service;

import com.bizzan.bitrade.sequencer.domain.SequencerInstruction;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 排序规则（与常见「价格优先、时间优先」的盘口展示相反，按需求实现为）：
 * <ul>
 *   <li>第一关键字：{@link SequencerInstruction#getReceivedAt()} 升序 —— 时间优先（先到先排）</li>
 *   <li>第二关键字：价格优先 —— 买单价高优先、卖单价低优先（更优价先进入撮合序列）</li>
 *   <li>第三稳定键：{@link SequencerInstruction#getId()} 升序，避免同时间同价抖动</li>
 * </ul>
 */
public final class InstructionSorter {

    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    private static final Comparator<SequencerInstruction> CMP = Comparator
            .comparing(SequencerInstruction::getReceivedAt)
            .thenComparing(InstructionSorter::priceRankKey)
            .thenComparing(SequencerInstruction::getId);

    private InstructionSorter() {
    }

    /**
     * 价格次序：买单用价高的在前 → negate 价格做升序比较；卖单价低的在前 → 直接升序。
     */
    private static BigDecimal priceRankKey(SequencerInstruction i) {
        String d = i.getDirection() == null ? "" : i.getDirection().toUpperCase();
        BigDecimal p = i.getPrice() != null ? i.getPrice() : BigDecimal.ZERO;
        if (BUY.equals(d)) {
            return p.negate();
        }
        if (SELL.equals(d)) {
            return p;
        }
        return p;
    }

    public static void sortInPlace(List<SequencerInstruction> list) {
        list.sort(CMP);
    }

    public static List<SequencerInstruction> sortedCopy(List<SequencerInstruction> list) {
        List<SequencerInstruction> copy = new ArrayList<>(list);
        sortInPlace(copy);
        return copy;
    }
}
