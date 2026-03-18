## `member_transaction` 字段规范与 `TransactionType` 映射

### 1. 字段含义约定

- **`type`**：资金流水类型，枚举 `TransactionType`，持久化时由 `TransactionTypeAttributeConverter` 写入（整数或枚举），语义以枚举为准。
- **`type_str`**：`type` 的**稳定字符串口径**，统一存 `TransactionType.name()`（如 `EXCHANGE_FREEZE`），用于对账、审计、外部报表；避免与 `type` 的数值编码强绑定。
- **`ref_type`**：业务类别，用于区分是哪条业务链路产生的流水，取值统一使用 `MemberTransaction.REF_TYPE_*` 常量。
- **`ref_id`**：业务主键或业务单号，`ref_type + ref_id` 一起唯一定位到业务记录（订单、提现记录、归集批次等）。
- **`trade_id`**：撮合成交 ID，仅撮合/结算相关流水有值；非撮合场景可为空。

---

### 2. 按场景的推荐取值

#### 2.1 充值 / 存款

| 业务场景 | `type` (`TransactionType`) | `type_str` | `ref_type` | `ref_id` |
|----------|---------------------------|-----------|-----------|---------|
| 通用充值记录 | `RECHARGE` | `"RECHARGE"` | `REF_TYPE_DEPOSIT` | 充值记录 ID（`deposit.id` 等） |
| 链上充值（扫链入账） | `RECHARGE_CHAIN` | `"RECHARGE_CHAIN"` | `REF_TYPE_DEPOSIT_CHAIN` | 链上充值记录 / 区块确认记录 ID |
| C2C 充值 | `RECHARGE_C2C` | `"RECHARGE_C2C"` | `REF_TYPE_DEPOSIT_C2C` | C2C 订单 ID / 划拨记录 ID |
| 人工充值 | `ADMIN_RECHARGE` | `"ADMIN_RECHARGE"` | `REF_TYPE_ADMIN_RECHARGE` | 工单 ID / 后台操作记录 ID |

> 说明：同一笔充值通常只记一条用户侧流水（入账），平台资产层面另记到平台账户表。

#### 2.2 提现

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 提现申请成功（资金实际扣减场景） | `WITHDRAW` | `"WITHDRAW"` | `REF_TYPE_WITHDRAW` | 提现记录 ID |
| 提现申请时冻结资金（可用→冻结） | `WITHDRAW_FREEZE` | `"WITHDRAW_FREEZE"` | `REF_TYPE_WITHDRAW` | 同上（提现记录 ID） |
| 提现取消/拒绝解冻资金（冻结→可用） | `WITHDRAW_UNFREEZE` | `"WITHDRAW_UNFREEZE"` | `REF_TYPE_WITHDRAW` | 同上（提现记录 ID） |

> 建议：提现相关所有流水统一用同一个 `ref_id = withdrawRecord.id`，便于按单号回溯。

#### 2.3 转账（站内转账/资金划转）

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 站内转账（单边视角：A → B） | `TRANSFER_ACCOUNTS` | `"TRANSFER_ACCOUNTS"` | `REF_TYPE_TRANSFER` | 转账记录 ID |

> 说明：一笔转账会在**双方**各记一条流水，`ref_type + ref_id` 一致，通过 amount 符号或 from/to 字段区分方向。

#### 2.4 币币交易（撮合）与下单冻结

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 币币下单冻结（可用→冻结） | `EXCHANGE_FREEZE` | `"EXCHANGE_FREEZE"` | `REF_TYPE_ORDER` | 币币订单 ID（`exchange_order.order_id`） |
| 币币成交／撮合结算（资金变动） | `EXCHANGE` | `"EXCHANGE"` | `REF_TYPE_ORDER` | 同上（订单 ID） |

> 若一笔成交拆成多笔 trade，可在 `trade_id` 中写入成交 ID，将同一订单下多笔成交与其资金流水一一对应。

#### 2.5 法币 / C2C 订单

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| OTC 买入 | `OTC_BUY` | `"OTC_BUY"` | `REF_TYPE_OTC` | 法币订单 ID |
| OTC 卖出 | `OTC_SELL` | `"OTC_SELL"` | `REF_TYPE_OTC` | 法币订单 ID |
| CTC 买入 | `CTC_BUY` | `"CTC_BUY"` | `REF_TYPE_CTC` | CTC 订单 ID |
| CTC 卖出 | `CTC_SELL` | `"CTC_SELL"` | `REF_TYPE_CTC` | CTC 订单 ID |

