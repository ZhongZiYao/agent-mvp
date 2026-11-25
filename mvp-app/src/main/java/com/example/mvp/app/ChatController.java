package com.example.mvp.app;

import com.example.mvp.agent.MvpAgentFactory;
import com.example.mvp.app.auth.AuthWebFilter;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 站 1 的 HTTP 出入口。
 *
 * <p><b>这里的每一行都是自建的</b>：框架 core 没有任何「HTTP 端点」「会话」「用户」概念。
 * AgentScope 提供的是 {@link ReActAgent} —— 一个「给它一句话，它回一句话」的对象；
 * 谁来调、调给谁、会话怎么归属，全是接入层的事。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code CustomerServiceController} +
 * {@code CustomerServiceService}；本 demo 刻意保持扁，
 * 只留「鉴权 → 会话归属 → 调 Agent → 出站」四步，让边界看得清。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final MvpAgentFactory agentFactory;

    public ChatController(MvpAgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * 同步问一句。
     *
     * <p><b>这里踩过一个真实的坑，值得记住</b>：第一版写的是
     * {@code agent.call(...).block()}，在 WebFlux 下直接炸：
     * <pre>
     * IllegalStateException: block()/blockFirst()/blockLast() are blocking,
     * which is not supported in thread reactor-http-nio-2
     * </pre>
     * 原因：REST 端点跑在 Netty 的<b>事件循环线程</b>上，Reactor 明令禁止在 event-loop 上阻塞
     * —— 一阻塞整条 IO 线程就卡死，其他请求全部排队。
     *
     * <p><b>正确写法有两层</b>：
     * <ol>
     *   <li>{@code subscribeOn(Schedulers.boundedElastic())} —— 把订阅链整体挪到弹性线程池，
     *       这样模型调用内部若是同步 HTTP，也不会占住 event-loop；</li>
     *   <li>返回值直接用 {@code Mono} 表达，<b>全程不 .block()</b>。</li>
     * </ol>
     *
     * <p>对照 customer_work：那边同样禁止在响应式链路里 {@code .block()}，
     * 需要落盘的写操作会显式切到 {@code boundedElastic}。
     */
    @PostMapping("/sync")
    public Mono<ChatReply> chatSync(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        String userId = exchange.getAttribute(AuthWebFilter.USER_ID_ATTRIBUTE);
        String sessionId = SessionIds.resolve(request.sessionId(), userId);

        return Mono.defer(() -> {
                    // 站 2：Agent 由装配层统一构建（模型 / 工具 / 状态槽位 / 权限 / 治理链）
                    ReActAgent agent = agentFactory.create(userId, sessionId);
                    return agent.call(request.message(), runtimeContext(sessionId, userId));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(reply -> new ChatReply(sessionId, reply == null ? "" : reply.getTextContent()))
                .defaultIfEmpty(new ChatReply(sessionId, ""));
    }

    /**
     * 流式问一句（SSE）。
     *
     * <p>这条路径与 customer_work 的 WS 出站是同构的：订阅事件流 → 只取正文增量 → 逐片下发。
     *
     * <p>同样要 {@code subscribeOn(boundedElastic())}：SSE 的第一个增量通常要等模型首个 token，
     * 那一步如果同步阻塞，event-loop 会被占住。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody ChatRequest request, ServerWebExchange exchange) {
        String userId = exchange.getAttribute(AuthWebFilter.USER_ID_ATTRIBUTE);
        String sessionId = SessionIds.resolve(request.sessionId(), userId);

        return Flux.defer(() -> {
                    ReActAgent agent = agentFactory.create(userId, sessionId);
                    return agent.streamEvents(request.message(), runtimeContext(sessionId, userId));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .filter(event -> event instanceof TextBlockDeltaEvent)
                .map(event -> ((TextBlockDeltaEvent) event).getDelta())
                .filter(delta -> delta != null && !delta.isEmpty());
    }

    /**
     * 构造运行时上下文 —— <b>已收敛到装配层</b>。
     *
     * <p><b>注意</b>：框架的 {@code RuntimeContext} 是「承载会话标识等运行期信息」的容器，
     * 它<b>不是鉴权边界</b> —— 谁把什么值放进去由我们决定，框架不校验。
     * 这正是 customer_work 强调「事实记在所有源必经的那一个入口」的原因：
     * 会话号本身不是凭证，归属校验必须单独做（见 {@code SessionIds}）。
     *
     * <p><b>站 2 改动</b>：原来这里直接 {@code RuntimeContext.builder()}，
     * 现在统一转发到 {@code MvpAgentFactory#contextFor} → 治理装配器，
     * 保证「建 Agent」与「造上下文」两件事的口径来自同一处。
     */
    private io.agentscope.core.agent.RuntimeContext runtimeContext(String sessionId, String userId) {
        return agentFactory.contextFor(userId, sessionId);
    }

    /** 请求体。 */
    public record ChatRequest(String sessionId, String message) {
    }

    /** 响应体。 */
    public record ChatReply(String sessionId, String reply) {
    }
}
