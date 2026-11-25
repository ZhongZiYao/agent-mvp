package com.example.mvp.auth;

import com.example.mvp.config.MvpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 1-A 测试：令牌签发与校验。
 *
 * <p>令牌是接入层的<b>安全边界</b>，每一条失败路径都要有用例钉住 ——
 * 「校验逻辑写错」不会编译报错、不会打日志，只会静默放行或静默拒绝。
 */
class TokenServiceTest {

    private static TokenService serviceWith(String secret, long ttlMillis) {
        return new TokenService(new MvpProperties(new MvpProperties.Auth(secret, ttlMillis)));
    }

    private static TokenService defaultService() {
        return serviceWith("unit-test-secret-0123456789", 60_000L);
    }

    @Test
    @DisplayName("站1-A1：签发的令牌能被校验回同一个 userId")
    void issuedTokenVerifies() {
        TokenService service = defaultService();
        String token = service.issue("u1001");

        assertNotNull(token);
        assertEquals("u1001", service.verify(token));
        assertTrue(service.remainingTtlMillis(token) > 0);
    }

    @Test
    @DisplayName("站1-A2：null / 空 / 格式非法的令牌一律拒绝")
    void malformedTokenRejected() {
        TokenService service = defaultService();

        assertNull(service.verify(null));
        assertNull(service.verify(""));
        assertNull(service.verify("   "));
        assertNull(service.verify("no-dots-here"));
        assertNull(service.verify("a.b.c"));
    }

    @Test
    @DisplayName("站1-A3：篡改 payload 会导致签名不匹配 → 拒绝")
    void tamperedPayloadRejected() {
        TokenService service = defaultService();
        String token = service.issue("u1001");
        // 把 userId 换成别人，签名保持不变
        String tampered = "u9999" + token.substring(token.indexOf('.'));

        assertNull(service.verify(tampered),
                "签名覆盖 payload，篡改 userId 必须验不过 —— 否则就能顶替别人");
    }

    @Test
    @DisplayName("站1-A4：过期令牌被拒绝")
    void expiredTokenRejected() throws InterruptedException {
        TokenService service = serviceWith("unit-test-secret-0123456789", 50L);
        String token = service.issue("u1001");

        Thread.sleep(120L);
        assertNull(service.verify(token), "过期令牌必须失效");
    }

    @Test
    @DisplayName("站1-A5：不同密钥签发的令牌互不认")
    void tokenFromAnotherSecretRejected() {
        TokenService a = serviceWith("secret-a-0123456789abcdef", 60_000L);
        TokenService b = serviceWith("secret-b-0123456789abcdef", 60_000L);

        assertNull(b.verify(a.issue("u1001")),
                "签名密钥不同 → 签名不同 → 必须拒绝（防伪造）");
    }

    @Test
    @DisplayName("站1-A6：密钥过短直接启动失败")
    void shortSecretRejectedAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> serviceWith("short", 60_000L),
                "短密钥让 HMAC 可被暴力破解，必须在启动时就炸掉");
    }

    @Test
    @DisplayName("站1-A7：配置缺失时用默认值兜底，不 NPE")
    void missingAuthConfigFallsBack() {
        TokenService service = new TokenService(new MvpProperties(null));
        assertNotNull(service.issue("u1001"));
    }
}
