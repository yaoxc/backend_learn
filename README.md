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

## 启动命令

在**项目根目录**执行：

```bash
# 启动
docker compose up -d

# 停止并删除卷
docker-compose down -v
```

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
2. 若需本地运行 **clearing / settlement / fund**，在启动中间件及其他服务后，在项目根目录执行例如：
   ```bash
   mvn -pl clearing spring-boot:run -Dspring-boot.run.profiles=dev
   mvn -pl settlement spring-boot:run -Dspring-boot.run.profiles=dev
   mvn -pl fund spring-boot:run -Dspring-boot.run.profiles=dev
   ```
   并保证各模块 `dev/application.properties` 中的 MySQL、Kafka 等地址与 docker 网络一致（如使用 `localhost` 或宿主机 IP 需相应修改配置）。
