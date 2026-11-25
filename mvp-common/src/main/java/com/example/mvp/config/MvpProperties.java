package com.example.mvp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * demo 的全局配置（对应 customer_work 的 {@code CustomerWorkProperties}，但刻意做小）。
 *
 * <p>customer_work 的那套有几十个开关、跨 starter/app/admin 三个模块；
 * 本 demo 只保留「站 1 接入层」真正用到的几个，
 * 其余开关等用到时再加 —— <b>避免出现「配了但没人读」的僵尸配置</b>。
 *
 * @param auth 鉴权配置
 */
@ConfigurationProperties(prefix = "mvp")
public record MvpProperties(Auth auth) {

    /** 默认签名密钥 —— 只在配置缺失时用；生产必须显式配置。 */
    private static final Auth DEFAULTS = new Auth("mvp-dev-only-secret-change-me", 2 * 60 * 60 * 1000L);

    /**
     * 取鉴权配置，带兜底。
     *
     * <p>record 的字段可能整个是 {@code null}（yml 里没写 {@code mvp.auth} 段），
     * 直接 {@code getAuth()} 会 NPE —— 这里统一兜底，
     * 免得「忘配鉴权」变成启动时的 NPE 堆栈，而不是一条能看懂的报错。
     */
    public Auth resolvedAuth() {
        return auth == null ? DEFAULTS : auth;
    }

    /**
     * @param tokenSecret    HMAC 签名密钥（至少 16 字节）
     * @param tokenTtlMillis 令牌有效期（毫秒）
     */
    public record Auth(String tokenSecret, long tokenTtlMillis) {
    }
}
