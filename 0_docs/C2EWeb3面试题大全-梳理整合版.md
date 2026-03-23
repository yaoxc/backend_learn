# C2E Web3 / CEX 面试题梳理整合版（流程与要点加强）

> 来源：`C2EWeb3面试题大全（最新）.docx`  
> 本文：在「归类」基础上补充 **主流程、关键字段/模块、易错点**；**「当前业务」** 指仓库 `backend_learn`。

---

## 使用说明

| 符号 | 含义 |
|------|------|
| 📎 | 可对照本仓库路径或类名 |
| 流程 | 按时间顺序可口述 |

---

## 附录 A：本仓库「现货下单 → 撮合 → 清算」主流程（面试核心）

**目的**：一条线说清 CEX 后端主链路，避免只背概念。

```
用户/API 下单
  → exchange-core：校验余额、冻结（freeze）、exchange_order 落库 TRADING
  →（可选 relay）exchange-relay：ingress topic → 内部 topic，Kafka key=symbol 保序
  → exchange：ExchangeOrderConsumer 消费订单
  → CoinTraderFactory 按 symbol 取 CoinTrader
  → CoinTrader.trade：seenOrderIds 幂等 → 内存订单簿撮合
  → flushMatchResult：MatchResult（messageId + symbol + ts + trades[] + completedOrders[]）→ Kafka exchange-match-result

  → market ExchangeTradeConsumer：
      processMatchResultIdempotentOrderOnly（messageId 幂等表）
      → 成交明细 Mongo、订单完结 MySQL、行情/推送
  → clearing ClearingMatchResultConsumer：
      ClearingService.processAndPublish → clearing_result 落库 → exchange-clearing-result
  →（配置 match.result.use.fund.pipeline=true 时）资金走清算→结算→资金，与订单域最终一致 + 对账
```

**关键信息（背字段）**：

| 环节 | 关键信息 |
|------|----------|
| 幂等（订单侧） | `orderId` 撮合内防重；`messageId` + `processed_match_result_message` 防重复消费整条 MatchResult |
| 幂等（资金侧） | `tradeId` 成交维度；清算 `messageId` / clearing_result 表 |
| 原子批 | 单条 Kafka 内 **trades + completedOrders**，下游 `processMatchResult*` 单事务内先处理成交再处理完结 |
| 顺序 | 同 symbol 建议 **同分区**；撮合 **单 symbol 单线程**（内存一致） |

📎 文档：`0_docs/用户下单：撮合完成后续总流程.md`、`用户下单：清算过程.md`

---

## 一、Web3 基础（概念 + 主信息）

### 1.1 智能合约

- **是什么**：部署在链上的字节码 + 合约账户存储；外部账户（EOA）发 **交易** 触发 `CALL`，改 **storage**、记 **log**、付 **gas**。  
- **不是什么**：链下 PDF 合同；**链上代码一般不可改**（除非代理升级模式）。  
- **主信息**：编译 Solidity → bytecode → `CREATE/CREATE2` 部署 → 得 **合约地址**；调用时 **data** = 函数选择器 + ABI 参数。

### 1.2 公链与代币

- **公链**：开放接入，自有共识与安全模型（PoW/PoS 等）；全节点同步区块与状态。  
- **代币（FT）**：多为 **ERC-20**（余额映射、Transfer 事件）；**NFT** 多为 **ERC-721/1155**。  
- **主信息**：转账 = 调合约 `transfer` 或原生币 `value`；余额用 `eth_getBalance` 或 `balanceOf`（eth_call）。

### 1.3 NFT / DeFi / Web3 / DAO（一句话 + 信息点）

| 概念 | 主流程/信息 |
|------|-------------|
| **NFT** | `mint` → tokenId 唯一；`tokenURI` 常指向 IPFS/HTTP 元数据；交易在二级市场合约或 OpenSea 类聚合。 |
| **DeFi** | 池子/借贷合约锁仓 → 用户交互 swap/borrow → 事件索引；风险：合约漏洞、预言机、清算。 |
| **Web3** | 钱包作身份、链上资产自有；DApp 通过 RPC + 签名交互。 |
| **DAO** | 提案 → 投票合约 → 金库执行；常与 **多签/MPC**、法务主体并存。 |

