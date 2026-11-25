package com.example.mvp.app.auth;

import com.example.mvp.auth.TokenService;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 站 1-A：HTTP 端点鉴权。
 *
 * <p><b>框架给 0 个东西</b> —— AgentScope core 的 21 个顶层包里没有任何鉴权概念
 * （没有 {@code security} 包、没有 filter、没有 token 机制）。
 * 整个 WebFilter / 令牌校验 / 错误响应都是 Spring 提供的机制 + 我们写的策略。
 *
 * <p><b>对照 customer_work</b>：那边用的是 Spring Security 的 {@code SecurityWebFilterChain}
 * + JWT（用户登录签发）+ {@code X-Agent-Token}（坐席共享密钥）三套并行。
 * 本 demo 刻意只用一套最简单的 Bearer Token，让「鉴权接入点在哪」这件事看得最清楚 ——
 * <b>customer_work 的关键教训是「鉴权有多个入口，漏一个就等于没做」</b>，
 * 本 demo 只有一个入口，所以不存在这个问题，但也没有演示多入口时的对齐成本。
 *
 * <p><b>拦截范围包含 {@code /api/**} 与 {@code /ws/**}</b>：
 * WS 握手虽然有 handler 内部的第二道校验，但**不能只靠那一道** ——
 * 「鉴权只接在一条路上」是 customer_work 反复踩出来的教训：
 * 能力分散在多个入口时，漏一个就等于没做。所以这里先在过滤器层统一收口，
 * handler 里的校验只作为「令牌在连接存续期间是否被换发」的 epoch 检查。
 *
 * <p><b>两条放行路径</b>：
 * <ul>
 *   <li>{@code POST /api/auth/token} —— 演示用的发号端点（生产环境不存在）</li>
 *   <li>非 {@code /api/**}、非 {@code /ws/**} 的路径 —— 静态资源、健康检查</li>
 * </ul>
 *
 * <p><b>Token 提取顺序</b>：{@code Authorization: Bearer xxx} 优先，其次 query {@code ?token=}。
 * query 那条是为 WebSocket 准备的 —— <b>浏览器 WS API 不支持自定义 header</b>，
 * 这是平台约束，不是设计偏好。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthWebFilter implements WebFilter {

    /** 校验通过后，把 userId 放进 exchange 的 attribute，供 Controller / WS handler 读取。 */
    public static final String USER_ID_ATTRIBUTE = "mvp.userId";

    /** 发号端点路径 —— 不需要鉴权。 */
    public static final String TOKEN_PATH = "/api/auth/token";

    /** WebSocket 路径前缀。 */
    private static final String WS_PREFIX = "/ws/";

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public AuthWebFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        boolean protectedPath = path.startsWith("/api/") || path.startsWith(WS_PREFIX);
        if (!protectedPath || TOKEN_PATH.equals(path)) {
            return chain.filter(exchange);
        }

        String token = extractToken(exchange);
        String userId = tokenService.verify(token);
        if (userId == null) {
            return unauthorized(exchange, "令牌无效或已过期");
        }

        exchange.getAttributes().put(USER_ID_ATTRIBUTE, userId);
        return chain.filter(exchange);
    }

    /** 提取令牌：Authorization 头优先，其次 query 参数。 */
    private String extractToken(ServerWebExchange exchange) {
        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return exchange.getRequest().getQueryParams().getFirst("token");
    }
    private Mono<Void> unauthorized(ServerWebExchange exchange, String reason) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"unauthorized\",\"message\":\"" + reason + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }
}
