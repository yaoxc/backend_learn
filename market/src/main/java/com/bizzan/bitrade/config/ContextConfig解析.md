# 类功能
这段代码的核心作用：**全局解决跨域（CORS）问题**，让所有前端/APP 能够跨域调用后端接口。

---

## 一、逐行中文注释

```java
@Configuration                          // 声明这是一个 Spring 配置类
public class ContextConfig extends WebMvcConfigurerAdapter { // 已过时，可用 WebMvcConfigurer

    @Bean                                // 向容器注册一个 Filter
    public FilterRegistrationBean corsFilter() {
        // 1. 创建 CORS 配置源
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 2. 创建 CORS 规则
        CorsConfiguration config = new CorsConfiguration();
        config.addAllowedOrigin("*");    // 允许任何域名访问
        config.setAllowCredentials(true);// 允许携带 Cookie/Authorization
        config.addAllowedHeader("*");    // 允许任何请求头
        config.addAllowedMethod("*");    // 允许任何 HTTP 方法（GET/POST/PUT/DELETE/OPTIONS）
        // 3. 把规则映射到所有路径 /**
        source.registerCorsConfiguration("/**", config);
        // 4. 包装成 Filter，并设置为最高优先级（Order=0）
        FilterRegistrationBean bean = new FilterRegistrationBean(new CorsFilter(source));
        bean.setOrder(0);
        return bean;
    }
}
```

---

## 二、为什么要“继承 WebMvcConfigurerAdapter”

| 原因 | 说明 |
|------|------|
| **历史兼容** | `WebMvcConfigurerAdapter` 是 Spring 4.x 的便利类，空实现 `WebMvcConfigurer` 接口，子类只需覆写需要的方法。 |
| **Spring 5.x 已过时** | 现在直接实现 `WebMvcConfigurer` 即可，无需继承。 |
| **本例其实没用到 MVC 配置** | 这里只是 **借 @Configuration 能力注册 Filter**，继承与否**不影响功能**；可改为：  
`@Configuration public class ContextConfig implements WebMvcConfigurer { ... }` |

---

## 三、不写这段代码会怎样？

| 场景 | 结果 |
|------|------|
| 前端 `http://localhost:3000` 调后端 `http://localhost:8080/api/xxx` | 浏览器拦截：**CORS policy: No 'Access-Control-Allow-Origin' header** |
| 移动端 WebView 跨域 | 同样失败 |
| 同域调用 | 正常，无需 CORS |

---

## 四、一句话总结

> `ContextConfig` 通过 **最高优先级的 CORS Filter** 允许 **任意域名、任意方法、任意头、带 Cookie** 访问后端接口，**解决前后端分离、APP、小程序等跨域问题**；继承 `WebMvcConfigurerAdapter` 只是历史习惯，可直接实现 `WebMvcConfigurer`。