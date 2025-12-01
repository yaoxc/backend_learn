package com.bizzan.bc.wallet.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * @Configuration 注解：
 *
 * 这个注解表明 RestTemplateConfig 类是一个配置类，用于定义Spring应用程序上下文的Bean。
 * Spring容器在启动时会扫描这个类，并根据其中定义的方法来创建和管理Bean。
 */
@Configuration
public class RestTemplateConfig {
    /**
     * @Bean 注解：
     *
     * @Bean 注解标记在 restTemplate 方法上，告诉Spring这个方法返回的对象应该被注册为一个Bean，放入Spring应用程序上下文中。
     * 在这个例子中，返回的 RestTemplate 实例会成为一个Spring管理的Bean
     * @return
     */
    /**
     * @LoadBalanced 注解：
     *
     * @LoadBalanced 注解为 RestTemplate 开启负载均衡功能。在Spring Cloud的微服务架构中，
     * 当应用需要调用其他微服务时，通常会使用 RestTemplate 进行HTTP请求。
     * 使用 @LoadBalanced 注解后，RestTemplate 会与负载均衡器（如Ribbon）集成，使得请求能够在多个服务实例之间进行负载均衡，
     * 提高系统的可用性和性能
     * @return
     */
    /**
     * restTemplate 方法：
     *
     * 该方法创建并返回一个 RestTemplate 实例。
     * RestTemplate 是Spring提供的用于同步客户端HTTP访问的核心类，它简化了与HTTP服务器进行交互的操作，
     * 例如发送GET、POST、PUT、DELETE等请求。
     */

    /**
     * @Service
     * public class MyService {
     *
     *     private final RestTemplate restTemplate;
     *
     *     @Autowired
     *     public MyService(RestTemplate restTemplate) {
     *         this.restTemplate = restTemplate;
     *     }
     *
     *     public String callOtherService() {
     *         // 于 RestTemplate 配置了负载均衡，请求会在 service - name 服务的多个实例间进行均衡分配
     *         return restTemplate.getForObject("http://service - name/some - endpoint", String.class);
     *     }
     */
    @Bean
    @LoadBalanced
    RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
