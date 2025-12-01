

## 一、`@Scheduled` 为什么能让方法“自动定时跑”？

1. **前提**：  
   配置类或任何 **Spring Bean** 上加 `@EnableScheduling`（通常写在主启动类），Spring 会注入一个 **ScheduledTaskRegistrar**。

2. **解析阶段**：  
   容器启动时，**后置处理器**扫描所有 `@Scheduled` 方法，解析其属性（`fixedRate` / `fixedDelay` / `cron`），包装成 **Runnable** 任务。

3. **调度阶段**：  
   默认使用 **单线程池** `ScheduledExecutorService`（可自定义），按指定周期把任务提交到线程池 → **无限循环执行**。

4. **异常处理**：  
   方法内抛异常 **不会停止下一次调度**；可配合 `@Scheduled` + `ErrorHandler` 记录日志。

---

## 二、本类作用一句话总结

> **ExchangePushJob** 是 **“行情广播调度器”**：  
> 通过 **内存队列 + `@Scheduled` 批量推送** 机制，把成交、盘口、币种简况 **每 500 ms / 2 s** 统一推给 **WebSocket 浏览器** 和 **Netty APP 终端**，实现**低延迟、高吞吐**的实时行情展示。

# @Scheduled能被调度的原理是什么

`@Scheduled` 能被自动调度的底层原理可以拆成 **4 个阶段**：

1. 开启调度能力
2. 扫描 & 注册任务
3. 创建定时器
4. 循环执行

全程由 **Spring Framework 的 `spring-context` 模块**完成，**不依赖 JDK 以外的组件**。

---

## 一、开启调度能力（Bootstrap）

在任意 **配置类或 Main 类** 加：

```java
@EnableScheduling        // ← 关键注解
@SpringBootApplication
public class ExchangeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExchangeApplication.class, args);
    }
}
```

`@EnableScheduling` 向容器注册一个 **`SchedulingConfiguration`** → 内部再注册 **`ScheduledAnnotationBeanPostProcessor`**（简称 **SABPP**：“已调度注解的 Bean 后置处理器”
）。

---

## 二、扫描 & 注册任务（Bean 创建期）

SABPP 实现了 `BeanPostProcessor`，在 **每个 Spring Bean 初始化之后** 执行：

```text
postProcessAfterInitialization(Object bean, String beanName)
```

伪代码逻辑：

```java
for (Method method : bean.getClass().getDeclaredMethods()) {
    Scheduled scheduled = method.getAnnotation(Scheduled.class);
    if (scheduled != null) {
        // 1. 解析 fixedRate / fixedDelay / cron
        // 2. 包装成 Runnable
        // 3. 注册到 ScheduledTaskRegistrar
        registrar.addCronTask(() -> method.invoke(bean), cron);
        registrar.addFixedRateTask(() -> method.invoke(bean), fixedRate);
        ...
    }
}
```

> 因此 **只要方法是 Spring Bean 的成员，且被 `@Scheduled` 标注**，就会被收集。

---

## 三、创建定时器（容器刷新完成时）

`ScheduledTaskRegistrar` 实现了 `InitializingBean`：

```java
afterPropertiesSet() {
    // 默认创建单线程池
    this.localExecutor = Executors.newSingleThreadScheduledExecutor();
    // 把第二步收集的所有任务提交到线程池
    cronTasks.forEach(task ->
        localExecutor.scheduleWithFixedDelay(
            task.getRunnable(), initialDelay, period, TimeUnit.MILLISECONDS));
}
```

也可通过 `SchedulingConfigurer` 自定义线程池：

```java
@Configuration
public class ScheduleConfig implements SchedulingConfigurer {
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setScheduler(Executors.newScheduledThreadPool(16));
    }
}
```

---

## 四、循环执行（运行期）

线程池会 **按 fixedRate/fixedDelay/cron 表达式** 无限调用：

```text
pool-1-thread-1 → Runnable.run() → method.invoke(targetBean)
```

- 方法执行完 **不会停止下一次调度**（异常仅打日志）。
- 下一次调度时间 **与上一次执行耗时无关**（fixedRate）或 **等上一次跑完再延迟**（fixedDelay）。
- 整个生命周期随 **Spring Context** 一起销毁，线程池优雅关闭。

