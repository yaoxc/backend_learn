package com.bizzan.bitrade.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.dao.ProcessedFundInstructionRepository;
import com.bizzan.bitrade.dto.FundInstructionDTO;
import com.bizzan.bitrade.entity.MemberWallet;
import com.bizzan.bitrade.entity.ProcessedFundInstruction;
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
 */
@Slf4j
@Service
public class FundInstructionExecuteService {

    @Autowired
    private ProcessedFundInstructionRepository processedFundInstructionRepository;
    @Autowired
    private MemberWalletService memberWalletService;

    @Value("${settlement.platform.memberId:0}")
    private Long platformMemberId;

    /**
     * 幂等：已处理过该 messageId 则直接返回；否则落库 PENDING → 按条执行指令 → 更新 PROCESSED 或 FAILED。
     */
    @Transactional(rollbackFor = Exception.class)
    public void processAndExecute(String messageId, String payload) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        if (processedFundInstructionRepository.existsByMessageIdAndStatus(messageId, ProcessedFundInstruction.Status.PROCESSED)) {
            return;
        }
        ProcessedFundInstruction record = processedFundInstructionRepository.findByMessageId(messageId).orElse(null);
        if (record == null) {
            record = new ProcessedFundInstruction();
            record.setMessageId(messageId);
            record.setStatus(ProcessedFundInstruction.Status.PENDING);
            record.setPayload(payload);
            record.setCreatedAt(new Date());
            processedFundInstructionRepository.save(record);
        }
        String payloadToUse = record.getPayload() != null ? record.getPayload() : payload;
        FundInstructionDTO dto = JSON.parseObject(payloadToUse, FundInstructionDTO.class);
        if (dto == null || dto.getInstructions() == null) {
            log.warn("fund instruction parse null, messageId={}", messageId);
            record.setStatus(ProcessedFundInstruction.Status.FAILED);
            processedFundInstructionRepository.save(record);
            return;
        }
        try {
            for (FundInstructionDTO.FundInstructionItem item : dto.getInstructions()) {
                executeOne(item);
            }
            record.setStatus(ProcessedFundInstruction.Status.PROCESSED);
            record.setProcessedAt(new Date());
            processedFundInstructionRepository.save(record);
        } catch (Exception e) {
            log.error("fund instruction execute failed, messageId={}", messageId, e);
            record.setStatus(ProcessedFundInstruction.Status.FAILED);
            processedFundInstructionRepository.save(record);
            throw e;
        }
    }

    private void executeOne(FundInstructionDTO.FundInstructionItem item) {
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
        switch (item.getType()) {
            case INCOME:
                memberWalletService.increaseBalance(wallet.getId(), amount);
                break;
            case OUTCOME:
                memberWalletService.decreaseFrozen(wallet.getId(), amount.abs());
                break;
            case FEE:
                int feeRet = memberWalletService.deductBalance(wallet, amount.abs());
                if (feeRet <= 0) {
                    throw new IllegalStateException("deduct balance failed: " + symbol + ", memberId=" + memberId + ", amount=" + amount.abs());
                }
                break;
            case FEE_REVENUE:
                memberWalletService.increaseBalance(wallet.getId(), amount);
                break;
            case REFUND:
                memberWalletService.thawBalance(wallet, amount);
                break;
            default:
                log.warn("unknown instruction type: {}", item.getType());
        }
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
