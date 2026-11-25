package com.example.mvp.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 0 冒烟测试：<b>不碰真实模型</b>，用假 Model 验证「框架机制是否如预期」。
 *
 * <p><b>为什么先测框架而不是测业务</b>：站 0 的目的是摸清框架边界。
 * 如果一开始就烧真模型，出了问题你分不清是「框架行为不对」还是「我的代码写错了」。
 * 用假 Model 反而能<b>确定性地</b>钉住框架行为：模型一定会返回 tool_call，
 * 于是「工具被调用了吗」「结果回灌了吗」「最终答复是什么」全都可断言。
 *
 * <p>对照 customer_work：那边有 5000+ 条测试，其中相当一部分就是这种
 * 「用假模型钉住框架契约」的用法。
 */
class SimpleAgentFactoryTest {

    /**
     * 假模型：第一次调用返回 tool_call，第二次返回最终答复。
     *
     * <p>这正是 ReAct 闭环的最小形态 —— 也证明了「一次提问，模型会被调用 N 次」。
     */
    private static final class ScriptedModel implements Model {
        private int calls = 0;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls++;
            if (calls == 1) {
                // 第一次：要求调用工具
                return Flux.just(ChatResponse.builder()
                        .content(List.of(io.agentscope.core.message.ToolUseBlock.builder()
                                .id("call_1")
                                .name("query_order_status")
                                .input(java.util.Map.of("orderId", "SO202611250001"))
                                .build()))
                        .build());
            }
            // 第二次：基于工具结果给最终答复
            return Flux.just(ChatResponse.builder()
                    .content(List.of(io.agentscope.core.message.TextBlock.builder()
                            .text("您的订单已发货，预计明天送达。")
                            .build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return "scripted";
        }

        @Override
        public int getContextWindowSize() {
            return 4096;
        }
    }

    @Test
    @DisplayName("站0-1：框架的 Builder 能直接产出可用的 Agent")
    void builderProducesAgent() {
        Model model = new ScriptedModel();
        ReActAgent agent = new SimpleAgentFactory(
                MvpTestFixtures.governance(MvpTestFixtures.props())).create(model);

        assertNotNull(agent, "builder().build() 应产出 Agent");
        assertEquals("MvpCustomerServiceAgent", agent.getName());
    }

    @Test
    @DisplayName("站0-2：工具被反射注册进 Toolkit，且 Schema 自动生成")
    void toolIsRegisteredWithGeneratedSchema() {
        Model model = new ScriptedModel();
        ReActAgent agent = new SimpleAgentFactory(
                MvpTestFixtures.governance(MvpTestFixtures.props())).create(model);

        // Toolkit 的工具名与 Schema 都是框架生成的 —— 我们只写了 @Tool 注解的方法
        var toolNames = agent.getToolkit().getToolNames();
        assertTrue(toolNames.contains("query_order_status"),
                "框架应通过反射注册 @Tool 方法，实际注册的是: " + toolNames);

        List<ToolSchema> schemas = agent.getToolkit().getToolSchemas();
        assertFalse(schemas.isEmpty(), "框架应自动生成工具参数 Schema");
        assertEquals("query_order_status", schemas.get(0).getName());
    }

    @Test
    @DisplayName("站0-3：ReAct 闭环成立 —— 工具被调用、结果回灌、模型被调用两次")
    void reactLoopRunsToolAndFeedsBack() {
        ScriptedModel model = new ScriptedModel();
        ReActAgent agent = new SimpleAgentFactory(
                MvpTestFixtures.governance(MvpTestFixtures.props())).create(model);

        Msg reply = agent.call("我的订单到哪了？",
                io.agentscope.core.agent.RuntimeContext.builder().build()).block();

        assertNotNull(reply, "Agent 应给出最终答复");
        assertEquals(2, model.calls,
                "ReAct 闭环的关键：一次提问，模型被调用两次（推理 → 调工具 → 再推理）");
        assertTrue(reply.getTextContent().contains("已发货"),
                "最终答复应基于工具结果，实际是: " + reply.getTextContent());
    }
}
