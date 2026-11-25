package com.example.mvp.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.message.ToolUseBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

/**
 * 站 2：自建中间件最小示例 —— 工具调用审计。
 *
 * <p><b>边界判定：框架给的是「插槽」，不是「策略」。</b>
 * {@code MiddlewareBase} 是<b>接口</b>，6 个方法<b>全是 default</b>：
 * <pre>
 *   onAgent / onReasoning / onActing / onModelCall / onSystemPrompt  + order()
 * </pre>
 * 也就是说<b>一个中间件可以什么都不做</b>（空实现即插桩），
 * 也<b>可以只覆盖其中一两个插槽</b>（本类只覆盖 {@code onActing}）。
 *
 * <p>框架 core 自带的中间件<b>只有 2 个</b>：
 * {@code FinalAnswerFilterMiddleware} 与 {@code TaskReminderMiddleware}。
 * customer_work 那边的 <b>27 个</b>中间件全部是自建 —— 站 3 展开讲。
 *
 * <p><b>关于 {@code order()}</b>：框架默认实现返回 <b>1</b>。
 * customer_work 用 {@code MiddlewareOrders} 那一整套常量（50~200）
 * 把治理中间件排在框架内置的 {@code FinalAnswerFilterMiddleware}（1）<b>外面</b>，
 * 使它稳定落在最内层。本 demo 只演示「order 可覆盖」，不展开契约。
 */
public class ToolAuditMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditMiddleware.class);

    /** 本中间件的 order：大值 = 更外层（更早看到事件）。 */
    private final int order;

    /** 已审计的工具调用（测试与真机观测用）。 */
    private final List<String> audited = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public ToolAuditMiddleware() {
        this(100);
    }

    public ToolAuditMiddleware(int order) {
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        if (input != null && input.toolCalls() != null) {
            for (ToolUseBlock call : input.toolCalls()) {
                String toolName = call == null ? "<unknown>" : call.getName();
                audited.add(toolName);
                log.info("[audit] tool call: {}", toolName);
            }
        }
        // 关键：必须把 next.apply(input) 原样传下去。
        // 中间件的契约是「包住下一段」，不调用 next 就等于把整条链路掐断 ——
        // 而且它不报错，只是「模型永远不回复」。
        return next.apply(input);
    }

    /** 已审计的工具名列表。 */
    public List<String> audited() {
        return List.copyOf(audited);
    }
}
