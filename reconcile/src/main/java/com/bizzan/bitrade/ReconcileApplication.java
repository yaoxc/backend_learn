package com.bizzan.bitrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 对账服务入口：仅依赖 JPA（读写凭证表）、定时任务（{@link com.bizzan.bitrade.reconcile.ReconcileScheduler}）。
 * <p>
 * 扫描范围故意收窄：不扫描 {@code com.bizzan.bitrade.service} 等，否则会连带初始化 ES、业务 Service，
 * 需要大量与本服务无关的配置占位符。
 */
@EnableScheduling
@SpringBootApplication(scanBasePackages = {
        // reconcile 包：Controller、Scheduler、Properties
        "com.bizzan.bitrade.reconcile",
        // exchange-core：JPA Repository 与 Entity（凭证表、reconcile_issue）
        "com.bizzan.bitrade.dao",
        "com.bizzan.bitrade.entity"
})
public class ReconcileApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReconcileApplication.class, args);
    }
}

