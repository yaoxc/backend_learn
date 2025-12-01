package com.bizzan.bc.wallet.config;


import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.entity.WatcherSetting;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自动配置币种参数
 */
@Configuration                                              // 1. 声明这是一个“配置类”，Spring 会把它扫描成 Bean，并在内部解析 @Bean 方法
@ConditionalOnProperty(name = "coin.name")                 // 2. 只有当环境里有“coin.name”这项配置（值不限）时，整个 CoinConfig 才会生效；否则 Spring 直接跳过此类
public class CoinConfig {                                  // 3. 配置类本身也会注册为单例 Bean，默认名 = coinConfig

    @Bean                                                  // 4. 把方法返回值注册成 Spring Bean，id = 方法名 getCoin，类型 = Coin，单例
    @ConfigurationProperties(prefix = "coin")              // 5. 将“coin.*”开头的全部配置一次性绑定到返回的 Coin 实例（字段/Setter 映射）
    public Coin getCoin(){                                 // 6. 创建并返回 Coin 对象，Spring 负责填充属性
        Coin coin = new Coin();                            // 7. 手动 new 出原始对象，后续由 @ConfigurationProperties 完成属性注入
        return coin;                                       // 8. 返回填充后的实例，成为容器里的 Bean
    }

    @Bean                                                  // 9. 再把另一个返回值注册成 Spring Bean，id = getWatcherSetting，类型 = WatcherSetting
    @ConfigurationProperties(prefix = "watcher")           // 10. 将“watcher.*”开头的全部配置绑定到返回的 WatcherSetting 实例
    public WatcherSetting getWatcherSetting(){             // 11. 创建并返回 WatcherSetting 对象，同样由 Spring 负责属性填充
        WatcherSetting setting = new WatcherSetting();     // 12. 手动 new 出原始对象
        return setting;                                    // 13. 返回填充后的实例，成为容器里的第二个 Bean
    }
}