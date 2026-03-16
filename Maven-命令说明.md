# Maven 常用命令说明

在项目根目录（`backend_learn`）下执行。`<module>` 表示子模块名，如 `exchange-api`、`ucenter-api`、`core`。

---

## 1. clean（清理）

删除该模块的 `target/`，去掉旧编译和打包结果。

```bash
# 只清理指定模块
mvn -pl <module> clean

# 示例：只清理 exchange-api
mvn -pl exchange-api clean

# 清理整个工程所有模块
mvn clean
```

---

## 2. 编译

只编译，不运行、不打包。

```bash
# 只编译指定模块（会先编译其依赖，如 core）
mvn -pl <module> compile

# 示例
mvn -pl exchange-api compile

# 编译整个工程
mvn compile
```

**重新编译**：先 clean 再 compile。

```bash
mvn -pl <module> clean compile
```

---

## 3. 运行（Spring Boot）

用 Spring Boot 插件启动应用（不先打 jar，直接运行）。

```bash
# 只运行指定模块
mvn -pl <module> spring-boot:run

# 示例：运行 exchange-api（默认 dev 配置）
mvn -pl exchange-api spring-boot:run

# 指定 Spring 环境
mvn -pl exchange-api spring-boot:run -Dspring-boot.run.profiles=prod

# wallet 子模块在根 pom 中路径为 wallet/xxx，须用路径形式（不能写 wallet-core）
mvn -pl wallet/wallet-core spring-boot:run
mvn -pl wallet/eth spring-boot:run
mvn -pl wallet/erc-eusdt spring-boot:run
```

---

## 4. 测试

运行单元测试 / 集成测试。

```bash
# 只测指定模块
mvn -pl <module> test

# 示例
mvn -pl exchange-api test

# 先 clean 再测试
mvn -pl <module> clean test
```

若模块里配置了 `maven-surefire-plugin` 的 `skip=true`，测试会被跳过（如本项目中部分模块）。

---

## 5. 一条命令：清理 + 编译 + 运行

最常用的「干净构建并启动」：

```bash
mvn -pl <module> clean spring-boot:run
```

Maven 会按顺序执行：`clean` → 编译（含依赖）→ `spring-boot:run`。  
**不会**单独执行 `test`，除非你在命令里加上 `test`。

示例：

```bash
# exchange-api：清理后编译并运行（使用 application.properties 里默认的 dev）
mvn -pl exchange-api clean spring-boot:run

# ucenter-api
mvn -pl ucenter-api clean spring-boot:run
```

---

## 6. 其他常用

| 目的           | 命令 |
|----------------|------|
| 打包（jar）    | `mvn -pl <module> package` |
| 打包并跳过测试 | `mvn -pl <module> package -DskipTests` |
| 清理 + 打包    | `mvn -pl <module> clean package` |
| 只编译不测     | `mvn -pl <module> compile -DskipTests` |

---

## 7. 参数简记

- **`-pl <module>`**：只对指定模块执行（project list）。
- **`-am`**：同时处理该模块依赖的模块（also make），如 `mvn -pl exchange-api -am clean compile` 会先编译 `core`、`exchange-core` 再编译 `exchange-api`。
- **`-DskipTests`**：跳过测试（仍会编译测试代码）。
- **`-q`**：少打日志（quiet）。

示例（带依赖一起清理+编译）：

```bash
mvn -pl exchange-api -am clean compile
```
