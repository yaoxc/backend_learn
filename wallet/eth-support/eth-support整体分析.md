# 项目文件功能分析与业务流程

## 一、文件功能及项目作用分析

### 1. `eth-support/pom.xml`
- **功能**：Maven项目配置文件，定义项目依赖、构建规则及版本信息。
- **核心依赖**：
    - Spring Boot基础组件（`spring-boot-starter`）
    - Web3j（以太坊Java开发库）
    - JSON-RPC客户端（`jsonrpc4j`）
    - HTTP客户端（`unirest-java`）
- **作用**：管理项目依赖，确保各组件版本兼容，指定Java编译版本为1.8。

### 2. `EthConfig.java`
- **功能**：Spring配置类，负责创建核心Bean。
- **核心Bean**：
    - `Web3j`：以太坊节点连接客户端，配置超时参数和自定义HTTP客户端
    - `EtherscanApi`：绑定Etherscan API配置
    - `JsonRpcHttpClient`：JSON-RPC通信客户端
- **作用**：通过`@ConditionalOnProperty`实现条件化Bean创建，根据配置动态初始化组件。

### 3. `Payment.java`
- **功能**：交易支付实体类，封装转账相关信息。
- **核心字段**：交易ID、凭证信息、接收地址、金额、Gas参数等
- **作用**：使用Lombok的`@Builder`模式简化对象构建，作为交易数据载体在各服务间传递。

### 4. `PaymentHandler.java`
- **功能**：ETH/Token转账处理核心服务。
- **核心能力**：
    - 同步/异步转账处理（`transferEth`/`transferToken`）
    - 交易状态定时检查（`@Scheduled`任务）
    - 交易结果Kafka通知（`notify`方法）
- **作用**：实现交易签名、发送及状态跟踪，处理交易队列确保- **关键逻辑**：
    - 使用任务LinkedList维护待处理交易队列
    - 通过定时任务轮询处理队列和检查交易状态

### 5. `EtherscanApi.java`
- **功能**：Etherscan区块链浏览器API客户端。
- **核心接口**：
    - 发送原始交易（`sendRawTransaction`）
    - 检查事件日志（`checkEventLog`）
- **作用**：作为以太坊节点的补充，提供交易广播和链上数据查询能力。

### 6. `EthService.java`
- **功能**：以太坊核心业务服务，封装钱包管理和交易操作。
- **核心能力**：
    - 钱包创建与加载（`createNewWallet`）
    - 余额查询与同步（`getBalance`/`syncAddressBalance`）
    - 转账与提现处理（`transfer`/`withdraw`）
    - Gas费计算（`getGasPrice`/`getMinerFee`）
- **作用**：对外提供业务接口，协调钱包、交易、账户等模块。

### 7. 其他文件
- `EthConfig解析.md`：解释`Web3j` Bean创建及`Coin`对象注入原理
- `.gitignore`：版本控制忽略配置，排除编译产物和IDE配置

## 二、组件协作关系

1. **配置依赖链**：
   ```
   application.yml → CoinConfig → Coin对象 → EthConfig → Web3j/EtherscanApi
   ```

2. **核心服务调用链**：
   ```
   EthService → PaymentHandler → Web3j/EtherscanApi
   ```

3. **数据流转**：
   ```
   业务请求 → EthService创建Payment对象 → PaymentHandler处理交易 → 
   Web3j发送到以太坊网络 → EtherscanApi补充广播 → 
   定时任务检查状态 → Kafka通知结果
   ```

## 三、业务流程图

