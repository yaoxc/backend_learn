package com.bizzan.bitrade.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.util.Date;

/**
 * 对账问题记录：以 messageId 为主线，记录链路缺失/失败/超时等异常，供告警与排障。
 */
@Data
@Entity
@Table(name = "reconcile_issue", indexes = {
        @Index(name = "idx_reconcile_issue_message_id", columnList = "message_id"),
        @Index(name = "idx_reconcile_issue_status_created_at", columnList = "status,created_at")
})
public class ReconcileIssue implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 异常所处链路阶段（与 ReconcileScheduler 写入的语义对齐）。
     * 当前调度器实际使用：CLEARING / SETTLEMENT / FUND_EXECUTION；MATCH_RESULT 预留扩展。
     */
    public enum Stage { MATCH_RESULT, CLEARING, SETTLEMENT, FUND_EXECUTION }
    public enum Status { OPEN, RESOLVED, IGNORED }
    public enum Severity { INFO, WARN, ERROR }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", length = 64, nullable = false)
    private String messageId;

    @Column(name = "symbol", length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", length = 32, nullable = false)
    private Stage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", length = 16, nullable = false)
    private Severity severity = Severity.WARN;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date createdAt;

    @Column(name = "resolved_at")
    @Temporal(TemporalType.TIMESTAMP)
    private Date resolvedAt;
}

