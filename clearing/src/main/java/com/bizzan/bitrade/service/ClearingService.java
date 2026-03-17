package com.bizzan.bitrade.service;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.dao.ClearingResultRepository;
import com.bizzan.bitrade.dto.ClearingResultDTO;
import com.bizzan.bitrade.entity.ClearingResult;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 清算服务：根据撮合结果计算清算数据，先落库再发 Kafka；发送失败保留 PENDING/FAILED，由定时任务重试。
 */
@Slf4j
@Service
public class ClearingService {

    public static final String TOPIC_EXCHANGE_CLEARING_RESULT = "exchange-clearing-result";

    @Autowired
    private ClearingResultRepository clearingResultRepository;
    @Autowired
    private ClearingComputeService clearingComputeService;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${clearing.result.topic:" + TOPIC_EXCHANGE_CLEARING_RESULT + "}")
    private String clearingResultTopic;

    /**
     * 幂等：已存在该 messageId 的清算记录则直接返回；否则计算 → 落库 PENDING → 发 Kafka → 成功则更新 PUBLISHED。
     */
    public void processAndPublish(String messageId, String symbol, Long ts,
                                   List<ExchangeTrade> trades, List<ExchangeOrder> completedOrders) {
        if (messageId == null || messageId.isEmpty()) {
            return;
        }
        if (clearingResultRepository.existsByMessageId(messageId)) {
            return;
        }
        ClearingResultDTO dto = clearingComputeService.compute(messageId, symbol, ts, trades, completedOrders);
        log.info("清算服务计算结果: payload={}", JSON.toJSONString(dto));
        String payload = JSON.toJSONString(dto);
        ClearingResult entity = new ClearingResult();
        entity.setMessageId(messageId);
        entity.setSymbol(symbol);
        entity.setTs(ts != null ? ts : System.currentTimeMillis());
        entity.setStatus(ClearingResult.Status.PENDING);
        entity.setPayload(payload);
        entity.setCreatedAt(new Date());
        // 发 Kafka之前入库， Pending状态， 保证幂等
        // 之后无论 Kafka 成功 / 失败 / 服务宕机，都有一条记录可以被“补发 job”扫描到。
        clearingResultRepository.save(entity);
        /**
         *  唯一让“完全不入库”的情况，是：
            在 clearingResultRepository.save(entity) 这一步本身就抛异常（数据库挂了、事务回滚等），
            此时代码不会执行到 kafkaTemplate.send(...)，也不会有任何清算结果对外可见；
          这属于“数据库不可用”的大故障，已经超出业务逻辑可控范围。
         * 
         */
        try {
            kafkaTemplate.send(clearingResultTopic, messageId, payload).get();
            entity.setStatus(ClearingResult.Status.PUBLISHED);
            entity.setPublishedAt(new Date());
            clearingResultRepository.save(entity);
        } catch (Exception e) {
            log.warn("clearing result kafka send failed, messageId={}, will retry by job", messageId, e);
            entity.setStatus(ClearingResult.Status.FAILED);
            clearingResultRepository.save(entity);
        }
    }

    /**
     * 重试发送：对已落库但未发送成功的记录重新发 Kafka，成功则更新为 PUBLISHED。
     */
    public int retryPublishPending() {
        List<ClearingResult> list = clearingResultRepository.findByStatusInOrderByIdAsc(
                java.util.Arrays.asList(ClearingResult.Status.PENDING, ClearingResult.Status.FAILED));
        int sent = 0;
        for (ClearingResult entity : list) {
            try {
                kafkaTemplate.send(clearingResultTopic, entity.getMessageId(), entity.getPayload()).get();
                entity.setStatus(ClearingResult.Status.PUBLISHED);
                entity.setPublishedAt(new Date());
                clearingResultRepository.save(entity);
                sent++;
            } catch (Exception e) {
                log.warn("clearing retry send failed, id={}, messageId={}", entity.getId(), entity.getMessageId(), e);
            }
        }
        return sent;
    }
}
