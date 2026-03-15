package com.bizzan.bitrade.dao;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
// 升级说明：Spring Data 2.x 改为 QuerydslPredicateExecutor（原 QueryDslPredicateExecutor）
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import com.bizzan.bitrade.entity.Country;

import java.util.List;

/**
 * @author GS
 * @date 2018年02月10日
 */
public interface CountryDao extends JpaRepository<Country,String>,JpaSpecificationExecutor<Country>,QuerydslPredicateExecutor<Country> {
    @Query("select a from Country a order by a.sort")
    List<Country> findAllOrderBySort();

    Country findByZhName(String zhname);
}
