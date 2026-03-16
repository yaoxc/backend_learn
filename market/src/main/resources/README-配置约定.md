# Spring Boot 多环境配置约定（market）

## 1. 约定概要

- **不依赖 Maven profile 切换资源目录**，所有环境配置放在同一 `src/main/resources` 下，通过 **Spring 的 `spring.profiles.active`** 切换。
- 使用 **`application.properties` + `application-{profile}.properties`** 的命名与加载顺序，符合 Spring Boot 官方约定。

## 2. 文件命名与加载顺序

| 文件 | 说明 |
|------|------|
| `application.properties` | 公共配置 + 默认激活的 profile（如 `spring.profiles.active=dev`），**始终加载**。 |
| `application-dev.properties` | dev 环境专用（端口 6004、context-path /market、本地 MySQL/Mongo/Kafka/Redis/Eureka 等）。 |
| `application-test.properties` | test 环境占位，可按需补全。 |
| `application-prod.properties` | prod 环境占位，可按需补全。 |

加载顺序：先 `application.properties`，再 `application-{profile}.properties`，**后加载的覆盖先加载的**。

## 3. 激活方式

- **配置文件**：在 `application.properties` 里写 `spring.profiles.active=dev`。
- **VM 参数**：`-Dspring.profiles.active=dev`。
- **命令行**（mvn）：`mvn -pl market spring-boot:run`（默认 dev），或 `-Dspring-boot.run.profiles=test`。

## 4. 本模块当前结构

- `application.properties` — 公共 + 默认 dev + Eureka 根配置（kebab 与 camelCase 双写）。
- `application-dev.properties` — 本地/开发（DataSource、Mongo、Kafka、Redis、Netty、ES 占位等）。
- `application-test.properties` / `application-prod.properties` — 占位，可按需补全。
- `dev/`、`test/`、`prod/` 下保留 **ehcache.xml、logback-spring.xml、QuoteMessage.proto** 等非主配置；主配置统一用根目录的 `application-{profile}.properties`。
