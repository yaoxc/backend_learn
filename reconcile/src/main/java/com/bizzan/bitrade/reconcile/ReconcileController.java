package com.bizzan.bitrade.reconcile;

import com.bizzan.bitrade.dao.ReconcileIssueRepository;
import com.bizzan.bitrade.entity.ReconcileIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对账问题查询：供运维/监控拉取 {@link ReconcileIssue}，与定时任务写入的数据源一致。
 */
@RestController
@RequiredArgsConstructor
public class ReconcileController {

    private final ReconcileIssueRepository reconcileIssueRepository;

    /**
     * 分页查询对账问题；默认 status=OPEN 即未处理项。
     */
    @GetMapping("/reconcile/issues")
    public Page<ReconcileIssue> listOpenIssues(
            @RequestParam(defaultValue = "OPEN") ReconcileIssue.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return reconcileIssueRepository.findByStatusOrderByIdDesc(status, PageRequest.of(page, Math.min(size, 200)));
    }
}

