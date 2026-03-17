package com.bizzan.bitrade.job;


import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.ExchangeCoin;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.service.ExchangeCoinService;
import com.bizzan.bitrade.service.ExchangeOrderService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OrderUpdateJob {
    @Autowired
    private ExchangeOrderService orderService;
    @Autowired
    private ExchangeCoinService coinService;
    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    private Logger logger = LoggerFactory.getLogger(OrderUpdateJob.class);

    // 1小时检查一次超时订单
    // 【说明】自动取消订单的原因：
    //        交易对（ExchangeCoin）可以配置 maxTradingTime（最大挂单时长，单位秒/分钟，视业务而定），
    //        撮合引擎只负责撮合撮中的部分，不主动清理“长时间无人成交、已超出允许挂单时长”的订单。
    //        这个定时任务会定期扫描所有启用的交易对，找出超出 maxTradingTime 的挂单，
    //        并向 Kafka 主题 exchange-order-cancel 发送撤单指令，让撮合系统或下游根据指令统一取消这些“超时挂单”，
    //        避免订单永久挂在簿上，占用用户额度或影响盘口展示。
    @Scheduled(fixedRate = 3600*1000)
    public void autoCancelOrder(){
        logger.info("start autoCancelOrder...");
        List<ExchangeCoin> coinList = coinService.findAllEnabled();
        logger.info("可交易的coin列表: {}", coinList.size());
        coinList.forEach(coin -> {
            logger.info("交易对: {} --- 最大挂单时长: {}", coin.getSymbol(), coin.getMaxTradingTime());
        });
        coinList.forEach(coin->{
            if(coin.getMaxTradingTime() > 0){
                List<ExchangeOrder> orders =  orderService.findOvertimeOrder(coin.getSymbol(), coin.getMaxTradingTime());
                orders.forEach(order -> {
                    logger.info("交易对: {} --- 超出maxTradingTime的 orderId: {} --- time: {}", coin.getSymbol(), order.getOrderId(), order.getTime());
                    // 【改动】超时撤单消息发送到对外入口 topic，由 exchange-relay 中转到内部撮合 topic。
                    // 【目的】统一所有订单/撤单入口，方便灰度、限流和权限控制，避免业务服务直接依赖撮合内部 topic。
                    // 这段代码只负责“发射撤单命令”，不负责直接改状态，是刻意的分层设计
                    kafkaTemplate.send("exchange-order-cancel-ingress", JSON.toJSONString(order));
                });
            }
        });
        logger.info("end autoCancelOrder...");
    }


}
