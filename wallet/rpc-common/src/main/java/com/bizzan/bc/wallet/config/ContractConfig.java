package com.bizzan.bc.wallet.config;

import com.bizzan.bc.wallet.entity.Contract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自动配置合约参数
 */
// 1. 告诉 Spring：这是一个“配置类”，里面可以定义 @Bean；
//    配置类本身也会被 Spring 扫描成 Bean（名字 = contractConfig）
@Configuration
// 2. 只有当**环境中存在** key = "contract.address" 的配置项时，
//    整个 ContractConfig 才会被实例化，否则 Spring 直接跳过，
//    里面所有 @Bean 都不会执行（相当于这个类不存在）
@ConditionalOnProperty(name = "contract.address")
public class ContractConfig {

    // 3. 声明一个 Bean，id = "getContract"（默认方法名），
    //    类型 = Contract；Spring 容器启动时会把返回值注册成单例
    @Bean
    // 4. 把“配置文件里前缀为 contract 的全部键值”一次性
    //    绑定到这个新创建的 Contract 对象（字段名/Setter 映射）；
    //    例如 application.yml 里有
    //      contract:
    //        address: 0x1234
    //        abi: [{...}]
    //        name: Token
    //    则 Contract 类里对应的 address/abi/name 字段会被自动填充
    @ConfigurationProperties(prefix = "contract")
    public Contract getContract(){
        return new Contract();
    }
}