---

## 二、链上数据同步（原文问题 1 类 — 完整策略）

**主流程**：

1. **实时主通道**  
   - WebSocket 订阅 **newHeads / logs**（**合约地址 + topic0** 过滤）。  
   - 消息进 **Kafka**，异步消费者解析 **tx receipt / logs**，更新业务库、缓存、前端状态。

2. **延迟确认（资金安全）**  
   - 入「核心账本」前等待 **N 个确认**（链不同 N 不同）。  
   - 幂等：**`chainId + blockNumber + txHash + logIndex`**（合约多事件必带 logIndex）。

3. **回补通道**  
   - 定时 RPC：`getBlock`、`getLogs` 扫 **最近 K 个块**，与本地水位对比，补 WS 漏包、节点延迟。

4. **重组（reorg）**  
   - 同一高度 **blockHash** 与本地不一致 → 标记该高度及之后数据 **无效（软删）**，从分叉点 **重放**。  
   - 进阶：比对 **tx 列表哈希/顺序**。

5. **多节点**  
   - 主备 RPC、心跳、限流、**Batch JSON-RPC** 减压。

📎 与 CEX 现货关系：链同步是 **充提/Web3 业务**；**撮合订单流** 可走中心化 Kafka，两套勿混谈。

---

## 三、CEX 撮合一致性与 Kafka（原文问题 2～5）

### 3.1 如何保证一致性（信息表 + 流程）

| 层次 | 手段 | 说明 |
|------|------|------|
| 入站顺序 | Kafka **key = symbol** | 同交易对进同分区，**分区内 FIFO**（单 consumer 消费该分区时）。 |
| 撮合内 | **orderId** 去重 | 如 `CoinTrader.seenOrderIds`，防重复进簿。 |
| 结果消费 | **messageId** | `processed_match_result_message` 插入与业务 **同事务**，重复则整条跳过。 |
| 单批语义 | **MatchResult** | `trades` 逐笔成交 + `completedOrders` 本批全成订单；**先 trades 后 completed** 在同一 `@Transactional`。 |
| 资金拆分 | **fund pipeline** | 订单/明细先落；钱包由 **清算→结算→资金** 消费指令；**最终一致**，靠幂等 + **对账**。 |
| 多实例 | 同 **group** 分区互斥 | 防双消费靠 **业务幂等**，不能只靠 Kafka。 |

### 3.2 Kafka 分区与 consumer（主信息）

- **规则**：同一 **consumer group** 内，**每个 partition 同一时刻只分配给一个 consumer**。  
- **并行度**：活跃 consumer 数 **≤ partition 数** 才有意义；**3 分区 ≈ 最多 3 个并行 consumer**。  
- **不满足「一一」**：consumer 少于分区（多对一）、多于分区（空闲）、**多 group 重复读**、rebalance 抖动。  
- **同一订单被多次处理**：**多 group**、**at-least-once 重投** 无幂等、**重复消息**、HTTP 重试 + **无分布式去重**。

### 3.3 撮合慢与 OOM（主因 + 处置）

**慢**：订单簿结构（需 **有序**）、锁竞争、逐笔调度、僵尸单、Full GC、同步 I/O 阻塞撮合线程。  
**治**：**按 symbol 分片**、内存簿、单线程 per symbol、**有界队列 + 背压**、异步落库、Profiler。  

**OOM 典型**：Kafka **无界队列** + 消费快于撮合 → 堆涨满；**停部分分区消费 / 扩容 / 有界队列**。

---

## 四、Uniswap V2 / 部署 / CREATE2（主流程）

### 4.1 三合约分工

| 合约 | 职责 |
|------|------|
| **Factory** | `createPair(tokenA,tokenB)` → **CREATE2** 部署 **Pair**；维护 **getPair** 映射。 |
| **Pair** | 储备 `reserve0/1`；**mint/burn/swap**；**恒定积** x·y=k（扣手续费后）。 |
| **Router** | 用户入口：`swapExact*`，内部 **WETH 包装**、调 Factory/Pair、**安全转账**。 |