---

## 五、完整时序图（文字）

```
Spring 启动
   ↓
@EnableScheduling 注册 ScheduledAnnotationBeanPostProcessor
   ↓
容器创建 Bean → 初始化后 SABPP 扫描 @Scheduled 方法
   ↓
把方法包装成 Runnable → ScheduledTaskRegistrar 保存
   ↓
容器刷新完成 → ScheduledTaskRegistrar 创建 ScheduledExecutorService
   ↓
提交所有 Runnable → 线程池循环 schedule
   ↓
运行期：线程池每 X ms 调用一次 method.invoke(bean)
```

---

## 六、常见疑问

| 问题 | 解答 |
|------|------|
| 必须加 `@EnableScheduling` 吗？ | 必须，否则 SABPP 不注册，注解无效。 |
| 可以多个 @Scheduled 吗？ | 可以，一个 Bean 多个方法各自独立调度。 |
| 方法抛异常会停止吗？ | 不会，下次照常触发；可用 `ErrorHandler` 记录。 |
| 想动态改 cron？ | 实现 `SchedulingConfigurer` + 外部配置中心刷新。 |

---

## 七、一句话总结

> `@Scheduled` 能被调度的本质是：  
> **Spring 在容器启动时扫描所有 Bean 方法 → 把注解解析成 Runnable → 注册到 ScheduledExecutorService → 线程池无限循环调用。**  
> 整个流程完全由 **Spring-Context** 自身实现，零外部依赖。

# SimpMessagingTemplate messagingTemplate 为什么可以被注入直接使用
`private SimpMessagingTemplate messagingTemplate;` 能被注入，是因为 **Spring Boot 自动配置机制** 已经提前把 `SimpMessagingTemplate` 实例化并注册成了 **Spring 容器里的一个 Bean**，你只需 `@Autowired` 即可使用。

---

## 一、自动配置源头

1. **启动器依赖**  
   在 `pom.xml` 里引入：
   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-websocket</artifactId>
   </dependency>
   ```
   该启动器内部包含：
   ```
   spring-boot-starter -> spring-boot-starter-web -> spring-websocket
   ```

2. **自动配置类**  
   Spring Boot 启动时会加载：
   ```java
   @Configuration
   @ConditionalOnClass({SimpMessagingTemplate.class, WebSocketMessageBrokerConfigurer.class})
   public class WebSocketMessageBrokerAutoConfiguration {
       @Bean
       @ConditionalOnMissingBean(SimpMessagingTemplate.class)
       public SimpMessagingTemplate simpMessagingTemplate(...) {
           return new SimpMessagingTemplate(...);
       }
   }
   ```
   只要 classpath 下存在 `SimpMessagingTemplate`，**就会自动创建一个单例 Bean** 并注册到容器。

---

## 二、注入流程

```java
@Component // 当前类被 Spring 扫描
public class ExchangePushJob {
    @Autowired // 告诉 Spring 把容器里的 SimpMessagingTemplate 实例注入进来
    private SimpMessagingTemplate messagingTemplate;
}
```

运行期：
```
Spring 容器启动
   ↓ 自动配置创建 SimpMessagingTemplate Bean
   ↓ 扫描到 ExchangePushJob
   ↓ 通过反射把 Bean 注入字段
   ↓ 业务代码直接调用 messagingTemplate.convertAndSend(...)
```

---

## 三、常见疑问

| 疑问 | 解答 |
|------|------|
| 没有 `@Bean` 自己创建，为什么能用？ | **Spring Boot 自动配置**已经帮你创建了。 |
| 可以换实现吗？ | 可以，自己 `@Bean` 覆盖默认的 `SimpMessagingTemplate`。 |
| 不加 `@Autowired` 行不行？ | 行，用构造函数注入或 `setter` 注入，只要容器里有 Bean。 |

---

## 四、一句话总结

> **Spring Boot 在引入 `spring-boot-starter-websocket` 时会自动注册一个 `SimpMessagingTemplate` Bean**；  
> 你只需 `@Autowired`，Spring 就会把 **现成的 WebSocket 广播工具** 注入进来，直接调用 `convertAndSend` 即可向浏览器推送消息。























