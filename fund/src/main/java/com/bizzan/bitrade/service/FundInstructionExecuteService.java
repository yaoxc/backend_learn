package com.bizzan.bitrade.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.dao.FundInstructionExecutionRepository;
import com.bizzan.bitrade.dao.ProcessedFundInstructionRepository;
import com.bizzan.bitrade.constant.TransactionType;
import com.bizzan.bitrade.dto.FundInstructionDTO;
import com.bizzan.bitrade.entity.FundInstructionExecution;
import com.bizzan.bitrade.entity.MemberWallet;
import com.bizzan.bitrade.entity.ProcessedFundInstruction;
import com.bizzan.bitrade.entity.MemberTransaction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 资金服务：消费结算产出的资金指令，落库后执行钱包操作（加减可用、扣减冻结、解冻等），保证幂等与可恢复。
 *
 * 设计要点（关键说明）：
 * 1）幂等键：以 Kafka 的 messageId 作为资金指令处理的幂等主键，配合表 ProcessedFundInstruction 记录状态；
 * 2）流程顺序：先在本地资金指令表中落一条 PENDING，再按 payload 逐条执行钱包操作，成功后标记 PROCESSED，失败则标记 FAILED；
 * 3）容错与重试：FAILED/PENDING 记录由 RetryFundInstructionJob 周期性重试，保证资金指令最终被执行；
 * 4）事务边界：processAndExecute / retryExecutePending 整体开启事务，保证“同一条指令要么全部钱包变更成功并落 PROCESSED，要么整体回滚并标记为 FAILED”；
 * 5）幂等与 Kafka：上游 Kafka 消息消费为“至少一次”，本服务依靠 messageId+ProcessedFundInstruction 表保证资金操作“至多一次生效”。
 * 6）refType/refId：本服务已完整支持，从 DTO 读取 refType/refId（默认 ORDER + messageId），按条解析 itemRefId 后写入流水。上游只需在构造 FundInstructionDTO 时按业务设置 dto.refType、dto.refId，必要时设置 item.itemRefId 或 item.orderId（ORDER 场景）。
 */
@Slf4j
@Service
public class FundInstructionExecuteService {

    @Autowired
    private ProcessedFundInstructionRepository processedFundInstructionRepository;
    @Autowired
    private MemberWalletService memberWalletService;
    @Autowired
    private MemberTransactionService memberTransactionService;

    @Autowired
    private FundInstructionExecutionRepository fundInstructionExecutionRepository;

    @Value("${settlement.platform.memberId:0}")
    private Long platformMemberId;

    /**
     * 幂等入口：
     * - 已存在 status=PROCESSED 的 messageId 直接返回，避免重复执行资金变更；
     * - 不存在则创建/补充 ProcessedFundInstruction 记录为 PENDING，再按 payload 解析并执行；
     * - 过程中抛出的任何异常会回滚当前事务，并将记录标记为 FAILED，留给重试任务处理。
     */
    @Transactional(rollbackFor = Exception.class)
    public void processAndExecute(String messageId, String payload) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        // 已成功处理过的指令（PROCESSED）直接跳过，实现跨进程幂等
        if (processedFundInstructionRepository.existsByMessageIdAndStatus(messageId, ProcessedFundInstruction.Status.PROCESSED)) {
            return;
        }
        ProcessedFundInstruction record = processedFundInstructionRepository.findByMessageId(messageId).orElse(null);
        if (record == null) {
            // 第一次见到该 messageId，先以原始 payload 落一条 PENDING 记录作为“本地资金指令表”
            record = new ProcessedFundInstruction();
            record.setMessageId(messageId);
            record.setStatus(ProcessedFundInstruction.Status.PENDING);
            record.setPayload(payload);
            record.setCreatedAt(new Date());
            processedFundInstructionRepository.save(record);
        }

