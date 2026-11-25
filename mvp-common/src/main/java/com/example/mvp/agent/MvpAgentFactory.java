package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站 2：<b>装配层主体</b> —— 把「一个能跑的客服 Agent」拼出来。
 *
 * <p><b>这一站的命题</b>：框架给了多少零件？我们还要写多少？
 *
 * <p><b>框架直接给（我们只传参，一行实现都没写）</b>：
 * <pre>
 *   Builder.name / description / sysPrompt   → Agent 的身份与行为约束
 *   Builder.model(Model)                    → 模型注入
 *   Builder.toolkit(Toolkit)                 → 工具集
 *   Builder.maxIters(int)                   → ReAct 迭代上限
 *   Builder.maxRetries(int)                 → 模型调用级重试（默认 3！）
 *   Builder.modelExecutionConfig(cfg)       → 模型调用超时 / 重试 / 退避（框架最全的执行参数）
 *   Builder.stateStore(store)               → 会话状态外置（按 userId+sessionId 落槽）
 *   Builder.conflictPolicy(policy)          → 状态并发写策略
 *   Builder.defaultSessionId(id)            → 默认会话号
 *   Builder.permissionContext(ctx)          → 声明式工具权限
 *   Builder.enablePendingToolRecovery(bool) → 中断后无缝恢复
 *   Builder.middleware(mw)                  → 治理插槽
 * </pre>
 *
 * <p><b>我们必须自己写的</b>：
 * <ul>
 *   <li><b>装什么</b> —— 哪个提示词、哪个模型、哪个工具集、哪个权限规则，
 *       全是业务决策，框架不可能替我们定；</li>
 *   <li><b>状态存哪</b> —— 框架只给 {@code AgentStateStore} 接口，
 *       内存版（{@link InMemoryAgentStateStore}）/ MySQL 版 / Redis 版要自己选；</li>
 *   <li><b>治理策略</b> —— 框架给插槽，中间件实体要自己写；</li>
 *   <li><b>唯一装配入口</b> —— 框架不强制，靠纪律 + 守卫测试。</li>
 * </ul>
 *
 * <p><b>对照 customer_work</b>：那边是 {@code CustomerServiceAgentFactory#createAgent}，
 * 装 14 个零件（含 Nacos 提示词、长期记忆、RAG、Skill、MCP、trace 导出…）。
 * <b>本 demo 只装 11 个</b> —— 差的 3 个（长期记忆 / RAG / Skill）留到站 4，
 * 它们在框架侧同样是「Builder 上的一格」，边界性质完全一样。
 */
@Component
public class MvpAgentFactory {

    /**
     * 系统提示词。
     *
     * <p><b>提示词也是装配的一部分</b>：customer_work 那边它来自 Nacos 配置中心
     * （{@code NacosPromptService}），发布时可热改；本 demo 用常量，
     * 但<b>拼接位置在同一个地方</b> —— 这才是「装配层」的形状。
     */
    private static final String SYSTEM_PROMPT = """
            你是电商平台的智能客服助手。
            规则：
            1. 回答订单相关问题时，必须先调用 query_order_status 工具查询真实状态，不要凭空编造。
            2. 只回答与订单、物流、退款相关的问题。
            3. 回答简洁，用中文，控制在 3 句话以内。
            """;

    private final Model model;
    private final AgentProperties properties;
    private final MvpGovernanceAssembler governance;
    private final AgentStateStore stateStore;
    private final OrderTools orderTools = new OrderTools();

    public MvpAgentFactory(Model model,
                           AgentProperties properties,
                           MvpGovernanceAssembler governance,
                           InMemoryAgentStateStore stateStore) {
        this.model = model;
        this.properties = properties;
        this.governance = governance;
        this.stateStore = stateStore;
    }

    /**
     * 为一次对话构建 Agent。
     *
     * @param userId    已鉴权用户 id（决定状态槽位）
     * @param sessionId 会话号（决定状态槽位）
     */
    public ReActAgent create(String userId, String sessionId) {
        Toolkit toolkit = buildToolkit();

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("MvpCustomerServiceAgent")
                .description("电商平台智能客服助手")
                .sysPrompt(systemPrompt())
                .model(model)
                .toolkit(toolkit)
                // 迭代上限：工具调用轮数的硬顶，防止模型绕圈烧 token
                .maxIters(properties.resolvedMaxIters())
                // 模型调用级重试。⚠️ 名字骗人：它映射到 ExecutionConfig.maxAttempts，
                //    语义是「总尝试次数」；而且不设时框架默认就是 3（不是 0）。
                .maxRetries(properties.resolvedMaxRetries())
                // 状态外置：按 (userId, sessionId) 自动加载 / 持久化短期会话状态
                .stateStore(stateStore)
                .defaultSessionId(sessionId)
                // 声明式工具权限：框架自带 PermissionEngine + 规则模型
                .permissionContext(permissionContext())
                // 中断后无缝恢复：保留并恢复被打断的待执行工具调用
                .enablePendingToolRecovery(true);

        if (properties.resolvedUseModelExecutionConfig()) {
            // 框架最全的执行参数：超时 + 尝试次数 + 退避基数 + 退避倍数。
            //
            // ⚠️⚠️ 站 2 挖到的坑：设了它，maxRetries 会被<b>整个丢弃</b>。
            // 因为 GenerateOptions.mergeOptions 是「字段级」合并，而 executionConfig 是
            // 一个原子字段 —— modelExecutionConfig 一旦非 null，就把 maxRetries 推导出来的
            // 那个 executionConfig 整体替换掉了。两个都设 = 只有一个生效，且生效的是这个。
            builder.modelExecutionConfig(ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(properties.resolvedModelTimeoutSeconds()))
                    .maxAttempts(properties.resolvedModelMaxAttempts())
                    .initialBackoff(Duration.ofMillis(properties.resolvedModelInitialBackoffMillis()))
                    .backoffMultiplier(properties.resolvedModelBackoffMultiplier())
                    .build());
        }

        // 治理装配：唯一入口（冲突策略 + 中间件链）
        governance.applyTo(builder);

        return builder.build();
    }

    /**
     * 构造运行时上下文 —— 转发到治理装配器，<b>口径只有一处</b>。
     *
     * <p>customer_work 把这条也收敛进 {@code AgentGovernanceAssembler#contextFor}，
     * 理由一样：多 Agent 编排器走的是同一份实现，
     * 两条路径的 {@code (userId, sessionId)} 口径必须一致，
     * 否则同一个会话在不同路径上会落到框架的不同状态槽位。
     */
    public io.agentscope.core.agent.RuntimeContext contextFor(String userId, String sessionId) {
        return governance.contextFor(userId, sessionId);
    }

    /**
     * 构建工具体系。
     *
     * <p><b>框架直接给</b>：{@code Toolkit} 的反射注册、参数 Schema 自动生成、
     * 工具分组（{@code createToolGroup}）、元工具（{@code registerMetaTool}）。
     * <b>我们写</b>：注册哪个业务对象、工具分几组、哪些组默认激活。
     */
    public Toolkit buildToolkit() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(orderTools);
        return toolkit;
    }

    /**
     * 生效的系统提示词 = 内置提示词 + 运行期事实注入。
     *
     * <p>customer_work 也这么做：模型训练知识有时间滞后，不注入当前日期，
     * 模型会把合法单号推理成「未来日期，不合逻辑」而拒查。
     */
    public String systemPrompt() {
        return SYSTEM_PROMPT + "\n补充事实与约束：\n"
                + "- 当前日期：" + LocalDate.now() + "（以此为准判断时间）。\n"
                + "- 订单号不要凭格式猜有效性，一律调用工具核实。\n";
    }

    /**
     * 工具权限上下文。
     *
     * <p><b>框架给了</b>：{@code PermissionContextState} / {@code PermissionEngine} /
     * {@code PermissionRule} / {@code PermissionMode}（DEFAULT / ACCEPT_EDITS / EXPLORE /
     * BYPASS / DONT_ASK 五种模式）。
     * <b>框架没给</b>：哪些工具该 ask、哪些该 deny —— <b>规则语义要自己写</b>。
     */
    public PermissionContextState permissionContext() {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(properties.resolvedPermissionMode());
        for (String tool : properties.resolvedDeniedTools()) {
            builder.addDenyRule(tool, new PermissionRule(
                    tool,
                    "工具 " + tool + " 在本 demo 中被配置为禁止调用",
                    PermissionBehavior.DENY,
                    "mvp-config"));
        }
        return builder.build();
    }

    /**
     * 装配快照：把这个 Agent 身上实际装了什么摊开给人看。
     *
     * <p>这是站 2 的「可观测出口」—— 装配错了（比如中间件漏挂、权限没生效）
     * 在生产里是不报错的，只能靠这种快照在运行期看出来。
     */
    public Map<String, Object> describe(ReActAgent agent) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agentName", agent.getName());
        out.put("model", agent.getModel() == null ? null : agent.getModel().getModelName());
        out.put("maxIters", agent.getMaxIters());
        out.put("maxRetries", agent.getModelConfig() == null ? null : agent.getModelConfig().maxRetries());
        out.put("conflictPolicy", String.valueOf(agent.getConflictPolicy()));
        out.put("stateStore", agent.getStateStore() == null ? null : agent.getStateStore().getClass().getSimpleName());
        out.put("permissionMode", String.valueOf(agent.getPermissionContext() == null
                ? null : agent.getPermissionContext().getMode()));
        out.put("permissionDenyRules",
                agent.getPermissionContext() == null ? List.of()
                        : agent.getPermissionContext().getDenyRules().keySet().stream().toList());
        out.put("middlewares", agent.getMiddlewares() == null ? List.of()
                : agent.getMiddlewares().stream()
                        .map(mw -> mw.getClass().getSimpleName() + "@" + mw.order())
                        .toList());
        out.put("toolNames", agent.getToolkit() == null ? List.of() : List.copyOf(agent.getToolkit().getToolNames()));
        ExecutionConfig exec = agent.getModelExecutionConfig();
        Map<String, Object> execView = new LinkedHashMap<>();
        execView.put("timeoutSeconds", exec == null || exec.getTimeout() == null ? null : exec.getTimeout().toSeconds());
        execView.put("maxAttempts", exec == null ? null : exec.getMaxAttempts());
        execView.put("initialBackoffMillis", exec == null || exec.getInitialBackoff() == null
                ? null : exec.getInitialBackoff().toMillis());
        execView.put("backoffMultiplier", exec == null ? null : exec.getBackoffMultiplier());
        out.put("modelExecutionConfig", exec == null ? null : execView);
        return out;
    }
}
