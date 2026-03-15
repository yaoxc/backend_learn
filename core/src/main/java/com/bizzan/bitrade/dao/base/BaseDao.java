package com.bizzan.bitrade.dao.base;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
// 升级说明：Spring Data 2.x 将 QueryDslPredicateExecutor 重命名为 QuerydslPredicateExecutor（小写 dsl）
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * @author GS
 * @description
 * @date 2018/1/18 10:38
 */
@NoRepositoryBean
public interface BaseDao<T> extends JpaRepository<T,Long>,JpaSpecificationExecutor<T>,QuerydslPredicateExecutor<T> {
}