### 4.2 部署顺序（主信息）

1. 部署 **WETH**（若用 ETH 路由）。  
2. 部署 **Factory**（feeToSetter 等参数）。  
3. 部署 **Router(WETH, Factory)**。  
4. Pair **不由你单独部署**，由 **Factory.createPair** 或 **Router 加流动性时** 创建。

### 4.3 Pair 创建流程（主信息）

- 入口：`Factory.createPair(tokenA, tokenB)`（Router 加流时会 `getPair` 没有再 create）。  
- **token0 < token1** 字典序排序，避免重复池。  
- **CREATE2**：地址 = f(部署者、`salt`、`initcode`)；**salt** 常与 **token0、token1** 绑定；**地址可预测**。

### 4.4 CREATE vs CREATE2

| | CREATE | CREATE2 |
|---|--------|---------|
| 地址依赖 | 部署者 **nonce** | **salt + initcode** |
| 预测 | 需知 nonce | **链下可算** 未来地址 |
| 用途 | 通用部署 | **工厂模式、跨链同地址**（需同 bytecode+salt） |

---

## 五、多链 / Gas / 交易 / Internal tx

### 5.1 多链对接（主信息）

- **EVM 链**：Web3j + RPC；注意 **chainId**、**finality**（L2 快确认 vs L1 挑战期）。  
- **限流**：多节点轮询、连接池、**batch eth_call**。  
- **非 EVM**（如 zkSync）：常用 **官方 SDK**，gas/账户模型不同。

### 5.2 手续费（主公式）

**Legacy**：`fee ≈ gasUsed × gasPrice`（多付的 gas 会退未用部分，按实现以节点为准）。  

**EIP-1559**：  
- 每单位 gas 有效价：`effectiveGasPrice = min(maxFeePerGas, baseFee + maxPriorityFeePerGas)`（语义上等价表述）。  
- **baseFee 销毁**；矿工/验证者拿 **(effectiveGasPrice - baseFee) × gasUsed**（小费部分）。  
- 推荐：`eth_feeHistory` 取分位数 **priority fee** + 当前 **baseFee**，再设 `maxFeePerGas` 封顶。

### 5.3 构建与上链（主流程）

1. 填 **nonce、chainId、to、value、data、gasLimit**。  
2. Legacy：`gasPrice`；EIP-1559：`maxFeePerGas`、`maxPriorityFeePerGas`，**type=0x2**。  
3. **本地签名** → **raw tx**。  
4. `eth_sendRawTransaction` → **txHash** → 等打包 → `eth_getTransactionReceipt`。

### 5.4 Internal tx（主信息）

- **receipt 顶层** 只描述 **这一笔外层交易** 的 `from/to/value`。  
- **合约内部** `CALL` 转 ETH、内部调用 **不会** 作为独立交易出现在 receipt 顶层。  
- **解析**：`debug_traceTransaction` / `trace_*`（节点开 debug）；或 **Etherscan 类 API**；ERC20 可看 **Transfer log**，原生 ETH 内部转常 **靠 trace**。  
- **原因**：协议设计，**不是因为没发事件**。

### 5.5 Solidity 0.8+

- 默认 **算术溢出回滚**；`unchecked { ... }` 关闭检查换 gas。  
- 0.8 前用 **OpenZeppelin SafeMath** 手动检查。

---

## 六、JVM / 并发 / Redis / 排障（主流程 + 主信息）

### 6.1 JVM 内存区域

| 区域 | 线程 | 存什么 |
|------|------|--------|
| **程序计数器** | 私有 | 当前字节码行号 |
| **虚拟机栈** | 私有 | 栈帧：局部变量、操作数栈、返回地址 |
| **本地方法栈** | 私有 | Native 方法 |
| **堆** | 共享 | 对象实例；**新生代**（Eden+S0+S1，Minor GC）+ **老年代**（Major/Full GC） |
| **元空间** | 共享 | 类元数据（JDK8+ 替代永久代） |

