# Spring Boot 多环境配置约定（exchange-api）

## 1. 约定概要

- **不依赖 Maven profile 切换资源目录**，所有环境配置放在同一 `src/main/resources` 下，通过 **Spring 的 `spring.profiles.active`** 切换。
- 使用 **`application.properties` + `application-{profile}.properties`** 的命名与加载顺序，符合 Spring Boot 官方约定。

## 2. 文件命名与加载顺序

| 文件 | 说明 |
|------|------|
| `application.properties` | 公共配置 + 默认激活的 profile（如 `spring.profiles.active=dev`），**始终加载**。 |
| `application-dev.properties` | dev 环境专用，仅当 `spring.profiles.active` 包含 `dev` 时加载，**覆盖**同名 key。 |
| `application-test.properties` | test 环境专用。 |
| `application-prod.properties` | prod 环境专用。 |

加载顺序：先 `application.properties`，再 `application-{profile}.properties`，**后加载的覆盖先加载的**。

## 3. 激活方式

任选其一即可：

- **配置文件**：在 `application.properties` 里写 `spring.profiles.active=dev`。
- **VM 参数**：`-Dspring.profiles.active=dev`。
- **环境变量**：`SPRING_PROFILES_ACTIVE=dev`。
- **命令行**（`java -jar`）：`--spring.profiles.active=dev`。

## 4. 与 Maven profile 的区别

- **Maven profile**：决定构建时拷贝哪些目录（如只拷贝 `dev/`），易导致“打包后只有一份配置”、IDE 与命令行行为不一致。
- **Spring profile**：同一套 classpath 下多份 `application-{profile}.properties`，运行时通过 `spring.profiles.active` 选择，**推荐**作为多环境切换方式。

本模块已改为仅用 Spring profile，不再用 Maven profile 替换 resources。

## 5. 本模块当前结构

- `application.properties` — 公共 + 默认 dev。
- `application-dev.properties` — 本地/开发（端口 6003、Eureka 7421、本地 MySQL/Redis/Kafka 等）。
- `application-test.properties` — 测试环境占位，可按需补全。
- `application-prod.properties` — 生产环境占位，可按需补全。
- `dev/`、`test/`、`prod/` 下仅保留 **i18n、logback** 等非主配置，主配置统一用根目录的 `application-{profile}.properties`。
