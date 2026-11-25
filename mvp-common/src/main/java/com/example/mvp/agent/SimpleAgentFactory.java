package com.example.mvp.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

/**
 * 站 0：<b>纯用框架</b>的最小 Agent。
 *
 * <p>这个类里<b>没有任何自建的基础设施</b> —— 没有 Agent 工厂、没有中间件链、
 * 没有状态槽位管理、没有模型装饰器。就是框架 Builder 的直接使用。
 *
 * <p><b>为什么要先写这么"裸"的一版</b>：只有先当一次框架使用者，
 * 才知道后面哪些代码是「框架替我做了」、哪些是「我必须自己写」。
 * 这正是本 MVP 的核心产出 —— 一份可核对的<b>能力边界清单</b>。
 *
 * <p>对照 customer_work：那边是 {@code CustomerServiceAgentFactory.createAgent} 里
 * 手工拼装 14 个零件 + {@code AgentGovernanceAssembler.applyTo} 织入 27 个中间件。
 * 那些东西<b>不是框架强制的</b>，是那个项目的业务需要。站 2 我们会逐件对照，
 * 判定每一件到底是「框架没给、我们必须自建」还是「框架其实给了、我们多写了」。
 * <p><b>站 2 的改动（重要）</b>：这个类现在<b>也走治理装配</b>
 * （{@code governance.applyTo(builder)}）。
 * 原因不是「站 0 写错了」，而是<b>「所有建 Agent 的点必须经过同一个入口」这条纪律
 * 比「最小示例」更重要</b>：站 0 的裸版不挂治理链，看起来只是个教学样本，
 * 但它<b>同样是一个能建出 Agent 的生产代码路径</b> ——
 * 哪天有人复用它，治理能力就会静默失效，而且不报任何错。
 *
 * <p>守卫测试 {@code AgentAssemblyAlignmentTest} 会扫源码断言：
 * 每个出现 {@code ReActAgent.builder()} 的文件，其装配标记次数不少于建 Agent 的次数。
 * <b>这条断言一开始是红的，正是它把这条纪律钉住的。</b>
 */
@Component
public class SimpleAgentFactory {

    /** 系统提示词：Agent 的人设与行为边界。 */
    private static final String SYS_PROMPT = """
            你是电商平台的智能客服助手。
            规则：
            1. 回答订单相关问题时，必须先调用 query_order_status 工具查询真实状态，不要凭空编造。
            2. 只回答与订单、物流、退款相关的问题。
            3. 回答简洁，用中文，控制在 3 句话以内。
            """;

    private final MvpGovernanceAssembler governance;

    public SimpleAgentFactory(MvpGovernanceAssembler governance) {
        this.governance = governance;
    }

    /**
     * 构建一个最小可用 Agent。
     *
     * <p><b>框架直接给的部分</b>（我们一行没写实现）：
     * <ul>
     *   <li>{@code ReActAgent.builder()} —— ReAct 循环本体（推理 → 调工具 → 再推理）</li>
     *   <li>{@code .model(Model)} —— 模型注入</li>
     *   <li>{@code .toolkit(Toolkit)} —— 工具集</li>
     *   <li>{@code .sysPrompt(String)} —— 系统提示词</li>
     *   <li>{@code .maxIters(int)} —— 迭代上限</li>
     * </ul>
     *
     * <p><b>我们自建的部分</b>：只有「把 {@code OrderTools} 注册进 {@code Toolkit}」这一步调用，
     * 以及决定用哪个模型 —— 业务决策，框架不可能替我们定。
     */
    public ReActAgent create(Model model) {
        Toolkit toolkit = new Toolkit();
        // 反射注册：框架扫出对象里所有 @Tool 方法，自动生成参数 Schema
        toolkit.registerTool(new OrderTools());

        ReActAgent.Builder builder = ReActAgent.builder()
                .name("MvpCustomerServiceAgent")
                .sysPrompt(SYS_PROMPT)
                .model(model)
                .toolkit(toolkit)
                // 迭代上限：工具调用轮数的硬顶，防止模型绕圈烧 token
                .maxIters(5);

        // 站 2：治理装配的唯一入口（与 MvpAgentFactory 共用同一份实现）
        governance.applyTo(builder);

        return builder.build();
    }
}
