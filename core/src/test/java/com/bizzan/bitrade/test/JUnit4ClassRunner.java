package com.bizzan.bitrade.test;

import org.junit.runners.model.InitializationError;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/** 升级说明：Spring 5 移除了 Log4jConfigurer，日志由 Spring Boot / Logback 统一配置，故去掉静态块。 */
public class JUnit4ClassRunner extends SpringJUnit4ClassRunner {
    public JUnit4ClassRunner(Class<?> clazz) throws InitializationError {
        super(clazz);
    }
}
