package com.example.mvp.app.outbound;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 站 1-D：出站过滤 —— <b>框架给事件，规则全是自建的</b>。
 *
 * <p>AgentScope core 给的是一整套 {@link AgentEvent}（文本增量、思考增量、工具调用、
 * 工具结果、最终结果、子智能体事件……）。<b>哪些该给用户看，是我们决定的</b>。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code CustomerServiceService#streamFromAgent}，
 * 三条规则一模一样：
 * <ol>
 *   <li><b>丢子智能体事件</b>（{@code event.getSource() != null}）—— 子智能体的正文
 *       不该混进主智能体的答复；</li>
 *   <li><b>只取正文增量</b>（{@code TextBlockDeltaEvent}）—— 思考/工具事件不给用户看；</li>
 *   <li><b>无增量时用最终结果兜底</b> —— 有些模型不吐增量，只给最终答复，
 *       此时不能因为「没有 delta」就让用户看到空白。</li>
 * </ol>
 *
 * <p><b>这条过滤链是「能力只接在一条路上」的高发区</b>：customer_work 已经踩过
 * 「早期只有流式做过滤、非流式命中缓存时原文直出」的坑。
 * demo 里把规则收敛成<b>一个纯函数</b>（入 {@code Flux<AgentEvent>}，出 {@code Flux<String>}），
 * 就是为了让「所有出口都走这一条路」在结构上成立。
 */
public final class OutboundFilter {

    private OutboundFilter() {
    }

    /**
     * 把事件流过滤成「用户可见的正文增量」。
     *
     * <p>保持流式：delta 来了就立刻下游；只有当整条流<b>一个 delta 都没有</b>时，
     * 才在收尾补一条最终结果全文。
     *
     * @param events AgentScope 事件流
     * @return 用户可见的正文片段流
     */
    public static Flux<String> textDeltas(Flux<AgentEvent> events) {
        return Flux.defer(() -> {
            AtomicBoolean emittedDelta = new AtomicBoolean(false);
            AtomicReference<String> fallback = new AtomicReference<>();

            Flux<String> body = events
                    // 规则 1：丢掉子智能体事件（它们带 source）
                    .filter(event -> event.getSource() == null)
                    .handle((event, sink) -> {
                        if (event instanceof TextBlockDeltaEvent delta) {
                            // 规则 2：只取正文增量
                            String text = delta.getDelta();
                            if (text != null && !text.isEmpty()) {
                                emittedDelta.set(true);
                                sink.next(text);
                            }
                        } else if (event instanceof AgentResultEvent result) {
                            // 记下最终结果，作为兜底候选
                            Msg msg = result.getResult();
                            if (msg != null) {
                                fallback.set(msg.getTextContent());
                            }
                        }
                    });

            // 规则 3：整条流没吐过 delta，才用最终结果兜底
            return body.concatWith(Flux.defer(() -> {
                if (!emittedDelta.get()) {
                    String text = fallback.get();
                    if (text != null && !text.isEmpty()) {
                        return Flux.just(text);
                    }
                }
                return Flux.empty();
            }));
        });
    }
}
