package com.bizzan.bitrade.entity;

import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Entity
@Table
public class ExchangeOrder implements Serializable {
    @Id
    private String orderId;
    private Long memberId;
    //挂单类型-市价单/限价单
    private ExchangeOrderType type;
    /**
     * 委托量 amount 的含义按订单类型区分（基础货币如 BTC/ETH，计价货币如 USDT）：
     * <ul>
     *   <li>限价买单：amount = 要买的「数量」（基础货币）。用户下的是「在 price 价买多少币」，金额 = amount × price。</li>
     *   <li>限价卖单：amount = 要卖的「数量」（基础货币）。用户下的是「在 price 价卖多少币」。</li>
     *   <li>市价卖单：amount = 要卖的「数量」（基础货币）。用户下的是「市价卖多少币」，与限价卖一致。</li>
     *   <li>市价买单：amount = 要花的「金额」（计价货币）。用户下的是「花多少 USDT 买」，不指定数量，故用金额表示规模。</li>
     * </ul>
     */
    @Column(columnDefinition = "decimal(18,8) DEFAULT 0 ")
    private BigDecimal amount = BigDecimal.ZERO;
    //交易对符号
    private String symbol;
    //成交量
    @Column(columnDefinition = "decimal(26,16) DEFAULT 0 ")
    private BigDecimal tradedAmount = BigDecimal.ZERO;
    //成交额，对市价买单有用
    @Column(columnDefinition = "decimal(26,16) DEFAULT 0 ")
    private BigDecimal turnover = BigDecimal.ZERO;
    //币单位
    private String coinSymbol;
    //结算单位
    private String baseSymbol;
    //订单状态-交易中/交易完成/交易取消
    private ExchangeOrderStatus status;
    //订单方向-买/卖
    private ExchangeOrderDirection direction;
    //挂单价格
    @Column(columnDefinition = "decimal(18,8) DEFAULT 0 ")
    private BigDecimal price = BigDecimal.ZERO;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    //挂单时间
    private Long time;
    //交易完成时间
    private Long completedTime;
    //取消时间
    private Long canceledTime;
    //是否使用折扣 0 不使用 1使用
    private  String useDiscount ;
    //一个订单可能会被拆成多单支付
    @Transient
    private List<ExchangeOrderDetail> detail;
    @Override
    public String toString() {
        return JSON.toJSONString(this);
    }

    public boolean isCompleted(){
        if(status != ExchangeOrderStatus.TRADING) {
            return true;
        } else{
            /*
             * 市价买单：业务上表示「花多少计价货币（如 USDT）去买」
             *   amount   = 要花的钱 = 委托金额（计价货币）
             *   turnover = 已经花掉的钱 = 成交额（计价货币）
             * 两者单位相同，均为金额。完成条件：已花费金额 >= 委托金额，
             * 即 turnover >= amount，即 amount.compareTo(turnover) <= 0。
             * 此处是委托金额与成交额比较，并非数量与金额混比。
             */
            if(type == ExchangeOrderType.MARKET_PRICE && direction == ExchangeOrderDirection.BUY){
                return amount.compareTo(turnover) <= 0;
            }
            else{
                // 限价单（买/卖）、市价卖单：amount 均为「委托数量」（基础货币如ETH），tradedAmount 为已成交数量，单位一致；
                // 完成条件：委托数量 <= 已成交数量，即 amount.compareTo(tradedAmount) <= 0
                /**
                * 委托量 amount 的含义按订单类型区分（基础货币如 BTC/ETH，计价货币如 USDT）：
                * <ul>
                *   <li>限价买单：amount = 要买的「数量」（基础货币）。用户下的是「在 price 价买多少币」，金额 = amount × price。</li>
                *   <li>限价卖单：amount = 要卖的「数量」（基础货币）。用户下的是「在 price 价卖多少币」。</li>
                *   <li>市价卖单：amount = 要卖的「数量」（基础货币）。用户下的是「市价卖多少币」，与限价卖一致。</li>
                *   <li>市价买单：amount = 要花的「金额」（计价货币）。用户下的是「花多少 USDT 买」，不指定数量，故用金额表示规模。</li>
                * </ul>
                */
                return amount.compareTo(tradedAmount) <= 0;
            }
        }
    }
}
