package com.bizzan.bitrade.Trader;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.*;
import com.bizzan.bitrade.Trader.result.MatchResult;
import com.bizzan.bitrade.Trader.result.MatchResultPublisher;
import com.bizzan.bitrade.Trader.result.OrderEventLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.util.concurrent.ListenableFuture;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单交易对内存撮合引擎。维护买卖限价/市价订单簿，按价格优先、时间优先撮合，产出成交与完成订单经 Kafka 或 MatchResultPublisher 下发。
 * <p>
 * 职责：纯内存撮合，不落库；落库由 market 模块消费 Kafka 完成。
 * 支持：限价/市价、分摊模式（FENTAN）；方案 A 下结果经队列+WAL 异步发 Kafka，热路径不阻塞。
 * <p>
 * <b>并发与锁（为何用 synchronized 而非分布式锁）</b><br>
 * 本引擎采用「单 JVM 内、每交易对一个 CoinTrader、订单簿纯内存」的部署模型：同一 symbol 的订单只会被本进程内
 * 的该 CoinTrader 处理，不存在多节点同时写同一订单簿的情况。因此只需保证同一 JVM 内多线程访问订单簿的互斥，
 * 使用 {@code synchronized(订单簿)} 即可，无需分布式锁。若改为多节点共享同一 symbol 的撮合（例如多实例竞争消费
 * 同一 symbol 的订单流），则需引入分布式锁或单写者设计；当前架构下不适用。
 * <p>
 * <b>业界 CEX 常见做法</b>：多数采用「按 symbol 单写者」——每个交易对由单一撮合进程/线程负责，订单按 symbol
 * 路由到对应进程；进程内或单线程事件循环（无锁）、或多线程 + 每 symbol 一把锁（与本实现类似）。分布式锁多用于
 * 高可用下的主备切换/主节点选举，而非对同一订单簿的多节点加锁。
 */
public class CoinTrader {

    private static final Logger logger = LoggerFactory.getLogger(CoinTrader.class);

    // ===================== 常量（Kafka 回退发送） =====================
    private static final int KAFKA_SEND_MAX_RETRIES = 3;
    private static final long KAFKA_SEND_RETRY_DELAY_MS = 200;
    private static final long KAFKA_SEND_GET_TIMEOUT_SECONDS = 10;
    private static final int KAFKA_BATCH_MAX_SIZE = 1000;

    // ===================== 交易对与配置 =====================
    private final String symbol;
    private KafkaTemplate<String, String> kafkaTemplate;
    private int coinScale = 4;
    private int baseCoinScale = 4;
    private ExchangeCoinPublishType publishType;
    private String clearTime;
    private SimpleDateFormat dateTimeFormat;

    // ===================== 订单簿（价格优先 + 时间优先） =====================
    /** 买限价队列：价格从高到低。无固定容量，受内存限制；挂单与撮合共用 synchronized(本队列)，锁竞争会影响挂单与撮合吞吐。 */
    private TreeMap<BigDecimal, MergeOrder> buyLimitPriceQueue;
    /** 卖限价队列：价格从低到高。无固定容量，受内存限制；挂单与撮合共用 synchronized(本队列)，锁竞争会影响挂单与撮合吞吐。 */
    private TreeMap<BigDecimal, MergeOrder> sellLimitPriceQueue;
    /** 买市价队列、卖市价队列：FIFO */
    private LinkedList<ExchangeOrder> buyMarketQueue;
    private LinkedList<ExchangeOrder> sellMarketQueue;

    // ===================== 盘口与状态 =====================
    private TradePlate buyTradePlate;
    private TradePlate sellTradePlate;
    private boolean tradingHalt = false;
    private boolean ready = false;

    // ===================== 幂等防重（撮合入口） =====================
    /**
     * 已接收订单集合（按 orderId 去重）。
     *
     * 设计目标：
     * - Kafka / exchange-relay / Outbox 等链路都按“至少一次”设计，某一笔订单消息可能被重复投递到 topic `exchange-order`；
     * - 为避免同一笔订单被重复写入订单簿、重复参与撮合，这里在单进程内对 orderId 做一次幂等判断；
     * - 仅对「正常撮合路径」生效（replayMode=false 时），回放 WAL 恢复订单簿时不使用该集合，避免挡住历史日志。
     *
     * 注意：
     * - 这是单进程内的防重，跨进程/跨实例的幂等仍需依赖下游（如成交结果以 messageId/orderId 幂等落库）。
     */
    private final Set<String> seenOrderIds = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** 方案 A：注入后撮合结果经队列+WAL 异步发送；未注入则同步发 exchange-trade / exchange-order-completed */
    private MatchResultPublisher matchResultPublisher;
    /** 形态一：订单事件日志，用于重启时回放恢复订单簿，避免从 DB 加载导致重复撮合 */
    private OrderEventLogger orderEventLogger;
    /** 回放模式：为 true 时不写订单日志、不发布撮合结果，仅用于 replayOrderLog() 恢复订单簿 */
    private volatile boolean replayMode = false;

    // ===================== 构造与初始化 =====================

    public CoinTrader(String symbol) {
        this.symbol = symbol;
        initialize();
    }

    /**
     * 初始化本交易对的订单簿与盘口。
     * 买限价队列价格降序：最高买价优先；卖限价队列价格升序：最低卖价优先，满足价格优先规则。
     */
    public void initialize(){
        logger.info("init CoinTrader for symbol {}",symbol);
        buyLimitPriceQueue = new TreeMap<>(Comparator.reverseOrder());
        this.sellLimitPriceQueue = new TreeMap<>(Comparator.naturalOrder());
        this.buyMarketQueue = new LinkedList<>();
        this.sellMarketQueue = new LinkedList<>();
        this.sellTradePlate = new TradePlate(symbol,ExchangeOrderDirection.SELL);
        this.buyTradePlate = new TradePlate(symbol,ExchangeOrderDirection.BUY);
        this.dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    }

