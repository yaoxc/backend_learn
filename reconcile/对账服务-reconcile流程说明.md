# 对账服务（`reconcile`）流程说明

## 1. 背景与目标

撮合结果经 Kafka 下发后，链路为：

`exchange-match-result` → **清算** → `exchange-clearing-result` → **结算** → `exchange-fund-instruction` → **资金服务**执行钱包动账。

每条撮合批次消息都有全局唯一的 **`messageId`**（与清算/结算/资金指令消息 key 对齐）。对账服务以 **`messageId` 为主线**，检查各层**凭证表**是否齐全、状态是否正常，发现缺失或失败则写入 **`reconcile_issue`**，便于告警与排障。

## 2. 数据流与凭证表（按 messageId 串联）

| 顺序 | 环节 | 凭证表 / 说明 |
|------|------|----------------|
| ① | 撮合结果已落库 | `match_result_event`（clearing 消费 match-result 时写入） |
| ② | 清算完成 | `clearing_result`（`existsByMessageId`） |
| ③ | 结算完成 | `settlement_result`（`existsByMessageId`） |
| ④ | 资金指令已生成/发布 | `fund_instruction_record`（状态 `PENDING` / `PUBLISHED` / `FAILED`） |
| ⑤ | 资金执行完成 | `fund_instruction_execution`（状态 `PENDING` / `PROCESSED` / `FAILED`） |

> 说明：本对账任务**不**逐笔核对 `member_wallet` / `member_transaction` 金额闭环；若需要可在此基础上扩展。

## 3. 对账逻辑（`ReconcileScheduler`）

1. **时间窗口**：查询 `match_result_event` 中 `created_at` 在「当前时间 − lookbackMinutes」之后的记录（默认 60 分钟）。
2. **阶段容忍**：对每条事件，若自 `created_at` 起经过时间 **小于** `stageTimeoutSeconds`（默认 120 秒），则**跳过**（认为链路仍在正常推进）。
3. **顺序检查**（超时后才判异常）：
   - 无 `clearing_result` → 记 `Stage=CLEARING`，`reconcile_issue`
   - 无 `settlement_result` → 记 `Stage=SETTLEMENT`
   - 无 `fund_instruction_record` 或状态为 `FAILED` → 记 `Stage=SETTLEMENT`
   - 无 `fund_instruction_execution` 或 `FAILED` → 记 `Stage=FUND_EXECUTION`
   - `fund_instruction_execution=PENDING` → 记 `WARN`（可能重试中或卡住）
4. **幂等**：同一 `messageId` + `stage` + `OPEN` 已存在则不再重复插入。

## 4. 调度与配置

- **调度**：`@Scheduled(fixedDelayString = "30000")`，约每 30 秒执行一次（上一次结束后间隔 30s 再跑）。
- **配置项**（`application.yml` 前缀 `reconcile`）：
  - `scan.lookbackMinutes`：扫描最近多少分钟内的撮合凭证。
  - `tolerance.stageTimeoutSeconds`：从 `match_result_event.created_at` 起算，超过该秒数才进入「缺表/失败」判定。

## 5. 查询接口

- `GET /reconcile/issues?status=OPEN&page=0&size=50`：分页查询对账问题（默认查 `OPEN`）。

## 6. 相关代码位置

| 说明 | 路径 |
|------|------|
| 启动类（扫描范围、定时任务） | `reconcile/.../ReconcileApplication.java` |
| 定时对账 | `reconcile/.../reconcile/ReconcileScheduler.java` |
| 配置绑定 | `reconcile/.../reconcile/ReconcileProperties.java` |
| HTTP 查询 | `reconcile/.../reconcile/ReconcileController.java` |
| 问题实体 | `exchange-core/.../entity/ReconcileIssue.java` |
