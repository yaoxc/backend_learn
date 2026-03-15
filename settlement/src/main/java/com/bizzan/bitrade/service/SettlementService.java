package com.bizzan.bitrade.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.dao.SettlementResultRepository;
import com.bizzan.bitrade.dto.ClearingResultDTO;
import com.bizzan.bitrade.dto.FundInstructionDTO;
import com.bizzan.bitrade.entity.SettlementResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

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
        for (ClearingResultDTO.TradeClearingItem item : clearing.getTradeClearingList()) {
            Long memberId = item.getMemberId();
            String orderId = item.getOrderId();
            if (item.getIncomeAmount() != null && item.getIncomeAmount().compareTo(BigDecimal.ZERO) > 0) {
                FundInstructionDTO.FundInstructionItem in = new FundInstructionDTO.FundInstructionItem();
                in.setMemberId(memberId);
                in.setOrderId(orderId);
                in.setSymbol(item.getIncomeSymbol());
                BigDecimal fee = (item.getFee() != null && item.getFee().compareTo(BigDecimal.ZERO) > 0) ? item.getFee() : BigDecimal.ZERO;
                in.setAmount(item.getIncomeAmount().add(fee));
                in.setType(FundInstructionDTO.InstructionType.INCOME);
                dto.getInstructions().add(in);
            }
            if (item.getOutcomeAmount() != null && item.getOutcomeAmount().compareTo(BigDecimal.ZERO) > 0) {
                FundInstructionDTO.FundInstructionItem out = new FundInstructionDTO.FundInstructionItem();
                out.setMemberId(memberId);
                out.setOrderId(orderId);
                out.setSymbol(item.getOutcomeSymbol());
                out.setAmount(item.getOutcomeAmount().negate());
                out.setType(FundInstructionDTO.InstructionType.OUTCOME);
                dto.getInstructions().add(out);
            }
            if (item.getFee() != null && item.getFee().compareTo(BigDecimal.ZERO) > 0) {
                String feeSymbol = item.getIncomeSymbol();
                FundInstructionDTO.FundInstructionItem fee = new FundInstructionDTO.FundInstructionItem();
                fee.setMemberId(memberId);
                fee.setOrderId(orderId);
                fee.setSymbol(feeSymbol);
                fee.setAmount(item.getFee().negate());
                fee.setType(FundInstructionDTO.InstructionType.FEE);
                dto.getInstructions().add(fee);
                FundInstructionDTO.FundInstructionItem feeRevenue = new FundInstructionDTO.FundInstructionItem();
                feeRevenue.setMemberId(platformMemberId);
                feeRevenue.setOrderId(orderId);
                feeRevenue.setSymbol(feeSymbol);
                feeRevenue.setAmount(item.getFee());
                feeRevenue.setType(FundInstructionDTO.InstructionType.FEE_REVENUE);
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
            ref.setType(FundInstructionDTO.InstructionType.REFUND);
            dto.getInstructions().add(ref);
        }
        return dto;
    }

    /**
     * 幂等：已存在该 messageId 的结算记录则直接返回；否则解析清算 payload → 生成资金指令 → 落库 PENDING → 发 Kafka。
     */
    public void processAndPublish(String clearingMessageId, String clearingPayload) {
        if (clearingMessageId == null || clearingMessageId.isEmpty()) {
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
        String payload = JSON.toJSONString(fundDto);
        SettlementResult entity = new SettlementResult();
        entity.setMessageId(clearingMessageId);
        entity.setSymbol(clearing.getSymbol());
        entity.setTs(clearing.getTs() != null ? clearing.getTs() : System.currentTimeMillis());
        entity.setStatus(SettlementResult.Status.PENDING);
        entity.setPayload(payload);
        entity.setCreatedAt(new Date());
        settlementResultRepository.save(entity);

        try {
            kafkaTemplate.send(fundInstructionTopic, clearingMessageId, payload).get();
            entity.setStatus(SettlementResult.Status.PUBLISHED);
            entity.setPublishedAt(new Date());
            settlementResultRepository.save(entity);
        } catch (Exception e) {
            log.warn("settlement fund instruction kafka send failed, messageId={}, will retry by job", clearingMessageId, e);
            entity.setStatus(SettlementResult.Status.FAILED);
            settlementResultRepository.save(entity);
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
