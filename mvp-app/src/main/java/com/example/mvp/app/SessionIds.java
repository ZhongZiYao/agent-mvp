package com.example.mvp.app;

import java.util.UUID;

/**
 * 站 1-C：会话号生成与归属校验。
 *
 * <p><b>核心教训（也是 customer_work 踩过并写进代码注释的那条）</b>：
 * <b>会话号不是凭证。</b>
 * 只要会话号是客户端可猜/可传的，服务端就必须<b>另外做归属校验</b>，
 * 否则「拿到别人的 sessionId 就能顶替他说话」。
 *
 * <p><b>对照 customer_work</b>：那边的 {@code ChatDispatchService#ownsSession}
 * 用<b>前缀校验</b>（{@code sessionId.startsWith(userId + "-")}）做归属判断。
 * 本 demo 用同一套规则 —— 简单、可预测、且演示了「归属校验必须存在」这件事。
 *
 * <p><b>为什么用前缀而不是查表</b>：前缀法不需要会话注册表，
 * 但代价是<b>它挡不住「知道 userId 就能伪造任意后缀会话」</b>。
 * 生产上真正的归属依据是数据库里那条会话记录（customer_work 就有会话表），
 * 前缀只是<b>便宜的快速否决</b>。这一条要在文档里说清楚，否则容易误导。
 */
public final class SessionIds {

    private SessionIds() {
    }

    /**
     * 解析本次请求使用的会话号。
     *
     * <p>客户端没传就新建一个；传了就<b>必须属于该用户</b>，否则抛异常。
     *
     * @param requested 客户端请求的会话号，可为 null
     * @param userId    当前已鉴权的用户 id
     * @return 可用的会话号
     * @throws IllegalArgumentException 会话号不属于该用户
     */
    public static String resolve(String requested, String userId) {
        if (requested == null || requested.isBlank()) {
            return newSessionId(userId);
        }
        String sessionId = requested.trim();
        if (!belongsTo(sessionId, userId)) {
            throw new IllegalArgumentException("会话号不属于当前用户：" + sessionId);
        }
        return sessionId;
    }

    /** 新建会话号：{@code userId-短随机串}。 */
    public static String newSessionId(String userId) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return userId + "-" + suffix;
    }

    /**
     * 归属校验：会话号必须以 {@code userId + "-"} 开头。
     *
     * <p>对照 customer_work 的 {@code ownsSession}，同一口径。
     */
    public static boolean belongsTo(String sessionId, String userId) {
        if (sessionId == null || userId == null || sessionId.isBlank() || userId.isBlank()) {
            return false;
        }
        return sessionId.startsWith(userId + "-");
    }
}
