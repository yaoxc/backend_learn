package com.bizzan.bitrade.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.HttpSessionIdResolver;

import com.bizzan.bitrade.ext.SmartHttpSessionStrategy;

/**
 * 升级说明：Spring Session 2.x 使用 HttpSessionIdResolver 替代 HttpSessionStrategy，SmartHttpSessionStrategy 已实现新接口。
 */
@Configuration
@EnableRedisHttpSession
public class HttpSessionConfig {

	@Bean
	public HttpSessionIdResolver httpSessionIdResolver() {
		return new SmartHttpSessionStrategy();
	}
}
