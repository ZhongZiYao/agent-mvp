package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 4 挖出、站 4 修掉的<b>站 3 真实治理漏洞</b>：<b>出站脱敏只接在同步出口上</b>。
 *
 * <p><b>漏洞本体</b>：初版 {@link RedactionMiddleware} 只改写 {@link AgentResultEvent}
 * （最终答复消息），完全没碰 {@link TextBlockDeltaEvent}（流式正文分片）。
 * 于是：</p>
 * <ul>
 *   <li>同步出口 {@code POST /api/chat/sync} → 走 {@code AgentResultEvent} → <b>已脱敏</b>；</li>
 *   <li>流式出口（WebSocket {@code chat_chunk} / SSE {@code chat_stream}）→ 走 delta 分片
 *       → <b>原手机号原样吐给用户</b>。</li>
 * </ul>
 *
 * <p><b>为什么站 3 的测试没抓到</b>：站 3 的全部治理用例都走 {@code agent.call()}（同步出口），
 * <b>一条流式用例都没有</b>。这不是「测试写得不够细」，是
 * <b>「治理能力只接在了被测试的那条路上」</b> —— 和 customer_work 反复踩的同一个形状。</p>
 *
 * <p><b>修法</b>：按 text block 缓冲，{@link TextBlockEndEvent} 时整段脱敏后放唯一安全 delta，
 * 流末兜底 flush（fail-closed，绝不吐原文）。语义与 customer_work 的 {@code MaskingMiddleware} 一致。</p>
 */
class RedactionStreamTest {

    private static final String PHONE = "13800138000";
    private static final String MASKED = "138****8000";

