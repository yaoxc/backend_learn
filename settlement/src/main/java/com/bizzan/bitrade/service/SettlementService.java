package com.bizzan.bitrade.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.dao.FundInstructionRecordRepository;
import com.bizzan.bitrade.dao.SettlementResultRepository;
import com.bizzan.bitrade.dto.ClearingResultDTO;
import com.bizzan.bitrade.dto.FundInstructionDTO;
import com.bizzan.bitrade.entity.FundInstructionRecord;
import com.bizzan.bitrade.entity.MemberTransaction;
import com.bizzan.bitrade.entity.SettlementResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * 结算服务：消费清算结果，生成资金指令，先落库再发 Kafka；发送失败由定时任务重试。
 */
@Slf4j
@Service
public class SettlementService {

    public static final String TOPIC_EXCHANGE_FUND_INSTRUCTION = "exchange-fund-instruction";

    @Autowired
    private SettlementResultRepository settlementResultRepository;
    @Autowired
    private FundInstructionRecordRepository fundInstructionRecordRepository;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${settlement.fund.topic:" + TOPIC_EXCHANGE_FUND_INSTRUCTION + "}")
    private String fundInstructionTopic;

    /** 平台账户 memberId，用于手续费收入指令；默认 0 表示系统/平台 */
    @Value("${settlement.platform.memberId:0}")
    private Long platformMemberId;

