# Spring Boot 多环境配置约定（exchange-relay）

## 1. 约定概要

- **不依赖 Maven profile 切换资源目录**，所有环境配置放在同一 `src/main/resources` 下，通过 **Spring 的 `spring.profiles.active`** 切换。
- 使用 **`application.properties` + `application-{profile}.properties`** 的命名与加载顺序，符合 Spring Boot 官方约定。

## 2. 文件命名与加载顺序

| 文件 | 说明 |
|------|------|
| `application.properties` | 公共配置 + 默认激活的 profile（如 `spring.profiles.active=dev`），**始终加载**。 |
| `application-dev.properties` | dev 环境专用（端口 6013、本地 MySQL/Kafka/Eureka 等）。 |
| `application-test.properties` | test 环境占位，可按需补全。 |
| `application-prod.properties` | prod 环境占位，可按需补全。 |

加载顺序：先 `application.properties`，再 `application-{profile}.properties`，**后加载的覆盖先加载的**。

## 3. 激活方式

- **配置文件**：在 `application.properties` 里写 `spring.profiles.active=dev`。
- **VM 参数**：`-Dspring.profiles.active=dev`。
- **命令行（mvn）**：**不要加 `-Pdev`**（本模块已不用 Maven profile）。推荐：
  - `mvn -pl exchange-relay clean spring-boot:run`（默认加载 dev；首次或改配置后建议带 `clean`，避免旧资源导致「profile dev 不存在」）。
  - 切换环境：`mvn -pl exchange-relay spring-boot:run -Dspring-boot.run.profiles=test`。

## 4. 本模块当前结构

- `application.properties` — 公共 + 默认 dev + Eureka 根配置（kebab 与 camelCase 双写）。
- `application-dev.properties` — 本地/开发（DataSource、Kafka、ES 占位等）。
- `application-test.properties` / `application-prod.properties` — 占位，可按需补全。
- 原 `dev/`、`test/`、`prod/` 下主配置已迁至根目录上述文件；子目录可保留其他资源（如 logback）或留空。

## 5. 常见问题

- **「The requested profile dev could not be activated because it does not exist」**：多因曾用 `-Pdev` 构建导致 `target/classes` 只有旧 dev 内容、缺少 `application-dev.properties`。解决：`mvn -pl exchange-relay clean spring-boot:run`（不要加 `-Pdev`）。
- **「Port 6013 was already in use」**：先停掉占用端口的进程（如之前的 relay 实例），或在本模块的 `application-dev.properties` 中修改 `server.port`。
