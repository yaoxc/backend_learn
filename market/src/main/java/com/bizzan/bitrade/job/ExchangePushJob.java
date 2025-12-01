package com.bizzan.bitrade.job;                   // 定时任务所在包

import org.springframework.beans.factory.annotation.Autowired; // 自动注入
import org.springframework.messaging.simp.SimpMessagingTemplate; // WebSocket 推送模板
import org.springframework.scheduling.annotation.Scheduled; // 定时器注解
import org.springframework.stereotype.Component; // 声明为 Spring 组件，可被扫描

import com.bizzan.bitrade.entity.CoinThumb; // 币种简况（24h 涨跌、最新价等）
import com.bizzan.bitrade.entity.ExchangeOrderDirection; // BUY/SELL 枚举
import com.bizzan.bitrade.entity.ExchangeTrade; // 成交明细
import com.bizzan.bitrade.entity.TradePlate; // 盘口深度对象
import com.bizzan.bitrade.handler.NettyHandler; // Netty 推送网关

import java.util.*; // 集合工具

@Component // 被 Spring 扫描 → 实例化 → 注册到容器
public class ExchangePushJob {

    @Autowired
    private SimpMessagingTemplate messagingTemplate; // 向 WebSocket 客户端广播

    @Autowired
    private NettyHandler nettyHandler; // 向 Netty 长连接客户端推送

    // ===== 以下三个 Map 是“内存队列”：生产者线程往里扔，定时任务每 X 毫秒批量推 =====
    private Map<String, List<ExchangeTrade>> tradesQueue = new HashMap<>(); // 成交明细队列
    private Map<String, List<TradePlate>> plateQueue = new HashMap<>();     // 盘口队列
    private Map<String, List<CoinThumb>> thumbQueue = new HashMap<>();      // 币种简况队列

    /* ------------------------------------------------------------------
     三个“生产者”方法，被 CoinProcessor 调用，把数据塞进对应队列
       ------------------------------------------------------------------ */

    // 成交明细入队（线程安全）
    public void addTrades(String symbol, List<ExchangeTrade> trades) {
        List<ExchangeTrade> list = tradesQueue.get(symbol);
        if (list == null) {
            list = new ArrayList<>();
            tradesQueue.put(symbol, list);
        }
        synchronized (list) { // 多线程生产，需要同步
            list.addAll(trades);
        }
    }

    // 盘口深度入队
    public void addPlates(String symbol, TradePlate plate) {
        List<TradePlate> list = plateQueue.get(symbol);
        if (list == null) {
            list = new ArrayList<>();
            plateQueue.put(symbol, list);
        }
        synchronized (list) {
            list.add(plate);
        }
    }

    // 币种简况入队（24h 涨跌、最新价）
    public void addThumb(String symbol, CoinThumb thumb) {
        List<CoinThumb> list = thumbQueue.get(symbol);
        if (list == null) {
            list = new ArrayList<>();
            thumbQueue.put(symbol, list);
        }
        synchronized (list) {
            list.add(thumb);
        }
    }

    /* ------------------------------------------------------------------
     三个“消费者”方法，被 Spring 调度器每 X 毫秒调用一次，批量推送数据
       ------------------------------------------------------------------ */

    /**
     * 每 500 毫秒把各交易对累计的成交明细一次性推出去
     * 固定速率（fixedRate），不管上一次是否跑完，500 ms 后再次触发
     */
    @Scheduled(fixedRate = 500)
    public void pushTrade() {
        Iterator<Map.Entry<String, List<ExchangeTrade>>> it = tradesQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<ExchangeTrade>> entry = it.next();
            String symbol = entry.getKey();
            List<ExchangeTrade> trades = entry.getValue();
            if (trades.size() > 0) { // 有数据才推
                synchronized (trades) {
                    // WebSocket 主题：/topic/market/trade/BTC_USDT
                    messagingTemplate.convertAndSend("/topic/market/trade/" + symbol, trades);
                    trades.clear(); // 推送完立即清空，避免重复
                }
            }
        }
    }

    /**
     * 每 2 秒把累计的盘口深度推一次
     * fixedDelay = 2000 表示“上一次跑完后再等 2 秒”，不会重叠
     */
    @Scheduled(fixedDelay = 2000)
    public void pushPlate() {
        Iterator<Map.Entry<String, List<TradePlate>>> it = plateQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<TradePlate>> entry = it.next();
            String symbol = entry.getKey();
            List<TradePlate> plates = entry.getValue();
            if (plates.size() > 0) {
                boolean hasPushAskPlate = false;
                boolean hasPushBidPlate = false;
                synchronized (plates) {
                    for (TradePlate plate : plates) {
                        // 只推一次买盘和卖盘，避免重复
                        if (plate.getDirection() == ExchangeOrderDirection.BUY && !hasPushBidPlate) {
                            hasPushBidPlate = true;
                        } else if (plate.getDirection() == ExchangeOrderDirection.SELL && !hasPushAskPlate) {
                            hasPushAskPlate = true;
                        } else {
                            continue;
                        }
                        // 1. WebSocket 推盘口（24 档）
                        messagingTemplate.convertAndSend("/topic/market/trade-plate/" + symbol, plate.toJSON(24));
                        // 2. WebSocket 推深度（50 档）
                        messagingTemplate.convertAndSend("/topic/market/trade-depth/" + symbol, plate.toJSON(50));
                        // 3. Netty 推送给 APP/终端
                        nettyHandler.handlePlate(symbol, plate);
                    }
                    plates.clear(); // 清空已推送
                }
            }
        }
    }

    /**
     * 每 500 毫秒把最新币种简况（24h 涨跌、成交量、最新价）推出去
     */
    @Scheduled(fixedRate = 500)
    public void pushThumb() {
        Iterator<Map.Entry<String, List<CoinThumb>>> it = thumbQueue.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, List<CoinThumb>> entry = it.next();
            String symbol = entry.getKey();
            List<CoinThumb> thumbs = entry.getValue();
            if (thumbs.size() > 0) {
                synchronized (thumbs) {
                    // 只取最后一条（最新）推送
                    messagingTemplate.convertAndSend("/topic/market/thumb", thumbs.get(thumbs.size() - 1));
                    thumbs.clear();
                }
            }
        }
    }
}