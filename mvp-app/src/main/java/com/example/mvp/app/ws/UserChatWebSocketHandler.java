package com.example.mvp.app.ws;

import com.example.mvp.agent.MvpAgentFactory;
import com.example.mvp.app.chat.ChatDispatchService;
import com.example.mvp.app.outbound.OutboundFilter;
import com.example.mvp.auth.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 站 1-B：用户侧 WebSocket 接入。
 *
 * <p><b>框架给 0 个东西。</b>AgentScope core 的 21 个顶层包里没有网络协议相关内容，
 * 没有握手、没有帧、没有连接管理。这整个类是接入层自建，
 * 用到的 {@link WebSocketHandler} / {@link WebSocketSession} / {@link Sinks}
 * 全部来自 Spring WebFlux。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code UserChatWebSocketHandler}，结构逐段对应：
 * <pre>
 *   customer_work                              本 demo
 *   ─────────────────────────────────         ───────────────────────────────
 *   handle(session) 多道握手鉴权                握手校验 token
 *   epoch 前后双检（check→register→epoch）      epoch 前后双检（token 代次）
 *   registry.registerUser(userId) → Sinks.Many  同
 *   session.receive() + concatMap              同
 *   Mono.firstWithSignal(send, receive)        同
 *   doFinally → unregisterUser                 同
 * </pre>
 *
 * <p><b>三条最容易踩的坑，这里都踩过了</b>：
 * <ol>
 *   <li><b>token 只能在 query 里</b>：浏览器 WS API 不支持自定义 header，
 *       所以 {@code ws://host/ws/user?token=xxx} 是平台约束，不是偷懒；</li>
 *   <li><b>WS 是用户维度不是会话维度</b>：一个用户一条连接挂着 N 个会话，
 *       出站要按 userId 找连接（见 {@link WsSessionRegistry}）；</li>
 *   <li><b>出站与入站要互相取消</b>：用 {@code firstWithSignal}，
 *       否则一端断了另一端还挂着，连接永远不释放。</li>
 * </ol>
 */
@Component
public class UserChatWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(UserChatWebSocketHandler.class);

    /** 握手路径。 */
    public static final String PATH = "/ws/user";

    private static final String ATTR_TOKEN = "mvp.token";
    private static final String ATTR_USER_ID = "mvp.userId";
    /** epoch：这条连接绑定的登录态代次。握手记一次，每次业务操作再核对一次。 */
    private static final String ATTR_EPOCH = "mvp.epoch";
    /** 出站 sink：注册时放进 session attribute，业务代码只管往里 emit。 */
    private static final String ATTR_SINK = "mvp.sink";

    private final TokenService tokenService;
    private final ChatDispatchService dispatchService;
    private final WsSessionRegistry registry;
    private final MvpAgentFactory agentFactory;
    private final ObjectMapper objectMapper;

    public UserChatWebSocketHandler(TokenService tokenService,
                                    ChatDispatchService dispatchService,
                                    WsSessionRegistry registry,
                                    MvpAgentFactory agentFactory,
                                    ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.dispatchService = dispatchService;
        this.registry = registry;
        this.agentFactory = agentFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String token = tokenOf(session);
        String userId = tokenService.verify(token);
        if (userId == null) {
            log.info("ws handshake rejected: invalid token");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }
        long epoch = epochOf(token);

        session.getAttributes().put(ATTR_TOKEN, token);
        session.getAttributes().put(ATTR_USER_ID, userId);
        session.getAttributes().put(ATTR_EPOCH, epoch);

        return handleAuthenticated(session, userId, epoch);
    }

    /** 已鉴权连接的处理：注册 → 双检 → 收发并行。 */
    private Mono<Void> handleAuthenticated(WebSocketSession session, String userId, long handshakeEpoch) {
        // ---- 注册前复查（epoch 双检的前半段）----
        if (epochOf(currentToken(session)) != handshakeEpoch) {
            log.info("ws registration rejected before register: epoch changed");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        Sinks.Many<String> sink = registry.registerUser(userId);
        session.getAttributes().put(ATTR_SINK, sink);

        // ---- 注册后复查（epoch 双检的后半段）----
        // 前后双检一起闭合「校验 → 注册」之间 epoch 轮换的并发窗口：
        // 只查一次的话，轮换恰好落在中间就会漏过去。
        if (epochOf(currentToken(session)) != handshakeEpoch) {
            registry.unregisterUser(userId, sink);
            log.info("ws registration rejected after register: epoch changed");
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        Flux<WebSocketMessage> outbound = sink.asFlux().map(session::textMessage);
        Mono<Void> receive = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(payload -> handleInbound(session, userId, payload))
                .then();

        // 任一方向结束就取消另一侧：否则一端断了另一端还挂着，连接永远不释放
        return Mono.firstWithSignal(session.send(outbound), receive)
                .doFinally(signal -> registry.unregisterUser(userId, sink));
    }

    /** 处理一条入站帧。 */
    private Mono<Void> handleInbound(WebSocketSession session, String userId, String payload) {
        Sinks.Many<String> sink = sinkOf(session);
        if (sink == null) {
            return Mono.empty();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception e) {
            emit(sink, WsFrame.error("BAD_FRAME", "帧不是合法 JSON", null, null));
            return Mono.empty();
        }

        String type = root.path("type").asText("");
        JsonNode data = root.path("data");

        if (WsFrame.TYPE_PING.equals(type)) {
            emit(sink, WsFrame.pong());
            return Mono.empty();
        }
        if (!WsFrame.TYPE_CHAT.equals(type)) {
            emit(sink, WsFrame.error("UNSUPPORTED_TYPE", "不支持的帧类型：" + type, null, null));
            return Mono.empty();
        }

        String clientMsgId = data.path(WsFrame.KEY_CLIENT_MSG_ID).asText(null);
        String sessionId = data.path(WsFrame.KEY_SESSION_ID).asText(null);
        String message = data.path(WsFrame.KEY_MESSAGE).asText("");

        // 每次业务操作前再核对一次 epoch（双检的另一半）
        long boundEpoch = (Long) session.getAttributes().get(ATTR_EPOCH);
        if (epochOf(currentToken(session)) != boundEpoch) {
            return session.close(CloseStatus.POLICY_VIOLATION);
        }

        ChatDispatchService.Acceptance acceptance =
                dispatchService.accept(userId, clientMsgId, sessionId, message);

        // 重复投递 <b>不是错误</b>：它是网络重发的正常形态。
        // 回一条受理帧（受理号与会话号都是首次的），前端据此把气泡对齐、不重复渲染。
        if (WsFrame.ACCEPTANCE_DUPLICATE.equals(acceptance.status())) {
            emit(sink, WsFrame.chatAccepted(clientMsgId, acceptance.acceptanceId(),
                    acceptance.sessionId(), WsFrame.ACCEPTANCE_DUPLICATE));
            return Mono.empty();
        }

        if (!WsFrame.ACCEPTANCE_ACCEPTED.equals(acceptance.status())) {
            emit(sink, WsFrame.error(
                    acceptance.status(), acceptance.reason(), acceptance.sessionId(), clientMsgId));
            return Mono.empty();
        }

        emit(sink, WsFrame.chatAccepted(clientMsgId, acceptance.acceptanceId(),
                acceptance.sessionId(), WsFrame.ACCEPTANCE_ACCEPTED));

        // 重复投递：受理号已回，但不再触发模型调用（省 token，也避免重复副作用）
        if (!acceptance.shouldDispatch()) {
            return Mono.empty();
        }

        return streamReply(sink, userId, acceptance.sessionId(), clientMsgId, message);
    }

    /** 调 Agent 并把正文增量逐片下发。 */
    private Mono<Void> streamReply(Sinks.Many<String> sink, String userId, String sessionId,
                                    String clientMsgId, String message) {
        StringBuilder fullText = new StringBuilder();
        return Flux.defer(() -> {
                    // 站 2：Agent 由装配层统一构建；上下文口径与建 Agent 来自同一处
                    ReActAgent agent = agentFactory.create(userId, sessionId);
                    RuntimeContext ctx = agentFactory.contextFor(userId, sessionId);
                    return OutboundFilter.textDeltas(agent.streamEvents(message, ctx));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    fullText.append(chunk);
                    emit(sink, WsFrame.chatChunk(chunk, sessionId, clientMsgId));
                })
                .doOnComplete(() -> emit(sink,
                        WsFrame.chatDone(sessionId, clientMsgId, fullText.toString())))
                .onErrorResume(e -> {
                    log.warn("ws reply failed: {}", e.toString());
                    emit(sink, WsFrame.error("REPLY_FAILED", String.valueOf(e.getMessage()),
                            sessionId, clientMsgId));
                    return Mono.empty();
                })
                .then();
    }

    /** 从握手的 query 里取 token —— 浏览器 WS API 不支持自定义 header。 */
    private String tokenOf(WebSocketSession session) {
        String query = session.getHandshakeInfo().getUri().getQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0 && "token".equals(pair.substring(0, idx))) {
                return URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    /**
     * 计算当前登录态的 epoch。
     *
     * <p>用「token 的剩余有效期」当 epoch —— 令牌换了或过期了 epoch 就变，旧连接立刻作废。
     * 语义与 customer_work 的「租户访问权 epoch」一致：
     * <b>都是回答「这条连接绑定的登录态还是不是当前那个登录态」</b>。
     */
    private long epochOf(String token) {
        if (token == null) {
            return 0L;
        }
        return System.currentTimeMillis() + tokenService.remainingTtlMillis(token);
    }

    private String currentToken(WebSocketSession session) {
        Object attr = session.getAttributes().get(ATTR_TOKEN);
        return attr instanceof String s ? s : null;
    }

    private Sinks.Many<String> sinkOf(WebSocketSession session) {
        Object attr = session.getAttributes().get(ATTR_SINK);
        if (attr instanceof Sinks.Many<?> s) {
            @SuppressWarnings("unchecked")
            Sinks.Many<String> sink = (Sinks.Many<String>) s;
            return sink;
        }
        return null;
    }

    private void emit(Sinks.Many<String> sink, WsFrame frame) {
        if (sink == null) {
            return;
        }
        try {
            sink.emitNext(objectMapper.writeValueAsString(frame), Sinks.EmitFailureHandler.FAIL_FAST);
        } catch (Exception e) {
            log.warn("ws emit failed: {}", e.toString());
        }
    }
}
