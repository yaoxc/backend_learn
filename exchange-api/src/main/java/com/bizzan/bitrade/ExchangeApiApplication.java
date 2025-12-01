package com.bizzan.bitrade;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class ExchangeApiApplication {

    public static void main(String[] args){
        SpringApplication.run(ExchangeApiApplication.class,args);
    }
}
