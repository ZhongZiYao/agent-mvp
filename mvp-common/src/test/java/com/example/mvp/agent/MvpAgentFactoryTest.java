package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.ConflictPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 2：<b>装配层断言</b> —— 框架给的每一格，我们真的装上了吗？
 *
 * <p>这类测试的价值在于：<b>装配层的错误几乎全部是不报错的静默错误</b>。
 * 中间件漏挂不报错、权限规则写错不报错、{@code maxRetries} 被丢弃也不报错。
 * 唯一的发现手段就是<b>把装配结果断言出来</b>。
 */
class MvpAgentFactoryTest {

    private static MvpAgentFactory factory(AgentProperties properties, MvpTestFixtures.CapturingModel model,
                                            InMemoryAgentStateStore store) {
        return new MvpAgentFactory(model, properties,
                MvpTestFixtures.governance(properties), store);
    }

    @Test
    @DisplayName("站2-1：框架 Builder 上的每一格都装上了 —— 11 个零件逐个断言")
    void allBuilderSlotsAreFilled() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();

        ReActAgent agent = factory(properties, model, store).create("u1001", "u1001-s1");

        // —— 身份与约束 ——
        assertNotNull(agent.getName(), "Agent 名字应装上");
        assertNotNull(agent.getSystemPrompt(), "系统提示词应装上");
        assertSame(model, agent.getModel(), "模型应装上");
        assertTrue(agent.getToolkit().getToolNames().contains("query_order_status"),
                "工具集应装上，实际: " + agent.getToolkit().getToolNames());

        // —— 执行参数 ——
        assertEquals(5, agent.getMaxIters(), "迭代上限应装上");
        assertEquals(2, agent.getModelConfig().maxRetries(), "maxRetries 应装上（框架把它存进 ModelConfig）");

        // —— 状态槽位 ——
        assertSame(store, agent.getStateStore(), "状态外置存储应装上");
        assertEquals(ConflictPolicy.OVERWRITE, agent.getConflictPolicy(), "冲突策略应装上");
        assertEquals("u1001-s1", agent.getDefaultSessionId(), "默认会话号应装上");

        // —— 权限 ——
        assertNotNull(agent.getPermissionContext(), "权限上下文应装上");
        assertEquals(PermissionMode.DEFAULT, agent.getPermissionContext().getMode(), "权限模式应装上");

        // —— 治理中间件 ——
        var middlewares = agent.getMiddlewares();
        assertNotNull(middlewares, "中间件列表应存在");
        assertTrue(middlewares.stream().anyMatch(m -> m instanceof ToolAuditMiddleware),
                "自建的工具审计中间件应装上");
        assertTrue(middlewares.stream().anyMatch(m -> m instanceof FinalAnswerFilterMiddleware),
                "框架自带的 FinalAnswerFilterMiddleware 应装上");
    }

    @Test
    @DisplayName("站2-2：deny 规则装上后，框架的 PermissionContextState 能看到")
    void denyRulesAreVisible() {
        AgentProperties properties = new AgentProperties(
                5, 2, 5, 60, 500, 2.0d, false, "OVERWRITE", "DEFAULT", List.of("submit_refund"));
        MvpAgentFactory f = factory(properties, new MvpTestFixtures.CapturingModel(),
                new InMemoryAgentStateStore());

        ReActAgent agent = f.create("u1001", "u1001-s1");

        assertTrue(agent.getPermissionContext().getDenyRules().containsKey("submit_refund"),
                "禁用规则应装进框架的权限上下文，实际: "
                        + agent.getPermissionContext().getDenyRules().keySet());
    }

    @Test
    @DisplayName("站2-3：状态槽位按 (userId, sessionId) 隔离 —— 换任一维度就是另一个槽位")
    void stateSlotsAreIsolatedByUserAndSession() {
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        io.agentscope.core.state.AgentState state = io.agentscope.core.state.AgentState.builder()
                .sessionId("u1001-s1")
                .userId("u1001")
                .build();

        store.save("u1001", "s1", "agent", state);

        assertTrue(store.exists("u1001", "s1"), "同一 (userId, sessionId) 应命中");
        assertTrue(store.get("u1001", "s1", "agent", io.agentscope.core.state.AgentState.class).isPresent(),
                "同一槽位应能读回");
        assertTrue(store.get("u1002", "s1", "agent", io.agentscope.core.state.AgentState.class).isEmpty(),
                "换 userId 应落到另一个槽位");
        assertTrue(store.get("u1001", "s2", "agent", io.agentscope.core.state.AgentState.class).isEmpty(),
                "换 sessionId 应落到另一个槽位");

        assertEquals(java.util.Set.of("s1"), store.listSessionIds("u1001"),
                "按 userId 列会话号");
        assertTrue(store.listSessionIds("u1002").isEmpty(),
                "另一个 userId 不应看到别人的会话");
    }

    @Test
    @DisplayName("站2-4：装配快照暴露「实际生效的执行参数」—— maxRetries 与 modelExecutionConfig 二选一")
    void describeExposesEffectiveExecutionConfig() {
        // 模式 A：只用 maxRetries
        AgentProperties onlyRetries = MvpTestFixtures.props();
        MvpAgentFactory fA = factory(onlyRetries, new MvpTestFixtures.CapturingModel(),
                new InMemoryAgentStateStore());
        var snapA = fA.describe(fA.create("u1", "u1-s"));
        assertNull(snapA.get("modelExecutionConfig"),
                "没配 modelExecutionConfig 时快照里它应为 null，实际: " + snapA.get("modelExecutionConfig"));
        // 模式 B：开了 modelExecutionConfig —— 此时 maxRetries 被整体丢弃
        AgentProperties withExec = MvpTestFixtures.propsWithExecutionConfig();
        var f = factory(withExec, new MvpTestFixtures.CapturingModel(), new InMemoryAgentStateStore());
        var agentB = f.create("u1", "u1-s");
        var snapB = f.describe(agentB);

        @SuppressWarnings("unchecked")
        var execB = (java.util.Map<String, Object>) snapB.get("modelExecutionConfig");
        assertNotNull(execB, "配了 modelExecutionConfig 快照里应可见");
        assertEquals(5, execB.get("maxAttempts"),
                "modelExecutionConfig 的 maxAttempts 应生效（配的是 5）");
        assertEquals(2, snapB.get("maxRetries"),
                "maxRetries 仍留在 ModelConfig 里 —— 但它对执行参数不再有影响，这正是站 2 挖到的坑");
    }

    @Test
    @DisplayName("站2-5：两次 create 拿到的是不同 Agent 实例，但共用同一个状态存储")
    void createReturnsFreshAgentButSharedState() {
        AgentProperties properties = MvpTestFixtures.props();
        InMemoryAgentStateStore store = new InMemoryAgentStateStore();
        MvpAgentFactory f = factory(properties, new MvpTestFixtures.CapturingModel(), store);

        ReActAgent a = f.create("u1", "u1-s");
        ReActAgent b = f.create("u1", "u1-s");

        assertNotSame(a, b, "每次对话建一个新 Agent（Agent 本身无状态，状态在 store 里）");
        assertSame(store, a.getStateStore());
        assertSame(store, b.getStateStore());
    }

    @Test
    @DisplayName("站2-6：contextFor 产出的 (userId, sessionId) 就是状态槽位坐标")
    void contextCarriesSlotCoordinates() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);

        RuntimeContext ctx = governance.contextFor("u1001", "u1001-s1");

        assertEquals("u1001", ctx.getUserId(), "userId 决定状态槽位");
        assertEquals("u1001-s1", ctx.getSessionId(), "sessionId 决定状态槽位");
    }
}