    /** 逐字吐 delta 的模型 —— 逼出「手机号被切在多个 delta 里」这个真实场景。 */
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

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("u1-s").build();
    }

    /** 直接驱动中间件：把给定事件流灌进去，看它吐出什么。 */
    private static List<AgentEvent> drive(RedactionMiddleware middleware, List<AgentEvent> events) {
        AgentInput input = new AgentInput(List.of());
        List<AgentEvent> out = middleware.onAgent(null, ctx(), input, ignored -> Flux.fromIterable(events))
                .collectList()
                .block();
        return out == null ? List.of() : out;
    }

    private static String concatDeltas(List<AgentEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (AgentEvent e : events) {
            if (e instanceof TextBlockDeltaEvent d) {
                sb.append(d.getDelta());
            }
        }
        return sb.toString();
    }

    private static String finalText(List<AgentEvent> events) {
        for (AgentEvent e : events) {
            if (e instanceof AgentResultEvent r && r.getResult() != null) {
                return r.getResult().getTextContent();
            }
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 端到端：走真实 ReActAgent + 完整治理装配，复现漏洞原貌
    // ------------------------------------------------------------------

    @Test
    @DisplayName("站4-01：流式出口不再泄漏原手机号 —— 端到端走真实 Agent 的 streamEvents")
    void streamingOutboundIsRedacted() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        MvpAgentFactory factory = new MvpAgentFactory(
                new DripModel("您的手机号 " + PHONE + " 已登记。"), properties, governance,
                new InMemoryAgentStateStore());
        ReActAgent agent = factory.create("u1", "u1-s");

        List<AgentEvent> events = agent.streamEvents("我的手机号是" + PHONE, ctx())
                .collectList()
                .block();

        assertNotNull(events, "应当拿到事件流");
        String streamed = concatDeltas(events);

        assertFalse(streamed.contains(PHONE),
                "流式正文里绝不能出现未脱敏的手机号，实际: " + streamed);
        assertTrue(streamed.contains(MASKED),
                "流式正文应当已脱敏，实际: " + streamed);

        // 最终答复（同步出口走的那条路）也必须脱敏 —— 两条出口口径一致
        String finalReply = finalText(events);
        assertFalse(finalReply.contains(PHONE), "最终答复里不能出现原手机号，实际: " + finalReply);
        assertTrue(finalReply.contains(MASKED), "最终答复应当已脱敏，实际: " + finalReply);
    }

    @Test
    @DisplayName("站4-02：两条出口口径一致 —— 流式正文与最终答复脱敏后内容相同")
    void bothOutletsAgree() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        MvpAgentFactory factory = new MvpAgentFactory(
                new DripModel("您的手机号 " + PHONE + " 已登记。"), properties, governance,
                new InMemoryAgentStateStore());
        ReActAgent agent = factory.create("u1", "u1-s");

        List<AgentEvent> events = agent.streamEvents("我的手机号是" + PHONE, ctx())
                .collectList()
                .block();

        assertEquals(finalText(events), concatDeltas(events),
                "流式正文与最终答复应当是同一段（脱敏后的）文本 —— "
                        + "不一致说明两条出口各走各的治理");
    }

    // ------------------------------------------------------------------
    // 单元级：直接驱动中间件，覆盖框架很难构造出来的边界
    // ------------------------------------------------------------------

    @Test
    @DisplayName("站4-03：手机号被切在多个 delta 里时依然脱敏")
    void phoneSplitAcrossDeltasIsStillRedacted() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = new ArrayList<>();
        // 逐字喂，手机号被切成 11 个单字符 delta
        for (char c : ("手机号 " + PHONE + " 已登记").toCharArray()) {
            in.add(new TextBlockDeltaEvent("r1", "text", String.valueOf(c)));
        }
        in.add(new TextBlockEndEvent("r1", "text"));

        List<AgentEvent> out = drive(middleware, in);
        String streamed = concatDeltas(out);

        assertFalse(streamed.contains(PHONE),
                "跨 delta 拼出的手机号必须被脱敏，实际: " + streamed);
        assertTrue(streamed.contains(MASKED), "应当放出掩码，实际: " + streamed);
    }

    @Test
    @DisplayName("站4-04：delta 不会逐片透传 —— 正文整块到达（隐私优先于逐字延迟）")
    void deltasAreNotPassedThroughOneByOne() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = new ArrayList<>();
        for (char c : ("手机号 " + PHONE).toCharArray()) {
            in.add(new TextBlockDeltaEvent("r1", "text", String.valueOf(c)));
        }
        in.add(new TextBlockEndEvent("r1", "text"));

        List<AgentEvent> out = drive(middleware, in);
        long deltaCount = out.stream().filter(e -> e instanceof TextBlockDeltaEvent).count();

        assertEquals(1, deltaCount,
                "缓冲后应当只放出一个整块 delta（这是刻意的取舍，不是缺陷），实际: " + deltaCount);
        assertEquals(1, out.stream().filter(e -> e instanceof TextBlockEndEvent).count(),
                "块结束事件要照常放行");
    }

    @Test
    @DisplayName("站4-05：上游漏发块结束时，兜底 flush 仍脱敏 —— fail-closed，不吐原文")
    void flushRemainingRedactsUnclosedBlock() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = new ArrayList<>();
        for (char c : ("手机号 " + PHONE).toCharArray()) {
            in.add(new TextBlockDeltaEvent("r1", "text", String.valueOf(c)));
        }
        // 刻意不发 TextBlockEndEvent

        List<AgentEvent> out = drive(middleware, in);
        String streamed = concatDeltas(out);

        assertFalse(streamed.contains(PHONE),
                "流末兜底必须脱敏，绝不能吐原文，实际: " + streamed);
        assertTrue(streamed.contains(MASKED), "兜底 flush 应当放出掩码，实际: " + streamed);
    }

    @Test
    @DisplayName("站4-06：子智能体转发进来的正文不被「洗白」 —— 改写后仍带原来源")
    void forwardedBlockKeepsItsSource() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = new ArrayList<>();
        TextBlockDeltaEvent delta = new TextBlockDeltaEvent("r1", "text", "手机号 " + PHONE);
        delta.withSource("sub-abc");
        in.add(delta);
        TextBlockEndEvent end = new TextBlockEndEvent("r1", "text");
        end.withSource("sub-abc");
        in.add(end);

        List<AgentEvent> out = drive(middleware, in);

        List<TextBlockDeltaEvent> deltas = out.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> (TextBlockDeltaEvent) e)
                .toList();
        assertEquals(1, deltas.size(), "应当放出一个整块 delta");
        assertEquals("sub-abc", deltas.get(0).getSource(),
                "改写后的正文必须沿用原来源，否则子智能体正文会被当成主智能体答复");
        assertFalse(deltas.get(0).getDelta().contains(PHONE), "子智能体正文也要脱敏");
    }

    @Test
    @DisplayName("站4-07：不同来源的块各缓冲各的 —— 不会串台")
    void differentSourcesAreBufferedSeparately() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = new ArrayList<>();

        TextBlockDeltaEvent a = new TextBlockDeltaEvent("r1", "text", "主智能体手机号 " + PHONE);
        in.add(a);
        TextBlockDeltaEvent b = new TextBlockDeltaEvent("r1", "text", "子智能体手机号 " + PHONE);
        b.withSource("sub-1");
        in.add(b);

        TextBlockEndEvent endA = new TextBlockEndEvent("r1", "text");
        in.add(endA);
        TextBlockEndEvent endB = new TextBlockEndEvent("r1", "text");
        endB.withSource("sub-1");
        in.add(endB);

        List<AgentEvent> out = drive(middleware, in);

        List<TextBlockDeltaEvent> deltas = out.stream()
                .filter(e -> e instanceof TextBlockDeltaEvent)
                .map(e -> (TextBlockDeltaEvent) e)
                .toList();

        assertEquals(2, deltas.size(), "两个来源的块应当各放出一个 delta，实际: " + deltas.size());
        for (TextBlockDeltaEvent d : deltas) {
            assertFalse(d.getDelta().contains(PHONE), "每个块都要脱敏，实际: " + d.getDelta());
        }
        assertTrue(deltas.stream().anyMatch(d -> d.getSource() == null), "主智能体的块应保持无来源");
        assertTrue(deltas.stream().anyMatch(d -> "sub-1".equals(d.getSource())), "子智能体的块应保留来源");
    }

    @Test
    @DisplayName("站4-08：同步出口没有被修坏 —— call() 仍然脱敏")
    void syncOutboundStillRedacted() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        MvpAgentFactory factory = new MvpAgentFactory(
                new DripModel("您的手机号 " + PHONE + " 已登记。"), properties, governance,
                new InMemoryAgentStateStore());
        ReActAgent agent = factory.create("u1", "u1-s");

        Msg reply = agent.call("我的手机号是" + PHONE, ctx()).block();

        assertNotNull(reply);
        assertFalse(reply.getTextContent().contains(PHONE), "同步答复里不能出现原手机号");
        assertTrue(reply.getTextContent().contains(MASKED), "同步答复应当已脱敏");
    }

    @Test
    @DisplayName("站4-09：不含手机号的正文原样放行 —— 脱敏件不能变成内容过滤器")
    void cleanTextPassesThrough() {
        RedactionMiddleware middleware = new RedactionMiddleware();
        List<AgentEvent> in = List.of(
                new TextBlockDeltaEvent("r1", "text", "好的，已为您登记。"),
                new TextBlockEndEvent("r1", "text"));

        List<AgentEvent> out = drive(middleware, in);

        assertEquals("好的，已为您登记。", concatDeltas(out));
        assertTrue(middleware.maskedBlocks().isEmpty(), "没有可脱敏内容时不应记一笔");
    }
}