    /**
     * 将清算结果 DTO 转为资金指令 DTO（每条清算项拆为收入/支出/手续费，退冻单独为 REFUND）。
     */
    public FundInstructionDTO clearingToFundInstructions(ClearingResultDTO clearing) {
        FundInstructionDTO dto = new FundInstructionDTO();
        dto.setMessageId(clearing.getMessageId());
        dto.setSymbol(clearing.getSymbol());
        dto.setTs(clearing.getTs());
        dto.setRefType(MemberTransaction.REF_TYPE_ORDER);
        dto.setRefId(clearing.getMessageId());
        for (ClearingResultDTO.TradeClearingItem item : clearing.getTradeClearingList()) {
            Long memberId = item.getMemberId();
            String orderId = item.getOrderId();
            if (item.getIncomeAmount() != null && item.getIncomeAmount().compareTo(BigDecimal.ZERO) > 0) {
                FundInstructionDTO.FundInstructionItem in = new FundInstructionDTO.FundInstructionItem();
                in.setMemberId(memberId);
                in.setOrderId(orderId);
                in.setTradeId(item.getTradeId());
                in.setSymbol(item.getIncomeSymbol());
                BigDecimal fee = (item.getFee() != null && item.getFee().compareTo(BigDecimal.ZERO) > 0) ? item.getFee() : BigDecimal.ZERO;
                in.setAmount(item.getIncomeAmount().add(fee));
                in.setInstructionType(FundInstructionDTO.InstructionType.INCOME);
                dto.getInstructions().add(in);
            }
            if (item.getOutcomeAmount() != null && item.getOutcomeAmount().compareTo(BigDecimal.ZERO) > 0) {
                FundInstructionDTO.FundInstructionItem out = new FundInstructionDTO.FundInstructionItem();
                out.setMemberId(memberId);
                out.setOrderId(orderId);
                out.setTradeId(item.getTradeId());
                out.setSymbol(item.getOutcomeSymbol());
                out.setAmount(item.getOutcomeAmount().negate());
                out.setInstructionType(FundInstructionDTO.InstructionType.OUTCOME);
                dto.getInstructions().add(out);
            }
            if (item.getFee() != null && item.getFee().compareTo(BigDecimal.ZERO) > 0) {
                String feeSymbol = item.getIncomeSymbol();
                FundInstructionDTO.FundInstructionItem fee = new FundInstructionDTO.FundInstructionItem();
                fee.setMemberId(memberId);
                fee.setOrderId(orderId);
                fee.setTradeId(item.getTradeId());
                fee.setSymbol(feeSymbol);
                fee.setAmount(item.getFee().negate());
                fee.setInstructionType(FundInstructionDTO.InstructionType.FEE);
                dto.getInstructions().add(fee);
                FundInstructionDTO.FundInstructionItem feeRevenue = new FundInstructionDTO.FundInstructionItem();
                feeRevenue.setMemberId(platformMemberId);
                feeRevenue.setOrderId(orderId);
                feeRevenue.setTradeId(item.getTradeId());
                feeRevenue.setSymbol(feeSymbol);
                feeRevenue.setAmount(item.getFee());
                feeRevenue.setInstructionType(FundInstructionDTO.InstructionType.FEE_REVENUE);
                dto.getInstructions().add(feeRevenue);
            }
        }
        for (ClearingResultDTO.OrderRefundItem item : clearing.getOrderRefundList()) {
            if (item.getRefundAmount() == null || item.getRefundAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            FundInstructionDTO.FundInstructionItem ref = new FundInstructionDTO.FundInstructionItem();
            ref.setMemberId(item.getMemberId());
            ref.setOrderId(item.getOrderId());
            ref.setSymbol(item.getCoinSymbol());
            ref.setAmount(item.getRefundAmount());
            ref.setInstructionType(FundInstructionDTO.InstructionType.REFUND);
            dto.getInstructions().add(ref);
        }
        return dto;
    }

    /**
     * 幂等：已存在该 messageId 的结算记录则直接返回；否则解析清算 payload → 生成资金指令 → 落库 PENDING → 发 Kafka。
     */
    @SuppressWarnings("null")
    public void processAndPublish(String clearingMessageId, String clearingPayload) {
        if (clearingMessageId == null || clearingMessageId.isEmpty()) {
            return;
        }
        final String messageId = Objects.requireNonNull(clearingMessageId);
        if (fundInstructionTopic == null || fundInstructionTopic.isEmpty()) {
            return;
        }
        if (settlementResultRepository.existsByMessageId(clearingMessageId)) {
            return;
        }
        ClearingResultDTO clearing = JSON.parseObject(clearingPayload, ClearingResultDTO.class);
        if (clearing == null) {
            log.warn("settlement: parse clearing payload null, messageId={}", clearingMessageId);
            return;
        }
        // 结算产出的资金指令 DTO: {messageId, symbol, ts, instructions}
        FundInstructionDTO fundDto = clearingToFundInstructions(clearing);
        log.info("结算服务产生资金指令 fundDto:{}", JSON.toJSONString(fundDto));
        String payload = JSON.toJSONString(fundDto);
        if (payload == null || payload.isEmpty()) {
            return;
        }

        // 资金指令“凭证表”：先落库 PENDING，保证后续对账能找到“应执行”的指令集合（按 messageId 幂等）
        if (!fundInstructionRecordRepository.existsByMessageId(messageId)) {
            FundInstructionRecord instructionRecord = new FundInstructionRecord();
            instructionRecord.setMessageId(messageId);
            instructionRecord.setSymbol(clearing.getSymbol());
            instructionRecord.setTs(clearing.getTs() != null ? clearing.getTs() : System.currentTimeMillis());
            instructionRecord.setStatus(FundInstructionRecord.Status.PENDING);
            instructionRecord.setPayload(payload);
            instructionRecord.setCreatedAt(new Date());
            fundInstructionRecordRepository.save(instructionRecord);
        }

        SettlementResult entity = new SettlementResult();
        entity.setMessageId(clearingMessageId);
        entity.setSymbol(clearing.getSymbol());
        entity.setTs(clearing.getTs() != null ? clearing.getTs() : System.currentTimeMillis());
        entity.setStatus(SettlementResult.Status.PENDING);
        entity.setPayload(payload);
        entity.setCreatedAt(new Date());
        settlementResultRepository.save(entity);

        try {
            kafkaTemplate.send(fundInstructionTopic, messageId, payload).get();
            entity.setStatus(SettlementResult.Status.PUBLISHED);
            entity.setPublishedAt(new Date());
            settlementResultRepository.save(entity);

            fundInstructionRecordRepository.findByMessageId(Objects.requireNonNull(messageId)).ifPresent(r -> {
                r.setStatus(FundInstructionRecord.Status.PUBLISHED);
                r.setPublishedAt(new Date());
                fundInstructionRecordRepository.save(r);
            });
        } catch (Exception e) {
            log.warn("settlement fund instruction kafka send failed, messageId={}, will retry by job", clearingMessageId, e);
            entity.setStatus(SettlementResult.Status.FAILED);
            settlementResultRepository.save(entity);

            fundInstructionRecordRepository.findByMessageId(Objects.requireNonNull(messageId)).ifPresent(r -> {
                r.setStatus(FundInstructionRecord.Status.FAILED);
                fundInstructionRecordRepository.save(r);
            });
        }
    }

    /**
     * 重试发送：对已落库但未发送成功的结算记录重新发 Kafka。
     */
    public int retryPublishPending() {
        List<SettlementResult> list = settlementResultRepository.findByStatusInOrderByIdAsc(
                java.util.Arrays.asList(SettlementResult.Status.PENDING, SettlementResult.Status.FAILED));
        int sent = 0;
        for (SettlementResult entity : list) {
            try {
                kafkaTemplate.send(fundInstructionTopic, entity.getMessageId(), entity.getPayload()).get();
                entity.setStatus(SettlementResult.Status.PUBLISHED);
                entity.setPublishedAt(new Date());
                settlementResultRepository.save(entity);
                sent++;
            } catch (Exception e) {
                log.warn("settlement retry send failed, id={}, messageId={}", entity.getId(), entity.getMessageId(), e);
            }
        }
        return sent;
    }
}
