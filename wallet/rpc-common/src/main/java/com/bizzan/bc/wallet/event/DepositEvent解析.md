# 分布式服务下，synchronized 可以保证互斥么
在**单体 JVM**里，`synchronized` 确实能保证**同一时刻只有一个线程**进入 `onConfirmed`；  
但在**微服务多实例**场景下，每个容器各持一把锁，**跨进程的并发依旧会穿透**，造成：

- 重复落库（幂等失效）
- 重复发 Kafka 消息

因此需要**分布式级互斥**或**幂等设计**，而**不是换一把更炫的锁**。

------------------------------------------------

> 根本思路：幂等 > 分布式锁  
   也即：把“是否已处理”做成**中心化判断**，让多实例同一事务串行化或快速失败。
> 

# 幂等常见稳定方案
下面给出在 **Spring Boot 实际产线** 中经过验证、组合使用的**“常规 + 稳态”**幂等方案，  
所有细节可直接落地，按“**场景 → 实现要点 → 代码片段 → 优缺点”** 说明，并在关键处给出引用来源。

---

### 1. 数据库唯一索引（插入型接口首选）
**场景**：支付回调、订单创建、充值记录等**新增型**业务。

**实现要点**
- 使用**业务唯一键**（如 `txid + address` 或 `out_biz_no`）建联合唯一索引。
- 先直接 `INSERT`，捕获 `DuplicateKeyException` 即视为重复，返回成功即可。

**代码示例**
```java
// 表级约束
ALTER TABLE deposit ADD UNIQUE KEY uk_tx_addr(txid, address);

// 代码
try {
    depositMapper.insert(deposit);   // 直接插入
} catch (DuplicateKeyException e) {
    log.warn("重复回调，已忽略");
}
```
**优点**：零外部依赖、性能高、实现极简。  
**缺点**：仅适合**插入**场景；需处理异常。


---

### 2. Token 令牌机制（表单/下单防重复点击）
**场景**：用户点击“提交订单”、“保存表单”等**客户端可能重试**的写操作。

**实现要点**
1. 进入页面时向后端获取 **一次性 Token**（UUID）并存入 **Redis**（TTL 10 min）。
2. 表单提交时携带 Token；后端**原子删除**（`DEL` 返回 1 才继续，0 表示已用过）。

**代码示例**
```java
// 获取令牌
@GetMapping("/token")
public String getToken() {
    String token = UUID.fastUUID().toString();
    redisTemplate.opsForValue().set("token:" + token, "1", Duration.ofMinutes(10));
    return token;
}

// 下单接口
@PostMapping("/order")
public R create(@RequestHeader String token, @RequestBody OrderDTO dto) {
    Boolean success = redisTemplate.delete("token:" + token);
    if (Boolean.FALSE.equals(success)) {
        throw new BizException("重复提交");
    }
    return orderService.create(dto);
}
```
**优点**：通用、防前端重复点击。  
**缺点**：需要额外接口；依赖 Redis。


---

### 3. 乐观锁（更新型接口）
**场景**：库存扣减、账户余额减少、状态变更。

**实现要点**
- 表加 `version` 字段（或 `sq` 剩余数量）。
- `UPDATE` 带条件 `WHERE version=#{oldVersion}`（或 `sq>=quantity`）。
- 返回行数 = 0 表示已被其他事务更新，抛**业务异常**重试或返回成功。

**代码示例**
```sql
UPDATE account
SET balance = balance - #{amount}, version = version + 1
WHERE id = #{id} AND version = #{version}
```
```java
if (accountMapper.updateBalance(id, amount, version) == 0) {
    throw new OptimisticLockingFailureException("并发更新失败");
}
```
**优点**：无外部依赖、适合高并发读多写少。  
**缺点**：冲突多时会重试；需改表结构。


---

### 4. 分布式锁（高频并发 & 跨 JVM）
**场景**：秒杀、库存、提现等**并发量极高**且**多实例**场景。

**实现要点**
- 使用 **Redisson** `RLock`（自动续期 + 可重入）。
- **锁粒度 = 业务唯一键**（如订单号）。
- **锁超时**必须 > 业务最大执行时间，防止死锁。

**代码示例**
```java
RLock lock = redisson.getLock("lock:order:" + orderNo);
try {
    if (!lock.tryLock(3, 15, TimeUnit.SECONDS)) {
        throw new BizException("系统繁忙，请稍后再试");
    }
    // 业务：扣库存、写流水
} finally {
    if (lock.isHeldByCurrentThread()) lock.unlock();
}
```
**优点**：跨实例互斥；适合秒杀。  
**缺点**：性能损耗；需 Redis 高可用。


---

### 5. 状态机幂等（有状态流转）
**场景**：订单、工单、审批流等**状态驱动**业务。

**实现要点**
- 定义枚举状态 & 合法转移列表。
- `UPDATE` 时加 `WHERE status=#{期望前状态}`，返回 0 表示已处理或状态非法。

