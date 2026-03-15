# Cloud（Eureka）升级改造说明

本文档记录 cloud 模块从 Spring Boot 1.5 / Spring Cloud Edgware 升级为 **Spring Boot 2.7 + Spring Cloud 2021** 的改造过程，便于后续维护与参考。

---

## 一、升级目标

- **Eureka 直接启动**：无需 JAXB/JAF、`--add-opens` 等兼容性补丁，任意 Java 8+ 环境均可运行。
- **控制台正常**：访问 `http://localhost:7421/` 直接返回 Eureka HTML 控制台，不再出现 XML。
- **仅升级 cloud**：其他模块（market、exchange、core 等）仍使用根 POM 的 Spring Boot 1.5.9，互不影响。

---

## 二、改造内容

### 1. POM 调整（`cloud/pom.xml`）

| 项目 | 改造前 | 改造后 |
|------|--------|--------|
| 父 POM | `bitrade-parent`（继承根 POM，Spring Boot 1.5.9） | `spring-boot-starter-parent` **2.7.18** |
| Spring Cloud | Edgware（根 POM 统一） | **2021.0.8**（cloud 的 `dependencyManagement` 中 import） |
| 依赖 | eureka-server、JAXB、JAF、mail、freemarker、rxjava 等 | 仅 **spring-cloud-starter-netflix-eureka-server**、**spring-boot-starter-test** |
| 启动参数 | JDK9+ profile 中 `--add-opens` 等 | 无，不再需要 |

cloud 不再继承根 POM 的 parent，因此自带 `groupId`、`version`，仅保留 Eureka Server 与测试依赖。

### 2. 删除的补丁与代码

- **JAXB / JAF**：原为解决 Java 9+ 下 Eureka 的 XML 序列化问题，Spring Boot 2.7 已内置兼容，故移除。
- **EurekaStateListener**：依赖 mail、freemarker，用于事件通知；已删除，若需通知功能可后续按需加回并配置。
- **EurekaDashboardFilter**：原为强制根路径返回 HTML，2.7 下 Eureka 自带控制台已正常，故删除。
- **本地 Eureka 模板**：`templates/eureka/status.ftl`、`header.ftl`、`navbar.ftl` 等，已删除。
- **JDK9+ profile 与 `jvmArguments`**：不再需要 `--add-opens` 等参数。

### 3. 配置更新（`cloud/src/main/resources/application.properties`）

- 端口仍为 **7421**，应用名 `spring.application.name=eureka-server`。
- 单机模式：`eureka.client.register-with-eureka=false`、`eureka.client.fetch-registry=false`。
- 属性名适配 2.x：如 `eureka.client.service-url.default-zone`（kebab-case）。
- 健康检查：`management.endpoints.web.exposure.include=health`，`management.endpoint.health.show-details=always`。

### 4. 启动类

`CloudApplication.java` 未改，仍为 `@EnableEurekaServer` + `@SpringBootApplication`。

---

## 三、验证结果

- 使用 **Java 8 或 Java 17+** 执行 `mvn -pl cloud spring-boot:run`，均可正常启动（Spring Boot 2.7.18）。
- 访问 **http://localhost:7421/** 返回 **200**，内容为 Eureka HTML 控制台（`<!doctype html>...`），不再返回 StatusInfo XML。

---

## 四、使用方式

在项目根目录执行：

```bash
mvn -pl cloud spring-boot:run
```

浏览器打开：**http://localhost:7421/** 即可查看 Eureka 控制台。

若端口 7421 被占用，可先结束占用进程再启动：

```bash
# 查看占用进程
lsof -i :7421

# 结束进程（将 <PID> 替换为实际进程号）
kill <PID>
```

或修改 `cloud/src/main/resources/application.properties` 中的 `server.port` 为其他端口（如 7422）。

---

## 五、与根 POM 的关系

- 根 POM（`pom.xml`）的 `<modules>` 仍包含 `cloud`，在根目录执行 `mvn install` 时会编译、打包 cloud。
- cloud 的父 POM 为 `spring-boot-starter-parent` 2.7.18，**不继承**根 POM 的 `bitrade-parent`，因此依赖与版本与其它模块隔离，仅 cloud 使用 Spring Boot 2.7。
