package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 0 补充：<b>ReAct 闭环的消息级真相</b>。
 *
 * <p>「用户说了一句订单号 → 工具被调用 → 结果回灌 → 模型继续回答」这条链，
 * 光看代码是拼不出来的 —— 中间发生在框架内部。本类用<b>捕获型假模型</b>
 * 把每一次模型调用的<b>入参原样截获</b>下来，从而把这条链的每个环节钉死。</p>
 *
 * <p><b>三个最容易被误解的地方，本类用断言钉住</b>：</p>
 * <ol>
 *   <li><b>订单号不是代码解析出来的</b> —— 是<b>模型自己</b>从用户输入里抽出来、
 *       按 schema 填进 {@code ToolUseBlock.input} 的。框架不解析自然语言，
 *       也<b>不校验</b>这个值对不对；框架只做「JSON → Map → 反射绑定到方法参数」。</li>
 *   <li><b>「确保传参正确」靠的是 schema 提示 + 模型的 JSON 输出</b>，
 *       不是框架的校验。参数错了框架会照单执行，然后由工具自己报错。</li>
 *   <li><b>工具结果不是"塞回给用户"</b>，而是拼成一条 {@code role=TOOL} 的消息，
 *       接在 assistant 的 tool_use 消息之后，再整体作为下一次模型调用的输入。</li>
 * </ol>
 */
class ToolCallFlowTest {

    /**
     * 捕获型假模型：把每次调用的入参（messages / tools）存下来，
     * 第一次返回 tool_call，第二次返回最终答复。
     */
    private static final class CapturingModel implements Model {

