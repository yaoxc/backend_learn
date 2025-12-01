package com.bizzan.bitrade.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.bizzan.bitrade.component.CoinExchangeRate;
import com.bizzan.bitrade.entity.ExchangeCoin;
import com.bizzan.bitrade.handler.MongoMarketHandler;
import com.bizzan.bitrade.handler.NettyHandler;
import com.bizzan.bitrade.handler.WebsocketMarketHandler;
import com.bizzan.bitrade.processor.CoinProcessor;
import com.bizzan.bitrade.processor.CoinProcessorFactory;
import com.bizzan.bitrade.processor.DefaultCoinProcessor;
import com.bizzan.bitrade.service.ExchangeCoinService;
import com.bizzan.bitrade.service.MarketService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration          // 声明配置类，Spring 启动时执行
@Slf4j                  // 日志
public class ProcessorConfig {

    @Bean               // 返回的 Bean 被整个应用注入
    public CoinProcessorFactory processorFactory(
            MongoMarketHandler mongoMarketHandler,   // 单例：写 Mongo
            WebsocketMarketHandler wsHandler,        // 单例：推 WebSocket
            NettyHandler nettyHandler,               // 单例：推 Netty
            MarketService marketService,             // 单例：读写行情缓存
            CoinExchangeRate exchangeRate,           // 单例：汇率组件
            ExchangeCoinService coinService,         // 单例：查数据库
            RestTemplate restTemplate) {             // 单例：HTTP 调用

        log.info("====initialized CoinProcessorFactory start==================================");

        // 创建工厂
        CoinProcessorFactory factory = new CoinProcessorFactory();

        // 查出所有 启用的 交易对
        List<ExchangeCoin> coins = coinService.findAllEnabled();
        log.info("exchange-coin result:{}", coins);

        // 为每个交易对 new 一个处理器，但 Handler 都是同一个 Spring Bean
        for (ExchangeCoin coin : coins) {
            // 新建处理器（每 symbol 一个实例）
            CoinProcessor processor = new DefaultCoinProcessor(coin.getSymbol(), coin.getBaseSymbol());

            // 【责任链设计模式】
            // 把「写库 + 推送给前端」的 Handler 全部挂到同一条责任链
            processor.addHandler(mongoMarketHandler);   // 写 MongoDB
            processor.addHandler(wsHandler);            // WebSocket 推行情
            processor.addHandler(nettyHandler);         // Netty 推行情

            // 注入业务服务
            processor.setMarketService(marketService);
            processor.setExchangeRate(exchangeRate);
            processor.setIsStopKLine(true);            // 示例：暂停 K 线生成（可按配置改）

            // 注册到工厂
            factory.addProcessor(coin.getSymbol(), processor);
            log.info("new processor = ", processor);
        }

        log.info("====initialized CoinProcessorFactory completed====");
        log.info("CoinProcessorFactory = ", factory);

        // 把工厂回灌给汇率组件，方便它内部调用行情
        exchangeRate.setCoinProcessorFactory(factory);
        return factory;
    }
}