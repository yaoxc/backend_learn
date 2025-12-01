package com.bizzan.bitrade.entity;


import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.LinkedList;

/**
 * 盘口信息 每一个币对，在某一个方向上是一个盘口
 */
@Data
@Slf4j
public class TradePlate {
    private LinkedList<TradePlateItem> items;
    //最大深度
    //性能要求以及超过100的深度，展示并没有意义
    private int maxDepth = 100;
    //方向
    private ExchangeOrderDirection direction;
    //币对符号
    private String symbol;
    public TradePlate(){

    }

    /**
     * 某一种
     * @param symbol
     * @param direction
     */
    public TradePlate(String symbol,ExchangeOrderDirection direction) {
        this.direction = direction;
        this.symbol = symbol;
        items = new LinkedList<>();
    }

    public boolean add(ExchangeOrder exchangeOrder) {
        //log.info("add TradePlate order={}",exchangeOrder);
        synchronized (items) {
            int index = 0;
            if (exchangeOrder.getType() == ExchangeOrderType.MARKET_PRICE) {
                return false;
            }
            if (exchangeOrder.getDirection() != direction) {
                return false;
            }
            //寻找盘口合适的位置
            if (items.size() > 0) {
                for (index = 0; index < items.size(); index++) {
                    TradePlateItem item = items.get(index);
                    if (exchangeOrder.getDirection() == ExchangeOrderDirection.BUY && item.getPrice().compareTo(exchangeOrder.getPrice()) > 0
                            || exchangeOrder.getDirection() == ExchangeOrderDirection.SELL && item.getPrice().compareTo(exchangeOrder.getPrice()) < 0) {
                        // 买单：新价格 < 当前档位 → 继续找更低价格
                        // 卖单：新价格 > 当前档位 → 继续找更高价格
                        continue;
                    } else if (item.getPrice().compareTo(exchangeOrder.getPrice()) == 0) {
                        //交易项目价格相同，将当前订单未售出数量写入交易项
                        BigDecimal deltaAmount = exchangeOrder.getAmount().subtract(exchangeOrder.getTradedAmount());
                        item.setAmount(item.getAmount().add(deltaAmount));
                        return true;
                    } else {
                        //找到插入位置
                        break;
                    }
                }
            }
            if(index < maxDepth) {
                //未超过交易深度
                TradePlateItem newItem = new TradePlateItem();
                newItem.setAmount(exchangeOrder.getAmount().subtract(exchangeOrder.getTradedAmount()));
                newItem.setPrice(exchangeOrder.getPrice());
                items.add(index, newItem);
            }
        }
        return true;
    }

    public void remove(ExchangeOrder order,BigDecimal amount) {
        synchronized (items) {
            //log.info("items>>init_size={},orderPrice={}",items.size(),order.getPrice());
            for (int index = 0; index < items.size(); index++) {
                TradePlateItem item = items.get(index);
                if (item.getPrice().compareTo(order.getPrice()) == 0) {
                    //从盘口移除数量
                    item.setAmount(item.getAmount().subtract(amount));
                    if (item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                        items.remove(index);
                    }
                    //log.info("items>>final_size={},itemAmount={},itemPrice={}",items.size(),item.getAmount(),item.getPrice());
                    return;
                }
            }
            log.info("items>>return_size={}",items.size());
        }
    }

    public void remove(ExchangeOrder order){
        remove(order,order.getAmount().subtract(order.getTradedAmount()));
    }

    public void setItems(LinkedList<TradePlateItem> items){
        this.items = items;
    }

    public BigDecimal getHighestPrice(){
        if(items.size() == 0) {
            return BigDecimal.ZERO;
        }
        if(direction == ExchangeOrderDirection.BUY){
            return items.getFirst().getPrice();
        }
        else{
            return items.getLast().getPrice();
        }
    }

    public int getDepth(){
        return items.size();
    }


    public BigDecimal getLowestPrice(){
        if(items.size() == 0) {
            return BigDecimal.ZERO;
        }
        if(direction == ExchangeOrderDirection.BUY){
            return items.getLast().getPrice();
        }
        else{
            return items.getFirst().getPrice();
        }
    }

    /**
     * 获取委托量最大的档位
     * @return
     */
    public BigDecimal getMaxAmount(){
        if(items.size() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = BigDecimal.ZERO;
        for(TradePlateItem item:items){
            if(item.getAmount().compareTo(amount)>0){
                amount = item.getAmount();
            }
        }
        return amount;
    }

    /**
     * 获取委托量最小的档位
     * @return
     */
    public BigDecimal getMinAmount(){
        if(items.size() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal amount = items.getFirst().getAmount();
        for(TradePlateItem item:items){
            if(item.getAmount().compareTo(amount) < 0){
                amount = item.getAmount();
            }
        }
        return amount;
    }

    public JSONObject toJSON(){
        JSONObject json = new JSONObject();
        json.put("direction",direction);
        json.put("maxAmount",getMaxAmount());
        json.put("minAmount",getMinAmount());
        json.put("highestPrice",getHighestPrice());
        json.put("lowestPrice",getLowestPrice());
        json.put("symbol",getSymbol());
        json.put("items",items);
        return json;
    }

    public JSONObject toJSON(int limit){
        JSONObject json = new JSONObject();
        json.put("direction",direction);
        json.put("maxAmount",getMaxAmount());
        json.put("minAmount",getMinAmount());
        json.put("highestPrice",getHighestPrice());
        json.put("lowestPrice",getLowestPrice());
        json.put("symbol",getSymbol());
        json.put("items",items.size() > limit ? items.subList(0,limit) : items);
        return json;
    }
}