#### 2.6 活动 / 营销 / 奖励

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 活动奖励 | `ACTIVITY_AWARD` | `"ACTIVITY_AWARD"` | `REF_TYPE_ACTIVITY` | 活动发放记录 ID |
| 推广奖励 | `PROMOTION_AWARD` | `"PROMOTION_AWARD"` | `REF_TYPE_ACTIVITY` | 推广奖励发放记录 ID |
| 分红 | `DIVIDEND` | `"DIVIDEND"` | `REF_TYPE_ACTIVITY` | 分红批次 ID |
| 投票 | `VOTE` | `"VOTE"` | `REF_TYPE_ACTIVITY` | 投票记录 / 扣费记录 ID |
| 活动兑换 | `ACTIVITY_BUY` | `"ACTIVITY_BUY"` | `REF_TYPE_ACTIVITY` | 兑换订单/记录 ID |
| 配对 | `MATCH` | `"MATCH"` | `REF_TYPE_MATCH` | 配对记录 ID |

#### 2.7 红包

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 发红包 | `RED_OUT` | `"RED_OUT"` | `REF_TYPE_RED_PACKET` | 红包主记录 ID |
| 领红包 | `RED_IN` | `"RED_IN"` | `REF_TYPE_RED_PACKET` | 领取明细 ID |

#### 2.8 归集 / 再平衡

| 业务场景 | `type` | `type_str` | `ref_type` | `ref_id` |
|----------|--------|-----------|-----------|---------|
| 归集冻结（可用→冻结） | `SWEEP_FREEZE` | `"SWEEP_FREEZE"` | `REF_TYPE_SWEEP` | 归集批次/任务 ID |
| 归集解冻 / 到账 | `SWEEP_UNFREEZE` | `"SWEEP_UNFREEZE"` | `REF_TYPE_SWEEP` | 同上 |
| 冷热再平衡 | `REBALANCE` | `"REBALANCE"` | `REF_TYPE_REBALANCE` | 再平衡批次 ID |

---

### 3. 使用规范小结

- **`type` 必须与 `type_str` 一致**：持久化时统一设置 `type_str = type.name()`，避免后续调整枚举顺序影响数字含义。
- **`ref_type` 只允许使用 `REF_TYPE_*` 常量**：禁止手写魔法字符串，保证对账/查询维度有限且稳定。
- **`ref_id` 始终写业务主键或业务单号**：不再复用 `address` 等字段做业务 ID，做到“看一条流水就能回溯到那条业务记录”。

---

### 4. 按类型构造资金指令（供 fund 动账）

业务侧若通过 **Kafka `exchange-fund-instruction`** 交由 **fund 服务**统一动账并落 `member_transaction`，需按下列约定构造 **FundInstructionDTO**（含 `refType`、`refId`、`instructions`）。资金服务执行逻辑见 `FundInstructionExecuteService` 与《流水记录：资金解冻与资金指令执行》。

#### 4.1 资金指令模型简述

- **DTO 顶层**：`messageId`（幂等）、`refType`、`refId`、`instructions`（必填）；`symbol`、`ts` 可选。
- **指令明细 FundInstructionItem**：`memberId`、`symbol`、`amount`（正数）、`instructionType`（见下）；`orderId` / `itemRefId` / `tradeId` 按场景选填。
- **InstructionType**：`INCOME`=加可用，`OUTCOME`=扣冻结，`FEE`=从可用扣手续费，`REFUND`=解冻退回可用，`FEE_REVENUE`=平台手续费加可用。  
- **流水对应**：fund 按 `refType` + `instructionType` 映射为 `TransactionType` 并写入 `member_transaction`（refType/refId/tradeId 一并写入）；无映射则不写流水。

下表按 **TransactionType / 业务场景** 给出：为得到对应流水类型，应如何设置 `refType`、`refId` 以及每条 instruction 的 `instructionType` 与关键字段。

#### 4.2 充值类（入账）

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| RECHARGE | `REF_TYPE_DEPOSIT` | 充值记录 ID | 1 条 **INCOME**：memberId、symbol、amount；可选 itemRefId=refId | 通用充值入账 |
| RECHARGE_CHAIN | `REF_TYPE_DEPOSIT_CHAIN` | 链上充值/区块确认 ID | 1 条 **INCOME**，同上 | 扫链入账 |
| RECHARGE_C2C | `REF_TYPE_DEPOSIT_C2C` | C2C 订单/划拨 ID | 1 条 **INCOME**，同上 | C2C 买币入账 |
| ADMIN_RECHARGE | `REF_TYPE_ADMIN_RECHARGE` | 工单/操作记录 ID | 1 条 **INCOME**，同上 | 人工充值 |

#### 4.3 提现类（扣款/解冻）

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| WITHDRAW | `REF_TYPE_WITHDRAW` | 提现记录 ID | **OUTCOME**（扣冻结）+ 可选 **FEE**（扣可用）：同 refId，memberId/symbol/amount 按实际 | 提现成功扣款与手续费 |
| WITHDRAW_UNFREEZE | `REF_TYPE_WITHDRAW` | 提现记录 ID | 1 条 **INCOME**：解冻退回可用，同 refId | 提现取消/拒绝后退款 |

> 说明：提现申请时的“可用→冻结”（WITHDRAW_FREEZE）通常由业务侧先冻结并写流水，不经过 fund 指令；若需统一经 fund，可约定“冻结”类指令扩展（当前 DTO 无 FREEZE 类型，需业务自管冻结与流水）。

