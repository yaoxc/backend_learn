package com.bizzan.bitrade.config; // 配置类所在包

import org.springframework.context.annotation.Configuration; // 声明这是一个 Spring 配置类
import org.springframework.messaging.simp.config.MessageBrokerRegistry; // STOMP 消息代理注册器
import org.springframework.web.socket.config.annotation.AbstractWebSocketMessageBrokerConfigurer; // Spring 提供的 STOMP 配置基类（已过时，可用 WebSocketMessageBrokerConfigurer）
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker; // 启用 WebSocket 消息代理（STOMP）
import org.springframework.web.socket.config.annotation.StompEndpointRegistry; // STOMP 端点注册器

@Configuration // 被 Spring 扫描加载
@EnableWebSocketMessageBroker // 开启“STOMP over WebSocket”功能
public class WebSocketConfig extends AbstractWebSocketMessageBrokerConfigurer {

    /**
     * 配置“消息代理”规则：谁负责广播、订阅路径前缀
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // 启用**内存级**简单代理，客户端可订阅 /topic/xxx
        config.setApplicationDestinationPrefixes("/app"); // 客户端发消息到服务器的前缀（如 /app/send）
    }

    /**
     * 注册 STOMP 端点：WebSocket 握手入口
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 暴露端点 /market-ws，允许跨域，同时支持 SockJS 降级（旧浏览器也能连）
        registry.addEndpoint("/market-ws").setAllowedOrigins("*").withSockJS();
    }
}