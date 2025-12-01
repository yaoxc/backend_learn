# Criteria（标准 / 条件）
MongoDB 的 Criteria 是 **Spring-Data-MongoDB** 提供的“查询条件构造器”，用来把 Java 方法链翻译成 MongoDB 的 JSON 操作符，最终生成 `Query` 对象给 `MongoTemplate` 执行。下面按“功能 → 代码 → 对应原生操作符”逐条说明（基于 5.x 版本，2025 文档）。

---

### 1. 等值 / 不等
```java
Criteria.where("name").is("Alice")           // {name: "Alice"}
Criteria.where("age").ne(20)                 // {age: {$ne: 20}}
```

### 2. 比较
```java
Criteria.where("price").gt(100)              // {price: {$gt: 100}}
Criteria.where("qty").gte(10)                // {qty: {$gte: 10}}
Criteria.where("score").lt(60)               // {score: {$lt: 60}}
Criteria.where("age").lte(30)                // {age: {$lte: 30}}
```

### 3. 包含 / 排除
```java
Criteria.where("status").in("A", "B")        // {status: {$in: ["A", "B"]}}
Criteria.where("city").nin("Paris", "Tokyo") // {city: {$nin: ["Paris", "Tokyo"]}}
```

### 4. 逻辑组合
```java
// AND（两种方式）
new Criteria().andOperator(
        Criteria.where("age").gt(25),
        Criteria.where("city").is("NY"))     // {$and: [{age: {$gt: 25}}, {city: "NY"}]}

Criteria.where("age").gt(25).and("city").is("NY") // {age: {$gt: 25}, city: "NY"}

// OR
new Criteria().orOperator(
        Criteria.where("status").is("NEW"),
        Criteria.where("priority").gt(5))    // {$or: [{status: "NEW"}, {priority: {$gt: 5}}]}
```

### 5. 存在性 / 类型
```java
Criteria.where("avatar").exists(true)        // {avatar: {$exists: true}}
Criteria.where("tags").type(4)               // {tags: {$type: "array"}}
Criteria.where("item").isNull()              // {item: null} 或字段缺失
Criteria.where("item").isNullValue()         // 仅匹配 BSON null 值
```

### 6. 正则 / 模糊
```java
Criteria.where("name").regex("^J.*")         // {name: {$regex: "^J.*"}}
Criteria.where("email").regex("gmail", "i")  // 忽略大小写
```

### 7. 数组专用
```java
Criteria.where("tags").all("java", "spring") // {tags: {$all: ["java", "spring"]}}
Criteria.where("tags").size(3)               // {tags: {$size: 3}}
Criteria.where("orders").elemMatch(
        Criteria.where("status").is("PAID")
        .and("amt").gt(100))                // {orders: {$elemMatch: {status: "PAID", amt: {$gt: 100}}}}
```

### 8. 随机采样（≥ 5.0）
```java
Criteria.where("").sampleRate(0.1f)          // { $sampleRate: 0.1 } 随机选 10% 文档
```

---

### 典型工作流程
```java
Query query = new Query();
query.addCriteria(Criteria.where("age").gte(18).lte(60)
                          .and("city").in("Paris", "London")
                          .and("status").ne("BLOCKED"));

List<User> users = mongoTemplate.find(query, User.class);
```

**一句话**：Criteria 就是 **“Java 链式写法 → MongoDB JSON 操作符”** 的翻译器，配合 `Query` 完成类型安全、可组合、可重用的动态查询条件构造。
