    /**
     * 将限价单加入本方订单簿并更新盘口。买盘按价格高到低、卖盘按价格低到高已由 TreeMap 顺序保证。
     * ready 为 false 时不推盘口，避免启动/恢复阶段把未就绪数据推给前端。
     */
    public void addLimitPriceOrder(ExchangeOrder exchangeOrder){
        if(exchangeOrder.getType() != ExchangeOrderType.LIMIT_PRICE){
            return ;
        }
        TreeMap<BigDecimal,MergeOrder> list;
        if(exchangeOrder.getDirection() == ExchangeOrderDirection.BUY){
            list = buyLimitPriceQueue;
            buyTradePlate.add(exchangeOrder);
            if(ready) {
                sendTradePlateMessage(buyTradePlate);
            }
        } else {
            list = sellLimitPriceQueue;
            sellTradePlate.add(exchangeOrder);
            if(ready) {
                sendTradePlateMessage(sellTradePlate);
            }
        }
        synchronized (list) {
            MergeOrder mergeOrder = list.get(exchangeOrder.getPrice());
            if(mergeOrder == null){
                mergeOrder = new MergeOrder();
                mergeOrder.add(exchangeOrder);
                list.put(exchangeOrder.getPrice(),mergeOrder);
            }
            else {
                mergeOrder.add(exchangeOrder);
            }
        }
    }

    /**
     * 添加市价单到本方市价队列尾部，FIFO 保证时间优先。
     */
    public void addMarketPriceOrder(ExchangeOrder exchangeOrder){
        if(exchangeOrder.getType() != ExchangeOrderType.MARKET_PRICE){
            return ;
        }
        logger.info("addMarketPriceOrder,orderId = {}", exchangeOrder.getOrderId());
        LinkedList<ExchangeOrder> list = exchangeOrder.getDirection() == ExchangeOrderDirection.BUY ? buyMarketQueue : sellMarketQueue;
        synchronized (list) {
            list.addLast(exchangeOrder);
        }
    }

    // ===================== 撮合入口 =====================

    /**
     * 批量处理订单：顺序调用单笔 trade，保证同批顺序且每笔独立撮合。
     * 为什么这么写：便于上层一次投递多笔订单时保持处理顺序，且每笔订单独立撮合、互不影响。
     */
    public void trade(List<ExchangeOrder> orders) throws ParseException {
        if (tradingHalt) return;
        for (ExchangeOrder order : orders) {
            trade(order);
        }
    }

    /**
     * 单笔订单撮合入口：校验 → 选对手盘 → 市价/限价分支 → 撮合后未成交部分挂单；结果经 flushMatchResult 发出。
     */
    public void trade(ExchangeOrder exchangeOrder) throws ParseException {
        // 0. 回放模式下仅用于恢复订单簿，不做幂等校验、不输出撮合结果（flushMatchResult 内已判断 replayMode）
        if (replayMode) {
            // 复用正常撮合逻辑恢复队列结构，但不写入 seenOrderIds，避免影响重启后的正常幂等判断
            internalTrade(exchangeOrder, false);
            return;
        }
        // 1. 暂停交易时不撮合，避免运维维护时产生新成交
        if (tradingHalt) {
            return;
        }
        // 1.5 幂等防重：同一进程内，同一个 orderId 只允许进入撮合逻辑一次。
        //      seenOrderIds.add(...) 返回 false 表示之前已经处理过该订单，本次认为是重复消息直接丢弃。
        if (exchangeOrder.getOrderId() != null && !seenOrderIds.add(exchangeOrder.getOrderId())) {
            logger.info("duplicate order ignored in trader, symbol={}, orderId={}", symbol, exchangeOrder.getOrderId());
            return;
        }
        internalTrade(exchangeOrder, true);
    }

