package com.bizzan.bitrade.reconcile;

import com.bizzan.bitrade.dao.*;
import com.bizzan.bitrade.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/**
 * 链路对账调度器。
 * <p>
 * <b>业务主线</b>：撮合结果 Kafka（exchange-match-result）经清算、结算、资金执行，全链路用同一 {@code messageId} 关联。
 * <p>
 * <b>对账含义</b>：以 clearing 落库的 {@link com.bizzan.bitrade.entity.MatchResultEvent} 为起点，按顺序检查下游凭证表是否齐全、状态是否正常；
 * 超时仍缺或失败则写入 {@link com.bizzan.bitrade.entity.ReconcileIssue}，供告警与人工排查。
 * <p>
 * <b>凭证链</b>（详见 {@code 0_docs/对账服务-reconcile流程说明.md}）：
 * <pre>
 * match_result_event → clearing_result → settlement_result
 *     → fund_instruction_record（结算发布资金指令） → fund_instruction_execution（fund 执行结果）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileScheduler {

    private final ReconcileProperties properties;

    private final MatchResultEventRepository matchResultEventRepository;
    private final ClearingResultRepository clearingResultRepository;
    private final SettlementResultRepository settlementResultRepository;
    private final FundInstructionRecordRepository fundInstructionRecordRepository;
    private final FundInstructionExecutionRepository fundInstructionExecutionRepository;
    private final ReconcileIssueRepository reconcileIssueRepository;

    /**
     * 定时扫描最近一段时间内的撮合结果凭证，对每条 {@code messageId} 做链路完整性检查。
     * <ul>
     *   <li>仅处理「自 match_result_event.created_at 起已超过 stageTimeoutSeconds」的事件，避免误判正在推进中的批次。</li>
     *   <li>检查顺序：清算 → 结算 → 资金指令凭证 → 资金执行凭证；任一环节异常则记一条 OPEN 的 ReconcileIssue（同 messageId+stage 幂等）。</li>
     * </ul>
     * fixedDelay：上一次执行结束后间隔 30 秒再跑，避免与单次扫描耗时重叠。
     */
    @Scheduled(fixedDelayString = "30000")
    public void reconcileRecent() {
        Date now = new Date();
        // 时间窗口：只扫最近 lookbackMinutes 内的撮合凭证，控制单次处理量
        Date from = new Date(now.getTime() - properties.getScan().getLookbackMinutes() * 60_000L);
        List<MatchResultEvent> events = matchResultEventRepository.findByCreatedAtAfterOrderByIdAsc(from);
        if (events.isEmpty()) {
            return;
        }
        int timeoutMs = properties.getTolerance().getStageTimeoutSeconds() * 1000;

        int issues = 0;
        for (MatchResultEvent e : events) {
            long ageMs = now.getTime() - (e.getCreatedAt() != null ? e.getCreatedAt().getTime() : now.getTime());
            if (ageMs < timeoutMs) {
                continue; // 仍在链路推进的正常窗口内，不判缺表
            }
            String messageId = e.getMessageId();
            String symbol = e.getSymbol();

            // --- 阶段 1：清算 --- 应有 clearing_result（与 messageId 对齐）
            if (!clearingResultRepository.existsByMessageId(messageId)) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.CLEARING,
                        ReconcileIssue.Severity.ERROR,
                        "缺失 clearing_result：match_result_event 已存在，但 clearing_result 不存在（可能清算消费异常或落库失败）");
                continue;
            }

            // --- 阶段 2：结算 --- 应有 settlement_result
            if (!settlementResultRepository.existsByMessageId(messageId)) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.SETTLEMENT,
                        ReconcileIssue.Severity.ERROR,
                        "缺失 settlement_result：clearing_result 已存在，但 settlement_result 不存在（可能结算消费异常或落库失败）");
                continue;
            }

            // 结算侧还应落 fund_instruction_record（发布 exchange-fund-instruction 前的凭证）
            FundInstructionRecord record = fundInstructionRecordRepository.findByMessageId(messageId).orElse(null);
            if (record == null) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.SETTLEMENT,
                        ReconcileIssue.Severity.ERROR,
                        "缺失 fund_instruction_record：settlement_result 已存在，但资金指令凭证不存在（应生成并发布 exchange-fund-instruction）");
                continue;
            }
            if (record.getStatus() == FundInstructionRecord.Status.FAILED) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.SETTLEMENT,
                        ReconcileIssue.Severity.ERROR,
                        "fund_instruction_record=FAILED：结算发布 Kafka 失败（可由结算重试任务补发）");
                continue;
            }

            // --- 阶段 3：资金执行 --- fund 消费资金指令后的执行凭证（与 processed_fund_instruction 配套）
            FundInstructionExecution exec = fundInstructionExecutionRepository.findByMessageId(messageId).orElse(null);
            if (exec == null) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.FUND_EXECUTION,
                        ReconcileIssue.Severity.ERROR,
                        "缺失 fund_instruction_execution：资金指令已发布，但资金执行凭证不存在（可能 fund 未消费或未落库）");
                continue;
            }
            if (exec.getStatus() == FundInstructionExecution.Status.FAILED) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.FUND_EXECUTION,
                        ReconcileIssue.Severity.ERROR,
                        "fund_instruction_execution=FAILED：资金执行失败（检查 error_msg / retry_count，或余额/冻结不足等）");
                continue;
            }
            if (exec.getStatus() == FundInstructionExecution.Status.PENDING) {
                issues += openIssueIfAbsent(messageId, symbol, ReconcileIssue.Stage.FUND_EXECUTION,
                        ReconcileIssue.Severity.WARN,
                        "fund_instruction_execution=PENDING：资金指令已进入执行记录但尚未 PROCESSED（可能正在重试或卡住）");
            }
        }

        if (issues > 0) {
            log.warn("reconcile scan done, events={}, newIssues={}", events.size(), issues);
        }
    }

    /**
     * 写入一条对账问题；若同一 messageId + stage 已存在 OPEN 记录则跳过（幂等，避免定时任务刷屏）。
     *
     * @return 新增 1 条返回 1，未新增返回 0
     */
    private int openIssueIfAbsent(String messageId, String symbol, ReconcileIssue.Stage stage, ReconcileIssue.Severity severity, String detail) {
        if (reconcileIssueRepository.existsByMessageIdAndStageAndStatus(messageId, stage, ReconcileIssue.Status.OPEN)) {
            return 0;
        }
        ReconcileIssue issue = new ReconcileIssue();
        issue.setMessageId(messageId);
        issue.setSymbol(symbol);
        issue.setStage(stage);
        issue.setSeverity(severity);
        issue.setStatus(ReconcileIssue.Status.OPEN);
        issue.setDetail(detail);
        issue.setCreatedAt(new Date());
        reconcileIssueRepository.save(issue);
        return 1;
    }
}

