# 安装 Java 8 并本地启动 cloud 服务

**说明**：**cloud（Eureka）** 已单独升级为 **Spring Boot 2.7 + Spring Cloud 2021**，可在 **Java 8 或更高版本**下直接启动，无需 `--add-opens` 等补丁。其余模块（market、exchange 等）仍使用根 POM 的 Spring Boot 1.5，若需本地跑全量服务，建议配置 **Java 8**。  
升级改造过程见：[cloud升级改造说明.md](./cloud升级改造说明.md)。

下面为安装 JDK 8、配置环境并启动 cloud 的步骤。

---

## 一、安装 Java 8（macOS，使用 Homebrew）

### 1. 若 Homebrew 报错「目录不可写」

在终端执行（需输入本机密码）：

```bash
sudo chown -R $(whoami) /usr/local/Cellar /usr/local/Frameworks /usr/local/Homebrew /usr/local/bin /usr/local/etc /usr/local/include /usr/local/lib /usr/local/opt /usr/local/sbin /usr/local/share /usr/local/var
```

Apple Silicon (M1/M2) 若 Homebrew 装在 `/opt/homebrew`，则执行：

```bash
sudo chown -R $(whoami) /opt/homebrew
```

### 2. 安装 OpenJDK 8

```bash
brew install openjdk@8
```

### 3. 确认安装路径

安装完成后，可用下面命令查看路径（二选一，取决于你的 Homebrew 前缀）：

```bash
# Intel / 旧版 Homebrew
brew --prefix openjdk@8
# 常见输出：/usr/local/opt/openjdk@8

# Apple Silicon 常见为
# /opt/homebrew/opt/openjdk@8
```

记下该路径，下面配置 `JAVA_HOME` 时会用到。

---

## 二、配置 Java 8 环境

### 方式 A：仅当前终端会话生效

在**项目根目录**执行（把路径换成你上一步得到的）：

```bash
# Intel / 常见路径
export JAVA_HOME=/usr/local/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home

# 若为 Apple Silicon，可能是：
# export JAVA_HOME=/opt/homebrew/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home

export PATH="$JAVA_HOME/bin:$PATH"
```

验证：

```bash
java -version
# 应看到 openjdk version "1.8.x" 或类似
```

### 方式 B：长期生效（推荐）

把环境变量写入 shell 配置文件，每次开终端都会用 Java 8。

**若使用 zsh（macOS 默认）：**

```bash
echo '' >> ~/.zshrc
echo '# Java 8 for backend_learn' >> ~/.zshrc
echo 'export JAVA_HOME=/usr/local/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home' >> ~/.zshrc
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.zshrc
```

**若使用 bash：**

```bash
echo '' >> ~/.bash_profile
echo '# Java 8 for backend_learn' >> ~/.bash_profile
echo 'export JAVA_HOME=/usr/local/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home' >> ~/.bash_profile
echo 'export PATH="$JAVA_HOME/bin:$PATH"' >> ~/.bash_profile
```

**Apple Silicon** 请把上面路径改为：

`/opt/homebrew/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home`

保存后执行一次：

```bash
source ~/.zshrc   # 或 source ~/.bash_profile
java -version
```

---

## 三、启动 cloud 服务

1. 确保已用上面方式之一配置好 **Java 8**，且 `java -version` 为 1.8。
2. 在**项目根目录**执行：

```bash
cd /Users/felix/Documents/Code/backend_learn
mvn -pl cloud spring-boot:run
```

3. 看到日志里出现类似 **`Started CloudApplication`**（Spring Boot v2.7.x）且无报错，即表示启动成功。
4. 浏览器访问 Eureka 控制台：**http://localhost:7421/**（直接为 HTML 控制台）

---

## 四、常见问题

| 现象 | 处理 |
|------|------|
| `java -version` 仍是 17 或其它版本 | 若只跑 cloud，2.7 支持 Java 8+，可不用改；若跑其它模块，确认 `JAVA_HOME` 指向 openjdk@8 并 `source ~/.zshrc` 后重试。 |
| 端口 7421 被占用 | 执行 `lsof -i :7421` 查看占用进程，用 `kill <PID>` 结束；或修改 `cloud/src/main/resources/application.properties` 中 `server.port`。 |

---

## 五、一键命令汇总（安装并配置好后）

```bash
# 使用 Java 8
export JAVA_HOME=/usr/local/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"

# 启动 cloud
cd /Users/felix/Documents/Code/backend_learn
mvn -pl cloud spring-boot:run
```

（Apple Silicon 将 `JAVA_HOME` 改为 `/opt/homebrew/opt/openjdk@8/libexec/openjdk.jdk/Contents/Home`）