    /**
     * 实际撮合实现，将原 trade(ExchangeOrder) 中的逻辑抽出，供正常路径与回放路径复用。
     *
     * @param exchangeOrder 当前订单
     * @param checkSymbol   是否校验 symbol 一致（正常路径需要，回放订单日志时可以默认一致）
     */
    private void internalTrade(ExchangeOrder exchangeOrder, boolean checkSymbol) throws ParseException {
        // 2. 本引擎只处理本交易对，避免误把其它 symbol 的订单写进本实例订单簿
        if (checkSymbol && !symbol.equalsIgnoreCase(exchangeOrder.getSymbol())) {
            logger.info("unsupported symbol,coin={},base={}", exchangeOrder.getCoinSymbol(), exchangeOrder.getBaseSymbol());
            return ;
        }
        // 2.5 形态一：非回放模式下将订单接入事件写入订单日志，供重启时回放恢复订单簿
        if (!replayMode && orderEventLogger != null) {
            orderEventLogger.appendOrder(exchangeOrder);
        }
        // 3. 无剩余可成交量则直接返回，避免无效遍历（已撤/已完成订单不应再参与撮合）
        if(exchangeOrder.getAmount().compareTo(BigDecimal.ZERO) <=0 || exchangeOrder.getAmount().subtract(exchangeOrder.getTradedAmount()).compareTo(BigDecimal.ZERO)<=0){
            return ;
        }

        // 4. 选择对手盘：买单对卖盘，卖单对买盘，保证价格方向可成交
        TreeMap<BigDecimal,MergeOrder> limitPriceOrderList;
        LinkedList<ExchangeOrder> marketPriceOrderList;
        if(exchangeOrder.getDirection() == ExchangeOrderDirection.BUY){
            limitPriceOrderList = sellLimitPriceQueue;
            marketPriceOrderList = sellMarketQueue;
        }
        else{
            limitPriceOrderList = buyLimitPriceQueue;
            marketPriceOrderList = buyMarketQueue;
        }

        // 5. 市价单：只和限价对手盘撮合，不挂本方盘口；有剩余再放回市价队列
        if(exchangeOrder.getType() == ExchangeOrderType.MARKET_PRICE){
            matchMarketPriceWithLPList(limitPriceOrderList, exchangeOrder);
        }
        // 6. 限价单：先和限价对手盘撮，再和市价对手盘撮，最后未成交部分挂本方限价队列
        //    为何不先和市价单撮？——价格优先：限价对手盘已按最优价排序（买对应最低卖价、卖对应最高买价），
        //    先吃限价盘才能先成交更优价格；若先吃市价单则成交价为本单限价，会跳过更优价的限价挂单。
        /**
         * 「本单」 = 当前正在撮合的这笔订单（即你下的限价单）。
         * 「本单限价」 = 这笔限价单自己挂的价格。
         * 「市价单」 = 没有报价的订单。
         * 所以 「若先吃市价单则成交价为本单限价」 的意思是：
         * 当你的限价单和市价单成交时，市价单没有报价，成交价会用你这笔限价单的价格，也就是「本单的限价」。
         */
        else if(exchangeOrder.getType() == ExchangeOrderType.LIMIT_PRICE){
            if(exchangeOrder.getPrice().compareTo(BigDecimal.ZERO) <= 0){
                return ;
            }
            // 6.1 分摊模式：卖单在清盘时间前按比例与所有买单分摊成交，不挂卖盘
            if(publishType == ExchangeCoinPublishType.FENTAN
            		&& exchangeOrder.getDirection() == ExchangeOrderDirection.SELL) {
            	logger.info(">>>>>分摊卖单>>>开始处理");
				if(exchangeOrder.getTime().longValue() < dateTimeFormat.parse(clearTime).getTime()) {
					logger.info(">>>>>分摊卖单>>>处在结束时间与清盘时间内");
					matchLimitPriceWithLPListByFENTAN(limitPriceOrderList, exchangeOrder, false);
					return;
				}
            }
            // 6.2 先与限价对手盘撮合（价格优先：买吃最低卖价，卖吃最高买价）
            matchLimitPriceWithLPList(limitPriceOrderList, exchangeOrder, false);
            // 6.3 若仍有剩余，再与市价对手盘撮合（限价盘最优价已吃完，再按本单限价满足市价单）
            if(exchangeOrder.getAmount().compareTo(exchangeOrder.getTradedAmount()) > 0) {
                matchLimitPriceWithMPList(marketPriceOrderList, exchangeOrder);
            }
            // 若还有剩余，在 matchLimitPriceWithLPList / matchLimitPriceWithMPList 内部会调用 addLimitPriceOrder 挂单
        }
    }

    // ===================== 撮合实现（限价 vs 限价 / 限价 vs 市价 / 市价 vs 限价 / 分摊） =====================

