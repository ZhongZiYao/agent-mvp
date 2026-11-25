package com.example.mvp.app.outbound;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 1-D 测试：出站过滤的三条规则。
 *
 * <p><b>这三条规则是「框架给事件、我们定规则」最直接的证据</b> ——
 * 框架把这三类事件都发出来，是我们的过滤器决定只让正文增量出去。
 */
class OutboundFilterTest {

    private static final String REPLY_ID = "reply-1";

    private static AgentResultEvent result(String text) {
        return new AgentResultEvent(Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent(text)
                .build());
    }

    private static TextBlockDeltaEvent delta(String text) {
        return new TextBlockDeltaEvent(REPLY_ID, "text", text);
    }

    @Test
    @DisplayName("站1-D1：带 source 的事件（子智能体）被丢弃，不混进正文")
    void subagentEventsAreDropped() {
        // withSource 模拟「这个事件来自子智能体」
        AgentEvent fromSubagent = delta("子智能体的结论").withSource("sub-uuid");
        AgentEvent fromMain = delta("主智能体的答复");

        List<String> out = OutboundFilter.textDeltas(Flux.just(fromSubagent, fromMain))
                .collectList().block();

        assertEquals(List.of("主智能体的答复"), out,
                "带 source 的事件必须被丢弃 —— 否则子智能体正文会混进用户看到的答复");
    }

    @Test
    @DisplayName("站1-D2：只取正文增量，其它事件类型不进正文")
    void onlyTextDeltaPassesThrough() {
        AgentEvent toolEvent = new AgentResultEvent(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("工具结果不是正文").build());

        List<String> out = OutboundFilter.textDeltas(
                        Flux.just(toolEvent, delta("你"), delta("好")))
                .collectList().block();

        assertEquals(List.of("你", "好"), out,
                "AgentResultEvent 不算增量 —— 它只在「一个增量都没出现」时用于兜底");
    }

    @Test
    @DisplayName("站1-D3：整条流没有增量时，用最终结果兜底补一条全文")
    void fallsBackToAgentResultWhenNoDelta() {
        List<String> out = OutboundFilter.textDeltas(Flux.just(result("完整答复")))
                .collectList().block();

        assertEquals(List.of("完整答复"), out,
                "有些模型只给最终答复不吐增量，此时不能给用户看空白");
    }

    @Test
    @DisplayName("站1-D4：已经有增量时不再补全文（否则前端会看到答案重复一遍）")
    void noDuplicateWhenDeltaAlreadyEmitted() {
        List<String> out = OutboundFilter.textDeltas(
                        Flux.just(delta("你好"), result("你好")))
                .collectList().block();

        assertEquals(List.of("你好"), out,
                "增量已经出现过，最终结果不再补 —— 否则前端会先看到逐字答案、结束时又整段重复");
        assertTrue(out.size() == 1);
    }

    @Test
    @DisplayName("站1-D5：流为空时输出为空，不抛异常")
    void emptyStreamYieldsNothing() {
        List<String> out = OutboundFilter.textDeltas(Flux.empty()).collectList().block();
        assertTrue(out.isEmpty());
    }
}
