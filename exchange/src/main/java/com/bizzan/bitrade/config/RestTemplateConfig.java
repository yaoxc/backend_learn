package com.bizzan.bitrade.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * 修改说明：@LoadBalanced 需依赖 spring-cloud-starter-loadbalancer，已在 exchange 的 pom 中显式引入，与 exchange-api 一致。
 * @author daishuyang
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    @LoadBalanced
    RestTemplate restTemplate() {
        return new RestTemplate();
    }

}
