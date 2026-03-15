package com.bizzan.bitrade.Trader.result;

import com.alibaba.fastjson.JSON;
import com.bizzan.bitrade.entity.ExchangeOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 订单事件日志：形态一「订单日志 + 撮合结果日志分离」中的订单侧。
 * 顺序追加 ORDER / CANCEL 事件，重启时回放以恢复订单簿，避免从 DB 加载 TRADING 导致已撮合未下发的订单再次撮合。
 * <p>
 * 与撮合结果 WAL 独立：本类只写订单事件，不写撮合结果；撮合结果由 {@link QueueAndWalMatchResultPublisher} 写并发 Kafka。
 */
public class OrderEventLogger {

    private static final Logger log = LoggerFactory.getLogger(OrderEventLogger.class);

    private static final String EVENT_ORDER = "ORDER";
    private static final String EVENT_CANCEL = "CANCEL";

    private final String symbol;
    private final Path logPath;

    /**
     * @param symbol    交易对，如 BTC/USDT（/ 会替换为 - 用于文件名）
     * @param walBasePath 与撮合 WAL 同目录，如 data/wal
     */
    public OrderEventLogger(String symbol, String walBasePath) {
        this.symbol = symbol == null ? "" : symbol;
        String base = walBasePath == null || walBasePath.isEmpty() ? "data/wal" : walBasePath;
        String safeSymbol = this.symbol.replace("/", "-");
        this.logPath = Paths.get(base, "order-" + safeSymbol + ".log");
    }

    /**
     * 追加「订单接入」事件。应在撮合入口 trade(order) 前由调用方写入（或由 CoinTrader 在非 replay 模式下写入）。
     */
    public void appendOrder(ExchangeOrder order) {
        if (order == null) return;
        appendLine(EVENT_ORDER, JSON.toJSONString(order));
    }

    /**
     * 追加「撤单」事件。应在 cancelOrder 成功后由调用方写入（或由 CoinTrader 在非 replay 模式下写入）。
     */
    public void appendCancel(ExchangeOrder order) {
        if (order == null) return;
        appendLine(EVENT_CANCEL, JSON.toJSONString(order));
    }

    private void appendLine(String eventType, String payload) {
        try {
            Files.createDirectories(logPath.getParent());
            String line = eventType + "\t" + payload + "\n";
            Files.write(logPath, line.getBytes(StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("[{}] order event log append failed, type={}", symbol, eventType, e);
        }
    }

    /**
     * 回放订单日志：按序读取每一行，ORDER 调用 onOrder，CANCEL 调用 onCancel。
     * 若文件不存在或为空则直接返回。
     *
     * @param onOrder  处理「订单接入」事件，一般调用 trader.trade(order)（此时应在 replay 模式）
     * @param onCancel 处理「撤单」事件，一般调用 trader.cancelOrder(order)（此时应在 replay 模式）
     */
    public void replay(OrderEventConsumer onOrder, OrderEventConsumer onCancel) {
        if (!Files.exists(logPath)) {
            log.info("[{}] order log not exists, skip replay: {}", symbol, logPath);
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(logPath, StandardCharsets.UTF_8)) {
            String line;
            long count = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String type = line.substring(0, tab);
                String payload = line.substring(tab + 1);
                ExchangeOrder order = JSON.parseObject(payload, ExchangeOrder.class);
                if (order == null) continue;
                if (EVENT_ORDER.equals(type)) {
                    onOrder.accept(order);
                } else if (EVENT_CANCEL.equals(type)) {
                    onCancel.accept(order);
                }
                count++;
            }
            log.info("[{}] order log replay done, events={}", symbol, count);
        } catch (IOException e) {
            log.error("[{}] order log replay failed", symbol, e);
        }
    }

    @FunctionalInterface
    public interface OrderEventConsumer {
        void accept(ExchangeOrder order);
    }
}
