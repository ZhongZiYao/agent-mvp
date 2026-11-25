package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 站 2：<b>治理装配的唯一入口</b>。
 *
 * <p><b>为什么必须有这个类</b>（这不是框架要求，是工程教训）：
 * 同一个项目里出现过多次「某个治理能力只接在了当时被测试的那条对话路径上，
 * 另一条路径照常裸奔」的缺陷 —— 根因永远是<b>装配散落在多个入口各写一遍</b>。
 * 新增能力时改了 A 忘了 B，而<b>两边都不会报错</b>。
 *
 * <p>本类把装配收敛成唯一实现：所有构建 {@link ReActAgent} 的入口一律调
 * {@link #applyTo(ReActAgent.Builder)}，<b>新增治理能力只改这里一处</b>。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code AgentGovernanceAssembler}，
 * 结构逐段对应 —— 冲突策略、生命周期中间件、可观测中间件、人工确认中间件、
 * 可插拔中间件（{@code @Order} 排序）、框架自带的 {@code FinalAnswerFilterMiddleware}，
 * 再加一个 {@code contextFor(sessionId)} 统一 {@code (userId, sessionId)} 口径。
 *
 * <p><b>框架在这一站给了什么</b>：{@code Builder.middleware(...)} /
 * {@code middlewares(List)} 插槽 + {@code MiddlewareBase} 接口（6 个方法全是 default）
 * + {@code Builder.conflictPolicy(...)} + {@code RuntimeContext.Builder}。
 * <b>框架没给</b>：任何一条治理策略、任何一种排序约定、任何「唯一入口」的强制机制
 * —— 唯一入口这件事，靠的是<b>项目自己的纪律和守卫测试</b>。
 */
@Component
public class MvpGovernanceAssembler {

    private final AgentProperties properties;

    /** 可插拔中间件：容器里所有 {@link MiddlewareBase} Bean（本 demo 里没有外部 Bean，全是空的）。 */
    private final ObjectProvider<MiddlewareBase> pluggableMiddlewares;

    /** 自建中间件：工具调用审计。 */
    private final ToolAuditMiddleware toolAudit = new ToolAuditMiddleware(100);

    public MvpGovernanceAssembler(AgentProperties properties,
                                  ObjectProvider<MiddlewareBase> pluggableMiddlewares) {
        this.properties = properties;
        this.pluggableMiddlewares = pluggableMiddlewares;
    }

    /**
     * 把全部治理件装配到给定的 Agent builder 上。
     *
     * <p><b>每一条建 Agent 的路径都必须调用本方法</b>；漏调不会被框架发现，
     * 只会被守卫测试 {@code AgentAssemblyAlignmentTest} 拦下。
     */
    public void applyTo(ReActAgent.Builder builder) {
        // 会话状态并发写策略：放在装配器而不是各个工厂里，理由与中间件相同 ——
        // 多条建 Agent 的路径必须给出同一个策略，否则同一份会话状态在不同入口上表现不一致。
        builder.conflictPolicy(properties.resolvedConflictPolicy());

        // 自建中间件：工具调用审计（证明 onActing 插槽可用，且 order 可覆盖）
        builder.middleware(toolAudit);

        // 框架自带中间件：最终答复过滤。框架 core 只自带 2 个中间件，
        // 这是其中之一（另一个是 TaskReminderMiddleware）。
        // 它的 order 默认是 1 —— 比本 demo 的治理件（100）小，稳定落在更内层。
        builder.middleware(new io.agentscope.core.middleware.FinalAnswerFilterMiddleware());

        // 可插拔中间件：外部容器里的所有 MiddlewareBase Bean，按 order 升序（外 → 内）
        if (pluggableMiddlewares != null) {
            pluggableMiddlewares.orderedStream()
                    .filter(m -> m != toolAudit)
                    .sorted(Comparator.comparingInt(MiddlewareBase::order))
                    .forEach(builder::middleware);
        }
    }

    /**
     * 构造一次 Agent 调用的运行时上下文。
     *
     * <p><b>这两个值决定了框架内部按 {@code (userId, sessionId)} 缓存的对话状态
     * 落在哪个槽位上，因此绝不能写成常量</b> ——
     * 写成常量会让所有用户共用一份对话历史。
     *
     * <p>对照 customer_work：那边还往 {@code RuntimeContext} 里 {@code put} 了不少东西
     * （本轮会话标记、租户身份、org 维度命名空间），本 demo 只保留最基础的两个。
     */
    public RuntimeContext contextFor(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /** 工具审计中间件（真机观测用）。 */
    public ToolAuditMiddleware toolAudit() {
        return toolAudit;
    }

    /** 装配器实际挂上去的中间件顺序（自建 + 框架自带 + 外部），供真机展示与测试断言。 */
    public List<MiddlewareBase> assembledMiddlewares() {
        List<MiddlewareBase> all = new java.util.ArrayList<>();
        all.add(toolAudit);
        all.add(new io.agentscope.core.middleware.FinalAnswerFilterMiddleware());
        if (pluggableMiddlewares != null) {
            pluggableMiddlewares.orderedStream()
                    .filter(m -> m != toolAudit)
                    .sorted(Comparator.comparingInt(MiddlewareBase::order))
                    .forEach(all::add);
        }
        return all;
    }
}
