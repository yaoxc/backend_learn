package com.bizzan.bc.wallet.entity;

import lombok.Builder;
import org.web3j.crypto.Credentials;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * @Builder 是 Lombok 提供的注解，写在类上就相当于 给你自动生成一个“建造者（Builder）”模式的全部代码；
 *
 * 意义(好处):
 *      1.链式代码更清爽
 *              Payment p = Payment.builder()
 *                                 .id(1L)
 *                                 .amount(new BigDecimal("100.00"))
 *                                 .currency("CNY")
 *                                 .build();
 *       避免十几行的巨型构造器或 setXxx 瀑布。
 */
@Builder
public class Payment {
    private String txBizNumber;
    private String txid;
    private Credentials credentials;
    private String to;
    private BigDecimal amount;
    private String unit;
    private BigInteger gasLimit;
    private BigInteger gasPrice;

    public String getTxid() {
        return txid;
    }

    public void setTxid(String txid) {
        this.txid = txid;
    }

    public Credentials getCredentials() {
        return credentials;
    }

    public void setCredentials(Credentials credentials) {
        this.credentials = credentials;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public BigInteger getGasLimit() {
        return gasLimit;
    }

    public void setGasLimit(BigInteger gasLimit) {
        this.gasLimit = gasLimit;
    }

    public BigInteger getGasPrice() {
        return gasPrice;
    }

    public void setGasPrice(BigInteger gasPrice) {
        this.gasPrice = gasPrice;
    }

    public String getTxBizNumber(){
        return txBizNumber;
    }
}
