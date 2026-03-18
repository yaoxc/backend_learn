package com.bizzan.bitrade.config;

import com.bizzan.bitrade.util.TradeIdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;

@Configuration
public class TradeIdConfig {

    @Value("${exchange.trade-id.worker-id:0}")
    private long workerId;

    @Value("${exchange.trade-id.datacenter-id:0}")
    private long datacenterId;

    @PostConstruct
    public void init() {
        TradeIdGenerator.init(workerId, datacenterId);
    }
}

