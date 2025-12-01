package com.bizzan.bc.wallet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class UsdtWalletApplication {

    public static void main(String ... args){
        SpringApplication.run(UsdtWalletApplication.class,args);
    }
}
