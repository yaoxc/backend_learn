package com.bizzan.bitrade.dao;

import com.bizzan.bitrade.entity.ReconcileIssue;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconcileIssueRepository extends JpaRepository<ReconcileIssue, Long> {
    boolean existsByMessageIdAndStageAndStatus(String messageId, ReconcileIssue.Stage stage, ReconcileIssue.Status status);

    Page<ReconcileIssue> findByStatusOrderByIdDesc(ReconcileIssue.Status status, Pageable pageable);
}

