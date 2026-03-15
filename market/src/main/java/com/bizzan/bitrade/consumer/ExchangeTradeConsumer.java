package com.bizzan.bitrade.consumer;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.bizzan.bitrade.constant.NettyCommand;
import com.bizzan.bitrade.entity.ExchangeOrder;
import com.bizzan.bitrade.entity.ExchangeTrade;
import com.bizzan.bitrade.entity.TradePlate;
import com.bizzan.bitrade.handler.NettyHandler;
import com.bizzan.bitrade.job.ExchangePushJob;
import com.bizzan.bitrade.processor.CoinProcessor;
import com.bizzan.bitrade.processor.CoinProcessorFactory;
import com.bizzan.bitrade.service.ExchangeOrderService;

import lombok.extern.slf4j.Slf4j;

/**
 * 消费撮合结果并落库、推送：成交明细、订单完成、行情等。
 * <p>
 * 本类订阅三个 topic：
 * <ul>
 *   <li><b>exchange-match-result</b>：方案 A 下撮合引擎唯一出口，单条消息含 trades + completedOrders，单事务处理。</li>
 *   <li><b>exchange-trade</b>：旧路径，仅成交明细（无 publisher 时由 CoinTrader 直接发送）。</li>
 *   <li><b>exchange-order-completed</b>：旧路径，仅已完全成交订单（无 publisher 时由 CoinTrader 直接发送）。</li>
 * </ul>
 * 方案 A 开启时，撮合只发 exchange-match-result，故通常只有该 topic 有数据。若同时保留三路监听（兼容/灰度/回退），
 * 同一批结果可能从 exchange-match-result 与旧 topic 各收一次，导致重复落库与重复推送。<br>
 * <b>因此若同时消费三个 topic，必须在落库侧做幂等</b>：例如按 tradeId 去重成交明细（避免重复加钱、重复写 ExchangeOrderDetail），
 * 按 orderId 去重订单完成（tradeCompleted 当前对非 TRADING 状态会返回错误，不重复退冻结，但建议显式幂等或统一只消费 exchange-match-result）。
 */
@Component
@Slf4j
public class ExchangeTradeConsumer {
	private Logger logger = LoggerFactory.getLogger(ExchangeTradeConsumer.class);
	@Autowired
	private CoinProcessorFactory coinProcessorFactory;
	@Autowired
	private SimpMessagingTemplate messagingTemplate;
	@Autowired
	private ExchangeOrderService exchangeOrderService;
	@Autowired
	private NettyHandler nettyHandler;
	@Value("${second.referrer.award}")
	private boolean secondReferrerAward;
	/** true=走清算→结算→资金流水线（仅落订单状态，资金由资金服务执行）；false=沿用原逻辑（直接改钱包） */
	@Value("${match.result.use.fund.pipeline:true}")
	private boolean useFundPipeline;
	private ExecutorService executor = new ThreadPoolExecutor(30, 100, 0L, TimeUnit.MILLISECONDS,
			new LinkedBlockingQueue<Runnable>(1024), new ThreadPoolExecutor.AbortPolicy());
	@Autowired
	private ExchangePushJob pushJob;

	/**
	 * 处理成交明细（旧路径）。方案 A 下撮合不发此 topic；若与 exchange-match-result 同时有数据，需幂等防重复。
	 *
	 * @param records
	 */
	@KafkaListener(topics = "exchange-trade", containerFactory = "kafkaListenerContainerFactory")
	public void handleTrade(List<ConsumerRecord<String, String>> records) {
		for (int i = 0; i < records.size(); i++) {
			ConsumerRecord<String, String> record = records.get(i);
			executor.submit(new HandleTradeThread(record));
		}
	}

