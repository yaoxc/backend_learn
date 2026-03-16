# 数字货币交易系统 - 后端

CEX 交易所后端项目，包含撮合引擎、行情、清算、结算、资金等模块，支持独立部署与扩展。

---

## 免责声明

1. 项目所涉及代码和中间件均来自开源社区以及社区二次开发。
2. 本项目为社区教学演示类项目，请谨慎使用，且不构成投资建议。

---

## 架构概览

```
订单入口(Kafka) → exchange 撮合 → exchange-match-result
    → market：订单/行情/推送
    → clearing：清算落库 → exchange-clearing-result
    → settlement：结算落库 → exchange-fund-instruction
    → fund：执行钱包操作（member_wallet）
```

- **exchange**：撮合引擎，消费 `exchange-order`，产出 `exchange-match-result`。
- **market**：消费 `exchange-match-result`，负责订单状态、成交明细、K 线、行情与推送。
- **clearing**：独立应用，消费 `exchange-match-result`（group=market-clearing-group），计算清算结果落库并发送 `exchange-clearing-result`。
- **settlement**：独立应用，消费 `exchange-clearing-result`，生成资金指令落库并发送 `exchange-fund-instruction`。
- **fund**：独立应用，消费 `exchange-fund-instruction`，落库后执行钱包操作（加减可用、扣冻、解冻等）。

---

## 后端启动说明

1. 使用 **docker-compose** 在本地启动所有依赖中间件及核心服务。
2. **中间件**：Redis、MySQL、MongoDB、Zookeeper、Kafka。
3. **核心服务**（docker-compose 内）：
   - **cloud**：注册中心（Eureka）
   - **uc**：用户中心（ucenter-api）
   - **exchange-api**：交易入口 API
   - **exchange**：撮合引擎
   - **market**：行情服务（订单状态、K 线、推送）
   - **wallet-core / wallet-eth / wallet-eusdt**：钱包相关服务
4. **可选独立服务**（需单独启动或自行加入 docker-compose）：
   - **clearing**：清算服务（端口 6005）
   - **settlement**：结算服务（端口 6006）
   - **fund**：资金服务（端口 6007）
5. 完整本地启动约 5～10 分钟，若卡在镜像下载可终止后重试。
6. 若遇 log4j/logback 无法写入，请根据系统修改各 `logback-spring.xml` 中的日志路径。

---

## 依赖与中间件版本（统一管理）

全项目以 **Spring Boot 2.7** 为基线，中间件与第三方库版本在**根 POM**（`pom.xml`）中**统一管理**：

- **根 POM**：`<properties>` 中定义各中间件版本变量，`<dependencyManagement>` 中声明依赖及版本；子模块**不得**再写 `<version>`，由根 POM 继承。
- **Spring 系**：Spring Boot 2.7.18、Spring Cloud 2021.0.8、Spring Kafka / Data MongoDB / Data Redis 等由 Boot 父 POM 或根 POM 的 BOM 管理。
- **数据库与连接**：MySQL 8.0.33、Druid、MongoDB（Spring Data 3.x + 新驱动）、Elasticsearch 7.17.15。
- **其他中间件**：Kafka（由 Spring Boot 管理）、OkHttp 4.9.3、Web3j 4.9.3、Fastjson、Shiro 1.4、QueryDSL 4.1.3、RxJava 1.3.8 等均在根 POM 中统一定义。

新增或升级中间件时，请在根 POM 的 `properties` 与 `dependencyManagement` 中增加/修改，子模块仅声明 `groupId`/`artifactId`。

---

## 启动命令

### Docker Compose（推荐）

在**项目根目录**执行：

```bash
# 启动
docker compose up -d

# 停止并删除卷
docker-compose down -v
```

### 脚本启停（services.sh）

本地按依赖顺序启停各服务（含钱包三件：wallet-core / wallet-eth / wallet-eusdt）：

```bash
./services.sh start all      # 按依赖顺序启动所有服务
./services.sh stop all       # 按逆序停止
./services.sh start wallet-core
./services.sh start wallet-eth
./services.sh start wallet-eusdt
```

钱包相关服务端口：**wallet-core** 6009、**wallet-eth** 7003、**wallet-eusdt** 7004。启动前需已启动 Eureka（cloud 7421）及 Kafka、MongoDB 等中间件。

---

## 模块说明