**区分**：「JVM 内存划分」≠「Java Memory Model（JMM）」；JMM 讲 **可见性、有序性、happens-before**。

### 6.2 类加载（五阶段）

加载 → 验证 → 准备（静态变量默认零值）→ 解析（符号引用→直接引用）→ 初始化（`<clinit>`）。  
**双亲委派**：先父加载器再子，保 `java.lang.*` 等核心类唯一。

### 6.3 volatile vs synchronized

| | volatile | synchronized |
|---|----------|----------------|
| 可见性 | ✅ | ✅ |
| 原子性（i++） | ❌ | ✅ |
| 互斥 | ❌ | ✅ |
| 典型场景 | 状态标志、双重检查配合锁 | 临界区、复合操作 |

### 6.4 Redis

- **命令执行**：核心路径 **单线程**（避免锁）。  
- **Redis 6+**：**网络 IO 多线程**（读/写）；**持久化子进程/线程**另说。  
- **持久化**：**RDB** 快照 vs **AOF** 日志；**maxmemory-policy** LRU/LFU 淘汰。

### 6.5 CPU 高与 OOM（排查主流程）

**CPU**：`top` 找线程 → **tid 转十六进制** 对 `jstack` → **async-profiler 火焰图**；看死循环、锁、GC 线程。  

**OOM**：看报错类型（heap / Metaspace / DirectBuffer / unable to create native thread）→ **heap dump（MAT）** → 大对象、泄漏、无界集合；**加 -Xmx、分页、有界队列**。

---

## 七、Gate / 币安类问题（流程 + 要点，加长版）

| 主题 | 主流程 / 主要信息 |
|------|-------------------|
| **用户发交易全流程** | 钱包/DApp 组交易 → **签名** → `eth_sendRawTransaction` → 节点校验 → **mempool** → 出块 → **EVM 执行**（合约从 `to` 入口执行）→ **receipt**（status、gasUsed、logs）。 |
| **Gas Price 作用** | 竞价被包含进块；Legacy 直接付 **gasPrice**；1559 拆 **baseFee（销毁）** 与 **priority**。 |
| **推荐 gas** | `eth_estimateGas`×buffer；1559 用 **`eth_feeHistory`** 看 baseFee 与 priority 分位数。 |
| **Gas Limit** | 本笔消耗 **上限**；不同合约方法 **不同**，需 **estimate**；Limit 定义 1559 前后 **一致**，变的是 **价格结构**。 |
| **模拟交易** | **`eth_call`**：节点本地执行，**不广播**、默认不提交状态（除非 trace/state override）；`estimateGas` 类似模拟估消耗。 |
| **交易进节点后** | 格式/nonce/余额校验 → 入池 → 排序打包 → 执行 → receipt。 |
| **EVM 何时跑** | **执行区块内该笔交易时**；每个 tx 一次执行上下文，合约内部再 `CALL`。 |
| **事件 vs 存储** | **链下监听/索引** → **event**；**合约逻辑依赖的状态** → **storage**；历史 → **logs 或链下库**。 |
| **线上合约 bug** | **暂停开关**、**代理升级**、迁移新池、审计与监控；不可改则 **社交披露 + 用户止损**。 |
| **call 类型** | **call** 改被调合约存储；**delegatecall** 用被调代码改 **调用方**存储；**staticcall** 只读；`transfer` **2300 gas** 限制易 OOG。 |

---

## 八、托管钱包 / 入账 / 提现 / 归集（主流程）

### 8.1 Nonce 管理

- **链上真相**：`eth_getTransactionCount(address, "pending")`（注意 pending/latest 区别）。  
- **工程**：服务内 **DB/Redis 维护下一 nonce**，发链成功后 **+1**；失败回滚或 **链上重查** 对齐，防 **nonce gap**。

### 8.2 上链失败处理

| 原因 | 处理 |
|------|------|
| gas 不足 / 价低 | 提 gas / `maxFeePerGas` 重发 |
| nonce 冲突 | 以链为准重置或补发 |
| 节点故障 | 换节点、网关负载均衡 |
| 硬分叉/升级 | 升级客户端、适配新类型 |

