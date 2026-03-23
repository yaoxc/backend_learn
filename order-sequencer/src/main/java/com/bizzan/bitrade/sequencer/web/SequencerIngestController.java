package com.bizzan.bitrade.sequencer.web;

import com.bizzan.bitrade.sequencer.domain.InstructionStatus;
import com.bizzan.bitrade.sequencer.domain.SequencerInstruction;
import com.bizzan.bitrade.sequencer.repo.SequencerInstructionRepository;
import com.bizzan.bitrade.sequencer.web.dto.IngestInstructionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 接收待定序指令写入 DB；真正排序与发 Kafka 由定时任务 {@link com.bizzan.bitrade.sequencer.job.SequencerDispatchJob} 驱动。
 */
@RestController
@RequestMapping("/api/sequencer")
@RequiredArgsConstructor
public class SequencerIngestController {

    private final SequencerInstructionRepository instructionRepository;

    @PostMapping("/instructions")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestInstructionRequest req) {
        if (req.getSymbol() == null || req.getOrderId() == null || req.getDirection() == null) {
            return ResponseEntity.badRequest().body(error("symbol, orderId, direction required"));
        }
        SequencerInstruction e = new SequencerInstruction();
        e.setSymbol(req.getSymbol().trim());
        e.setOrderId(req.getOrderId().trim());
        e.setDirection(req.getDirection().trim().toUpperCase());
        e.setPrice(req.getPrice() != null ? req.getPrice() : java.math.BigDecimal.ZERO);
        // 关键：receivedAt 决定时间优先次序，应由接入层尽量传撮合/网关收到请求的单调时间
        e.setReceivedAt(req.getReceivedAt() != null ? req.getReceivedAt() : System.currentTimeMillis());
        e.setPayload(req.getPayload());
        e.setStatus(InstructionStatus.PENDING);
        e = instructionRepository.save(e);
        Map<String, Object> ok = new HashMap<>();
        ok.put("ok", true);
        ok.put("instructionId", e.getId());
        return ResponseEntity.ok(ok);
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new HashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }
}
