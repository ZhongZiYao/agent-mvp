package com.example.mvp.app.ws;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站 1-B：<b>用户维度</b>的连接注册表。
 *
 * <p><b>为什么是用户维度而不是会话维度</b>——这是接入层最容易搞错的一件事：
 * <ul>
 *   <li>浏览器一条 WS 连接上可能挂着 N 个会话（用户切会话不重连）；</li>
 *   <li>所以「一条消息往哪儿发」不能按 sessionId 找连接，得按 <b>userId</b> 找。</li>
 * </ul>
 * 帧里再带 {@code sessionId}，让前端能过滤出当前正在看的那条会话。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code infra/ws/WsSessionRegistry}，
 * {@code registerUser(userId)} 返回一个 {@link Sinks.Many}，
 * 出站时 {@code sink.asFlux().map(session::textMessage)}。
 * 用 Sink 而不是直接持有 session，是因为<b>要把「发送」和「接收」两个方向解耦</b>：
 * 业务代码只管往 sink 里 emit，不用管当前是哪个连接、连接是否还活着。
 *
 * <p><b>Sink 用 {@code multicast} 还是 {@code unicast}</b>：这里用 {@code multicast}，
 * 允许同一用户开多个页面（多连接）时都能收到。customer_work 同款。
 */
@Component
public class WsSessionRegistry {

    /** userId -> 该用户所有连接的出站 sink。 */
    private final Map<String, Sinks.Many<String>> userSinks = new ConcurrentHashMap<>();

    /**
     * 注册一条连接。
     *
     * @param userId 用户 id
     * @return 出站 sink；调用方 {@code emitNext} 即可下发
     */
    public Sinks.Many<String> registerUser(String userId) {
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        userSinks.put(userId, sink);
        return sink;
    }

    /**
     * 注销一条连接。
     *
     * <p><b>注意</b>：这里是「按 userId 覆盖式」注销 —— 同一用户开两个页面时，
     * 后一个会顶掉前一个的 sink。生产上要按连接 id 管理（customer_work 的 registry
     * 支持一用户多连接）。demo 里<b>刻意保留这个简化并在注释里标出来</b>，
     * 因为「用户维度」这个概念本身才是这站要讲的东西。
     */
    public void unregisterUser(String userId, Sinks.Many<String> sink) {
        userSinks.remove(userId, sink);
    }

    /** 当前是否有该用户的连接。 */
    public boolean hasUser(String userId) {
        return userSinks.containsKey(userId);
    }
}
