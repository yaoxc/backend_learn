package com.bizzan.bitrade.job;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.alibaba.fastjson.JSON;
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

import lombok.extern.slf4j.Slf4j;

/**
 * 自动同步Exchange撮合交易中心中的交易对
 *
 */
@Component
@Slf4j
public class CoinProcessorJob {
	@Autowired
    private CoinProcessorFactory processorFactory;
    @Autowired
    private ExchangeCoinService coinService;
    
    @Autowired
    private RestTemplate restTemplate;
    
    @Autowired
    MongoMarketHandler mongoMarketHandler;
    
    @Autowired
    WebsocketMarketHandler wsHandler;
    
    @Autowired
    NettyHandler nettyHandler;
    
    @Autowired
    MarketService marketService;
    
    @Autowired
    CoinExchangeRate exchangeRate;
    
    
    /**
     * 1分钟定时器，每隔1分钟进行一次
     */
    @Scheduled(cron = "0 */1 * * * ?")
    public void synchronizeExchangeCenter(){
    	log.info("========CoinProcessorJob========synchronize the exchange coinpairs");
    	// 获取撮合交易中心支持的币种
    	String serviceName = "SERVICE-EXCHANGE-TRADE";
        String url = "http://" + serviceName + "/monitor/engines";
        ResponseEntity<HashMap> resultStr = restTemplate.getForEntity(url, HashMap.class);
        Map<String, Integer> exchangeCenterCoins = (HashMap<String, Integer>)resultStr.getBody();
        log.info("========CoinProcessorJob========now exchange support coins:{}", JSON.toJSONString(exchangeCenterCoins));
        Map<String, CoinProcessor> processorMap = processorFactory.getProcessorMap();

        log.info("========CoinProcessorJob========now market support coins");

        // 判断撮合交易中心存在的币是否在market中存在
        //
        for (Map.Entry<String, Integer> coin : exchangeCenterCoins.entrySet()) {
        	String symbol = coin.getKey();
        	Integer status = coin.getValue();  // 根据CoinTrader.java中的 tradingHalt字段； 1 正常交易， 2 暂停交易
        	// 是否已有该币种处理者
        	if(processorMap.containsKey(symbol)) {
        		CoinProcessor temProcessor = processorMap.get(symbol);
        		if(status.intValue() == 1) { // 正常交易
        			// 撮合交易启用状态，那么market中应该开始处理K线
        			if(temProcessor.isStopKline()) {
        				temProcessor.setIsStopKLine(false);
        				log.info("[Start] " + symbol + " will start generate KLine.");
        			}
        		}else if(status.intValue() == 2) {  // 暂停交易
        			// 停止状态，那么market中应该停止处理K线
        			if(!temProcessor.isStopKline()) {
        				log.info("[Stop]" + symbol + " will stop generate KLine.");
        				temProcessor.setIsStopKLine(true);
        			}
        		}

                // 立即跳过当前这一次循环的剩余代码，直接进入下一次循环判断（即继续执行循环的下一个迭代）。
        		continue;
        	}

            // 下面到continue的作用： 如果数据库里找不到这个币种（focusCoin == null），就不执行当前循环里后面的代码，直接跳过本次循环，进入下一个币种的处理。
        	ExchangeCoin focusCoin = coinService.findBySymbol(symbol); // 该币种是否存在于数据库中
        	if(focusCoin == null) {
        		continue;
        	}

            // 如果 代币对 之前暂停交易，现在开启了交易，就给它创建一个 Processor
            //  MonitorController 的 startTrader 方法，可以开启一个之前 暂停交易的
        	log.info("============[Start]initialized New CoinProcessor(" + symbol + ") start=====================");
        	// 新建 Processor
        	CoinProcessor processor = new DefaultCoinProcessor(symbol, focusCoin.getBaseSymbol());
            processor.addHandler(mongoMarketHandler);
            processor.addHandler(wsHandler);
            processor.addHandler(nettyHandler);
            processor.setMarketService(marketService);
            processor.setExchangeRate(exchangeRate);
            processor.initializeThumb();
            processor.initializeUsdRate();


            /**
             // 在 CoinTrader 代码里的具体含义

             private boolean tradingHalt = false;   // 正常交易

             public void haltTrading(){
                this.tradingHalt = true;            // 立即暂停撮合
             }

             public void resumeTrading(){
                this.tradingHalt = false;           // 恢复交易
             }


             */
            processor.setIsHalt(false);
            
            if(status.intValue() == 2) { // 暂停交易
            	processor.setIsStopKLine(true);
            }
            processorFactory.addProcessor(symbol, processor);
            
            log.info("============[End]initialized  New CoinProcessor(" + symbol + ") end=====================");
        }
    }
}
