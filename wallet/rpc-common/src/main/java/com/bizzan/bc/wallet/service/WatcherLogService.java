package com.bizzan.bc.wallet.service;

import com.bizzan.bc.wallet.entity.WatcherLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Date;

@Service
public class WatcherLogService {
    private static final Logger logger = LoggerFactory.getLogger(WatcherLogService.class);
    @Autowired
    private MongoTemplate mongoTemplate;

    public void update(String coinName,Long height){
        WatcherLog watcherLog = findOne(coinName);
        if(watcherLog != null){
            Query query = new Query();
            Criteria criteria = Criteria.where("coinName").is(coinName);
            query.addCriteria(criteria);
            Update update =  new Update();
            update.set("lastSyncHeight",height);
            update.set("lastSyncTime",new Date());
            mongoTemplate.updateFirst(query, update, "watcher_log");
        }
        else{
            watcherLog = new WatcherLog();
            watcherLog.setCoinName(coinName);
            watcherLog.setLastSyncHeight(height);
            watcherLog.setLastSyncTime(new Date());
            mongoTemplate.insert(watcherLog,"watcher_log");
        }
    }

    public WatcherLog findOne(String coinName){
        Query query = new Query();
        Criteria criteria = Criteria.where("coinName").is(coinName);
        query.addCriteria(criteria);
        // 【改动】Mongo 查询增加统一容错。
        // 【目的】当 MongoDB 未启动或网络异常时，findOne 会抛出 DataAccessResourceFailureException /
        //        MongoTimeoutException 并导致整个应用启动失败。这里捕获异常，仅打印告警日志并返回 null，
        //        由调用方回退到配置的初始区块高度，保证 ETH / USDT 等钱包服务在 Mongo 不可用时也能继续启动。
        try {
            return mongoTemplate.findOne(query, WatcherLog.class,"watcher_log");
        } catch (Exception e) {
            logger.warn("查询 watcher_log 失败，coinName={}，可能是 MongoDB 未启动或连接异常，将返回 null 让调用方使用默认高度。原因: {}",
                    coinName, e.getMessage());
            return null;
        }
    }
}