    /** 分摊模式：卖单按比例与买单分摊成交，不挂卖盘。 */
    public void matchLimitPriceWithLPListByFENTAN(TreeMap<BigDecimal,MergeOrder> lpList, ExchangeOrder focusedOrder,boolean canEnterList) {
    	List<ExchangeTrade> exchangeTrades = new ArrayList<>();
        List<ExchangeOrder> completedOrders = new ArrayList<>();
        synchronized (lpList) {
            Iterator<Map.Entry<BigDecimal,MergeOrder>> mergeOrderIterator = lpList.entrySet().iterator();
            boolean exitLoop = false;
            while (!exitLoop && mergeOrderIterator.hasNext()) {
                Map.Entry<BigDecimal,MergeOrder> entry = mergeOrderIterator.next();
                MergeOrder mergeOrder = entry.getValue();
                Iterator<ExchangeOrder> orderIterator = mergeOrder.iterator();
                //买入单需要匹配的价格不大于委托价，否则退出
                if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY && mergeOrder.getPrice().compareTo(focusedOrder.getPrice()) > 0) {
                    break;
                }
                //卖出单需要匹配的价格不小于委托价，否则退出
                if (focusedOrder.getDirection() == ExchangeOrderDirection.SELL && mergeOrder.getPrice().compareTo(focusedOrder.getPrice()) < 0) {
                    break;
                }
                BigDecimal totalAmount = mergeOrder.getTotalAmount();
                while (orderIterator.hasNext()) {
                    ExchangeOrder matchOrder = orderIterator.next();
                    //处理匹配
                    ExchangeTrade trade = processMatchByFENTAN(focusedOrder, matchOrder, totalAmount);
                    exchangeTrades.add(trade);
                    //判断匹配单是否完成
                    if (matchOrder.isCompleted()) {
                        //当前匹配的订单完成交易，删除该订单
                        orderIterator.remove();
                        completedOrders.add(matchOrder);
                    }
                    //判断交易单是否完成
                    if (focusedOrder.isCompleted()) {
                        //交易完成
                        completedOrders.add(focusedOrder);
                        //退出循环
                        exitLoop = true;
                        break;
                    }
                }
                if (mergeOrder.size() == 0) {
                    mergeOrderIterator.remove();
                }
            }
        }
        if (focusedOrder.getTradedAmount().compareTo(focusedOrder.getAmount()) < 0 && canEnterList) {
            addLimitPriceOrder(focusedOrder);
        }
        flushMatchResult(exchangeTrades, completedOrders);
        if (!completedOrders.isEmpty()) {
            TradePlate plate = focusedOrder.getDirection() == ExchangeOrderDirection.BUY ? sellTradePlate : buyTradePlate;
            sendTradePlateMessage(plate);
        }
    }

    /**
     * 限价单与限价对手盘撮合：价格优先（买吃最低卖价、卖吃最高买价），同价时间优先。
     * @param lpList        对手盘限价队列（买为卖盘，卖为买盘）
     * @param focusedOrder  当前撮合订单
     * @param canEnterList  true 表示未完全成交时可挂到本方限价队列
     */
    public void matchLimitPriceWithLPList(TreeMap<BigDecimal, MergeOrder> lpList, ExchangeOrder focusedOrder, boolean canEnterList) {
        List<ExchangeTrade> exchangeTrades = new ArrayList<>();
        List<ExchangeOrder> completedOrders = new ArrayList<>();
        synchronized (lpList) {
            Iterator<Map.Entry<BigDecimal,MergeOrder>> mergeOrderIterator = lpList.entrySet().iterator();
            boolean exitLoop = false;
            while (!exitLoop && mergeOrderIterator.hasNext()) {
                Map.Entry<BigDecimal,MergeOrder> entry = mergeOrderIterator.next();
                MergeOrder mergeOrder = entry.getValue();
                Iterator<ExchangeOrder> orderIterator = mergeOrder.iterator();
                // 买：只能吃卖价<=自己委托价；卖：只能吃买价>=自己委托价。不满足则后面价格更差，直接 break
                if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY && mergeOrder.getPrice().compareTo(focusedOrder.getPrice()) > 0) {
                    break;
                }
                if (focusedOrder.getDirection() == ExchangeOrderDirection.SELL && mergeOrder.getPrice().compareTo(focusedOrder.getPrice()) < 0) {
                    break;
                }
                while (orderIterator.hasNext()) {
                    ExchangeOrder matchOrder = orderIterator.next();
                    ExchangeTrade trade = processMatch(focusedOrder, matchOrder);
                    if (trade != null) {
                        exchangeTrades.add(trade);
                    }
                    if (matchOrder.isCompleted()) {
                        orderIterator.remove();
                        completedOrders.add(matchOrder);
                    }
                    if (focusedOrder.isCompleted()) {
                        completedOrders.add(focusedOrder);
                        exitLoop = true;
                        break;
                    }
                }
                if (mergeOrder.size() == 0) {
                    mergeOrderIterator.remove();
                }
            }
        }
        if (focusedOrder.getTradedAmount().compareTo(focusedOrder.getAmount()) < 0 && canEnterList) {
            addLimitPriceOrder(focusedOrder);
        }
        flushMatchResult(exchangeTrades, completedOrders);
        if (!completedOrders.isEmpty()) {
            TradePlate plate = focusedOrder.getDirection() == ExchangeOrderDirection.BUY ? sellTradePlate : buyTradePlate;
            sendTradePlateMessage(plate);
        }
    }

    /** 限价单与市价对手盘撮合。 */
    public void matchLimitPriceWithMPList(LinkedList<ExchangeOrder> mpList,ExchangeOrder focusedOrder){
        List<ExchangeTrade> exchangeTrades = new ArrayList<>();
        List<ExchangeOrder> completedOrders = new ArrayList<>();
        synchronized (mpList) {
            Iterator<ExchangeOrder> iterator = mpList.iterator();
            while (iterator.hasNext()) {
                ExchangeOrder matchOrder = iterator.next();
                ExchangeTrade trade = processMatch(focusedOrder, matchOrder);
                logger.info(">>>>>"+trade);
                if(trade != null){
                    exchangeTrades.add(trade);
                }
                //判断匹配单是否完成，市价单amount为成交量
                if(matchOrder.isCompleted()){
                    iterator.remove();
                    completedOrders.add(matchOrder);
                }
                //判断吃单是否完成，判断成交量是否完成
                if (focusedOrder.isCompleted()) {
                    //交易完成
                    completedOrders.add(focusedOrder);
                    //退出循环
                    break;
                }
            }
        }
        //如果还没有交易完，订单压入列表中
        if (focusedOrder.getTradedAmount().compareTo(focusedOrder.getAmount()) < 0) {
            addLimitPriceOrder(focusedOrder);
        }
        flushMatchResult(exchangeTrades, completedOrders);
    }

    /**
     * 市价单与限价对手盘撮合：从最优价档起依次吃单，未完全成交的市价单放回市价队列。
     * @param lpList        对手盘限价队列（市价买对应卖盘，市价卖对应买盘）
     * @param focusedOrder  当前要撮合的市价单
     */
    public void matchMarketPriceWithLPList(TreeMap<BigDecimal, MergeOrder> lpList, ExchangeOrder focusedOrder) {
        // 本轮产生的成交明细与已完全成交的订单，最后统一发出
        List<ExchangeTrade> exchangeTrades = new ArrayList<>();
        List<ExchangeOrder> completedOrders = new ArrayList<>();
        // 对对手盘限价队列加锁，保证同一交易对撮合串行、避免并发修改
        synchronized (lpList) {
            // 按价格档遍历：买对应卖盘（价格从低到高），卖对应买盘（价格从高到低）
            Iterator<Map.Entry<BigDecimal, MergeOrder>> mergeOrderIterator = lpList.entrySet().iterator();
            boolean exitLoop = false;
            while (!exitLoop && mergeOrderIterator.hasNext()) {
                Map.Entry<BigDecimal, MergeOrder> entry = mergeOrderIterator.next();
                MergeOrder mergeOrder = entry.getValue();
                // 同价位内按时间顺序逐个订单匹配
                Iterator<ExchangeOrder> orderIterator = mergeOrder.iterator();
                while (orderIterator.hasNext()) {
                    ExchangeOrder matchOrder = orderIterator.next();
                    // 计算本笔成交价与量，更新双方 tradedAmount/turnover，构造成交记录
                    ExchangeTrade trade = processMatch(focusedOrder, matchOrder);
                    if (trade != null) {
                        exchangeTrades.add(trade);
                    }
                    // 对手单若已全部成交，从订单簿移除并计入完成列表
                    if (matchOrder.isCompleted()) {
                        // 
                        orderIterator.remove();
                        completedOrders.add(matchOrder);
                    }
                    // 当前市价单若已全部成交，计入完成列表并退出所有循环
                    if (focusedOrder.isCompleted()) {
                        completedOrders.add(focusedOrder);
                        exitLoop = true;
                        break;
                    }
                }
                // 该价格档已无订单，从 TreeMap 中移除该档位
                if (mergeOrder.size() == 0) {
                    mergeOrderIterator.remove();
                }
            }
        }
        // 市价卖单按“数量”判断是否未成交完；市价买单按“金额”判断是否未成交完
        boolean sellIncomplete = focusedOrder.getDirection() == ExchangeOrderDirection.SELL && focusedOrder.getTradedAmount().compareTo(focusedOrder.getAmount()) < 0;
        boolean buyIncomplete = focusedOrder.getDirection() == ExchangeOrderDirection.BUY && focusedOrder.getTurnover().compareTo(focusedOrder.getAmount()) < 0;
        // 未完全成交的市价单放回本方市价队列，等待后续继续撮合
        if (sellIncomplete || buyIncomplete) {
            addMarketPriceOrder(focusedOrder);
        }
        // 统一发出本批成交与完成订单（方案 A 或双 topic）
        flushMatchResult(exchangeTrades, completedOrders);
        // 若有订单在本轮完全成交，推送盘口变化给前端
        if (!completedOrders.isEmpty()) {
            TradePlate plate = focusedOrder.getDirection() == ExchangeOrderDirection.BUY ? sellTradePlate : buyTradePlate;
            sendTradePlateMessage(plate);
        }
    }

    // ===================== 撮合辅助（成交价/量、单笔成交、分摊成交） =====================

    /** 当前订单在成交价下剩余可成交量；市价买单的 amount 为金额，用 (金额-已用)/价格 得数量。 */
    private BigDecimal calculateTradedAmount(ExchangeOrder order, BigDecimal dealPrice){
        if(order.getDirection() == ExchangeOrderDirection.BUY && order.getType() == ExchangeOrderType.MARKET_PRICE){
            BigDecimal leftTurnover = order.getAmount().subtract(order.getTurnover());
            return leftTurnover.divide(dealPrice,coinScale,BigDecimal.ROUND_DOWN);
        }
        else{
            return  order.getAmount().subtract(order.getTradedAmount());
        }
    }

    /**
     * 市价买单：当剩余金额不足以再成交 1 个最小单位时，把 turnover 直接设为总金额，标记该订单“用完”，避免后续继续参与撮合产生精度问题。
     */
    private BigDecimal adjustMarketOrderTurnover(ExchangeOrder order, BigDecimal dealPrice){
        if(order.getDirection() == ExchangeOrderDirection.BUY && order.getType() == ExchangeOrderType.MARKET_PRICE){
            BigDecimal leftTurnover = order.getAmount().subtract(order.getTurnover());
            if(leftTurnover.divide(dealPrice,coinScale,BigDecimal.ROUND_DOWN)
                    .compareTo(BigDecimal.ZERO)==0){
                order.setTurnover(order.getAmount());
                return leftTurnover;
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * 处理两笔订单的一笔成交。
     * <p>
     * 代码目的：在焦点单与对手单可成交的前提下，确定成交价与成交量，更新双方订单的已成交量/已成交额，
     * 构造成交记录供下游落库与推送；若对手为限价单则同步从盘口扣减对应数量，保证盘口与订单簿一致。
     * </p>
     * 成交价规则：对手为限价单则用对手价（挂单优先），否则用焦点单价（市价吃单场景）。
     *
     * @param focusedOrder 当前正在撮合的订单（吃单方）
     * @param matchOrder   对手盘订单（被吃方）
     * @return 本笔成交记录 ExchangeTrade，供 flushMatchResult 下发；无法成交时返回 null
     */
    private ExchangeTrade processMatch(ExchangeOrder focusedOrder, ExchangeOrder matchOrder) {
        BigDecimal needAmount, dealPrice, availAmount;

        // ----- 成交价确定（dealPrice） -----
        // 1) 谁在调 processMatch、双方分别是什么类型：
        //    调用处                      | focusedOrder | matchOrder
        //    ---------------------------|--------------|---------------------------
        //    matchLimitPriceWithLPList  | 限价         | 限价（来自限价对手盘）
        //    matchLimitPriceWithMPList  | 限价         | 市价（来自市价对手盘）
        //    matchMarketPriceWithLPList | 市价         | 限价（来自限价对手盘）
        // 2) 结论：当 matchOrder 是市价单时，只会在 matchLimitPriceWithMPList 里出现，
        //    此时 focusedOrder 固定是限价单，故 dealPrice = focusedOrder.getPrice() 取到的是「本单限价」。
        //    当前实现下从没有「focusedOrder=市价、matchOrder=市价」的调用，else 分支里 focusedOrder 不会是市价单。
        // 3) 若将来支持「市价对市价」：市价单通常没有委托价（或 price 为 0/null），
        //    dealPrice = focusedOrder.getPrice() 可能为 null/0，后面 dealPrice<=0 会 return null，本笔不成交；
        //    若需支持市价对市价，此处需单独逻辑（例如用最新成交价、中间价等），而不是直接用 focusedOrder.getPrice()。
        if (matchOrder.getType() == ExchangeOrderType.LIMIT_PRICE) {
            dealPrice = matchOrder.getPrice();
        } else {
            dealPrice = focusedOrder.getPrice();
        }
        // 目的：非法成交价直接放弃本笔匹配，避免除零或错误更新
        if (dealPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // 目的：计算焦点单、对手单在当前成交价下各自还能成交的数量（市价买单按剩余金额/价格换算）
        needAmount = calculateTradedAmount(focusedOrder, dealPrice);
        availAmount = calculateTradedAmount(matchOrder, dealPrice);
        // 目的：实际成交量取两者最小值，保证不超任何一方的可成交量
        BigDecimal tradedAmount = (availAmount.compareTo(needAmount) >= 0 ? needAmount : availAmount);
        logger.info("dealPrice={},amount={}", dealPrice, tradedAmount);
        // 目的：若算出的成交量为 0（如市价买单剩余金额不足 1 档），不生成成交记录
        if (tradedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        // 目的：成交额 = 成交量 × 成交价，用于下游按金额结算与手续费计算
        BigDecimal turnover = tradedAmount.multiply(dealPrice);
        // 目的：更新对手单的已成交量、已成交额，用于判断是否完全成交及后续落库
        matchOrder.setTradedAmount(matchOrder.getTradedAmount().add(tradedAmount));
        matchOrder.setTurnover(matchOrder.getTurnover().add(turnover));
        // 目的：更新焦点单的已成交量、已成交额
        focusedOrder.setTradedAmount(focusedOrder.getTradedAmount().add(tradedAmount));
        focusedOrder.setTurnover(focusedOrder.getTurnover().add(turnover));

        // 目的：构造成交记录 DTO，供 Kafka 下发、下游写成交明细与资金流水
        ExchangeTrade exchangeTrade = new ExchangeTrade();
        exchangeTrade.setSymbol(symbol);
        exchangeTrade.setAmount(tradedAmount);
        exchangeTrade.setDirection(focusedOrder.getDirection());
        exchangeTrade.setPrice(dealPrice);
        exchangeTrade.setBuyTurnover(turnover);
        exchangeTrade.setSellTurnover(turnover);

        // 目的：市价买单按“金额”下单，剩余金额不足 1 档时需把“找零”算进 buyTurnover，下游按金额结算才正确
        if (ExchangeOrderType.MARKET_PRICE == focusedOrder.getType() && focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
            BigDecimal adjustTurnover = adjustMarketOrderTurnover(focusedOrder, dealPrice);
            exchangeTrade.setBuyTurnover(turnover.add(adjustTurnover));
        } else if (ExchangeOrderType.MARKET_PRICE == matchOrder.getType() && matchOrder.getDirection() == ExchangeOrderDirection.BUY) {
            BigDecimal adjustTurnover = adjustMarketOrderTurnover(matchOrder, dealPrice);
            exchangeTrade.setBuyTurnover(turnover.add(adjustTurnover));
        }

        // 目的：明确本笔成交的买卖订单 id，下游据此更新买卖双方钱包与订单明细
        if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
            exchangeTrade.setBuyOrderId(focusedOrder.getOrderId());
            exchangeTrade.setSellOrderId(matchOrder.getOrderId());
        } else {
            exchangeTrade.setBuyOrderId(matchOrder.getOrderId());
            exchangeTrade.setSellOrderId(focusedOrder.getOrderId());
        }

        exchangeTrade.setTime(Calendar.getInstance().getTimeInMillis());

        // 目的：对手单为限价单时，从对应盘口扣减本笔成交量，保证盘口深度与订单簿一致、前端展示正确
        if (matchOrder.getType() == ExchangeOrderType.LIMIT_PRICE) {
            if (matchOrder.getDirection() == ExchangeOrderDirection.BUY) {
                buyTradePlate.remove(matchOrder, tradedAmount);
            } else {
                sellTradePlate.remove(matchOrder, tradedAmount);
            }
        }
        return exchangeTrade;
    }

    /**
     * 处理两个匹配的委托订单
     * @param focusedOrder 焦点单
     * @param matchOrder 匹配单
     * @return
     */
    private ExchangeTrade processMatchByFENTAN(ExchangeOrder focusedOrder, ExchangeOrder matchOrder, BigDecimal totalAmount){
        //需要交易的数量，成交量,成交价，可用数量
        BigDecimal dealPrice;
        //如果匹配单是限价单，则以其价格为成交价
        if(matchOrder.getType() == ExchangeOrderType.LIMIT_PRICE){
            dealPrice = matchOrder.getPrice();
        }
        else {
            dealPrice = focusedOrder.getPrice();
        }
        //成交价必须大于0
        if(dealPrice.compareTo(BigDecimal.ZERO) <= 0){
            return null;
        }
        // 成交数 = 发行卖单总数*匹配单数量占比（例：1.2345%）
        //计算成交量
        BigDecimal tradedAmount = focusedOrder.getAmount().multiply(matchOrder.getAmount().divide(totalAmount, 8, BigDecimal.ROUND_HALF_DOWN)).setScale(8, BigDecimal.ROUND_HALF_DOWN);
        logger.info("dealPrice={},amount={}",dealPrice,tradedAmount);
        //如果成交额为0说明剩余额度无法成交，退出
        if(tradedAmount.compareTo(BigDecimal.ZERO) == 0){
            return null;
        }

        //计算成交额,成交额要保留足够精度
        BigDecimal turnover = tradedAmount.multiply(dealPrice).setScale(8, BigDecimal.ROUND_HALF_DOWN);
        matchOrder.setTradedAmount(matchOrder.getTradedAmount().add(tradedAmount).setScale(8, BigDecimal.ROUND_HALF_DOWN));
        matchOrder.setTurnover(matchOrder.getTurnover().add(turnover).setScale(8, BigDecimal.ROUND_HALF_DOWN));
        focusedOrder.setTradedAmount(focusedOrder.getTradedAmount().add(tradedAmount).setScale(8, BigDecimal.ROUND_HALF_DOWN));
        focusedOrder.setTurnover(focusedOrder.getTurnover().add(turnover).setScale(8, BigDecimal.ROUND_HALF_DOWN));

        //创建成交记录
        ExchangeTrade exchangeTrade = new ExchangeTrade();
        exchangeTrade.setSymbol(symbol);
        exchangeTrade.setAmount(tradedAmount);
        exchangeTrade.setDirection(focusedOrder.getDirection());
        exchangeTrade.setPrice(dealPrice);
        exchangeTrade.setBuyTurnover(turnover);
        exchangeTrade.setSellTurnover(turnover);

        if (focusedOrder.getDirection() == ExchangeOrderDirection.BUY) {
            exchangeTrade.setBuyOrderId(focusedOrder.getOrderId());
            exchangeTrade.setSellOrderId(matchOrder.getOrderId());
        } else {
            exchangeTrade.setBuyOrderId(matchOrder.getOrderId());
            exchangeTrade.setSellOrderId(focusedOrder.getOrderId());
        }

        exchangeTrade.setTime(Calendar.getInstance().getTimeInMillis());
        if(matchOrder.getType() == ExchangeOrderType.LIMIT_PRICE){
            if(matchOrder.getDirection() == ExchangeOrderDirection.BUY){
                buyTradePlate.remove(matchOrder,tradedAmount);
            }
            else{
                sellTradePlate.remove(matchOrder,tradedAmount);
            }
        }
        return exchangeTrade;
    }

    // ===================== 撮合结果输出（方案 A 或双 topic 回退） =====================

    /**
     * 一次撮合结果统一出口：有 matchResultPublisher 则 publish（不阻塞），
     * 否则同步发 exchange-trade + exchange-order-completed。
     */
    private void flushMatchResult(List<ExchangeTrade> trades, List<ExchangeOrder> completedOrders) {
        if (replayMode) {
            return;
        }
        if (matchResultPublisher != null) {
            // 生成全局 messageId，供消费端幂等：同一 messageId 只落库一次
            String messageId = UUID.randomUUID().toString();
            matchResultPublisher.publish(new MatchResult(messageId, symbol, System.currentTimeMillis(),
                    trades != null ? trades : Collections.emptyList(),
                    completedOrders != null ? completedOrders : Collections.emptyList()));
            return;
        }

        // kafka 发交易流水
        handleExchangeTrade(trades != null ? trades : Collections.emptyList());
        // kafka 发已完全成交订单
        if (completedOrders != null && !completedOrders.isEmpty()) {
            orderCompleted(completedOrders);
        }
    }

    /**
     * 同步发送 Kafka 并有限重试。失败时打 ERROR 日志并带上 payload，便于告警与人工/脚本补发。
     * 若需接入告警或失败落库，可在此处扩展。
     *
     * @param topic   Kafka topic
     * @param payload 消息体 JSON
     * @return true 发送成功，false 重试后仍失败
     */
    private boolean sendToKafkaWithRetry(String topic, String payload) {
        Exception lastEx = null;
        for (int i = 0; i < KAFKA_SEND_MAX_RETRIES; i++) {
            try {
                ListenableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, payload);
                future.get(KAFKA_SEND_GET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Kafka send interrupted, topic={}", topic, e);
                return false;
            } catch (TimeoutException | ExecutionException e) {
                lastEx = e;
                if (i < KAFKA_SEND_MAX_RETRIES - 1) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(KAFKA_SEND_RETRY_DELAY_MS * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        logger.error("Kafka send retry sleep interrupted, topic={}", topic, ie);
                        return false;
                    }
                }
            }
        }
        logger.error("Kafka send failed after {} retries, topic={}, payloadLength={}. Payload for manual replay: {}",
                KAFKA_SEND_MAX_RETRIES, topic, payload != null ? payload.length() : 0, payload, lastEx);
        return false;
    }

    /**
     * 将本轮撮合产生的成交明细发送到 Kafka topic "exchange-trade"。
     * 下游 market 模块 ExchangeTradeConsumer 消费后调用 processExchangeTrade 落库：成交明细、钱包、流水、返佣等。
     * 发送失败时同步重试，仍失败则打 ERROR 日志（可接告警），撮合结果可能未落库需人工补发。
     * 超过 1000 条拆分发送，避免单条消息过大。
     */
    public void handleExchangeTrade(List<ExchangeTrade> trades) {
        if (trades == null || trades.isEmpty()) return;
        for (int i = 0; i < trades.size(); i += KAFKA_BATCH_MAX_SIZE) {
            int end = Math.min(i + KAFKA_BATCH_MAX_SIZE, trades.size());
            List<ExchangeTrade> batch = trades.subList(i, end);
            sendToKafkaWithRetry("exchange-trade", JSON.toJSONString(batch));
        }
    }

    /** 已完全成交订单发往 exchange-order-completed，下游更新状态并退剩余冻结。 */
    public void orderCompleted(List<ExchangeOrder> orders) {
        if (orders == null || orders.isEmpty()) return;
        for (int i = 0; i < orders.size(); i += KAFKA_BATCH_MAX_SIZE) {
            int end = Math.min(i + KAFKA_BATCH_MAX_SIZE, orders.size());
            List<ExchangeOrder> batch = orders.subList(i, end);
            sendToKafkaWithRetry("exchange-order-completed", JSON.toJSONString(batch));
        }
    }

    public void sendTradePlateMessage(TradePlate plate) {
        synchronized (plate) {
            kafkaTemplate.send("exchange-trade-plate", JSON.toJSONString(plate));
        }
    }

    // ===================== 撤单与订单查询 =====================

    /** 从订单簿中移除并取消委托，限价单同时从盘口移除并推送。 */
    public ExchangeOrder cancelOrder(ExchangeOrder exchangeOrder) {
        logger.info("cancelOrder,orderId={}", exchangeOrder.getOrderId());
        if (exchangeOrder.getType() == ExchangeOrderType.MARKET_PRICE) {
            LinkedList<ExchangeOrder> list = exchangeOrder.getDirection() == ExchangeOrderDirection.BUY ? buyMarketQueue : sellMarketQueue;
            synchronized (list) {
                Iterator<ExchangeOrder> orderIterator = list.iterator();
                while (orderIterator.hasNext()) {
                    ExchangeOrder order = orderIterator.next();
                    if (order.getOrderId().equalsIgnoreCase(exchangeOrder.getOrderId())) {
                        orderIterator.remove();
                        onRemoveOrder(order);
                        if (!replayMode && orderEventLogger != null) {
                            orderEventLogger.appendCancel(exchangeOrder);
                        }
                        return order;
                    }
                }
            }
        } else {
            TreeMap<BigDecimal, MergeOrder> list = exchangeOrder.getDirection() == ExchangeOrderDirection.BUY ? buyLimitPriceQueue : sellLimitPriceQueue;
            synchronized (list) {
                MergeOrder mergeOrder = list.get(exchangeOrder.getPrice());
                if(mergeOrder!=null) {
                    Iterator<ExchangeOrder> orderIterator = mergeOrder.iterator();
                    while (orderIterator.hasNext()) {
                        ExchangeOrder order = orderIterator.next();
                        if (order.getOrderId().equalsIgnoreCase(exchangeOrder.getOrderId())) {
                            orderIterator.remove();
                            if (mergeOrder.size() == 0) {
                                list.remove(exchangeOrder.getPrice());
                            }
                            onRemoveOrder(order);
                            if (!replayMode && orderEventLogger != null) {
                                orderEventLogger.appendCancel(exchangeOrder);
                            }
                            return order;
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 订单从订单簿移除时，若为限价单则从盘口移除并推送。 */
    public void onRemoveOrder(ExchangeOrder order) {
        if (order.getType() == ExchangeOrderType.LIMIT_PRICE) {
            if (order.getDirection() == ExchangeOrderDirection.BUY) {
                buyTradePlate.remove(order);
                sendTradePlateMessage(buyTradePlate);
            } else {
                sellTradePlate.remove(order);
                sendTradePlateMessage(sellTradePlate);
            }
        }
    }

    public TradePlate getTradePlate(ExchangeOrderDirection direction) {
        return direction == ExchangeOrderDirection.BUY ? buyTradePlate : sellTradePlate;
    }

    /** 按 orderId/type/direction 在订单簿中查找订单。 */
    public ExchangeOrder findOrder(String orderId, ExchangeOrderType type, ExchangeOrderDirection direction) {
        if (type == ExchangeOrderType.MARKET_PRICE) {
            LinkedList<ExchangeOrder> list = direction == ExchangeOrderDirection.BUY ? buyMarketQueue : sellMarketQueue;
            synchronized (list) {
                for (ExchangeOrder order : list) {
                    if (order.getOrderId().equalsIgnoreCase(orderId)) return order;
                }
            }
        } else {
            TreeMap<BigDecimal, MergeOrder> list = direction == ExchangeOrderDirection.BUY ? buyLimitPriceQueue : sellLimitPriceQueue;
            synchronized (list) {
                for (MergeOrder mergeOrder : list.values()) {
                    Iterator<ExchangeOrder> it = mergeOrder.iterator();
                    while (it.hasNext()) {
                        ExchangeOrder order = it.next();
                        if (order.getOrderId().equalsIgnoreCase(orderId)) return order;
                    }
                }
            }
        }
        return null;
    }

    // ===================== Getter / Setter =====================

    public TreeMap<BigDecimal, MergeOrder> getBuyLimitPriceQueue() {
        return buyLimitPriceQueue;
    }

    public LinkedList<ExchangeOrder> getBuyMarketQueue() {
        return buyMarketQueue;
    }

    public TreeMap<BigDecimal,MergeOrder> getSellLimitPriceQueue() {
        return sellLimitPriceQueue;
    }

    public LinkedList<ExchangeOrder> getSellMarketQueue() {
        return sellMarketQueue;
    }

    public void setKafkaTemplate(KafkaTemplate<String,String> template){
        this.kafkaTemplate = template;
    }
    public void setCoinScale(int scale){
        this.coinScale = scale;
    }

    public void setBaseCoinScale(int scale){
        this.baseCoinScale = scale;
    }

    public boolean isTradingHalt(){
        return this.tradingHalt;
    }

    /**
     * 暂停交易,不接收新的订单
     */
    public void haltTrading(){
        this.tradingHalt = true;
    }

    /**
     * 恢复交易
     */
    public void resumeTrading(){
        this.tradingHalt = false;
    }

    /** 停止交易：暂停接收新撮合；当前实现仅 halt，不主动取消已在簿订单（如需可扩展遍历队列发撤单）。 */
    public void stopTrading() {
        this.tradingHalt = true;
    }

    public boolean getReady(){
        return this.ready;
    }

    public void setReady(boolean ready){
        this.ready = ready;
    }
    public void setPublishType(ExchangeCoinPublishType publishType) {
    	this.publishType = publishType;
    }
    public void setClearTime(String clearTime) {
    	this.clearTime = clearTime;
    }

    /** 【改造范围】注入方案 A 的 MatchResultPublisher 后，撮合结果经队列+WAL 由后台线程发 Kafka，热路径不阻塞 */
    public void setMatchResultPublisher(MatchResultPublisher matchResultPublisher) {
        this.matchResultPublisher = matchResultPublisher;
    }

    public MatchResultPublisher getMatchResultPublisher() {
        return matchResultPublisher;
    }

    public void setOrderEventLogger(OrderEventLogger orderEventLogger) {
        this.orderEventLogger = orderEventLogger;
    }

    public void setReplayMode(boolean replayMode) {
        this.replayMode = replayMode;
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    /**
     * 形态一：从订单事件日志回放恢复订单簿，避免从 DB 加载 TRADING 导致已撮合未下发的订单再次撮合。
     * 回放期间不写订单日志、不发布撮合结果；回放完成后恢复正常模式。
     */
    public void replayOrderLog() {
        if (orderEventLogger == null) {
            return;
        }
        setReplayMode(true);
        try {
            orderEventLogger.replay(
                    order -> {
                        try {
                            trade(order);
                        } catch (ParseException e) {
                            logger.warn("[{}] replay trade parse error, orderId={}", symbol, order != null ? order.getOrderId() : null, e);
                        }
                    },
                    this::cancelOrder
            );
        } finally {
            setReplayMode(false);
        }
    }

    public int getLimitPriceOrderCount(ExchangeOrderDirection direction){
        int count = 0;
        TreeMap<BigDecimal,MergeOrder> queue = direction == ExchangeOrderDirection.BUY ? buyLimitPriceQueue : sellLimitPriceQueue;
        Iterator<Map.Entry<BigDecimal,MergeOrder>> mergeOrderIterator = queue.entrySet().iterator();
        while (mergeOrderIterator.hasNext()) {
            Map.Entry<BigDecimal,MergeOrder> entry = mergeOrderIterator.next();
            MergeOrder mergeOrder = entry.getValue();
            count += mergeOrder.size();
        }
        return count;
    }
}
