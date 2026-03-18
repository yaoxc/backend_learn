# 本地 JAR 安装到 Maven 仓库 - 命令归档

项目中原先通过 `<scope>system</scope>` + `<systemPath>` 引用的 JAR，已统一改为「先安装到本地仓库、再通过 `<dependency>` 引用」。  
以下命令用于**手动**将本地 JAR 安装到 `~/.m2/repository`，便于换机、清空本地仓库后重新安装。

**说明**：从项目**根目录**执行；路径均相对根目录。安装一次即可，IDEA 与 Maven 会从本地仓库解析。

---

## 一、自动安装（推荐）

在项目根目录执行任意生命周期，会按需安装存在文件对应的 JAR：

```bash
cd /path/to/backend_learn
mvn validate
```

- 若存在 `core/lib/spark-core-2.6.0.jar` → 安装 `com.sparkframework:spark-core:2.6.0`
- 若存在 `market/lib/aqmd-netty-2.0.1.jar` 等 → 安装 `com.aqmd:aqmd-netty*:2.0.1` 三件套

---

## 二、手动安装命令（归档备用）

以下命令在**项目根目录**执行，可直接复制粘贴。用于未使用 profile 自动安装、或需单独重装某 jar 时。

### 1. spark-core（core 与多模块使用）

```bash
mvn install:install-file \
  -Dfile=core/lib/spark-core-2.6.0.jar \
  -DgroupId=com.sparkframework \
  -DartifactId=spark-core \
  -Dversion=2.6.0 \
  -Dpackaging=jar
```

### 2. aqmd-netty（market 模块使用）

JAR 内嵌 POM 引用了不存在的 parent，需加 `-DgeneratePom=true` 让 Maven 生成简易 POM：

```bash
mvn install:install-file \
  -Dfile=market/lib/aqmd-netty-2.0.1.jar \
  -DgroupId=com.aqmd \
  -DartifactId=aqmd-netty \
  -Dversion=2.0.1 \
  -Dpackaging=jar \
  -DgeneratePom=true

mvn install:install-file \
  -Dfile=market/lib/aqmd-netty-api-2.0.1.jar \
  -DgroupId=com.aqmd \
  -DartifactId=aqmd-netty-api \
  -Dversion=2.0.1 \
  -Dpackaging=jar \
  -DgeneratePom=true

mvn install:install-file \
  -Dfile=market/lib/aqmd-netty-core-2.0.1.jar \
  -DgroupId=com.aqmd \
  -DartifactId=aqmd-netty-core \
  -Dversion=2.0.1 \
  -Dpackaging=jar \
  -DgeneratePom=true
```

若之前安装过 aqmd 且解析报错 `netty-parent:pom:2.0.1 not found`，可先删除再重装：  
`rm -rf ~/.m2/repository/com/aqmd`，再执行上述命令或 `mvn validate`。

---

## 三、一键脚本（可选）

在项目根目录创建脚本 `scripts/install-local-jars.sh`（或直接执行下面内容），用于一次性安装当前项目用到的所有本地 JAR：

```bash
#!/bin/bash
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

install_spark() {
  [ -f core/lib/spark-core-2.6.0.jar ] || return 0
  mvn install:install-file -Dfile=core/lib/spark-core-2.6.0.jar \
    -DgroupId=com.sparkframework -DartifactId=spark-core -Dversion=2.6.0 -Dpackaging=jar
  echo "Installed spark-core"
}

install_aqmd() {
  [ -f market/lib/aqmd-netty-2.0.1.jar ] || return 0
  mvn install:install-file -Dfile=market/lib/aqmd-netty-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty -Dversion=2.0.1 -Dpackaging=jar
  mvn install:install-file -Dfile=market/lib/aqmd-netty-api-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty-api -Dversion=2.0.1 -Dpackaging=jar
  mvn install:install-file -Dfile=market/lib/aqmd-netty-core-2.0.1.jar \
    -DgroupId=com.aqmd -DartifactId=aqmd-netty-core -Dversion=2.0.1 -Dpackaging=jar
  echo "Installed aqmd-netty (3 jars)"
}

install_spark
install_aqmd
echo "Done."
```

使用：在项目根目录执行 `bash scripts/install-local-jars.sh`（脚本已保存在 `scripts/install-local-jars.sh`）。

---

## 四、POM 中的引用方式

安装后，各模块通过父 POM 的 `dependencyManagement` 统一版本，子模块只需写：

```xml
<dependency>
    <groupId>com.sparkframework</groupId>
    <artifactId>spark-core</artifactId>
</dependency>
<!-- 或 -->
<dependency>
    <groupId>com.aqmd</groupId>
    <artifactId>aqmd-netty</artifactId>
</dependency>
<dependency>
    <groupId>com.aqmd</groupId>
    <artifactId>aqmd-netty-api</artifactId>
</dependency>
<dependency>
    <groupId>com.aqmd</groupId>
    <artifactId>aqmd-netty-core</artifactId>
</dependency>
```

无需再写 `<scope>system</scope>` 与 `<systemPath>`。
