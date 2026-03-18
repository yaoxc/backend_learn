# Maven 依赖与「程序包不存在」说明

## 为什么经常出现「程序包 xxx 不存在」？

多模块项目里，**A 模块依赖 B 模块**时，编译 A 用的 classpath 来自：

- B 的 jar（或本次构建的 B 的产物）
- **以及 B 的传递依赖**（B 的 pom 里声明的、非 optional 的依赖）

一旦「B 的 POM 被 Maven 判定为无效」或「某些依赖在 B 里是 optional/system 不传递」，A 就**拿不到**这些包，就会出现「程序包 com.xxx 不存在」「找不到符号」等错误。

### 常见触发情况

1. **从不同入口构建**  
   上次在根目录 `mvn compile`（会先建 core、exchange-core），这次只 `mvn -pl ucenter-api compile`，ucenter-api 从**本地仓库**读 core 的 POM；若该 POM 无效，传递依赖就不会被解析。
2. **core / exchange-core 的 system 依赖**  
   它们依赖本地的 `spark-core-2.6.0.jar`，用了 `<scope>system</scope>` + `<systemPath>${project.basedir}/.../spark-core-2.6.0.jar</systemPath>`。  
   当 **core 的 POM 被别的模块当依赖解析**时，Maven 要求 systemPath 是**绝对路径**，而 `${project.basedir}` 在「被依赖方」的 POM 里不会被解析，所以 POM 会被判为无效，导致 **core 的传递依赖（如 fastjson、querydsl）不会提供给依赖 core 的模块**。
3. **依赖在 B 里是 optional**  
   B 里写了 `<optional>true</optional>` 的依赖不会传递给 A。

所以会出现：**上次全量编译 OK，这次只编某个模块就报「程序包不存在」**。

---

## systemPath 是什么？

- **用途**：和 `<scope>system</scope>` 一起用，表示「这个依赖不在 Maven 仓库里，而是本机某个路径下的 jar」。
- **要求**：Maven 规定 systemPath 必须是**绝对路径**。写 `${project.basedir}/lib/xxx.jar` 在本模块构建时能工作，但当这个 POM 被**其他模块当依赖读**时，`project.basedir` 不会按「被依赖模块」解析，Maven 就认为路径不合法，把该 POM 标成无效。
- **后果**：依赖 core/exchange-core 的模块在解析 core 时可能拿不到**任何传递依赖**，于是缺 fastjson、querydsl 等。

**当前项目已做依赖重构**：spark-core 不再使用 systemPath。根 POM 中增加了 profile `install-spark-core`（在存在 `core/lib/spark-core-2.6.0.jar` 时激活），在 **validate** 阶段会把该 jar 安装到本地仓库；各模块改为普通依赖 `com.sparkframework:spark-core`，因此 POM 有效、传递依赖可被解析，**IntelliJ IDEA 与单模块编译均可正常使用**。

---

## 根本解决办法：谁用谁声明

**规则：哪个模块的源码里 import 了某个包，就在该模块的 pom.xml 里显式声明对应依赖。**

- 不要依赖「core 有 fastjson，所以 ucenter-api 一定能拿到」——core 的 POM 可能因 systemPath 被判无效，传递依赖会丢。
- 本模块直接用的库（fastjson、querydsl-core、commons-lang3 等）都在**本模块**的 `<dependencies>` 里写一遍，这样无论从根目录全量编还是 `-pl 某模块` 单编，都能通过。

### 已按此规则做的修改示例

- **ucenter-api**：源码里用了 `com.alibaba.fastjson` 和 `com.querydsl.core.types`，已在 ucenter-api 的 pom 中显式增加 `fastjson`、`querydsl-core`。
- **market**：显式增加 `lombok`、`fastjson`。
- **exchange**：测试依赖 exchange-core 的类，编译/测试时用 `mvn -pl exchange -am test-compile`，保证先构建 exchange-core。

### 以后加代码时

- 在新模块或现有模块里用了新包（例如又用了一个 JSON 库、工具库），**顺手在该模块的 pom.xml 里加上对应 dependency**，避免以后再出现「程序包不存在」。

---

## 小结

| 现象 | 原因 | 做法 |
|------|------|------|
| 程序包 com.xxx 不存在 | 该包来自传递依赖，但传递链断了（POM 无效 / optional / system） | 在**当前模块** pom 里显式声明该依赖 |
| 上次 OK 这次不行 | 构建方式变了（全量 vs 单模块），或本地仓库里 POM 无效 | 坚持「谁用谁声明」，单模块编也能过 |
| systemPath 警告 | （已消除）spark-core 已改为本地仓库依赖 | 无需处理 |

---

## 在 IntelliJ IDEA 中运行

1. **首次或 clean 后**：从项目根目录在终端执行一次 **`mvn validate`**（或任意生命周期如 `mvn compile`），将 `core/lib/spark-core-2.6.0.jar` 安装到本地仓库。
2. 在 IDEA 中 **File → Open** 选择根目录的 `pom.xml`，或对已有项目 **右键 Maven → Reload Project**，依赖会正常解析。
3. 之后可随意在 IDEA 中运行任意模块的 main、单测，或只编单个模块，无需再带 `-am`。
