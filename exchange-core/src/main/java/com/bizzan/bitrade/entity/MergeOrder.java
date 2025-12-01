package com.bizzan.bitrade.entity;

import com.alibaba.fastjson.JSON;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 组合订单-怎么理解
 */
public class MergeOrder {
    private List<ExchangeOrder> orders = new ArrayList<>();

    //最后位置添加一个
    public void add(ExchangeOrder order){
        orders.add(order);
    }


    public ExchangeOrder get(){
        return orders.get(0);
    }

    public int size(){
        return orders.size();
    }

    /**
     * 取第一个作为撮合价
     * @return
     */
    public BigDecimal getPrice(){
        return orders.get(0).getPrice();
    }

    public Iterator<ExchangeOrder> iterator(){
        return orders.iterator();
    }

    /**
     * 订单总供应量
     * @return
     */
    public BigDecimal getTotalAmount() {
    	BigDecimal total = new BigDecimal(0);
    	for(ExchangeOrder item : orders) {
    		total = total.add(item.getAmount());
    	}
    	return total;
    }
}
