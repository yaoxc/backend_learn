package com.bizzan.bc.wallet.entity;


import lombok.Data;

import java.math.BigDecimal;

@Data
public class Account {
    private String account;
    private String address;
    //私钥路径
    private String walletFile;
    
    // 钱包实际余额
    private BigDecimal balance = BigDecimal.ZERO;
    
    // 钱包可用余额
    private BigDecimal memberWalletBalance = BigDecimal.ZERO;
    
    //地址燃料余额，对Token,USDT有用
    private BigDecimal gas = BigDecimal.ZERO;
}
