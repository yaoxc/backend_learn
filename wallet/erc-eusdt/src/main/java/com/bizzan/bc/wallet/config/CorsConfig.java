package com.bizzan.bc.wallet.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

/**
 * 配置跨域资源共享（CORS）策略，确保你的 Spring 应用能够接受来自不同源（origin）的 HTTP 请求
 *
 * 通过这个配置类，Spring 应用可以允许来自不同源的请求，这在前后端分离架构或多个微服务相互调用时非常重要。
 * 它通过配置 CORS 过滤器，对请求进行拦截和处理，使得应用能够安全地接受跨域请求，同时可以根据需求灵活调整允许的来源、请求头和方法等，
 * 平衡安全性和功能性
 */

// 这段代码的意义：让浏览器在任何域名下都能调本项目的接口，且带 Cookie/认证头，避免开发/上线后被 CORS 策略拦截
@Configuration
public class CorsConfig extends WebMvcConfigurerAdapter {
	@Bean
	public FilterRegistrationBean corsFilter() {
	     UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
	     CorsConfiguration config = new CorsConfiguration();
	     config.addAllowedOrigin("*");
	     config.setAllowCredentials(true);
	     config.addAllowedHeader("*");
	     config.addAllowedMethod("*");
	     source.registerCorsConfiguration("/**", config);
	     FilterRegistrationBean bean = new FilterRegistrationBean(new CorsFilter(source));
	     bean.setOrder(0);
	     return bean;
	}

}