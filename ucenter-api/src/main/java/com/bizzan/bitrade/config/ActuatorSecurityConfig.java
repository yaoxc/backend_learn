package com.bizzan.bitrade.config;

import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;

/**
 * 升级说明：原读取 management.context-path（1.x）配置监控路径；2.x 改为 management.endpoints.web.base-path，
 * 若需与配置一致可改为 getProperty("management.endpoints.web.base-path")，默认 "/actuator"；当前为空则按 "" 处理，仍对根路径做 Basic 认证。
 */
@Configuration
@EnableWebSecurity
public class ActuatorSecurityConfig extends WebSecurityConfigurerAdapter {

	@Autowired
	Environment env;

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		String contextPath = env.getProperty("management.context-path");
		if (StringUtils.isEmpty(contextPath)) {
			contextPath = "";
		}
		http.csrf().disable();
		http.authorizeRequests().antMatchers("/**" + contextPath + "/**").authenticated().anyRequest().permitAll().and()
				.httpBasic();
	}
}