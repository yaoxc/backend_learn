package com.bizzan.bitrade.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.bizzan.bitrade.Trader.CoinTrader;
import com.bizzan.bitrade.Trader.CoinTraderFactory;
import com.bizzan.bitrade.entity.ExchangeCoin;
import com.bizzan.bitrade.service.ExchangeCoinService;
import com.bizzan.bitrade.service.ExchangeOrderService;
import com.bizzan.bitrade.Trader.result.MatchResultPublisher;
import com.bizzan.bitrade.Trader.result.QueueAndWalMatchResultPublisher;

import java.util.List;

@Slf4j
@Configuration
public class CoinTraderConfig {

    /** 【改造范围】方案 A：WAL 根目录，默认 data/wal；可配置 match.wal.path */
    @Value("${match.wal.path:data/wal}")
    private String matchWalPath;
    /** 方案 A 内存队列容量，默认 2 万；可配置 match.queue.capacity，建议 1 万～10 万 */
    @Value("${match.queue.capacity:20000}")
    private int matchQueueCapacity;

    /**
     * 配置交易处理类；启用方案 A 时为每个交易对创建 队列+WAL+Sender 发布器并注入到 CoinTrader。
     */
    @Bean
    public CoinTraderFactory getCoinTrader(ExchangeCoinService exchangeCoinService, KafkaTemplate<String,String> kafkaTemplate, ExchangeOrderService exchangeOrderService){
        CoinTraderFactory factory = new CoinTraderFactory();
        List<ExchangeCoin> coins = exchangeCoinService.findAllEnabled();
        for(ExchangeCoin coin:coins) {
            log.info("init trader,symbol={}",coin.getSymbol());
            CoinTrader trader = new CoinTrader(coin.getSymbol());
            trader.setKafkaTemplate(kafkaTemplate);
            trader.setBaseCoinScale(coin.getBaseCoinScale());
            trader.setCoinScale(coin.getCoinScale());
            trader.setPublishType(coin.getPublishType());
            trader.setClearTime(coin.getClearTime());
            trader.stopTrading();

            // 【改造范围】方案 A：为每个 symbol 创建 内存队列+WAL+后台 Sender，撮合结果经此可靠投递且不阻塞热路径
            MatchResultPublisher publisher = new QueueAndWalMatchResultPublisher(coin.getSymbol(), kafkaTemplate, matchWalPath, matchQueueCapacity);
            ((QueueAndWalMatchResultPublisher) publisher).start();
            trader.setMatchResultPublisher(publisher);

            factory.addTrader(coin.getSymbol(),trader);
        }
        return factory;
    }

}
