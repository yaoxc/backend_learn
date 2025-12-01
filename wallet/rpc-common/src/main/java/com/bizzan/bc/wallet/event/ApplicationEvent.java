package com.bizzan.bc.wallet.event;

import com.bizzan.bc.wallet.service.WatcherLogService;
import com.bizzan.bc.wallet.component.Watcher;
import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.entity.WatcherLog;
import com.bizzan.bc.wallet.entity.WatcherSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Service;

@Service
public class ApplicationEvent implements ApplicationListener<ContextRefreshedEvent> {
    private Logger logger = LoggerFactory.getLogger(ApplicationEvent.class);
    @Autowired
    private DepositEvent depositEvent;

    /**
     * @Autowired(required = false) 里的 required 单词直译就是 “必需的”.
     * 用来告诉 Spring： “这个依赖我‘想要’，但‘没有也不碍事’；容器里找得到就注入，找不到也别启动失败，直接留 null（或保持原值）。”
     *
     * ex:
     *@Component
     * public class PayService {
     *
     *     // 1. 找不到 SmsSender 启动也不报错，只是 smsSender = null
     *     @Autowired(required = false)
     *     private SmsSender smsSender;
     *
     *     public void pay(Order order) {
     *         // 2. 用前必须判空，否则 NPE
     *         if (smsSender != null) {
     *             smsSender.send("支付成功 " + order.getId());
     *         }
     *     }
     * }
     *
     */
    @Autowired(required = false)
    private Watcher watcher;
    @Autowired
    private Coin coin;
    @Autowired
    private WatcherLogService watcherLogService;
    @Autowired
    private WatcherSetting watcherSetting;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        if(watcher != null) {
            logger.info("=======Initialize Block Data Watcher=====");
            WatcherLog watcherLog = watcherLogService.findOne(coin.getName());
            logger.info("watcherLog:{}",watcherLog);
            if (watcherLog != null ) {
                watcher.setCurrentBlockHeight(watcherLog.getLastSyncHeight());
            } else if(watcherSetting.getInitBlockHeight().equalsIgnoreCase("latest")) {
                watcher.setCurrentBlockHeight(watcher.getNetworkBlockHeight());
            }else {
                Long height = Long.parseLong(watcherSetting.getInitBlockHeight());
                watcher.setCurrentBlockHeight(height);
            }
            //初始化参数
            //设置每次同步区块数量
            watcher.setStep(watcherSetting.getStep());
            //设置任务执行间隔
            watcher.setCheckInterval(watcherSetting.getInterval());
            watcher.setDepositEvent(depositEvent);
            //设置币种配置信息
            watcher.setCoin(coin);
            watcher.setWatcherLogService(watcherLogService);
            //设置交易需要的确认数
            watcher.setConfirmation(watcherSetting.getConfirmation());
            new Thread(watcher).start();
        }
        else{
            logger.error("=====启动程序失败=====");
        }
    }
}
