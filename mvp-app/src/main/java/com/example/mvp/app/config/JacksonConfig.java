package com.example.mvp.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 站 1-B：显式声明 {@link ObjectMapper} Bean。
 *
 * <p><b>踩到的坑，以及它背后那个 Boot 4 的大变化</b>：
 * <pre>
 *   No qualifying bean of type 'com.fasterxml.jackson.databind.ObjectMapper' available
 * </pre>
 *
 * <p>根因不是「WebFlux 不给 ObjectMapper」，而是 —— <b>Spring Boot 4 迁到 Jackson 3 了</b>：
 * <pre>
 *   spring-boot-starter-jackson:4.1.3
 *     └── spring-boot-jackson:4.1.3
 *           └── tools.jackson.core:jackson-databind:3.1.8     ← Jackson 3，坐标前缀变了
 * </pre>
 * Boot 4 把 Jackson 自动配置从 {@code spring-boot-autoconfigure} 拆到了独立的
 * {@code spring-boot-jackson} 模块，且那个 {@code JacksonAutoConfiguration}
 * 产出的是 <b>Jackson 3 的 {@code tools.jackson.databind.ObjectMapper}</b>。
 * 而 {@code com.fasterxml.jackson.core:jackson-databind}（Jackson 2）
 * 在这里只是 {@code agentscope-harness} 传递进来的<b>共存产物</b>，没有任何自动配置会给它建 bean。
 *
 * <p><b>对照 customer_work</b>：那边是 <b>Boot 3.2.5</b>（Jackson 2 时代），
 * {@code JacksonAutoConfiguration} 就在 {@code spring-boot-autoconfigure} 里，
 * 产出的正是 {@code com.fasterxml.jackson.databind.ObjectMapper}，所以那边能直接注入。
 * <b>同一个类名，在 Boot 3 与 Boot 4 下由不同的自动配置提供</b> —— 这是升级时的高发坑。
 *
 * <p><b>demo 这里选 Jackson 2 而不是 Jackson 3</b>：理由是对照性 ——
 * customer_work 全程用 {@code com.fasterxml.jackson.databind.ObjectMapper}，
 * demo 用同一套 API，读者能一行行对得上。
 *
 * @see <a href="https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Release-Notes">Spring Boot 4.0 Release Notes</a>
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return Jackson2ObjectMapperBuilder.json().build();
    }
}