```
┌─────────────────────────────────────────────────────────────────────┐
│                        客户端请求入口                               │
│ （发起转账/提现请求，携带接收地址、金额、同步/异步标识等参数）       │
└─────────────────────────────────────┬─────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        EthService 处理层                           │
├─────────────────────────────────────────────────────────────────────┤
│ 1. 加载钱包：                                                       │
│    - 提现钱包：通过 keystore 文件路径+密码加载（getResourceAsFile）   │
│    - 用户钱包：调用 AccountService 查询账户信息获取 keystore 文件     │
│ 2. 校验处理：                                                       │
│    - 余额校验（getBalance/tokenBalance 检查余额是否充足）             │
│    - 参数验证（接收地址格式、金额合法性等）                           │
│ 3. 交易分发：                                                       │
│    - 同步处理：直接调用 PaymentHandler 执行交易                       │
│    - 异步处理：调用 transferEthAsync/transferTokenAsync 加入任务队列  │
└───┬───────────────┬───────────────────────┬───────────────────────┬─┘
    │               │                       │                       │
    ▼               ▼                       ▼                       ▼
┌─────────────┐ ┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│  ETH转账    │ │ Token转账   │       │ 同步执行    │       │ 异步入队    │
└──────┬──────┘ └──────┬──────┘       └──────┬──────┘       └──────┬──────┘
       │               │                       │                       │
       └───────────────┼───────────────────────┼───────────────────────┘
                       │                       │
                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     PaymentHandler 核心处理层                       │
├─────────────────────────────────────────────────────────────────────┤
│ 1. 交易构建：                                                       │
│    - ETH：创建 EtherTransaction（包含 nonce、gasPrice、gasLimit 等）  │
│    - Token：通过 Function 编码 transfer 方法，生成 data 字段         │
│ 2. 交易签名：                                                       │
│    - 使用 Credentials 对 RawTransaction 签名（TransactionEncoder）    │
│ 3. 交易发送：                                                       │
│    - 调用 Web3j.ethSendRawTransaction 发送到以太坊节点               │
│    - 可选：通过 EtherscanApi.sendRawTransaction 补充广播             │
│ 4. 异步任务调度：                                                   │
│    - doJob（30s 一次）：从 LinkedList 队列提取任务执行               │
└─────────────────────────────────────┬─────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        交易状态跟踪                                │
├─────────────────────────────────────────────────────────────────────┤
│ 1. 记录 txid：交易发送成功后保存交易哈希                            │
│ 2. 定时检查（checkJob，30s 一次）：                                │
│    - 调用 EthService.isTransactionSuccess 查询状态                  │
│    - 成功（区块确认且状态为 0x1）：通过 Kafka 发送成功通知           │
│    - 失败/超时（>100 次检查）：通过 Kafka 发送失败通知               │
│ 3. 任务清理：通知完成后清空当前任务                                │
└─────────────────────────────────────────────────────────────────────┘
```

### 核心组件交互说明：
1. **配置层**：`EthConfig` 初始化 `Web3j`、`EtherscanApi` 等 Bean，通过 `Coin` 对象注入配置参数（RPC 地址、gas 限制等）
2. **数据封装**：`Payment` 类（Lombok @Builder）封装交易关键信息（签名凭证、地址、金额、gas 参数等）
3. **外部依赖**：
    - `Web3j`：与以太坊节点交互的核心库（发送交易、查询状态等）
    - `EtherscanApi`：提供交易补充广播和事件日志查询功能
    - `KafkaTemplate`：发送交易结果通知（主题 `withdraw-notify`）
4. **关键机制**：
    - 异步队列避免并发 nonce 冲突
    - 双重发送（节点 + Etherscan）提高交易成功率
    - 定时任务轮询确保交易状态最终一致性
---

## 四、关键业务场景说明

1. **ETH转账流程**：
    - 通过`EthService.transfer`接收请求
    - `PaymentHandler`构建ETH转账交易（`RawTransaction.createEtherTransaction`）
    - 签名后通过`web3j.ethSendRawTransaction`发送
    - 定时检查交易状态直至确认或超时

2. **Token转账流程**：
    - 与ETH转账类似，但使用`Function`构建ERC20转账方法调用
    - 通过`FunctionEncoder`编码交易数据
    - 发送到Token合约地址而非直接转账

3. **交易状态跟踪**：
    - 每30秒执行`checkJob`检查当前交易
    - 通过`EthService.isTransactionSuccess`验证交易状态
    - 达到最大检查次数（100次）后标记为失败

4. **异步任务处理**：
    - 交易加入`LinkedList`队列
    - 定时任务`doJob`从队列提取任务处理
    - 确保单线程处理避免nonce冲突