        private final List<List<Msg>> capturedMessages = new ArrayList<>();
        private final List<List<ToolSchema>> capturedTools = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            capturedMessages.add(List.copyOf(messages));
            capturedTools.add(List.copyOf(tools));
            if (capturedMessages.size() == 1) {
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("call_1")
                                .name("query_order_status")
                                // 这里的值就是「模型抽出来的订单号」——不是框架解析的
                                .input(Map.of("orderId", "SO202611250001"))
                                .build()))
                        .build());
            }
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("您的订单已发货，预计明天送达。").build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return "capturing";
        }
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("u1-s").build();
    }

    private static ReActAgent agent(CapturingModel model) {
        AgentProperties properties = MvpTestFixtures.props();
        return new MvpAgentFactory(model, properties, MvpTestFixtures.governance(properties),
                new InMemoryAgentStateStore()).create("u1", "u1-s");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("站0-4：工具调用链的完整消息序列（探针：把每次模型调用的入参打出来）")
    void dumpTheWholeChain() {
        CapturingModel model = new CapturingModel();
        ReActAgent agent = agent(model);

        Msg reply = agent.call("我的订单 SO202611250001 到哪了？", ctx()).block();

        System.out.println("================ 第 1 次模型调用 ================");
        System.out.println("messages 条数 = " + model.capturedMessages.get(0).size());
        for (Msg m : model.capturedMessages.get(0)) {
            System.out.println("  - " + describe(m));
        }
        System.out.println("tools 条数 = " + model.capturedTools.get(0).size());
        for (ToolSchema t : model.capturedTools.get(0)) {
            System.out.println("  - name = " + t.getName());
            System.out.println("    description = " + t.getDescription());
            System.out.println("    parameters = " + t.getParameters());
        }

        System.out.println("================ 第 2 次模型调用 ================");
        System.out.println("messages 条数 = " + model.capturedMessages.get(1).size());
        for (Msg m : model.capturedMessages.get(1)) {
            System.out.println("  - " + describe(m));
        }

        System.out.println("================ 最终答复 ================");
        System.out.println("  role = " + reply.getRole());
        System.out.println("  text = " + reply.getTextContent());

        // 把捕获到的内容带进断言（见下面的分项用例）
        assertNotNull(reply);
    }

    @Test
    @DisplayName("站0-5：第 1 次调用 —— 模型收到 system + user，以及自动生成的工具 Schema")
    void firstCallCarriesSchemaAndTwoMessages() {
        CapturingModel model = new CapturingModel();
        agent(model).call("我的订单 SO202611250001 到哪了？", ctx()).block();

        List<Msg> first = model.capturedMessages.get(0);
        assertEquals(2, first.size(), "第 1 次调用的 messages 应只有 system + user，实际: " + describe(first));
        assertEquals(io.agentscope.core.message.MsgRole.SYSTEM, first.get(0).getRole());
        assertEquals(io.agentscope.core.message.MsgRole.USER, first.get(1).getRole());

        List<ToolSchema> tools = model.capturedTools.get(0);
        assertEquals(1, tools.size(), "应当只注册了一个业务工具");
        ToolSchema schema = tools.get(0);
        assertEquals("query_order_status", schema.getName());
        assertTrue(schema.getDescription() != null && schema.getDescription().contains("订单"),
                "工具描述应当来自 @Tool 注解，实际: " + schema.getDescription());

        @SuppressWarnings("unchecked")
        Map<String, Object> params = schema.getParameters();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) params.get("properties");
        assertNotNull(properties, "parameters 应含 properties，实际: " + params);
        assertTrue(properties.containsKey("orderId"),
                "参数名来自 @ToolParam(name=...) ，实际: " + properties.keySet());
    }

    @Test
    @DisplayName("站0-6：第 2 次调用 —— 工具结果以 role=TOOL 的消息接在 assistant 的 tool_use 之后")
    void secondCallCarriesToolResult() {
        CapturingModel model = new CapturingModel();
        agent(model).call("我的订单 SO202611250001 到哪了？", ctx()).block();

        List<Msg> second = model.capturedMessages.get(1);
        assertEquals(4, second.size(),
                "第 2 次调用应是 system + user + assistant(tool_use) + tool(tool_result)，实际: " + describe(second));

        Msg assistant = second.get(2);
        assertEquals(io.agentscope.core.message.MsgRole.ASSISTANT, assistant.getRole());
        List<ToolUseBlock> useBlocks = assistant.getContentBlocks(ToolUseBlock.class);
        assertEquals(1, useBlocks.size(), "assistant 消息里应有 1 个 tool_use 块");
        ToolUseBlock use = useBlocks.get(0);
        assertEquals("query_order_status", use.getName());
        assertEquals("SO202611250001", use.getInput().get("orderId"),
                "工具入参由模型给出，框架原样带给工具");
        assertFalse(use.hasMalformedArguments(), "本例参数应当是合法的");

        Msg toolMsg = second.get(3);
        assertEquals(io.agentscope.core.message.MsgRole.TOOL, toolMsg.getRole(),
                "工具结果必须是 role=TOOL 的独立消息");
        List<ToolResultBlock> resultBlocks = toolMsg.getContentBlocks(ToolResultBlock.class);
        assertEquals(1, resultBlocks.size(), "tool 消息里应有 1 个 tool_result 块");
        ToolResultBlock result = resultBlocks.get(0);
        assertEquals("query_order_status", result.getName());

        String output = result.getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .reduce("", String::concat);
        assertTrue(output.contains("已发货"),
                "工具返回值应原样进入 tool_result，实际: " + output);
    }

    // ------------------------------------------------------------------

    /**
     * 探针：<b>模型把参数传错时会发生什么</b> —— 用来回答「如何确保传参正确」。
     *
     * <p>两种错法：① 键名错（{@code wrongKey} 而不是 {@code orderId}）；
     * ② 值类型错（{@code orderId} 传了数字而不是字符串）。</p>
     */
    /**
     * 站0-7：<b>参数传错时，框架会挡下并把错误回灌给模型</b>。
     *
     * <p>这回答了「如何确保传参正确」：<b>框架确实会校验</b>，用的是自动生成的
     * JSON Schema 里的 {@code required} 与 {@code type}。校验不通过时
     * <b>工具根本不会被执行</b>，而是生成一条 {@code Error: Parameter validation failed...}
     * 的 tool_result —— 模型在下一轮就能看到这个错误并自我纠正。</p>
     */
    @Test
    @DisplayName("站0-7：参数错误被框架拦下，错误以 tool_result 回灌给模型")
    void badArgumentsAreRejectedAndFedBack() {
        assertToolResultIsValidationError(
                runWithBadInput(Map.of("wrongKey", "SO202611250001")), "键名错");
        assertToolResultIsValidationError(
                runWithBadInput(Map.of("orderId", 12345)), "值类型错（schema 要求 string）");
        assertToolResultIsValidationError(
                runWithBadInput(Map.of()), "缺参数");
    }

    /** 断言 tool_result 是一条参数校验错误，且不含工具的真实返回（说明工具没被执行）。 */
    private static void assertToolResultIsValidationError(List<ToolResultBlock> results, String label) {
        assertEquals(1, results.size(), label + "：应当只有一条 tool_result");
        String output = results.get(0).getOutput().stream()
                .filter(b -> b instanceof TextBlock)
                .map(b -> ((TextBlock) b).getText())
                .reduce("", String::concat);
        assertTrue(output.startsWith("Error: Parameter validation failed for tool 'query_order_status'"),
                label + "：应当是参数校验错误，实际: " + output);
        assertFalse(output.contains("当前状态"),
                label + "：工具不该被执行（否则会出现订单状态文本），实际: " + output);
    }

    /** 用一个「总是传错参数」的模型跑一轮，返回第 2 次调用里的 tool_result。 */
    private static List<ToolResultBlock> runWithBadInput(Map<String, Object> badInput) {
        List<List<Msg>> captured = new ArrayList<>();
        Model badModel = new Model() {
            @Override
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                             GenerateOptions options) {
                captured.add(List.copyOf(messages));
                if (captured.size() == 1) {
                    return Flux.just(ChatResponse.builder()
                            .content(List.of(ToolUseBlock.builder()
                                    .id("call_1").name("query_order_status")
                                    .input(badInput).build()))
                            .build());
                }
                return Flux.just(ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("（模型已看到工具报错）").build()))
                        .build());
            }

            @Override
            public String getModelName() {
                return "bad";
            }
        };

        AgentProperties properties = MvpTestFixtures.props();
        ReActAgent agent = new MvpAgentFactory(badModel, properties,
                MvpTestFixtures.governance(properties), new InMemoryAgentStateStore())
                .create("u1", "u1-s");
        agent.call("我的订单 SO202611250001 到哪了？", ctx()).block();

        assertTrue(captured.size() >= 2,
                "校验失败后仍要有第 2 次模型调用（模型需要看到错误才能改），实际调用次数: " + captured.size());
        return captured.get(1).stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                .toList();
    }

    private static String describe(Msg msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("role=").append(msg.getRole()).append(" | ");
        List<ContentBlock> blocks = msg.getContent();
        if (blocks == null) {
            return sb.append("<no content>").toString();
        }
        sb.append("blocks=[");
        for (int i = 0; i < blocks.size(); i++) {
            ContentBlock b = blocks.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(b.getClass().getSimpleName());
            if (b instanceof TextBlock t) {
                sb.append("(\"").append(t.getText()).append("\")");
            } else if (b instanceof ToolUseBlock u) {
                sb.append("(name=").append(u.getName())
                  .append(", input=").append(u.getInput()).append(")");
            } else if (b instanceof ToolResultBlock r) {
                sb.append("(name=").append(r.getName()).append(", output=").append(r.getOutput()).append(")");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String describe(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : messages) {
            sb.append("\n    ").append(describe(m));
        }
        return sb.toString();
    }
}
