package com.bizzan.bc.wallet.config;

import com.bizzan.bc.wallet.entity.Coin;
import com.bizzan.bc.wallet.service.EtherscanApi;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import okhttp3.OkHttpClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class EthConfig {
    /**
     * 只有当 application.yml 里写了 coin.keystore-path: /secure/keystore.p12
     * （或启动参数 --coin.keystore-path=xxx）这个 Bean 才会被创建
     *
     * 常见坑：
     * 把注解写在整个配置类上时，类里所有 Bean 一起失效。
     * 使用 prefix 时要把完整 key 拆开： @ConditionalOnProperty(prefix = "coin", name = "keystore-path")
     * 对应配置 coin.keystore-path，而不是 coin.keystorePath
     */
    @Bean                                                      // 1. 把返回值注册成 Spring Bean，id = web3j；其他地方直接 @Autowired Web3j web3j 即可
    @ConditionalOnProperty(name = "coin.keystore-path")       // 2. 只有当配置里出现 coin.keystore-path 时（值不限）才实例化；没有就跳过，防止无钱包节点浪费连接
    public Web3j web3j(Coin coin) {                           // 3. 注入前面 @ConfigurationProperties 绑定的 Coin 对象，里面至少要有 rpc 节点地址
        // 4. 自建 OkHttpClient，解决 Web3j 默认超时过短/连接池缺失问题
        OkHttpClient.Builder builder = new OkHttpClient.Builder();
        builder.connectTimeout(30 * 1000, TimeUnit.MILLISECONDS); // 5. 建立 TCP 连接超时 30s（节点在海外时常用）
        builder.writeTimeout(30 * 1000, TimeUnit.MILLISECONDS);   // 6. 写请求超时 30s（大交易发送）
        builder.readTimeout(30 * 1000, TimeUnit.MILLISECONDS);    // 7. 读响应超时 30s（大区块同步）
        OkHttpClient httpClient = builder.build();

        // 8. 创建 HttpService：参数1 = RPC URL，参数2 = 自定义 client，参数3 = 是否启用 gzip（false=不压缩，降低 CPU）
        Web3j web3j = Web3j.build(new HttpService(coin.getRpc(), httpClient, false));

        return web3j;                                             // 9. 返回的 Web3j 实例是**线程安全**的，全局单例可复用
    }

    @Bean
    @ConfigurationProperties(prefix = "etherscan")
    public EtherscanApi etherscanApi(){
        EtherscanApi api = new EtherscanApi();
        return api;
    }

    @Bean
    public JsonRpcHttpClient jsonrpcClient(Coin coin) throws MalformedURLException {
        System.out.println("init jsonRpcClient");
        JsonRpcHttpClient jsonrpcClient = new JsonRpcHttpClient(new URL(coin.getRpc()));
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        jsonrpcClient.setHeaders(headers);
        return jsonrpcClient;
    }
}
