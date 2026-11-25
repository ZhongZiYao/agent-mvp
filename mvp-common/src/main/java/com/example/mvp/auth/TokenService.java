package com.example.mvp.auth;

import com.example.mvp.config.MvpProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 站 1-A：令牌签发与校验 —— <b>框架一个字节都没给，这段全是自建</b>。
 *
 * <p><b>对照 customer_work</b>：那边客服端走 JWT（用户登录签发），
 * 坐席端走 {@code X-Agent-Token} 共享密钥，admin 侧走 Sa-Token。
 * 本 demo <b>刻意不引 JWT 库、不引 Sa-Token</b> —— 用 JDK 自带的
 * {@link Mac} 手写 HMAC-SHA256，理由有二：
 * <ol>
 *   <li>demo 的目的是验证「框架给了什么」，引一个框架/SPI 就看不到边界在哪；</li>
 *   <li>零依赖让 demo 的 {@code pom.xml} 保持干净 —— 依赖越少，
 *       「这个能力到底是谁给的」越清楚。</li>
 * </ol>
 *
 * <p><b>令牌格式</b>（不用 JWT，但结构一样是三段）：{@code userId.expiresAtMillis.hmac}
 * <ul>
 *   <li>第 1 段：用户 id</li>
 *   <li>第 2 段：过期时间戳（毫秒）</li>
 *   <li>第 3 段：对前两段的 HMAC-SHA256 签名，Base64URL 无填充</li>
 * </ul>
 *
 * <p><b>为什么不用 JWT 库</b>不是偷懒：JWT 库省的是「格式标准化 + 库支持算法」，
 * 而令牌真正的风险点是<b>校验逻辑写错</b>（时序攻击、过期判断、算法混淆）。
 * 手写一遍反而让这几处显式可见 —— {@link MessageDigest#isEqual} 用于防时序攻击。
 */
@Component
public class TokenService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    /** 令牌有效期：默认 2 小时。 */
    private final long ttlMillis;
    /** 签名密钥。 */
    private final byte[] secret;
    private final SecureRandom random = new SecureRandom();

    public TokenService(MvpProperties properties) {
        MvpProperties.Auth auth = properties.resolvedAuth();
        this.ttlMillis = auth.tokenTtlMillis() <= 0 ? 2 * 60 * 60 * 1000L : auth.tokenTtlMillis();
        this.secret = auth.tokenSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 16) {
            throw new IllegalStateException(
                    "mvp.auth.token-secret 至少 16 字节（当前 " + secret.length + "）—— 短密钥让 HMAC 可被暴力破解");
        }
    }

    /**
     * 签发令牌。
     *
     * @param userId 用户 id
     * @return {@code userId.expiresAt.signature}
     */
    public String issue(String userId) {
        long expiresAt = System.currentTimeMillis() + ttlMillis;
        String payload = userId + "." + expiresAt;
        return payload + "." + sign(payload);
    }

    /**
     * 校验令牌。
     *
     * @return 用户 id；令牌无效/过期返回 {@code null}
     */
    public String verify(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return null;
        }
        String userId = parts[0];
        String expiresAt = parts[1];
        String signature = parts[2];

        long expiry;
        try {
            expiry = Long.parseLong(expiresAt);
        } catch (NumberFormatException e) {
            return null;
        }
        if (System.currentTimeMillis() > expiry) {
            return null;
        }

        String expected = sign(userId + "." + expiresAt);
        // isEqual 是常量时间比较，避免通过响应时间差逐字节爆破签名
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return userId;
    }

    /** 令牌剩余有效期（毫秒），用于给客户端续期。 */
    public long remainingTtlMillis(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return 0L;
        }
        try {
            return Math.max(0L, Long.parseLong(parts[1]) - System.currentTimeMillis());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 生成一个随机的用户 id —— 仅供演示端点用。
     *
     * <p>生产环境不存在这个入口：用户 id 来自真实登录（手机号/账号），
     * 由服务端根据会话态查出，而不是让客户端自己报。
     */
    public String newDemoUserId() {
        byte[] buf = new byte[9];
        random.nextBytes(buf);
        return "u" + ENCODER.encodeToString(buf);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return ENCODER.encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 签名失败", e);
        }
    }
}
