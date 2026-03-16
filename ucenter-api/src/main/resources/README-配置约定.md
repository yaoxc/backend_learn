# Spring Boot 多环境配置约定（ucenter-api）

## 1. 约定概要

- **不依赖 Maven profile 切换资源目录**，所有环境配置放在同一 `src/main/resources` 下，通过 **Spring 的 `spring.profiles.active`** 切换。
- 使用 **`application.properties` + `application-{profile}.properties`** 的命名与加载顺序，与 exchange-api 改造一致。

## 2. 文件命名与加载顺序

| 文件 | 说明 |
|------|------|
| `application.properties` | 公共配置 + 默认激活的 profile（dev），**始终加载**。 |
| `application-dev.properties` | dev 环境专用。 |
| `application-test.properties` | test 环境占位。 |
| `application-prod.properties` | prod 环境占位。 |

加载顺序：先 `application.properties`，再 `application-{profile}.properties`，**后加载的覆盖先加载的**。

## 3. 激活方式

- **配置文件**：`application.properties` 中 `spring.profiles.active=dev`。
- **VM 参数**：`-Dspring.profiles.active=test`。
- **环境变量**：`SPRING_PROFILES_ACTIVE=prod`。

## 4. 本模块当前结构

- `application.properties` — 公共 + 默认 dev + Eureka 7421（kebab+camelCase 双写）。
- `application-dev.properties` — 开发环境（端口 6001、context-path /uc、本地 MySQL/Redis 等）。
- `application-test.properties` — 测试环境占位。
- `application-prod.properties` — 生产环境占位（Docker 主机名 redis、mysql-db、kafka、mongo、cloud）。
- `dev/`、`test/`、`prod/` 下保留 **i18n、templates、logback** 等，主配置统一用根目录的 `application-{profile}.properties`。
