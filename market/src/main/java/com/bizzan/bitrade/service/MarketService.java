package com.bizzan.bitrade.service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import com.bizzan.bitrade.entity.ExchangeTrade;
import com.bizzan.bitrade.entity.KLine;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class MarketService {
    @Autowired
    private MongoTemplate mongoTemplate;

    public List<KLine> findAllKLine(String symbol,String peroid){
        Sort sort = Sort.by(Sort.Order.desc("time"));
        Query query = new Query().with(sort).limit(1000);

        // 【改动】初始化阶段加载 K 线数据时增加 MongoDB 查询容错。
        // 【目的】当 MongoDB 未启动或连接异常时，mongoTemplate.find 会抛出 MongoTimeoutException /
        //        DataAccessResourceFailureException 等，之前会直接导致 Application run failed，
        //        进而让 market 服务在启动过程中退出。这里捕获异常，仅打印 warn 日志并返回空列表，
        //        允许应用继续启动，待 MongoDB 恢复后由后续行情推送正常写回数据。
        try {
            return mongoTemplate.find(query,KLine.class,"exchange_kline_"+symbol+"_"+peroid);
        } catch (Exception e) {
            log.warn("从 MongoDB 加载 K 线初始化数据失败，symbol={} period={}，可能是 MongoDB 未启动或连接异常，将返回空列表继续启动。原因: {}",
                    symbol, peroid, e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<KLine> findAllKLine(String symbol,long fromTime,long toTime,String period){
        Criteria criteria = Criteria.where("time").gte(fromTime).andOperator(Criteria.where("time").lte(toTime));
        Sort sort = Sort.by(Sort.Order.asc("time"));
        Query query = new Query(criteria).with(sort);
        List<KLine> kLines = mongoTemplate.find(query,KLine.class,"exchange_kline_"+symbol+"_"+ period);
        return kLines;
    }

    public ExchangeTrade findFirstTrade(String symbol,long fromTime,long toTime){
        Criteria criteria = Criteria.where("time").gte(fromTime).andOperator(Criteria.where("time").lte(toTime));
        Sort sort = Sort.by(Sort.Order.asc("time"));
        Query query = new Query(criteria).with(sort);
        return mongoTemplate.findOne(query,ExchangeTrade.class,"exchange_trade_"+symbol);
    }

    public ExchangeTrade findLastTrade(String symbol,long fromTime,long toTime){
        Criteria criteria = Criteria.where("time").gte(fromTime).andOperator(Criteria.where("time").lte(toTime));
        Sort sort = Sort.by(Sort.Order.desc("time"));
        Query query = new Query(criteria).with(sort);
        return mongoTemplate.findOne(query,ExchangeTrade.class,"exchange_trade_"+symbol);
    }

    public ExchangeTrade findTrade(String symbol, long fromTime, long toTime, Sort.Order order){
        Criteria criteria = Criteria.where("time").gte(fromTime).andOperator(Criteria.where("time").lte(toTime));
        Sort sort = Sort.by(order);
        Query query = new Query(criteria).with(sort);
        return mongoTemplate.findOne(query,ExchangeTrade.class,"exchange_trade_"+symbol);
    }

    public List<ExchangeTrade> findTradeByTimeRange(String symbol, long timeStart, long timeEnd){
        Criteria criteria = Criteria.where("time").gte(timeStart).andOperator(Criteria.where("time").lt(timeEnd));
        Sort sort = Sort.by(Sort.Order.asc("time"));
        Query query = new Query(criteria).with(sort);

        return mongoTemplate.find(query,ExchangeTrade.class,"exchange_trade_"+symbol);
    }

    public void saveKLine(String symbol,KLine kLine){
        mongoTemplate.insert(kLine,"exchange_kline_"+symbol+"_"+kLine.getPeriod());
    }

    /**
     * 查找某时间段内的交易量
     * @param symbol
     * @param timeStart
     * @param timeEnd
     * @return
     */
    public BigDecimal findTradeVolume(String symbol, long timeStart, long timeEnd){
        Criteria criteria = Criteria.where("time").gt(timeStart)
                .andOperator(Criteria.where("time").lte(timeEnd));
                //.andOperator(Criteria.where("volume").gt(0));
        Sort sort = Sort.by(Sort.Order.asc("time"));
        Query query = new Query(criteria).with(sort);
        List<KLine> kLines =  mongoTemplate.find(query,KLine.class,"exchange_kline_"+symbol+"_1min");
        BigDecimal totalVolume = BigDecimal.ZERO;
        for(KLine kLine:kLines){
            totalVolume = totalVolume.add(kLine.getVolume());
        }
        return totalVolume;
    }
}
