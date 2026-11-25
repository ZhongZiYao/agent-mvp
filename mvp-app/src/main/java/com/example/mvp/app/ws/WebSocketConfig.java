package com.example.mvp.app.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站 1-B：响应式 WebSocket 装配。
 *
 * <p><b>关键点：用手写 {@link SimpleUrlHandlerMapping}，不用 {@code @EnableWebSocket} 注解。</b>
 * 原因是 {@code @EnableWebSocket} 那套 {@code WebSocketHandlerRegistry} 是
 * <b>Servlet 风格</b>的，依赖 {@code spring-websocket}；
 * 而 Reactive 的 WebSocket 适配器在 {@code spring-webflux} 里自带。
 *
 * <p>如果为了图省事加 {@code spring-boot-starter-websocket}，会连带引入
 * {@code spring-boot-starter-tomcat} —— Spring Boot 见到 Tomcat 就会把应用类型判定成
 * {@code SERVLET} 而��是 {@code REACTIVE}，**WebFlux 直接失效**。
 * 这是「响应式项目加 WebSocket」最经典的一个坑。
 *
 * <p><b>对照 customer_work</b>：那边同样是手写 {@code SimpleUrlHandlerMapping} +
 * {@code WebSocketHandlerAdapter}，注释里写着「spring-webflux 原生，无额外依赖」——
 * 本 demo 沿用同一做法，依赖清单保持干净。
 */
@Configuration
public class WebSocketConfig {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    /** 用户侧 WS 路径。 */
    private static final String PATH_USER = UserChatWebSocketHandler.PATH;

    @Bean
    public HandlerMapping webSocketHandlerMapping(UserChatWebSocketHandler userHandler) {
        Map<String, WebSocketHandler> map = new LinkedHashMap<>();
        map.put(PATH_USER, userHandler);

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(map);
        // 高于注解型 @RequestMapping（默认 order=0），确保 /ws/* 走 WebSocket 升级而非普通请求
        mapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        log.info("WS handler mapping registered: paths={} order={}", map.keySet(), mapping.getOrder());
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
