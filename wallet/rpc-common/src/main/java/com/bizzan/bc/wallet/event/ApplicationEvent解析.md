```java
// ① 注册为 Spring Bean，默认单例，beanName = applicationEvent
@Service     
// ② ApplicationListener<ContextRefreshedEvent> 声明自己关心的事件类型
public class ApplicationEvent implements ApplicationListener<ContextRefreshedEvent> {  
```

------------------------------------------------

1. 接口 `ApplicationListener<ContextRefreshedEvent>` 含义

- `ApplicationListener` 是 Spring 事件机制的最小功能接口，泛型 `<E>` 决定**关心哪种事件**。
- `ContextRefreshedEvent` 是 **ApplicationContext 刷新完成** 后发布的事件：  
  – 首次刷新（启动完成）会发一次；  
  – 调用 `ConfigurableApplicationContext#refresh()` 又会再发一次。
- 实现该接口的 Bean 会被自动注册到 **ApplicationEventMulticaster**，事件到来时**同步回调** `onApplicationEvent(...)`。
> ApplicationListener<E> = “事件回调接口”； 
> 实现它或加 @EventListener（Spring 4.2+ 推荐），Spring 就会在 E 类型事件发布时 `自动`调你的方法，完成解耦、异步、扩展 各种业务逻辑
------------------------------------------------
2. `@Service` 的作用
- 本质 `@Component`，仅用于语义分层（服务层）。
- 保证 **Spring 扫描**时能把当前类实例化并注册为 **单例 Bean**；否则实现了接口也没人帮你发布/监听事件。
- 默认 beanName = 类名首字母小写 → `applicationEvent`（可自己指定 `@Service("xxx")`）。

------------------------------------------------

3. 需要实现的唯一方法

```java
@Override
public void onApplicationEvent(ContextRefreshedEvent event) {
    // ③ 当 ApplicationContext 刷新完毕后会走到这里
    // event 里能拿到：
    //   event.getApplicationContext() → 当前已 ready 的上下文
    //   event.getTimestamp()          → 事件发生时间戳
}
```

执行特点

- **同步调用**：默认在同一条刷新线程里跑，若耗时过长会**阻塞容器启动完成**。

- **多次触发**：重复 refresh 会重复进；如果只关心“**首次启动**”需自己加防护：

  ```java
  private static final AtomicBoolean initialized = new AtomicBoolean(false);
  @Override
  public void onApplicationEvent(ContextRefreshedEvent event) {
      if (initialized.compareAndSet(false, true)) {
          // 真正只做一次的业务
      }
  }
  ```

- **所有子容器**都会发布该事件：Spring MVC 的 `DispatcherServlet` 也有独立 WebApplicationContext；想只处理根容器可判断：

  ```java
  if (event.getApplicationContext().getParent() == null) { ... }
  ```

------------------------------------------------

4. 典型用途

- 系统启动后预热缓存 / 加载字典 / 触发定时任务
- 注册动态路由、插件扫描
- 优雅地做一次“自建线程池启动、后台任务拉取”
- 在 **单元测试**里等待事件后再发请求，确保初始化完成

------------------------------------------------

5. 与 `@EventListener` 的对比

| 方式                           | 代码量   | 支持条件表达式     | 支持异步         | 阻塞风险 |
| ------------------------------ | -------- | ------------------ | ---------------- | -------- |
| 实现接口 `ApplicationListener` | 1 行实现 | ❌                  | ❌（默认同步）    | 高       |
| `@EventListener`               | 1 行注解 | ✅（`condition =`） | ✅（加 `@Async`） | 可控     |

Spring 5+ 更推荐：

```java
@EventListener(ContextRefreshedEvent.class)
public void onAppReady(ContextRefreshedEvent event) { ... }
```

------------------------------------------------

6. 常见坑

- 耗时任务直接写 → 启动线程被卡住，健康检查迟迟不过
- 重复 refresh（ actuator 、多 DispatcherServlet）导致业务被执行多次
- 测试用 `@MockBean` 会触发 refresh，事件次数 > 1

------------------------------------------------

一句话总结  
`ApplicationListener<ContextRefreshedEvent>` 就是 **“容器启动完成回调”** 的正规入口：  
@Service 让它成为 Bean → 容器 ready 后自动回调 `onApplicationEvent` →  
你可以安全地执行**仅需一次**的初始化逻辑，但要自己防重复、防阻塞。