        // 资金指令执行结果凭证（用于对账/追溯），幂等按 messageId
        FundInstructionExecution exec = fundInstructionExecutionRepository.findByMessageId(messageId).orElse(null);
        if (exec == null) {
            exec = new FundInstructionExecution();
            exec.setMessageId(messageId);
            exec.setStatus(FundInstructionExecution.Status.PENDING);
            exec.setPayload(record.getPayload() != null ? record.getPayload() : payload);
            exec.setCreatedAt(new Date());
            fundInstructionExecutionRepository.save(exec);
        } else {
            exec.setRetryCount(exec.getRetryCount() == null ? 1 : exec.getRetryCount() + 1);
            fundInstructionExecutionRepository.save(exec);
        }
        // 以库中 payload 为准，保证重试时使用的是同一份指令数据
        String payloadToUse = record.getPayload() != null ? record.getPayload() : payload;
        FundInstructionDTO dto = JSON.parseObject(payloadToUse, FundInstructionDTO.class);
        log.info("资金服务处理资金指令 dto:{}", JSON.toJSONString(dto));
        if (dto == null || dto.getInstructions() == null) {
            log.warn("fund instruction parse null, messageId={}", messageId);
            record.setStatus(ProcessedFundInstruction.Status.FAILED);
            processedFundInstructionRepository.save(record);
            exec.setStatus(FundInstructionExecution.Status.FAILED);
            exec.setErrorMsg("fund instruction parse null");
            fundInstructionExecutionRepository.save(exec);
            return;
        }
        String refType = (dto.getRefType() != null && !dto.getRefType().isEmpty()) ? dto.getRefType() : MemberTransaction.REF_TYPE_ORDER;
        String refId = dto.getRefId() != null ? dto.getRefId() : messageId;
        try {
            // 按指令列表逐条执行钱包操作，所有指令在同一事务内，要么全部成功要么全部回滚
            for (FundInstructionDTO.FundInstructionItem item : dto.getInstructions()) {
                executeOne(item, refType, refId, messageId);
            }
            record.setStatus(ProcessedFundInstruction.Status.PROCESSED);
            record.setProcessedAt(new Date());
            processedFundInstructionRepository.save(record);

            exec.setStatus(FundInstructionExecution.Status.PROCESSED);
            exec.setProcessedAt(new Date());
            exec.setErrorMsg(null);
            fundInstructionExecutionRepository.save(exec);
        } catch (Exception e) {
            log.error("fund instruction execute failed, messageId={}", messageId, e);
            // 标记为 FAILED，交由重试任务后续处理
            record.setStatus(ProcessedFundInstruction.Status.FAILED);
            processedFundInstructionRepository.save(record);
            exec.setStatus(FundInstructionExecution.Status.FAILED);
            exec.setErrorMsg(e.getMessage());
            fundInstructionExecutionRepository.save(exec);
            throw e;
        }
    }

    /**
     * 执行单条资金指令：钱包动账 + 按业务类型写入流水（refType/refId + TransactionType 映射）。
     *
     * @param item    指令明细
     * @param refType 业务类型（ORDER/DEPOSIT/WITHDRAW/SWEEP/REBALANCE），来自 DTO，为空时已默认为 ORDER
     * @param refId   业务主键，来自 DTO；本条若 item.refId 或 item.orderId 存在则优先用其覆盖
     * @param messageId 消息 ID，用于 refId 回填
     */
    private void executeOne(FundInstructionDTO.FundInstructionItem item, String refType, String refId, String messageId) {
        Long memberId = item.getMemberId();
        String symbol = item.getSymbol();
        BigDecimal amount = item.getAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        MemberWallet wallet = memberWalletService.findByCoinUnitAndMemberId(symbol, memberId);
        if (wallet == null) {
            throw new IllegalStateException("wallet not found: symbol=" + symbol + ", memberId=" + memberId);
        }
        String itemRefId = resolveRefId(item, refType, refId, messageId);

        switch (item.getInstructionType()) {
            case INCOME:
                memberWalletService.increaseBalance(wallet.getId(), amount);
                saveTransactionIfMapped(memberId, symbol, amount, BigDecimal.ZERO, item.getInstructionType(), refType, itemRefId, item.getTradeId());
                break;
            case OUTCOME:
                memberWalletService.decreaseFrozen(wallet.getId(), amount.abs());
                saveTransactionIfMapped(memberId, symbol, amount.abs().negate(), BigDecimal.ZERO, item.getInstructionType(), refType, itemRefId, item.getTradeId());
                break;
            case FEE:
                int feeRet = memberWalletService.deductBalance(wallet, amount.abs());
                if (feeRet <= 0) {
                    throw new IllegalStateException("deduct balance failed: " + symbol + ", memberId=" + memberId + ", amount=" + amount.abs());
                }
                saveTransactionIfMapped(memberId, symbol, amount.abs().negate(), amount.abs(), item.getInstructionType(), refType, itemRefId, item.getTradeId());
                break;
            case FEE_REVENUE:
                memberWalletService.increaseBalance(wallet.getId(), amount);
                saveTransactionIfMapped(memberId, symbol, amount, BigDecimal.ZERO, item.getInstructionType(), refType, itemRefId, item.getTradeId());
                break;
            case REFUND:
                memberWalletService.thawBalance(wallet, amount);
                saveTransactionIfMapped(memberId, symbol, amount, BigDecimal.ZERO, item.getInstructionType(), refType, itemRefId, item.getTradeId());
                break;
            default:
                log.warn("unknown instruction type: {}", item.getInstructionType());
        }
    }

    /** 本条流水 refId：item.refId > (ORDER 时 item.orderId) > dto.refId > messageId */
    private static String resolveRefId(FundInstructionDTO.FundInstructionItem item, String refType, String refId, String messageId) {
        if (item.getItemRefId() != null && !item.getItemRefId().isEmpty()) {
            return item.getItemRefId();
        }
        if (MemberTransaction.REF_TYPE_ORDER.equals(refType) && item.getOrderId() != null && !item.getOrderId().isEmpty()) {
            return item.getOrderId();
        }
        return refId != null ? refId : messageId;
    }

    /**
     * 按业务类型 + 指令类型映射流水类型（TransactionType），有映射则写流水并带 refType/refId。
     * 映射规则：ORDER→EXCHANGE；DEPOSIT→RECHARGE；WITHDRAW→WITHDRAW/WITHDRAW_FREEZE；SWEEP→SWEEP_FREEZE；REBALANCE→REBALANCE。
     */
    private void saveTransactionIfMapped(Long memberId, String symbol, BigDecimal amount, BigDecimal fee,
                                        FundInstructionDTO.InstructionType instructionType, String refType, String refId, String tradeId) {
        TransactionType txType = getTransactionType(refType, instructionType);
        if (txType == null) {
            return;
        }
        saveTransaction(memberId, symbol, amount, txType, fee, refType, refId, tradeId);
    }

    /**
     * 业务类型 + 指令类型 → 流水类型。返回 null 表示本条不写流水（兼容原 OUTCOME 不记等行为）。
     */
    private static TransactionType getTransactionType(String refType, FundInstructionDTO.InstructionType instructionType) {
        if (refType == null) {
            refType = MemberTransaction.REF_TYPE_ORDER;
        }
        switch (refType) {
            case MemberTransaction.REF_TYPE_ORDER:
                return TransactionType.EXCHANGE;
            case MemberTransaction.REF_TYPE_DEPOSIT:
                return instructionType == FundInstructionDTO.InstructionType.INCOME ? TransactionType.RECHARGE : null;
            case MemberTransaction.REF_TYPE_DEPOSIT_CHAIN:
                return instructionType == FundInstructionDTO.InstructionType.INCOME ? TransactionType.RECHARGE_CHAIN : null;
            case MemberTransaction.REF_TYPE_DEPOSIT_C2C:
                return instructionType == FundInstructionDTO.InstructionType.INCOME ? TransactionType.RECHARGE_C2C : null;
            case MemberTransaction.REF_TYPE_ADMIN_RECHARGE:
                return instructionType == FundInstructionDTO.InstructionType.INCOME ? TransactionType.ADMIN_RECHARGE : null;
            case MemberTransaction.REF_TYPE_WITHDRAW:
                if (instructionType == FundInstructionDTO.InstructionType.OUTCOME || instructionType == FundInstructionDTO.InstructionType.FEE) {
                    return TransactionType.WITHDRAW;
                }
                if (instructionType == FundInstructionDTO.InstructionType.INCOME) {
                    return TransactionType.WITHDRAW_UNFREEZE;
                }
                return null;
            case MemberTransaction.REF_TYPE_SWEEP:
                if (instructionType == FundInstructionDTO.InstructionType.INCOME) {
                    return TransactionType.SWEEP_UNFREEZE;
                }
                if (instructionType == FundInstructionDTO.InstructionType.OUTCOME) {
                    return TransactionType.SWEEP_FREEZE;
                }
                return null;
            case MemberTransaction.REF_TYPE_REBALANCE:
                return TransactionType.REBALANCE;
            default:
                return TransactionType.EXCHANGE;
        }
    }

    /**
     * 写入资金流水，并设置 refType/refId 便于按业务分类与平账。
     */
    private void saveTransaction(Long memberId, String symbol, BigDecimal amount, TransactionType type, BigDecimal fee,
                                 String refType, String refId, String tradeId) {
        MemberTransaction tx = new MemberTransaction();
        tx.setMemberId(memberId);
        tx.setSymbol(symbol);
        tx.setAmount(amount);
        tx.setType(type);
        tx.setFee(fee != null ? fee : BigDecimal.ZERO);
        tx.setDiscountFee("0");
        tx.setRealFee(fee != null ? fee.toPlainString() : "0");
        if (refType != null && !refType.isEmpty()) {
            tx.setRefType(refType);
        }
        if (refId != null && !refId.isEmpty()) {
            tx.setRefId(refId);
        }
        if (tradeId != null && !tradeId.isEmpty()) {
            tx.setTradeId(tradeId);
        }
        memberTransactionService.save(tx);
    }

    /**
     * 重试：对 PENDING/FAILED 记录按 payload 重新执行，成功则更新为 PROCESSED。
     */
    @Transactional(rollbackFor = Exception.class)
    public int retryExecutePending() {
        List<ProcessedFundInstruction> list = processedFundInstructionRepository.findByStatusInOrderByIdAsc(
                java.util.Arrays.asList(ProcessedFundInstruction.Status.PENDING, ProcessedFundInstruction.Status.FAILED));
        int done = 0;
        for (ProcessedFundInstruction record : list) {
            try {
                processAndExecute(record.getMessageId(), record.getPayload());
                done++;
            } catch (Exception e) {
                log.warn("fund instruction retry failed, id={}, messageId={}", record.getId(), record.getMessageId(), e);
            }
        }
        return done;
    }
}