#### 4.4 转账（站内）

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| TRANSFER_ACCOUNTS | `REF_TYPE_TRANSFER` | 转账记录 ID | **转出方** 1 条 **OUTCOME**（或从可用扣款则需 FEE 类），**转入方** 1 条 **INCOME**；两条的 refType/refId 一致，itemRefId 可用同一转账单 ID | 当前 fund 的 getTransactionType 未映射 TRANSFER，若由 fund 写流水需扩展映射；此处约定指令构造，便于后续对接 |

#### 4.5 币币交易（撮合）

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| EXCHANGE | `REF_TYPE_ORDER` | 订单 ID（orderId） | 由结算按清算结果生成：**INCOME**（收入）、**OUTCOME**（扣冻结）、**FEE**（用户手续费）、**REFUND**（退冻）、**FEE_REVENUE**（平台手续费）；每条 item 设 orderId=订单 ID，可选 tradeId | 成交后资金由 settlement 产出指令，fund 执行后流水 type 均为 EXCHANGE，refId=orderId |
| EXCHANGE_FREEZE | - | - | 不下发 fund 指令；由**下单/撮合侧**在用户下单时直接冻结并写一条 EXCHANGE_FREEZE 流水，refType=ORDER、refId=orderId | 下单冻结不经过 exchange-fund-instruction |

#### 4.6 法币/C2C、CTC

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| OTC_BUY / OTC_SELL | `REF_TYPE_OTC` | 法币订单 ID | 买方：OUTCOME（或 FEE）；卖方：INCOME（或 FEE）；按实际金额与方向构造 INCOME/OUTCOME/FEE | 当前 fund 未映射 OTC，若由 fund 动账需扩展 refType→TransactionType；此处约定 refType/refId 与指令方向 |
| CTC_BUY / CTC_SELL | `REF_TYPE_CTC` | CTC 订单 ID | 同上，refType=REF_TYPE_CTC | 同上，扩展映射后可写 CTC 流水 |

#### 4.7 活动/营销/红包/归集

| 流水类型 `type` | refType | refId | 资金指令构造要点 | 说明 |
|-----------------|---------|-------|------------------|------|
| ACTIVITY_AWARD / PROMOTION_AWARD / DIVIDEND 等 | `REF_TYPE_ACTIVITY` | 活动/发放记录 ID | 1 条 **INCOME**：memberId、symbol、amount；itemRefId=refId | fund 未映射 ACTIVITY，扩展后可用 |
| VOTE / ACTIVITY_BUY | `REF_TYPE_ACTIVITY` | 投票/兑换记录 ID | **OUTCOME** 或 **FEE**（扣款）+ 对方 **INCOME**（若需） | 扩展映射后由 fund 写流水 |
| MATCH | `REF_TYPE_MATCH` | 配对记录 ID | 按配对结果 INCOME/OUTCOME，refId=配对记录 ID | 扩展映射后可写 MATCH 流水 |
| RED_OUT | `REF_TYPE_RED_PACKET` | 红包主记录 ID | 1 条 **OUTCOME**（发红包扣款） | 扩展映射后可经 fund 动账 |
| RED_IN | `REF_TYPE_RED_PACKET` | 领取明细 ID | 1 条 **INCOME**（领红包入账） | 同上 |
| SWEEP_FREEZE | `REF_TYPE_SWEEP` | 归集批次/任务 ID | 1 条 **OUTCOME**（扣减用户冻结，即归集执行时从冻结划转）；“可用→冻结”若经 fund 需扩展 FREEZE 类或由业务侧先冻结 | 当前已映射：OUTCOME→SWEEP_FREEZE |
| SWEEP_UNFREEZE | `REF_TYPE_SWEEP` | 同上 | 1 条 **INCOME** 或 **REFUND**（解冻到账） | 当前已映射：INCOME→SWEEP_UNFREEZE |
| REBALANCE | `REF_TYPE_REBALANCE` | 再平衡批次 ID | 1 条 **INCOME** 或 **OUTCOME**，按冷热调配方向 | 当前已映射→REBALANCE |

#### 4.8 小结

- **已支持经 fund 且会写流水**：`ORDER`（EXCHANGE）、`DEPOSIT`/`DEPOSIT_CHAIN`/`DEPOSIT_C2C`/`ADMIN_RECHARGE`（充值类）、`WITHDRAW`/`WITHDRAW_UNFREEZE`、`SWEEP`、`REBALANCE`；按上表设置 refType/refId 与 instructionType 即可。
- **未支持映射、需扩展**：TRANSFER、OTC、CTC、ACTIVITY、MATCH、RED_PACKET；构造指令时先统一 refType/refId 与 2、3 节约定，待 fund 增加 refType→TransactionType 后再写流水。
- **不经 fund**：EXCHANGE_FREEZE 由下单侧冻结并写流水；其他业务若仍由各服务直接改钱包，可不发资金指令，但流水 refType/refId 仍建议与本文档一致。

---