| 模块 | 说明 |
|------|------|
| **core** | 核心依赖：用户、代币、钱包等实体与基础服务，被 uc、exchange、market、fund 等依赖。 |
| **exchange-core** | 交易核心：订单/成交/清算/结算等实体与 DAO，被 exchange、market、clearing、settlement、fund 依赖。 |
| **exchange** | 撮合引擎：消费 `exchange-order`，内存订单簿撮合，产出 `exchange-match-result`。 |
| **exchange-api** | 交易入口：对外 API，接收下单/撤单等请求，写入订单并投递至 Kafka。 |
| **exchange-relay** | 订单中继（可选）：可将外部订单转发至内部 Kafka topic。 |
| **market** | 行情服务：消费 `exchange-match-result`，更新订单状态、成交明细、K 线、行情并推送前端。 |
| **clearing** | 清算服务（独立应用）：消费撮合结果，计算清算数据落库并发送 `exchange-clearing-result`。 |
| **settlement** | 结算服务（独立应用）：消费清算结果，生成资金指令落库并发送 `exchange-fund-instruction`。 |
| **fund** | 资金服务（独立应用）：消费资金指令，执行钱包加减款、扣冻、解冻等。 |
| **ucenter-api** | 用户中心：注册、登录、用户信息等，依赖 core。 |
| **cloud** | 注册中心：Eureka（已升级为 Spring Boot 2.7 + Spring Cloud 2021），供各服务注册与发现。详见 `0_docs/cloud升级改造说明.md`。 |
| **wallet-core** | 钱包业务中台：见下方「钱包相关模块」。 |
| **wallet-eth / wallet-eusdt** | 链上 RPC 服务：见下方「钱包相关模块」。 |

---

## 钱包相关模块

钱包侧分为「业务中台」和「链上 RPC」：中台不碰私钥、不连链，通过 Kafka 收事件并调用 RPC 服务；RPC 服务连接节点，负责地址、充值扫描、提币。

| 模块 | 类型 | 作用 |
|------|------|------|
| **wallet-core** | 可运行服务 | **钱包业务中台**。监听 Kafka（`member-register` / `deposit` / `withdraw` / `withdraw-notify` / `reset-member-address`），通过 RestTemplate 调用各链 RPC（如 `service-rpc-eth`、`service-rpc-usdt`）。负责用户钱包创建、充值上账、提币下发、余额与流水，不直接连链、不持私钥。 |
| **wallet-eth** | 可运行服务 | **以太坊 ETH 的 RPC 服务**。连接以太坊节点，管理 ETH 地址与 keystore；提供地址生成、余额、链高度、提币接口；EthWatcher 扫描区块发现充值后发 Kafka `deposit`，供 wallet-core 上账。 |
| **wallet-eusdt** (erc-eusdt) | 可运行服务 | **ERC20 USDT 的 RPC 服务**。连接以太坊节点 + USDT 合约，管理 USDT 地址；提供提币、地址、余额等接口；TokenWatcher 监听合约事件发现 USDT 充值后发 Kafka `deposit`。 |
| **rpc-common** | 公共 JAR | **RPC 公共库**。实体（Account、Deposit、Coin、Contract 等）、DepositEvent、AccountService、DepositService、Watcher 基类、Mongo/Kafka 配置等，被 eth-support、eth、erc-eusdt 依赖，不单独启动。 |
| **eth-support** | 公共 JAR | **以太坊能力库**。EthService、EtherscanApi、EthConfig、PaymentHandler 等，依赖 rpc-common，被 wallet-eth 与 wallet-eusdt 依赖，不单独启动。 |

关系简图：

```
                ┌─────────────────────────────────────────┐
                │           wallet-core (业务中台)          │
                │  Kafka 事件 + 调 RPC 接口，不碰私钥/链    │
                └──────────────┬──────────────────────────┘
                               │ RestTemplate 调用
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
     ┌────────────────┐ ┌────────────────┐  (其他链 RPC…)
     │  wallet-eth    │ │ wallet-eusdt   │
     │  (ETH RPC 服务) │ │ (USDT RPC 服务) │
     └────────┬───────┘ └────────┬───────┘
              └────────┬─────────┘
                       ▼
              ┌────────────────────┐
              │   eth-support      │  ← 公共 JAR（以太坊逻辑）
              └─────────┬──────────┘
                        ▼
              ┌────────────────────┐
              │   rpc-common       │  ← 公共 JAR（实体/服务/配置）
              └────────────────────┘
```

---

## 中间件与存储

| 中间件 | 用途 |
|--------|------|
| **MySQL** | 结构化数据：用户、订单、钱包、清算/结算结果等。 |
| **Redis** | 缓存：Session、代币对等高频数据。 |
| **MongoDB** | 日志与列表：登录日志、交易信息等，供快速查询。 |
| **Kafka** | 消息队列：订单、撮合结果、清算结果、资金指令等 topic。 |

---

## 补充说明

1. 首次启动时 Docker 会初始化 MySQL 并执行 `sql/init` 下脚本；基础数据仅首次初始化。若需重置，可删除 `sql/data` 后重启相关容器。
2. 若需本地运行 **clearing / settlement / fund** 或 **wallet-core / wallet-eth / wallet-eusdt**，可优先使用根目录 `./services.sh start <服务名>`；或手动执行例如：
   ```bash
   mvn -pl clearing spring-boot:run -Dspring-boot.run.profiles=dev
   mvn -pl settlement spring-boot:run -Dspring-boot.run.profiles=dev
   mvn -pl fund spring-boot:run -Dspring-boot.run.profiles=dev
   mvn -pl wallet/wallet-core spring-boot:run -DskipTests
   mvn -pl wallet/eth spring-boot:run -DskipTests
   mvn -pl wallet/erc-eusdt spring-boot:run -DskipTests
   ```
   并保证各模块配置中的 MySQL、Kafka、Eureka、MongoDB 等地址与当前环境一致（本地一般为 `localhost`）。
