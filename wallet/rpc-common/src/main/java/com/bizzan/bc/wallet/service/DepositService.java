package com.bizzan.bc.wallet.service;

import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.entity.Deposit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * 充值记录 Mongo 读写。
 * 升级说明：Spring Data 2.x→3.x，Sort 使用 Sort.by(...)、PageRequest 使用 PageRequest.of(...)。
 */
@Service
public class DepositService {
    @Autowired
    private MongoTemplate mongoTemplate;
    @Autowired
    private Coin coin;

    public void save(Deposit tx){
        mongoTemplate.insert(tx,getCollectionName());
    }

    public String getCollectionName(){
        return coin.getUnit() + "_deposit";
    }

    public boolean exists(Deposit deposit){
        Criteria criteria =  Criteria.where("address").is(deposit.getAddress())
                .andOperator(Criteria.where("txid").is(deposit.getTxid()));
        Query query = new Query(criteria);
        return mongoTemplate.exists(query,getCollectionName());
    }


    public Deposit findLatest(){
        // Spring Data 2.x→3.x：Sort.by(...)、PageRequest.of(...) 替代已废弃的构造方式
        Sort sort = Sort.by(Sort.Direction.DESC, "blockHeight");
        PageRequest page = PageRequest.of(0, 1, sort);
        Query query = new Query();
        query.with(page);
        Deposit result = mongoTemplate.findOne(query,Deposit.class,getCollectionName());
        return result;
    }
}