**代码示例**
```sql
UPDATE `order`
SET status = 'PAID'
WHERE id = #{id} AND status = 'INIT'
```
```java
if (orderMapper.pay(orderId, "INIT") == 0) {
    throw new BizException("订单已支付或状态异常");
}
```
**优点**：业务语义清晰；天然幂等。  
**缺点**：只适用于**可明确定义前状态**的场景。


---

### 6. 组合打法（产线最常用）
| 业务类型 | 推荐组合 |
|----------|----------|
| 新增记录 | **唯一索引** 兜底（必须）+ Token（可选，防前端重点） |
| 更新/减少 | **乐观锁/状态机** 为主 + 分布式锁（极高并发时） |
| 秒杀/库存 | **分布式锁** + 乐观锁 **双重保护** |

---

### 7. 落地 checklist
1. 给所有**资金/订单**类接口加 **业务唯一键 + 唯一索引**；
2. 更新场景**必须带条件**（version / 前状态）；
3. 高并发系统提前压测 **Redis 锁** 与 **数据库冲突率**；
4. **幂等异常**直接返回成功（或相同单号），**不要抛 500**，避免重试雪崩；
5. 监控：对“重复请求率”“锁等待时间”建告警，及时调优。

> 一句话：  
> **“唯一索引兜底，乐观锁/状态机主治，分布式锁救急，Token 防点击”** —— 这套组合在 Spring Boot 产线久经考验，可直接复制使用。
>

# 案例 ：“全局锁”，Redisson 一行注解

**1 引入**

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

**2 使用**

```java
@Component
public class DepositEvent {
    @Autowired
    private RedissonClient redisson;

    public void onConfirmed(Deposit deposit) {
        // 以业务唯一键为锁
        RLock lock = redisson.getLock("deposit:" + deposit.getTxid() + deposit.getAddress());
        // 等待 3s、持有 10s 自动解锁
        if (lock.tryLock(3, 10, TimeUnit.SECONDS)) {
            try {
                if (!depositService.exists(deposit)) {
                    depositService.save(deposit);
                    kafkaTemplate.send("deposit", coinName, JSON.toJSONString(deposit));
                }
            } finally {
                lock.unlock();
            }
        } else {
            log.warn("获取分布式锁失败，放弃处理 tx={}", deposit.getTxid());
        }
    }
}
```

>**注意**：锁只是**兜底**，仍需 `exists` 二次判断（防锁竞争+可重入）。

# 上锁解析
是，**lock 对象只是“锁引用”，真正“上锁”发生在 `tryLock(...)` 返回 `true` 的那一刻**；  
执行到 `RLock lock = redisson.getLock(...)` 时**还没有任何加锁动作**，也不会阻塞线程。

------------------------------------------------
时间线逐行拆解

| 代码 | 是否加锁 | 说明 |
|---|---|---|
| `RLock lock = redisson.getLock(...)` | ❌ | 只在**本地 JVM** 里创建了一个 Redisson 锁代理对象，**没跟 Redis 交互** |
| `lock.tryLock(3, 10, TimeUnit.SECONDS)` | ✅（可能） | **第一次**向 Redis 发送 `SET key NX PX 10000` 命令；<br>返回 `true` 表示**抢锁成功**，此时别的线程/实例再来就会得到 `false` |
| `lock.unlock()` | ❌（释放） | 向 Redis 发 `DEL` 或 `Lua` 脚本，**只有当前线程持有锁时才真正删除** |

------------------------------------------------
验证实验
```java
System.out.println("① 准备抢锁");
RLock lock = redisson.getLock("test");
System.out.println("② 锁对象已拿到，还未加锁");
boolean got = lock.tryLock();   // 这里才会出现 Redis 命令
System.out.println("③ 抢锁结果 = " + got);
```
控制台只有 **②→③** 之间才会出现 Redisson 日志：
```
RedissonLock: acquired lock 'test' ...
```

------------------------------------------------
>结论
>- `getLock` 只是**获取锁句柄**；
>- `tryLock`（或 `lock()`）才是**真正向 Redis 发起加锁命令**；
>- 因此 **“执行到这里才开始上锁”** 的理解完全正确。
>

# 加锁是向Redis写一条数据么
>是，**加锁的本质就是向 Redis 写入一条“临时”记录**，并利用 Redis 的单线程模型保证**原子性判断**；  
Redisson 默认使用 **官方推荐算法（Redlock 变种）**
> 
对应命令：
>SET lockKey uniqueValue NX PX 30000


------------------------------------------------
2. 可重入时的“写数据”
- **同一线程**多次 `tryLock()` 不会继续 `SET`，而是**把 value 的“重入计数器” + 1**（通过 `HINCRBY` 实现）。
- 数据结构变为 **Hash**：
  ```
  lockKey: {
      "uuid:threadId" : 3   // 重入 3 次
  }
  ```
解锁时先减到 0，再整个 `DEL`。

------------------------------------------------
3. 结论一句话
> **抢锁成功 = Redis 里新增（或重入计数）一条记录**；  
> **解锁成功 = 删除（或计数归零后删除）这条记录**；  
> 整个过程依赖 Redis **单线程 + 原生命令原子性**，无需外部协调器。













