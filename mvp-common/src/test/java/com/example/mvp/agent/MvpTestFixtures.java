package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 站 2 测试夹具。
 *
 * <p><b>为什么需要它</b>：站 2 要验证的全是「装配层语义」，
 * 而装配层的语义<b>只在模型被调用的那一刻才可见</b>
 * （{@code GenerateOptions} 是框架合并完的结果，在调用前拿不到）。
 * 所以必须有一个能<b>把入参截获下来</b>的假模型。
 *
 * <p>这与站 0 的 {@code ScriptedModel} 思路一致：
 * <b>用假模型确定性地钉住框架行为，不烧 token、不看网络。</b>
 */
final class MvpTestFixtures {

    private MvpTestFixtures() {
    }

    /**
     * 默认装配参数。
     *
     * <p>刻意把 {@code maxRetries=2} 与 {@code modelMaxAttempts=5} 设成<b>不同值</b>：
     * 两者同时生效路径只有一个，谁生效看数字就知道了。
     */
    static AgentProperties props() {
        return new AgentProperties(
                5,      // maxIters
                2,      // maxRetries
                5,      // modelMaxAttempts
                60,     // modelTimeoutSeconds
                500,    // modelInitialBackoffMillis
                2.0d,   // modelBackoffMultiplier
                false,  // useModelExecutionConfig
                "OVERWRITE",
                "DEFAULT",
                List.of());
    }

    /** 打开 {@code modelExecutionConfig} 的装配参数。 */
    static AgentProperties propsWithExecutionConfig() {
        return new AgentProperties(
                5, 2, 5, 60, 500, 2.0d,
                true,
                "OVERWRITE",
                "DEFAULT",
                List.of());
    }

    /** 治理装配器（无可插拔中间件）。 */
    static MvpGovernanceAssembler governance(AgentProperties properties) {
        return new MvpGovernanceAssembler(properties, null);
    }

    /**
     * 截获 {@code GenerateOptions} 的假模型：永远返回一个文本答复。
     *
     * <p>它回答的是站 2 最核心的那个问题：
     * <b>框架到底把什么执行参数交给了模型？</b>
     */
    static final class CapturingModel implements Model {
        private final AtomicReference<GenerateOptions> captured = new AtomicReference<>();
        private int calls = 0;

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls++;
            captured.set(options);
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("好的。").build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return "capturing";
        }

        /** 模型实际收到的 GenerateOptions。 */
        GenerateOptions captured() {
            return captured.get();
        }

        /** 被调用次数。 */
        int calls() {
            return calls;
        }
    }
}
