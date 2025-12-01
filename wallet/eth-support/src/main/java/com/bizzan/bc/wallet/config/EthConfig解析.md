# coin对象是在创建web3j bean的时候注入的么？怎么注入的？

是的，`Coin` 对象**正是在创建 `web3j` Bean 时被注入的**，而且**完全由 Spring 容器自动完成**，不需要你手写 `new` 或者 `ApplicationContext.getBean`。  
下面把“怎么注入”的底层流程拆开说明：

------------------------------------------------
1. 方法签名里的参数就是“隐式依赖声明”
```java
@Bean
@ConditionalOnProperty(name = "coin.keystore-path")
public Web3j web3j(Coin coin) {   // ← 这里不是普通参数，而是**依赖声明**
    ...
}
```
Spring 在解析配置类时，发现 `web3j` 工厂方法需要一个 `Coin` 类型参数，就会**先去容器里按类型查找**已存在的 `Coin` Bean。

------------------------------------------------
2. Coin Bean 的来源——**配置属性绑定**
   前面必定有另一个 `@Configuration` 类（或当前类）写了类似：
```java
@Configuration
public class CoinConfig {
    @Bean
    @ConfigurationProperties(prefix = "coin")   // 把 yml 里 coin.* 全部注入字段
    public Coin coin() {
        return new Coin();   // 空对象，后续由 Binder 填充
    }
}
```
于是容器里已经存在一个**单例、字段已赋值**的 `Coin` Bean（包含 `rpc`、`chainId`、`keystore-path` 等）。

------------------------------------------------
3. 依赖注入时机——**配置类增强阶段**
- 容器启动到 `ConfigurationClassPostProcessor` 时，会**代理**所有带 `@Bean` 的方法。
- 真正调用 `web3j(Coin coin)` 前，Spring 通过 `AutowireCapableBeanFactory#resolveDependency`  
  把容器里现成的 `Coin` 实例**作为实参传进来**——这就是**方法参数依赖注入**（`Method Injection`）。

------------------------------------------------
4. 时序图（简化）
```
1. 容器启动
2. CoinConfig.coin() 被执行 → Coin 对象（已绑定好属性）注册为单例 Bean
3. EthConfig 被增强
4. 调用 web3j() 前 → 容器把第 2 步的 Coin Bean 当参数传进来
5. 方法体内直接用 coin.getRpc() 拿到 RPC 地址
```

------------------------------------------------
5. 验证方式（调试可见）
   在 `web3j` 方法里打断点，观察 `coin` 参数：
- **非 null**
- 字段（如 `rpc`）已经是 `application.yml` 里的值  
  → 证明注入完成。

------------------------------------------------
一句话总结  
`Coin` 对象**先**由配置属性类创建并注册为 Bean，**后**被 Spring 作为依赖**自动注入**到 `web3j` 工厂方法参数里；全程无手写 `new`，完全靠“方法参数依赖注入”机制完成。




