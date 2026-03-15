package com.bizzan.bitrade.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import com.bizzan.bitrade.entity.Coin;
import com.bizzan.bitrade.service.CoinService;
import com.bizzan.bitrade.system.CoinExchangeFactory;
import com.bizzan.bitrade.util.MessageResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
@Slf4j
public class CheckExchangeRate {
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private CoinExchangeFactory factory;
    @Autowired
    private CoinService coinService;
    
    private String serviceName = "bitrade-market";
    
    /**
     * 升级说明：syncRate 通过服务名 bitrade-market 调 market 服务；本地未起 Eureka 或 market 未注册时，
     * LoadBalancer 无可用实例会抛 Service Instance cannot be null。此处 try-catch 做容错，仅打 debug 日志不抛错，避免定时任务刷屏。
     */
    @Scheduled(fixedRate = 60 * 1000)
    public void syncRate() {
        try {
            BigDecimal cnyRate = getUsdCnyRate();
            factory.getCoins().forEach((symbol, value) -> {
                BigDecimal usdRate = getUsdRate(symbol);
                factory.set(symbol, usdRate, cnyRate.multiply(usdRate).setScale(2, RoundingMode.UP));
            });
        } catch (Exception e) {
            log.debug("syncRate skip: market service unavailable, {}", e.getMessage());
        }
    }
    
    /**
     * 每10分钟运行(检查是否有新增的币种）
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void checkIfHasNewCoin(){
    	List<Coin> coinList = coinService.findAll();
    	
    	for(Coin coin : coinList) {
    		if(factory.getCoins().containsKey(coin.getUnit())) {
    			continue;
    		}
    		factory.set(coin.getUnit(), new BigDecimal(coin.getUsdRate()), new BigDecimal(coin.getCnyRate()));
    	}
    }
    
    private BigDecimal getUsdRate(String unit) {
        try {
            String url = "http://" + serviceName + "/market/exchange-rate/usd/{coin}";
            ResponseEntity<MessageResult> result = restTemplate.getForEntity(url, MessageResult.class, unit);
            log.info("remote call:url={},unit={}", url, unit);
            if (result.getStatusCode().value() == 200 && result.getBody() != null && result.getBody().getCode() == 0) {
                BigDecimal rate = new BigDecimal((String) result.getBody().getData());
                return rate;
            }
        } catch (Exception e) {
            log.trace("getUsdRate {} failed: {}", unit, e.getMessage());
        }
        return BigDecimal.ZERO;
    }

    /** 同上，getUsdCnyRate 失败时容错返回 ZERO。 */
    private BigDecimal getUsdCnyRate() {
        try {
            String url = "http://" + serviceName + "/market/exchange-rate/usd-cny";
            ResponseEntity<MessageResult> result = restTemplate.getForEntity(url, MessageResult.class);
            log.info("remote call:url={}", url);
            if (result.getStatusCode().value() == 200 && result.getBody() != null && result.getBody().getCode() == 0) {
                BigDecimal rate = new BigDecimal((Double) result.getBody().getData());
                return rate;
            }
        } catch (Exception e) {
            log.trace("getUsdCnyRate failed: {}", e.getMessage());
        }
        return BigDecimal.ZERO;
    }
}
