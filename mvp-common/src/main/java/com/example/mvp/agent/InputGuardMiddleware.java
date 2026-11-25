package com.example.mvp.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 站 3：自建治理中间件 —— <b>入站拦截</b>。
 *
 * <p><b>它在链上的位置</b>：{@link MvpMiddlewareOrders#GUARD}（120）——
 * 比脱敏（125）<b>更内</b>，比审计（150）更内。</p>
 *
 * <p><b>命中时直接短路</b>：不调用 {@code next.apply(input)} ⇒ 模型根本不会被调用。
 * 这既省了 token，也保证越界内容<b>不会进入任何下游链路</b>
 * （不会进工具、不会进检索、不会进模型上下文）。</p>
 *
 * <p><b>短路与「返回空流」不是一回事</b>：返回 {@code Flux.empty()} 会让整轮<b>没有任何答复</b>，
 * 调用方看到的是「模型不回复」而不是「被拒绝了」。要表达「我拒绝了你」，
 * 就要像本类这样<b>自己造一个 {@link AgentResultEvent}</b>。</p>
 *
 * <p><b>对照 customer_work</b>：那边同位置的 {@code AgentLifecycleMiddleware}（200）
 * 用同样的手法做「撤销态拦截」，而 {@code PromptInjectionGuardMiddleware}（140）
 * 做提示词注入防护。三者是同一族手法。</p>
 */
public class InputGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(InputGuardMiddleware.class);

    /** 命中即拒绝的关键词。 */
    static final List<String> FORBIDDEN = List.of("内部密钥", "越权查询");

    /** 拦截时返回给用户的固定答复。 */
    static final String REJECTION = "抱歉，这个问题超出了我能处理的范围。";

    private final int order;

    /** 被拦下的输入（真机观测用）。 */
    private final List<String> rejected = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public InputGuardMiddleware() {
        this(MvpMiddlewareOrders.GUARD);
    }

    public InputGuardMiddleware(int order) {
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        String userText = extractUserText(input);
        String hit = firstForbidden(userText);
        if (hit != null) {
            rejected.add(userText);
            log.warn("[guard] input rejected, hit={}", hit);
            Msg reply = Msg.builder()
                    .role(MsgRole.ASSISTANT)
                    .name(agent == null ? "assistant" : agent.getName())
                    .content(TextBlock.builder().text(REJECTION).build())
                    .build();
            // 注意：不是 Flux.empty() —— 空流等于「没有答复」，不是「拒绝了」。
            return Flux.just(new AgentResultEvent(reply));
        }
        return next.apply(input);
    }

    /** 取最后一条消息的文本作为本轮用户输入。 */
    private String extractUserText(AgentInput input) {
        if (input == null || input.msgs() == null || input.msgs().isEmpty()) {
            return "";
        }
        Msg last = input.msgs().get(input.msgs().size() - 1);
        return last == null ? "" : last.getTextContent();
    }

    /** 返回命中的第一个禁用词；没命中返回 {@code null}。 */
    private String firstForbidden(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String word : FORBIDDEN) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 被拦下的输入。 */
    public List<String> rejected() {
        return List.copyOf(rejected);
    }
}
