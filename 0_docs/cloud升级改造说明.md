# Cloud（Eureka）与全项目 Spring Boot 统一升级说明

本文档记录 **全项目** 从 Spring Boot 1.5 / Spring Cloud Edgware 统一升级为 **Spring Boot 2.7 + Spring Cloud 2021** 的改造过程，便于后续维护与参考。

---

## 一、升级目标

- **版本统一**：根 POM（bitrade-parent）与所有模块统一使用 **Spring Boot 2.7.18**、**Spring Cloud 2021.0.8**，由父 POM 与 BOM 统一管理版本。
- **Eureka 直接启动**：无需 JAXB/JAF、`--add-opens` 等补丁，任意 Java 8+ 环境均可运行；访问 `http://localhost:7421/` 为 HTML 控制台。
- **cloud 与父 POM 一致**：cloud 恢复继承 `bitrade-parent`，不再单独使用 spring-boot-starter-parent。

---

## 二、根 POM（`pom.xml`）改造

| 项目 | 改造前 | 改造后 |
|------|--------|--------|
| parent | spring-boot-starter-parent **1.5.9** | spring-boot-starter-parent **2.7.18** |
| Spring Cloud | Edgware.RELEASE | **2021.0.8** |
| Java | project.build.jdk_version=1.8 | java.version=1.8 |
| Spring Boot 依赖版本 | 各 starter 显式写 `${spring-boot_version}` | 由 parent 2.7.18 统一管理，不再写版本 |
| Druid | 1.1.9 | **1.2.20**（与 2.7 兼容） |
| MySQL 驱动 | 8.0.11 | **8.0.33** |
| JUnit | 4.12 | 4.13.2 |
| dubbo | 无版本属性 | dubbo_version=2.7.15 |

根 POM 的 `dependencyManagement` 中仅保留 Spring Cloud BOM 的 import，Spring Boot 相关 starter 不再写版本号，由 parent 统一管理。

---

## 三、Cloud 模块（`cloud/pom.xml`）

- **父 POM**：改回 **bitrade-parent**（与根 POM 一致）。
- **依赖**：仅保留 `spring-cloud-starter-netflix-eureka-server`、`spring-boot-starter-test`，版本由父 POM 与 Spring Cloud BOM 管理。
- 不再需要独立的 parent、dependencyManagement、properties。

---

## 四、Eureka 客户端与负载均衡

- **依赖名**：所有使用 Eureka 客户端的模块，由 `spring-cloud-starter-eureka` 改为 **spring-cloud-starter-netflix-eureka-client**（core、exchange、market、ucenter-api、exchange-relay、wallet/eth、wallet/erc-eusdt、wallet-core）。
- **Ribbon 替换**：`spring-cloud-starter-ribbon` 改为 **spring-cloud-starter-loadbalancer**（ucenter-api、market、wallet-core）。Spring Cloud 2021 推荐使用 LoadBalancer。

---

## 五、配置项适配 Spring Boot 2.x

以下配置已在各模块的 `application.properties` 中按 2.x 规范调整：

| 原配置（1.x） | 2.x 配置 |
|---------------|----------|
| server.context-path | **server.servlet.context-path** |
| eureka.client.serviceUrl.defaultZone | **eureka.client.service-url.default-zone** |
| eureka.client.registerWithEureka / fetchRegistry | **eureka.client.register-with-eureka** / **fetch-registry** |
| spring.http.multipart.maxFileSize / maxRequestSize | **spring.servlet.multipart.max-file-size** / **max-request-size** |
| management.security.enabled、security.user.* | 移除或改为 **spring.security.user.***、**management.endpoints.web.base-path** 等 |

涉及文件：ucenter-api、market、exchange、exchange-api、exchange-relay、wallet/eth、wallet/erc-eusdt、wallet-core、test、core 测试配置等。

---

## 六、Wallet 子工程

- `wallet/pom.xml` 中 `spring-cloud.version` 由 Edgware.RELEASE 改为 **2021.0.8**，与根 POM 一致。

---

## 七、使用方式

- **启动 Eureka（cloud）**：在项目根目录执行  
  `mvn -pl cloud spring-boot:run`  
  浏览器访问：**http://localhost:7421/**  
- **端口占用**：`lsof -i :7421` 查看进程，`kill <PID>` 结束，或修改 `cloud/.../application.properties` 中的 `server.port`。
- **全量编译**：`mvn clean install -DskipTests`（建议使用 Java 8 或 11 执行，以通过全部模块）。

---

## 八、与根 POM 的关系（当前）

- 根 POM 的 parent 为 **spring-boot-starter-parent 2.7.18**，`<modules>` 包含 cloud 等所有模块。
- **cloud** 的父 POM 为 **bitrade-parent**，与其余模块一致，Spring Boot 与 Spring Cloud 版本由根 POM 与 BOM 统一管理。
