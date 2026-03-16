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
            // 【改动】从 MongoDB 读取上次同步高度时加 try-catch。
            // 【目的】MongoDB 未启动或网络异常时 MongoTemplate.findOne 会抛异常导致应用直接退出(exit code 1)。
            //        捕获后仅打 warn 日志并降级为使用配置的初始区块高度，保证服务能启动，待 MongoDB 恢复后由 Watcher 正常写回进度。
            WatcherLog watcherLog = null;
            try {
                watcherLog = watcherLogService.findOne(coin.getName());
            } catch (Exception e) {
                logger.warn("无法从 MongoDB 读取 WatcherLog（如 MongoDB 未启动或网络异常），将使用配置的初始区块高度: {}", e.getMessage());
            }
            logger.info("watcherLog:{}", watcherLog);
            if (watcherLog != null ) {
                watcher.setCurrentBlockHeight(watcherLog.getLastSyncHeight());
            } else {
                // 【改动】对 watcherSetting / getInitBlockHeight() 做空值保护，未配置时按 "latest" 处理，避免 NPE 或解析异常。
                String init = watcherSetting != null && watcherSetting.getInitBlockHeight() != null
                        ? watcherSetting.getInitBlockHeight() : "latest";
                if (init.equalsIgnoreCase("latest")) {
                    watcher.setCurrentBlockHeight(watcher.getNetworkBlockHeight());
                } else {
                    watcher.setCurrentBlockHeight(Long.parseLong(init));
                }
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
