package com.example.mvp.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 4：<b>框架流式事件形状</b>的固化测试。
 *
 * <p><b>为什么这些事实要用测试钉住</b>：脱敏中间件的设计完全建立在三个框架事实上 ——
 * 「发不发块结束事件」「delta 是不是逐字的」「改写 delta 会不会丢来源」。
 * 这三条一旦变了（框架升级），脱敏的实现必须跟着改；
 * <b>而这些事实在代码里看不出来，只能用测试钉住</b>。</p>
 *
 * <p>实测形状（agentscope-core 2.0.5）：</p>
 * <pre>
 * AGENT_START → MODEL_CALL_START → TEXT_BLOCK_START
 *   → TEXT_BLOCK_DELTA × N（逐字）→ TEXT_BLOCK_END
 *   → MODEL_CALL_END → AGENT_RESULT → AGENT_END
 * </pre>
 */
class EventStreamShapeTest {

    private static final String PHONE = "13800138000";

    /** 逐字吐 delta 的模型。 */
    private static final class DripModel implements Model {

        private final String text;

        private DripModel(String text) {
            this.text = text;
        }

        @Override
               public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            return Flux.fromIterable(text.chars()
                    .mapToObj(c -> (char) c)
                    .map(c -> ChatResponse.builder()
                            .content(List.of(TextBlock.builder().text(String.valueOf(c)).build()))
                            .build())
                    .toList());
        }

        @Override
        public String getModelName() {
            return "drip";
        }
    }

    /** 不挂任何治理件的裸 Agent —— 用来观察框架的原始事件流。 */
    private static ReActAgent rawAgent(String text) {
        return ReActAgent.builder()
                .name("shape")
                .sysPrompt("test")
                .model(new DripModel(text))
                .maxIters(1)
                .build();
    }

    @Test
    @DisplayName("站4-10：框架的流式事件形状 —— 块开始 → 多片 delta → 块结束")
    void frameworkEmitsTextBlockLifecycle() {
        ReActAgent agent = rawAgent("您好");

        List<AgentEvent> events = agent.streamEvents("你好",
                        RuntimeContext.builder().userId("u1").sessionId("u1-s").build())
                .collectList()
                .block();

        assertNotNull(events, "应当拿到事件流");

        assertTrue(events.stream().anyMatch(e -> e instanceof TextBlockStartEvent),
                "框架必须发文本块开始事件");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextBlockDeltaEvent),
                "框架必须发文本块增量事件");
        assertTrue(events.stream().anyMatch(e -> e instanceof TextBlockEndEvent),
                "框架必须发文本块结束事件 —— 脱敏中间件正是靠它知道「块可以整体放出了」");

        int startIdx = indexOf(events, TextBlockStartEvent.class);
        int deltaIdx = indexOf(events, TextBlockDeltaEvent.class);
        int endIdx = indexOf(events, TextBlockEndEvent.class);
        assertTrue(startIdx < deltaIdx && deltaIdx < endIdx,
                "顺序必须是 开始 → 增量 → 结束，实际下标: " + startIdx + "/" + deltaIdx + "/" + endIdx);
    }

    @Test
    @DisplayName("站4-11：框架逐字吐 delta —— 所以「每片各脱敏一次」在原理上就不成立")
    void deltasAreCharacterGranular() {
        ReActAgent agent = rawAgent("您的手机号 " + PHONE + " 已登记");

        List<AgentEvent> events = agent.streamEvents("我的手机号是" + PHONE,
                        RuntimeContext.builder().userId("u1").sessionId("u1-s").build())
                .collectList()
                .block();

        assertNotNull(events);
        List<TextBlockDeltaEvent> deltas = events.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> (TextBlockDeltaEvent) e)
                .toList();

        assertTrue(deltas.size() > 1,
                "框架应当把正文拆成多片，实际: " + deltas.size());
        for (TextBlockDeltaEvent d : deltas) {
            assertFalse(d.getDelta().contains(PHONE),
                    "任何单片都不该含完整手机号 —— 这正是必须整块缓冲的原因");
        }

        String joined = String.join("", deltas.stream().map(TextBlockDeltaEvent::getDelta).toList());
        assertTrue(joined.contains(PHONE), "拼起来才构成完整手机号，实际: " + joined);
    }

    @Test
    @DisplayName("站4-12：AgentEvent.withSource 是【原地改字段并返回自身】")
    void withSourceIsInPlace() {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent("r1", "text", "x");

        AgentEvent returned = event.withSource("sub-1");

        assertSame(event, returned, "withSource 返回的就是它自己");
        assertEquals("sub-1", event.getSource(), "来源被就地写进了事件本身");
    }

    @Test
    @DisplayName("站4-13：FinalAnswerFilterMiddleware 只覆写 onReasoning ⇒ 不合并 onAgent 事件流里的 delta")
    void finalAnswerFilterDoesNotCoalesceAgentStreamDeltas() {
        // 站 3 早期结论「它会吃掉流式打字机效果」只对 onReasoning 成立。
        // streamEvents 走的是 onAgent 链段（字节码：buildAgentStream → MiddlewareChain.build(..., MiddlewareBase::onAgent, ...)），
        // 而 FinalAnswerFilterMiddleware 只覆写 onReasoning —— 所以它**不动**这条流。
        ReActAgent agent = ReActAgent.builder()
                .name("final-answer")
                .sysPrompt("test")
                .model(new DripModel("您好，这里是逐字输出的正文"))
                .maxIters(1)
                .middleware(new FinalAnswerFilterMiddleware())
                .build();

        List<AgentEvent> events = agent.streamEvents("你好",
                        RuntimeContext.builder().userId("u1").sessionId("u1-s").build())
                .collectList()
                .block();

        assertNotNull(events);
        long deltaCount = events.stream().filter(e -> e instanceof TextBlockDeltaEvent).count();

        assertTrue(deltaCount > 1,
                "onAgent 事件流里的 delta 仍应逐片穿过（未被合并），实际片数: " + deltaCount);
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