	/**
	 * 消费方案 A 下撮合引擎统一出口 exchange-match-result（单条消息 = 一批 trades + 一批 completedOrders）。
	 * <p>
	 * market 职责：只做行情与盘口 + 订单状态与成交明细 + 推送，不碰资金。资金由 清算→结算→资金服务 执行。
	 * <ul>
	 *   <li>订单域：落 exchange_order_detail、order_detail_aggregation，更新 exchange_order 状态（COMPLETED 等）；不写 member_wallet、不退冻结（useFundPipeline=true 时）。</li>
	 *   <li>行情与盘口：K 线、24h、盘口、最新成交（CoinProcessor、pushJob）。</li>
	 *   <li>推送：WebSocket / Netty 推送订单部分成交、订单完成。</li>
	 *   <li>清算由独立消费者 ClearingMatchResultConsumer 监听 exchange-match-result 完成，此处不再触发。</li>
	 * </ul>
	 */
	@KafkaListener(topics = "exchange-match-result", containerFactory = "kafkaListenerContainerFactory")
	public void handleMatchResult(List<ConsumerRecord<String, String>> records) {
		try {
			for (ConsumerRecord<String, String> record : records) {
				// 1. 解析 MatchResult：messageId, symbol, ts, trades[], completedOrders[]
				JSONObject obj = JSON.parseObject(record.value());
				JSONArray tradesArr = obj.getJSONArray("trades");
				JSONArray completedArr = obj.getJSONArray("completedOrders");
				List<ExchangeTrade> trades = tradesArr != null ? JSON.parseArray(tradesArr.toJSONString(), ExchangeTrade.class) : java.util.Collections.emptyList();
				List<ExchangeOrder> completedOrders = completedArr != null ? JSON.parseArray(completedArr.toJSONString(), ExchangeOrder.class) : java.util.Collections.emptyList();
				if (trades.isEmpty() && completedOrders.isEmpty()) {
					continue;
				}

				// 2. 幂等落库（订单状态 + 成交明细）。true=仅订单不碰资金，false=原逻辑含钱包/流水/返佣/退冻结
				String messageId = obj.getString("messageId");
				boolean processed;
				try {
					if (useFundPipeline) {
						processed = exchangeOrderService.processMatchResultIdempotentOrderOnly(messageId, trades, completedOrders);
					} else {
						processed = exchangeOrderService.processMatchResultIdempotent(messageId, trades, completedOrders, secondReferrerAward);
					}
				} catch (Exception ex) {
					log.error("handleMatchResult processMatchResult error", ex);
					throw ex;
				}
				if (!processed) {
					continue; // 已处理过（重复消费），不再推送与清算
				}

				// 3. 取交易对 symbol（推送与行情用）
				String symbol = obj.getString("symbol");
				if (symbol == null && !trades.isEmpty()) {
					symbol = trades.get(0).getSymbol();
				}

				// 4. 推送：订单部分成交（逐笔推给买卖双方）
				for (ExchangeTrade trade : trades) {
					ExchangeOrder buyOrder = exchangeOrderService.findOne(trade.getBuyOrderId());
					ExchangeOrder sellOrder = exchangeOrderService.findOne(trade.getSellOrderId());
					if (buyOrder != null) {
						messagingTemplate.convertAndSend("/topic/market/order-trade/" + symbol + "/" + buyOrder.getMemberId(), buyOrder);
						nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_TRADE, buyOrder);
					}
					if (sellOrder != null) {
						messagingTemplate.convertAndSend("/topic/market/order-trade/" + symbol + "/" + sellOrder.getMemberId(), sellOrder);
						nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_TRADE, sellOrder);
					}
				}

				// 5. 行情与盘口：更新 K 线、CoinThumb，加入盘口/最新成交推送队列
				CoinProcessor coinProcessor = symbol != null ? coinProcessorFactory.getProcessor(symbol) : null;
				if (coinProcessor != null && !trades.isEmpty()) {
					coinProcessor.process(trades);
				}
				if (symbol != null && !trades.isEmpty()) {
					pushJob.addTrades(symbol, trades);
				}

				// 6. 推送：订单完全成交（通知买卖双方订单已结束）
				for (ExchangeOrder order : completedOrders) {
					messagingTemplate.convertAndSend("/topic/market/order-completed/" + order.getSymbol() + "/" + order.getMemberId(), order);
					nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_COMPLETED, order);
				}
			}
		} catch (Exception e) {
			log.error("handleMatchResult error", e);
		}
	}

	/**
	 * 处理订单完成（旧路径）。方案 A 下撮合不发此 topic；若与 exchange-match-result 同时有数据，需幂等防重复。
	 */
	@KafkaListener(topics = "exchange-order-completed", containerFactory = "kafkaListenerContainerFactory")
	public void handleOrderCompleted(List<ConsumerRecord<String, String>> records) {
		logger.info("接收到exchange-order-completed消息");
		try {
			for (int i = 0; i < records.size(); i++) {
				ConsumerRecord<String, String> record = records.get(i);
				//logger.info("订单交易处理完成消息topic={},value={}", record.topic(), record.value());
				List<ExchangeOrder> orders = JSON.parseArray(record.value(), ExchangeOrder.class);
				for (ExchangeOrder order : orders) {
					String symbol = order.getSymbol();
					// 委托成交完成处理
					exchangeOrderService.tradeCompleted(order.getOrderId(), order.getTradedAmount(),
							order.getTurnover());
					// 推送订单成交
					messagingTemplate.convertAndSend(
							"/topic/market/order-completed/" + symbol + "/" + order.getMemberId(), order);
					nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_COMPLETED, order);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 处理模拟交易
	 *
	 * @param records
	 */
	@KafkaListener(topics = "exchange-trade-mocker", containerFactory = "kafkaListenerContainerFactory")
	public void handleMockerTrade(List<ConsumerRecord<String, String>> records) {
		try {
			for (int i = 0; i < records.size(); i++) {
				ConsumerRecord<String, String> record = records.get(i);
				logger.info("mock数据topic={},value={},size={}", record.topic(), record.value(), records.size());
				List<ExchangeTrade> trades = JSON.parseArray(record.value(), ExchangeTrade.class);
				String symbol = trades.get(0).getSymbol();
				// 处理行情
				CoinProcessor coinProcessor = coinProcessorFactory.getProcessor(symbol);
				if (coinProcessor != null) {
					coinProcessor.process(trades);
				}
				pushJob.addTrades(symbol, trades);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 消费交易盘口信息
	 *
	 * @param records
	 */
	@KafkaListener(topics = "exchange-trade-plate", containerFactory = "kafkaListenerContainerFactory")
	public void handleTradePlate(List<ConsumerRecord<String, String>> records) {
		try {
			for (int i = 0; i < records.size(); i++) {
				ConsumerRecord<String, String> record = records.get(i);
				//logger.info("推送盘口信息topic={},value={},size={}", record.topic(), record.value(), records.size());
				TradePlate plate = JSON.parseObject(record.value(), TradePlate.class);
				String symbol = plate.getSymbol();
				pushJob.addPlates(symbol, plate);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * 订单取消成功
	 *
	 * @param records
	 */
	@KafkaListener(topics = "exchange-order-cancel-success", containerFactory = "kafkaListenerContainerFactory")
	public void handleOrderCanceled(List<ConsumerRecord<String, String>> records) {
		try {
			for (int i = 0; i < records.size(); i++) {
				ConsumerRecord<String, String> record = records.get(i);
				//logger.info("取消订单消息topic={},value={},size={}", record.topic(), record.value(), records.size());
				ExchangeOrder order = JSON.parseObject(record.value(), ExchangeOrder.class);
				String symbol = order.getSymbol();
				// 调用服务处理
				exchangeOrderService.cancelOrder(order.getOrderId(), order.getTradedAmount(), order.getTurnover());
				// 推送实时成交
				messagingTemplate.convertAndSend("/topic/market/order-canceled/" + symbol + "/" + order.getMemberId(),
						order);
				nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_CANCELED, order);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public class HandleTradeThread implements Runnable {
		private ConsumerRecord<String, String> record;

		private HandleTradeThread(ConsumerRecord<String, String> record) {
			this.record = record;
		}

		@Override
		public void run() {
			//logger.info("topic={},value={}", record.topic(), record.value());
			try {
				List<ExchangeTrade> trades = JSON.parseArray(record.value(), ExchangeTrade.class);
				String symbol = trades.get(0).getSymbol();
				CoinProcessor coinProcessor = coinProcessorFactory.getProcessor(symbol);
				for (ExchangeTrade trade : trades) {
					// 成交明细处理
					exchangeOrderService.processExchangeTrade(trade, secondReferrerAward);
					// 推送订单成交订阅
					ExchangeOrder buyOrder = exchangeOrderService.findOne(trade.getBuyOrderId());
					ExchangeOrder sellOrder = exchangeOrderService.findOne(trade.getSellOrderId());
					messagingTemplate.convertAndSend(
							"/topic/market/order-trade/" + symbol + "/" + buyOrder.getMemberId(), buyOrder);
					messagingTemplate.convertAndSend(
							"/topic/market/order-trade/" + symbol + "/" + sellOrder.getMemberId(), sellOrder);
					nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_TRADE, buyOrder);
					nettyHandler.handleOrder(NettyCommand.PUSH_EXCHANGE_ORDER_TRADE, sellOrder);
				}
				// 处理K线行情
				if (coinProcessor != null) {
					coinProcessor.process(trades);
				}
				pushJob.addTrades(symbol, trades);
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}
}
