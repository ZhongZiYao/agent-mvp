package com.example.mvp.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 站 2 最核心的一条：<b>{@code maxRetries} 到底是什么？</b>
 *
 * <p>清单里原本挂着一个待验证项：
 * 「{@code maxRetries} 是<b>模型调用级</b>重试还是 <b>Agent 级</b>重试？
 * 语义不同，若重复就是两条重试路径叠加（token 成本翻倍）」。
 * <b>本测试就是那个待验证项的答案。</b>
 *
 * <p><b>结论（javap 反编译 + 本测试实测，双向确认）</b>：
 * <ol>
 *   <li>{@code Builder.maxRetries(n)} → {@code ModelConfig.maxRetries = n}
 *       → {@code buildGenerateOptions()} 把它翻译成
 *       {@code GenerateOptions.executionConfig.maxAttempts = n}；</li>
 *   <li>而 {@code ExecutionConfig.maxAttempts} 最终由
 *       <b>{@code OpenAIChatModel}</b> 内部的
 *       {@code ModelUtils.applyTimeoutAndRetry} 执行（Reactor {@code Retry.backoff}）——
 *       <b>所以重试发生在「一次模型调用」这一层，不是 Agent 整轮这一层</b>；</li>
 *   <li><b>语义是「总尝试次数」不是「额外重试次数」</b>：
 *       {@code maxRetries(2)} = 最多试 2 次（1 次原始 + 1 次重试），不是 1+2=3 次；</li>
 *   <li><b>不设时框架默认就是 3</b>（{@code assembleModelConfig} 里 {@code flatMaxRetries} 为 null 时填 3）
 *       —— 「没配 = 不重试」是错的；</li>
 *   <li><b>与 {@code modelExecutionConfig} 冲突时后者赢</b>：
 *       {@code mergeOptions} 是字段级合并，而 {@code executionConfig} 是原子字段，
 *       一旦 {@code modelExecutionConfig} 非 null，
 *       {@code maxRetries} 推导出来的那个 {@code executionConfig} 就被<b>整体替换</b>了。</li>
 * </ol>
 *
 * <p><b>为什么这条值得钉住</b>：customer_work 自建了 {@code ResilientChatModel}
 * （在 Model 装饰器层重试），而底层 {@code OpenAIChatModel} 又会按
 * {@code executionConfig} 重试 —— <b>两层叠起来，总尝试数是乘积</b>，
 * 且每一次重试都是一次完整的 HTTP 请求（token 成本同步放大）。
 * 框架其实已经把这个能力给全了，自建装饰器做重试是<b>重复造轮子</b>。
 */
class ExecutionConfigSemanticsTest {

    /** 调一次 Agent，把模型实际收到的 GenerateOptions 截获下来。 */
    private static io.agentscope.core.model.GenerateOptions capture(
            ReActAgent.Builder builder, MvpTestFixtures.CapturingModel model) {
        ReActAgent agent = builder.model(model).toolkit(new Toolkit()).build();
        assertNotNull(agent);
        agent.call("你好", RuntimeContext.builder().userId("u1").sessionId("s1").build()).block();
        return model.captured();
    }

    @Test
    @DisplayName("站2-7：maxRetries 被翻译成 executionConfig.maxAttempts（模型调用级）")
    void maxRetriesBecomesMaxAttempts() {
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();

        var options = capture(ReActAgent.builder()
                .name("t")
                .sysPrompt("s")
                .maxRetries(2), model);

        assertNotNull(options.getExecutionConfig(),
                "maxRetries 应被翻译成 GenerateOptions 上的 executionConfig");
        assertEquals(2, options.getExecutionConfig().getMaxAttempts(),
                "maxRetries(2) 应变成 maxAttempts=2 —— 注意是「总尝试次数」不是「额外重试次数」");
    }

    @Test
    @DisplayName("站2-8：不设 maxRetries 时，框架默认就是 3 次尝试（不是 0）")
    void defaultMaxRetriesIsThree() {
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();

        var options = capture(ReActAgent.builder()
                .name("t")
                .sysPrompt("s"), model);

        assertNotNull(options.getExecutionConfig(),
                "框架默认就该带 executionConfig（assembleModelConfig 里 flatMaxRetries 为 null 时填 3）");
        assertEquals(3, options.getExecutionConfig().getMaxAttempts(),
                "框架默认 maxRetries=3 —— 「没配 = 不重试」是错的");
    }

    @Test
    @DisplayName("站2-9：modelExecutionConfig 与 maxRetries 同时设时 —— 前者赢，后者被整体丢弃")
    void modelExecutionConfigWinsOverMaxRetries() {
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();

        var options = capture(ReActAgent.builder()
                .name("t")
                .sysPrompt("s")
                .maxRetries(2)
                .modelExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(30))
                        .maxAttempts(5)
                        .initialBackoff(Duration.ofMillis(200))
                        .backoffMultiplier(2.0d)
                        .build()), model);

        assertEquals(5, options.getExecutionConfig().getMaxAttempts(),
                "modelExecutionConfig 的 maxAttempts=5 应生效");
        assertEquals(30, options.getExecutionConfig().getTimeout().getSeconds(),
                "timeout 也来自 modelExecutionConfig");
        assertEquals(200, options.getExecutionConfig().getInitialBackoff().toMillis(),
                "退避基数也来自 modelExecutionConfig");
    }

    @Test
    @DisplayName("站2-10：modelExecutionConfig 存在时，maxRetries 在 ModelConfig 里还在，但对执行参数无效")
    void maxRetriesStaysButIsInert() {
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();

        ReActAgent agent = ReActAgent.builder()
                .name("t")
                .sysPrompt("s")
                .model(model)
                .toolkit(new Toolkit())
                .maxRetries(2)
                .modelExecutionConfig(ExecutionConfig.builder()
                        .maxAttempts(5)
                        .build())
                .build();

        assertEquals(2, agent.getModelConfig().maxRetries(),
                "ModelConfig 里 maxRetries 仍是 2 —— 它没被「清掉」，只是被 executionConfig 整体盖掉了");
        agent.call("你好", RuntimeContext.builder().userId("u1").sessionId("s1").build()).block();
        assertEquals(5, model.captured().getExecutionConfig().getMaxAttempts(),
                "但真正生效的是 modelExecutionConfig 的 5");
    }
}
