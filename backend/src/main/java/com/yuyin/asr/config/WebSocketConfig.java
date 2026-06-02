package com.yuyin.asr.config;

import com.yuyin.asr.ws.AsrWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler handler;

    public WebSocketConfig(AsrWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/asr").setAllowedOriginPatterns("*");
    }

    /** 放宽入站缓冲上限，避免较大音频帧被默认 8KB 限制截断；设置空闲超时。 */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean c = new ServletServerContainerFactoryBean();
        c.setMaxBinaryMessageBufferSize(256 * 1024);
        c.setMaxTextMessageBufferSize(64 * 1024);
        c.setMaxSessionIdleTimeout(120_000L);
        return c;
    }
}
