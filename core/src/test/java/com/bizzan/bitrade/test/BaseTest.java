package com.bizzan.bitrade.test;

import org.junit.runner.RunWith;
import org.springframework.test.annotation.Commit;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.AbstractJUnit4SpringContextTests;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.transaction.annotation.Transactional;

/** 升级说明：Spring 5 移除 @TransactionConfiguration，改为 @Transactional + @Commit（等价于 defaultRollback = false）。 */
@RunWith(JUnit4ClassRunner.class)
@ContextConfiguration({"classpath:spring-service.xml"})
@Transactional
@Commit
public class BaseTest extends AbstractJUnit4SpringContextTests {

}
