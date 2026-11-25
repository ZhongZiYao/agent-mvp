package com.example.mvp.app.auth;

import com.example.mvp.auth.TokenService;
import com.example.mvp.config.MvpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 站 1-A 测试：HTTP 端点鉴权。
 *
 * <p>用 {@code MockServerWebExchange} 直接测过滤器，<b>不启动 Spring 容器</b> ——
 * 快，且不受应用其它 Bean 的影响。
 */
class AuthWebFilterTest {

    private static final String SECRET = "unit-test-secret-0123456789";

    private static TokenService tokenService() {
        return new TokenService(new MvpProperties(new MvpProperties.Auth(SECRET, 60_000L)));
    }

    private static AuthWebFilter filter() {
        return new AuthWebFilter(tokenService());
    }

    /** 记录 chain 是否被调用过、userId 有没有被写入。 */
    private static final class RecordingChain implements org.springframework.web.server.WebFilterChain {
        boolean invoked;
        String userId;

        @Override
        public Mono<Void> filter(ServerWebExchange exchange) {
            invoked = true;
            userId = exchange.getAttribute(AuthWebFilter.USER_ID_ATTRIBUTE);
            return Mono.empty();
        }
    }

    @Test
    @DisplayName("站1-A8：没有令牌 → 401，且不进入业务链")
    void missingTokenRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/chat/sync"));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
        assertNull(chain.userId);
        assertEquals(false, chain.invoked, "鉴权失败不得进入业务链");
    }

    @Test
    @DisplayName("站1-A9：无效令牌 → 401")
    void invalidTokenRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/chat/sync")
                        .header("Authorization", "Bearer garbage"));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value());
    }

    @Test
    @DisplayName("站1-A10：有效 Bearer 令牌 → 放行，且把 userId 交给下游")
    void validBearerTokenPasses() {
        String token = tokenService().issue("u1001");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/chat/sync")
                        .header("Authorization", "Bearer " + token));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertNotNull(chain.userId);
        assertEquals("u1001", chain.userId);
    }

    @Test
    @DisplayName("站1-A11：query 参数里的令牌也认（WebSocket 握手用这条路）")
    void queryTokenPasses() {
        String token = tokenService().issue("u1002");
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/ws/user?token=" + token));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals("u1002", chain.userId);
    }

    @Test
    @DisplayName("站1-A11b：WS 路径也走这道闸 —— 不能只靠 handler 内部鉴权")
    void webSocketPathIsAlsoProtected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/ws/user"));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals(401, exchange.getResponse().getStatusCode().value(),
                "WS 握手必须被这道闸拦一道 —— 否则 handler 里漏了鉴权就是全线裸奔");
    }

    @Test
    @DisplayName("站1-A12：发号端点本身不需要鉴权")
    void tokenEndpointIsPublic() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post(AuthWebFilter.TOKEN_PATH));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals(true, chain.invoked, "发号端点必须放行，否则拿不到令牌");
        assertNull(exchange.getResponse().getStatusCode(),
                "放行时不应设置状态码");
    }

    @Test
    @DisplayName("站1-A13：非 /api/** 路径不拦（静态资源、健康检查）")
    void nonApiPathNotIntercepted() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health"));
        RecordingChain chain = new RecordingChain();

        filter().filter(exchange, chain).block();

        assertEquals(true, chain.invoked);
    }
}
