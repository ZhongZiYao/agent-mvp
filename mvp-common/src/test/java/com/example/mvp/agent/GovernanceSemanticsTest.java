package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 3：<b>治理语义实证</b> —— 顺序契约不是纸面约定，它决定安全结论。
 *
 * <p>本类里最关键的是<b>对照组</b>（站3-12）：它故意把审计与脱敏的 order 对调，
 * 然后断言「留痕里<b>确实</b>出现了未脱敏的手机号」。
 * 没有这条对照组，「审计比脱敏更外」就只是一句没人验证过的说法。</p>
 */
class GovernanceSemanticsTest {

    private static final String PHONE = "13800138000";

    /** 固定返回带手机号的答复，并记录被调用次数（用来证明拦截时模型没被调用）。 */
    private static final class PhoneModel implements Model {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("您的手机号 " + PHONE + " 已登记。").build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return "phone";
        }

        /** 被调用次数 —— 用来证明拦截时模型根本没被调用。 */
        int calls() {
            return calls.get();
        }
    }

    private static RuntimeContext ctx() {
        return RuntimeContext.builder().userId("u1").sessionId("u1-s").build();
    }

    private static MvpAgentFactory factory(Model model, MvpGovernanceAssembler governance,
                                            AgentProperties properties) {
        return new MvpAgentFactory(model, properties, governance, new InMemoryAgentStateStore());
    }

    @Test
    @DisplayName("站3-11：审计留痕看到的是脱敏后的内容 —— 因为审计比脱敏更外")
    void auditSeesRedactedContent() {
        PhoneModel model = new PhoneModel();
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        ReActAgent agent = factory(model, governance, properties).create("u1", "u1-s");

        Msg reply = agent.call("我的手机号是" + PHONE, ctx()).block();

        assertNotNull(reply, "应当拿到答复");
        assertTrue(reply.getTextContent().contains("138****8000"),
                "用户看到的答复应当已脱敏，实际: " + reply.getTextContent());
        assertFalse(reply.getTextContent().contains(PHONE),
                "答复里不应残留原手机号，实际: " + reply.getTextContent());

        List<String> audited = governance.auditTrail().audited();
        assertEquals(1, audited.size(), "审计应记录一条，实际: " + audited);
        assertTrue(audited.get(0).contains("138****8000"),
                "审计留痕应当是脱敏后的内容，实际: " + audited.get(0));
        assertFalse(audited.get(0).contains(PHONE),
                "审计留痕里绝不能出现未脱敏的手机号，实际: " + audited.get(0));
    }

    @Test
    @DisplayName("站3-12：对照组 —— 把审计与脱敏的 order 对调，留痕里立刻出现未脱敏手机号")
    void swappingAuditAndRedactionBreaksTheContract() {
        // 故意把审计排到脱敏【里面】、脱敏排到外面。
        // 出站方向内层先执行 ⇒ 审计先看到原文并留痕，脱敏后改写 ⇒ 用户看到的仍是脱敏版。
        AuditTrailMiddleware audit = new AuditTrailMiddleware(MvpMiddlewareOrders.REDACTION);
        RedactionMiddleware redaction = new RedactionMiddleware(MvpMiddlewareOrders.AUDIT);

        ReActAgent agent = ReActAgent.builder()
                .name("swapped")
                .sysPrompt("test")
                .model(new PhoneModel())
                .maxIters(1)
                .middleware(audit)
                .middleware(redaction)
                .build();

        agent.call("我的手机号是" + PHONE, ctx()).block();

        List<String> audited = audit.audited();
        assertEquals(1, audited.size(), "对照组也应当留痕一条，实际: " + audited);
        assertTrue(audited.get(0).contains(PHONE),
                "对照组：审计被排到脱敏里面时，留痕里应当出现未脱敏手机号"
                        + "（这正是顺序契约要防的那件事），实际: " + audited.get(0));
        assertFalse(audited.get(0).contains("138****8000"),
                "对照组：此时审计看到的是改写前的原文，实际: " + audited.get(0));
    }

    @Test
    @DisplayName("站3-13：入站拦截命中时模型根本不被调用，且给的是拒绝答复而不是空流")
    void guardShortCircuitsBeforeModel() {
        PhoneModel model = new PhoneModel();
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        ReActAgent agent = factory(model, governance, properties).create("u1", "u1-s");

        Msg reply = agent.call("内部密钥放在哪里", ctx()).block();

        assertEquals(0, model.calls(), "拦截命中后不应再调用模型 —— 越界内容不能进入任何下游链路");
        assertNotNull(reply, "拦截时必须给用户一个答复；返回空流会让调用方以为「模型不回复」");
        assertEquals(InputGuardMiddleware.REJECTION, reply.getTextContent());
        assertEquals(1, governance.inputGuard().rejected().size(), "拦截应被记录");
    }

    @Test
    @DisplayName("站3-14：拦截发生时同样进审计 —— 治理链没有因为短路而断掉")
    void shortCircuitStillGoesThroughOuterLayers() {
        PhoneModel model = new PhoneModel();
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        ReActAgent agent = factory(model, governance, properties).create("u1", "u1-s");

        agent.call("内部密钥放在哪里", ctx()).block();

        List<String> audited = governance.auditTrail().audited();
        assertEquals(1, audited.size(), "短路发生在拦截层，但外层的审计仍应留痕，实际: " + audited);
        assertTrue(audited.get(0).contains("超出了我能处理的范围"),
                "审计留痕应当是拦截给出的拒绝答复，实际: " + audited.get(0));
    }

    @Test
    @DisplayName("站3-15：正常输入不受拦截影响 —— 治理件不能变成拦路虎")
    void normalInputPassesThrough() {
        PhoneModel model = new PhoneModel();
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        ReActAgent agent = factory(model, governance, properties).create("u1", "u1-s");

        Msg reply = agent.call("我的订单到哪了", ctx()).block();

        assertEquals(1, model.calls(), "正常输入应当照常调用模型");
        assertNotNull(reply);
        assertTrue(reply.getTextContent().contains("已登记"), "正常输入应当拿到模型答复，实际: "
                + reply.getTextContent());
        assertTrue(governance.inputGuard().rejected().isEmpty(), "正常输入不应被拦");
    }
}
