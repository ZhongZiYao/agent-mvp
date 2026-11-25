package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.FinalAnswerFilterMiddleware;
import io.agentscope.core.middleware.MiddlewareBase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 站 3：<b>治理装配的唯一入口</b>。
 *
 * <p><b>为什么必须有这个类</b>（这不是框架要求，是工程教训）：
 * 同一个项目里出现过多次「某个治理能力只接在了当时被测试的那条对话路径上，
 * 另一条路径照常裸奔」的缺陷 —— 根因永远是<b>装配散落在多个入口各写一遍</b>。
 * 新增能力时改了 A 忘了 B，而<b>两边都不会报错</b>。
 *
 * <p>本类把装配收敛成唯一实现：所有构建 {@link ReActAgent} 的入口一律调
 * {@link #applyTo(ReActAgent.Builder)}，<b>新增治理能力只改这里一处</b>。
 *
 * <h3>站 3 新增的认知：挂载顺序 ≠ 执行顺序</h3>
 * <p>本类里治理中间件的<b>挂载先后</b>看起来有讲究，实际上<b>完全不影响执行顺序</b>：
 * 框架在 {@code ReActAgent.Builder#build()} 的最后一步会做一次
 * {@code middlewares.sort(Comparator.comparingInt(MiddlewareBase::order).reversed())}
 * —— <b>按 order() 降序重排</b>。所以：</p>
 * <ul>
 *   <li><b>不要自己预排序</b>：任何预排序都会被框架这次重排整体覆盖（白写）；</li>
 *   <li><b>执行顺序只由 order() 决定</b>：数值越大越靠外（入站先执行、出站后执行）；</li>
 *   <li>挂载顺序只在<b>order 值相同</b>时才有意义（同值保持注册顺序）。</li>
 * </ul>
 * <p>证据见 {@link MvpMiddlewareOrders} 的 javadoc，运行时验证见
 * {@code MiddlewareChainOrderTest}。</p>
 *
 * <p><b>对照 customer_work</b>：那边是 {@code AgentGovernanceAssembler}，
 * 结构逐段对应 —— 冲突策略、生命周期中间件、可观测中间件、人工确认中间件、
 * 可插拔中间件、框架自带的 {@code FinalAnswerFilterMiddleware}，
 * 再加一个 {@code contextFor(sessionId)} 统一 {@code (userId, sessionId)} 口径。
 * 差别在治理件的<b>规模</b>：那边 27 个（{@code MiddlewareOrders} 27 常量 ↔ 27 实现类），
 * 本 demo 只有 3 个 + 1 个站 2 的工具审计。</p>
 *
 * <p><b>框架在这一站给了什么</b>：{@code Builder.middleware(...)} 插槽 +
 * {@code MiddlewareBase} 接口（6 个方法全是 default）+ {@code order()} 排序契约 +
 * 框架自动注入的 {@code GracefulShutdownMiddleware}。
 * <b>框架没给</b>：任何一条治理策略、任何一种分层约定、任何「唯一入口」的强制机制
 * —— 唯一入口这件事，靠的是<b>项目自己的纪律和守卫测试</b>。</p>
 */
@Component
public class MvpGovernanceAssembler {

    private final AgentProperties properties;

    /** 可插拔中间件：容器里所有 {@link MiddlewareBase} Bean（本 demo 里没有外部 Bean，全是空的）。 */
    private final ObjectProvider<MiddlewareBase> pluggableMiddlewares;

    /** 自建治理件（站 3）：审计留痕 —— 最外。 */
    private final AuditTrailMiddleware auditTrail = new AuditTrailMiddleware();

    /** 自建治理件（站 3）：出站脱敏 —— 比审计更内。 */
    private final RedactionMiddleware redaction = new RedactionMiddleware();

    /** 自建治理件（站 3）：入站拦截 —— 命中即短路，不调用模型。 */
    private final InputGuardMiddleware inputGuard = new InputGuardMiddleware();

    /** 自建中间件（站 2）：工具调用审计。 */
    private final ToolAuditMiddleware toolAudit = new ToolAuditMiddleware(100);

    /** 框架自带的最终答复过滤。框架<b>不会自动注入</b>它，必须显式注册。 */
    private final FinalAnswerFilterMiddleware finalAnswerFilter = new FinalAnswerFilterMiddleware();

    public MvpGovernanceAssembler(AgentProperties properties,
                                  ObjectProvider<MiddlewareBase> pluggableMiddlewares) {
        this.properties = properties;
        this.pluggableMiddlewares = pluggableMiddlewares;
    }

    /**
     * 把全部治理件装配到给定的 Agent builder 上。
     *
     * <p><b>每一条建 Agent 的路径都必须调用本方法</b>；漏调不会被框架发现，
     * 只会被守卫测试 {@code AgentAssemblyAlignmentTest} 拦下。</p>
     */
    public void applyTo(ReActAgent.Builder builder) {
        // 会话状态并发写策略：放在装配器而不是各个工厂里，理由与中间件相同 ——
        // 多条建 Agent 的路径必须给出同一个策略，否则同一份会话状态在不同入口上表现不一致。
        builder.conflictPolicy(properties.resolvedConflictPolicy());

        // —— 站 3：自建治理件 ——
        // 这里的挂载先后【不影响】执行顺序（框架会按 order() 降序重排），
        // 但按「从外到内」的顺序书写，读代码的人一眼能对上语义。
        builder.middleware(auditTrail);      // order 150：审计，出站最后执行，记录最终发出的内容
        builder.middleware(redaction);       // order 125：脱敏，比审计更内 ⇒ 审计看到的是脱敏后的版本
        builder.middleware(inputGuard);      // order 120：入站拦截，命中即短路
        builder.middleware(toolAudit);       // order 100：工具调用审计

        // 框架自带中间件：最终答复过滤。
        // ⚠️ 它的 order 取框架默认值 1，比本 demo 的治理件（100~150）都小 ⇒ 稳定落在【最内层】。
        // ⚠️ 它的语义是「丢弃模型调用工具前的中间叙述，缓冲到 ModelCallEnd 再一次性放出」——
        //    这会吃掉流式打字机效果。本 demo 只挂它做对照，不依赖它的行为。
        builder.middleware(finalAnswerFilter);

        // 可插拔中间件：外部容器里的所有 MiddlewareBase Bean。
        // ⚠️ 这里【刻意不做预排序】：框架 build() 里会按 order() 降序重排一次，
        //    任何本地排序都会被整体覆盖。orderedStream() 只在 order 值相同时决定相对次序。
        if (pluggableMiddlewares != null) {
            pluggableMiddlewares.orderedStream().forEach(builder::middleware);
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
     * （本轮会话标记、租户身份、org 维度命名空间），本 demo 只保留最基础的两个。</p>
     */
    public RuntimeContext contextFor(String userId, String sessionId) {
        return RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();
    }

    /**
     * 本装配器注册上去的全部中间件（<b>注册视角</b>，不是执行视角）。
     *
     * <p>用来给装配快照标注「这个中间件是谁挂上去的」。
     * <b>执行视角的链在 {@code agent.getMiddlewares()} 里</b> ——
     * 那是框架排序之后的真正生效顺序。</p>
     */
    public List<MiddlewareBase> selfRegistered() {
        return List.of(auditTrail, redaction, inputGuard, toolAudit, finalAnswerFilter);
    }

    /** 站 3：自建的三个治理件，供真机观测与测试断言。 */
    public AuditTrailMiddleware auditTrail() {
        return auditTrail;
    }

    /** 站 3：脱敏中间件。 */
    public RedactionMiddleware redaction() {
        return redaction;
    }

    /** 站 3：入站拦截中间件。 */
    public InputGuardMiddleware inputGuard() {
        return inputGuard;
    }

    /** 站 2：工具调用审计中间件。 */
    public ToolAuditMiddleware toolAudit() {
        return toolAudit;
    }
}
