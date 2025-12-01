# @Data
@Data 位置：类级别
   Lombok 在**编译期**会为 **抽象类** 也全套生成：

>- 所有字段的 `getter / setter`
>- `toString()`
>- `equals(Object o)` & `hashCode()`
>- **不会**生成构造器（要构造器需再加 `@NoArgsConstructor` / `@AllArgsConstructor`）

# implements Runnable
- 把“**是否继续跑**”的标志 `running` 暴露出来，子类只需在 `while (isRunning())` 循环里轮询即可优雅退出。
- 典型模板写法：

```java
@Override
public final void run() {
    while (isRunning()) {
        try {
            watch();              // 子类真正实现的检查逻辑
            Thread.sleep(getInterval());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
        }
    }
}

/** 子类填充具体观察逻辑 */
protected abstract void watch();
```

------------------------------------------------

. 用法示例

```java
public class PriceWatcher extends Watcher {
    @Override
    protected void watch() {
        BigDecimal price = fetchFromExchange();
        if (price.compareTo(BigDecimal.valueOf(50_000)) > 0) {
            log.warn("BTC 突破 5W !");
        }
    }
}

// 启动
PriceWatcher w = new PriceWatcher();
w.setInterval(5_000);   // 5 秒扫一次
new Thread(w).start();
```

------------------------------------------------

一句话总结  
`@Data` 让抽象“模板”也能享受**全套 getter/setter/equals/hashCode/toString**，  
`implements Runnable` 给出**可中断的轮询框架**；  
子类只需聚焦 `watch()` 逻辑，间隔与启停交给父类统一控制。



