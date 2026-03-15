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

## 八、方式二：core 编译与 ucenter-api 本机启动

为支持本机直接 `mvn -pl ucenter-api spring-boot:run`，对 **core** 做了以下改造（并在代码中加了改造注释）：

1. **QueryDSL Q 类生成**  
   - 原 `apt-maven-plugin` 与 QueryDSL 4.x 存在二进制不兼容（NoClassDefFoundError / NoSuchMethodError）。  
   - 改为：用 **maven-resources-plugin** 将 `entity` 包复制到 `target/entity-src`，再用 **maven-compiler-plugin** 仅对该目录执行 `proc:only` 与 `annotationProcessorPaths`（querydsl-apt + querydsl-jpa），将 Q 类生成到 `target/generated-sources/java`，最后用 **build-helper-maven-plugin** 将该目录加入源路径。详见 `core/pom.xml` 内注释。

2. **Spring Data JPA**  
   - `QueryDslPredicateExecutor` 改为 **QuerydslPredicateExecutor**（Spring Data 2.x 重命名），已在 BaseDao、各 Dao 及 RewardActivitySettingService 的 import 处加注释。

3. **Bean Validation**  
   - `@NotBlank` / `@NotEmpty` 由 `org.hibernate.validator.constraints` 改为 **javax.validation.constraints**（Bean Validation 2），并在 core 中增加 **spring-boot-starter-validation**、**dom4j** 依赖；实体类 import 处已加注释。

4. **Spring Session**  
   - `SmartHttpSessionStrategy` 由实现 `HttpSessionStrategy` 改为实现 **HttpSessionIdResolver**（Cookie/Header 使用 `CookieHttpSessionIdResolver`、`HeaderHttpSessionIdResolver`），类上已加升级说明注释。  
   - ucenter-api / exchange-api 的 `HttpSessionConfig` 中 Bean 由 `httpSessionStrategy()` 改为 **httpSessionIdResolver()**，并加注释。

5. **其他**  
   - `Decimal128ToBigDecimalConverter` 移除未使用的 `com.mongodb.Mongo` import，并加注释。  
   - 根 POM 的 **spring-session-core** 版本为 2.6.3（与 Boot 2.7 兼容）。

本机启动 ucenter-api：先 `mvn install -pl core,exchange-core,ucenter-api -am -DskipTests`，再 `mvn -pl ucenter-api spring-boot:run -Dspring-boot.run.profiles=dev -Deureka.client.service-url.default-zone=http://localhost:7421/eureka/`（需本地 MySQL、Redis、Mongo、Kafka 或改 dev 配置中的地址）。

---

## 九、Spring Data 2.x API 适配（core 模块）

为与 Spring Data 2.x / JpaRepository 兼容，core 中做了以下修改，**关键处已加「升级说明」注释**：

| 改造点 | 原因 | 修改方式 |
|--------|------|----------|
| **findOne(ID)** | 2.x 移除 CrudRepository.findOne(ID) | 改为 **findById(id).orElse(null)** |
| **findOne(Predicate)** | QuerydslPredicateExecutor.findOne 返回 Optional\<T\> | 调用处加 **.orElse(null)**（如 MemberWalletService、LegalWalletRechargeService、LegalWalletWithdrawService） |
| **Sort / PageRequest 构造** | 2.x 中构造函数改为包内或 protected | 使用 **Sort.by(orders)**、**Sort.by(Direction, String...)**、**PageRequest.of(page, size)** / **PageRequest.of(page, size, sort)** |
| **Sort.Order(String)** | 2.x 不再支持单参构造 | 改为 **Sort.Order(Sort.Direction.ASC, property)**（Criteria） |
| **delete(ID)** | 部分场景 2.x 需按实体删除 | 使用 **deleteById(id)**，或先 **findById(id).orElse(null)** 再 **delete(entity)**（Department、SysAdvertise、InitPlate、SysHelp 等） |
| **Dao 自定义 findById** | 与 CrudRepository.findById 返回 Optional 冲突 | MemberInviteStasticDao / MemberInviteStasticRankDao 的 findById 改名为 **findOneById**，调用处同步修改 |

涉及文件：PageModel、Criteria、TopBaseService、各 Service（如 AnnouncementService、DepartmentService、SysAdvertiseService、OtcCoinService、CoinService、SysPermissionService、SysRoleService、InitPlateService、SysHelpService）、MemberInviteStasticDao / MemberInviteStasticRankDao、MemberInviteStasticService、MemberWalletService、LegalWalletRechargeService、LegalWalletWithdrawService 等。具体见代码内「升级说明」注释。

---

## 十、与根 POM 的关系（当前）

- 根 POM 的 parent 为 **spring-boot-starter-parent 2.7.18**，`<modules>` 包含 cloud 等所有模块。
- **cloud** 的父 POM 为 **bitrade-parent**，与其余模块一致，Spring Boot 与 Spring Cloud 版本由根 POM 与 BOM 统一管理。

---

## 十一、core 模块 Q 类与编译插件（简要）

- **Q 类**（QMember、QOrder 等）：由 QueryDSL 在编译期根据 JPA 实体自动生成，不提交仓库。core 使用 **querydsl-apt（classifier=jpa）** 在 compile 阶段生成到 `target/generated-sources/java`，并通过 **build-helper-maven-plugin** 将该目录加入源码路径。
- **Lombok**：在 maven-compiler-plugin 的 **annotationProcessorPaths** 中显式加入 Lombok（版本 1.18.38），以兼容 JDK 17+ 并避免与 QueryDSL 处理器冲突；同时 1.18.38 可避免在 JDK 24+ 上出现 TypeTag 相关错误。
- **endPosTable already set（JDK 8）**：在 JDK 8 下，Lombok 与 QueryDSL 同时做注解处理时可能触发 javac 缺陷 JDK-8067747（Java 9 已修复）。core 已做缓解：**fork=true**（独立 JVM 编译）、**generate-sources 阶段清空 target/generated-sources/java**。若仍报错，请先执行 **mvn clean compile -pl core** 或使用 **Java 11+** 编译。