### 8.3 入账幂等

- **主链币**：`txHash` 唯一一条。  
- **合约多 Transfer**：**`txHash + logIndex`**（+ 合约地址、to 地址）唯一；同一 tx 多 log 必须拆开。

### 8.4 提现防重放 / 幂等（业务流）

1. 业务生成 **唯一 requestId** 落库（创建态）。  
2. **风控** 审批（可要求风控签名）。  
3. **签名机** 签 raw tx（或 MPC）。  
4. 广播；**监听 receipt** 更新完成态。  
5. 幂等：**requestId** + 链上 **(from, nonce)** 或 **txHash** 防重复出款。

### 8.5 归集

- **小额不归集**：归集收益 < **gas+租金**。  
- **动态阈值**：按当前 **gas price** 算成本，低于阈值跳过。  
- **冷热分离**：热钱包额度、大额多签/MPC。

### 8.6 多签 / MPC / TEE / KMS / HSM

| 名词 | 主要信息 |
|------|----------|
| **HSM** | 硬件里生成/签名，**私钥不导出明文**。 |
| **KMS** | 云托管密钥、**IAM、轮换、审计**。 |
| **MPC 分片** | 多方各持 **分片**，协议协同出 **合法签名**，单方不恢复全钥。 |
| **多签（链上）** | 多把地址各签，合约 **M-of-N** 验证。 |
| **TEE** | 可信执行环境，防宿主偷窥；常与 KMS 结合。 |

---

## 九、Solana / IPFS / Uniswap V3（要点）

| 主题 | 主要信息 |
|------|----------|
| **Solana SPL** | 代币余额在 **ATA**；无 ATA 要先 **创建（租金）**；与 EVM 账户模型不同。 |
| **IPFS** | **CID** 内容寻址；链上存 **ipfs://...** 或网关 URL。 |
| **Uniswap V3** | LP 选 **价格区间 [Pa,Pb]**；**tick** 离散；**集中流动性**，资本效率高于 V2 全曲线。 |

---

## 十、CEX 撮合架构（有状态 + Disruptor + Kafka）

**为何有状态**：订单簿必须 **单一写者** 才能保证 **撮合结果确定**；多副本无状态需 **强一致复制** 或 **定序总线**，复杂度高。  

**常见拆法**：**按交易对分片** → 每片 **单进程/单线程撮合** → 订单 **Kafka 按 symbol 路由** → 故障 **分区迁移 + 快照/WAL 恢复**。  

**Disruptor**：**进程内** 环形缓冲，**低延迟** 把「接单 / 撮合 / 发结果」流水线化；**不能替代** Kafka 持久化与跨机。📎 本仓库 **未用** Disruptor。  

**Kafka 重复消费**：至少一次语义下用 **`messageId` / `tradeId` / 业务唯一键** 幂等。

---

## 十一、HR / 谈薪（主信息）

- **自我介绍**：年限 + **链上栈（Solidity/RPC）** + **后端栈（Java/Kafka）** + **代表项目（CEX/钱包）** + 求职动机。  
- **了解公司**：**白皮书 / GitHub / 产品页** + 与自己经历 **1～2 个共同点**。  
- **协作**：角色 + **文档/会议节奏** + **可量化结果**（延迟、吞吐、故障次数）。  
- **谈薪**：区间 + **入职时间** + 对 **远程/强度** 的接受度；背调诚实。

---

## 十二、自检清单（面试前过一遍）

- [ ] 画得出：**下单 → Kafka → CoinTrader → MatchResult → market + clearing**  
- [ ] 说得出：**messageId**、**tradeId**、**至少一次 + 幂等**  
- [ ] Kafka：**partition 与 consumer group**、**并行度 ≤ 分区数**  
- [ ] Gas：**Legacy vs 1559**、**baseFee 销毁**  
- [ ] Internal tx：**trace vs receipt**  
- [ ] 入账幂等：**txHash + logIndex**  
- [ ] HSM / KMS / MPC 各一句定义  

---

*需要再把「链上同步」或「提现状态机」单独画成一页时序图，可说明要 Mermaid 还是表格版。*
