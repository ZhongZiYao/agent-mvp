package com.example.mvp.config;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.minimax.MiniMaxFormatter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型配置（对应 customer_work 的 {@code ModelProperties}，但刻意做小）。
 *
 * <p><b>站 0 的边界判定</b>：框架直接给了
 * {@link OpenAIChatModel.Builder#formatter} 这个扩展点，
 * 所以「OpenAI 兼容协议 + 厂商专用 Formatter」这件事框架已经解决了 —— 我们只需要传参。
 *
 * <p><b>为什么不直接用框架的 {@code MiniMaxModelProvider}</b>：那是给
 * {@code model.stream(...)} 这种「按 providerId 取模型」的注册表路径用的；
 * 而我们这个 MVP 要复刻 customer_work 的做法 —— <b>显式构造一个 Model 实例</b>并注入，
 * 这样才能在「框架给什么」这个问题上有对照物。
 *
 * @param provider    厂商标识（本 MVP 只支持 openai 兼容路径的 minimax）
 * @param apiKey      鉴权密钥（从环境变量注入，不写进配置文件）
 * @param baseUrl     端点；为空则用 MiniMax 国内默认
 * @param model       模型名，以 MiniMax 控制台的模型列表为准
 * @param stream      是否流式
 * @param temperature 采样温度
 * @param maxCompletionTokens 单次生成上限（对应请求体 {@code max_completion_tokens}）
 */
@ConfigurationProperties(prefix = "mvp.model")
public record ModelConfig(
        String provider,
        String apiKey,
        String baseUrl,
        String model,
        boolean stream,
        Double temperature,
        Integer maxCompletionTokens) {

    /** MiniMax 国内开放平台的 OpenAI 兼容端点。 */
    public static final String MINIMAX_BASE_URL = "https://api.minimaxi.com/v1";

    /** provider 为空时按 minimax 处理 —— 与 customer_work 的 {@code ModelProviders.normalize()} 同口径。 */
    public String normalizedProvider() {
        return provider == null || provider.isBlank() ? "minimax" : provider.trim().toLowerCase();
    }

    /** 端点：配置优先，为空按 provider 取默认。 */
    public String resolvedBaseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.trim();
        }
        return "minimax".equals(normalizedProvider()) ? MINIMAX_BASE_URL : null;
    }

    /**
     * 按配置构建模型实例。
     *
     * <p>刻意做成「一个方法、一个模型」，而不是 customer_work 的五路 provider switch：
     * <b>本 MVP 只验证「框架的 Builder 能不能支撑我们」，不验证多厂商路由</b>。
     * 多厂商那一层留到站 2（装配层）再做，届时对照 customer_work 的
     * {@code ChatModelFactory} 就能看出「哪些是框架的、哪些是我们必须自己写的」。
     */
    public Model build() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "缺少模型 API Key：请设置环境变量 MINIMAX_API_KEY，或配置 mvp.model.api-key");
        }
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(apiKey.trim())
                .modelName(model)
                .stream(stream)
                .generateOptions(buildOptions())
                // 关键：走 MiniMax 专用 Formatter，而不是通用 OpenAI Formatter。
                // 通用路径会丢掉 system role（框架 #2189）——
                // 系统提示词是 Agent 全部行为约束的载体，丢了不报错、直接静默失效。
                .formatter(new MiniMaxFormatter());

        String url = resolvedBaseUrl();
        if (url != null) {
            builder.baseUrl(url);
        }
        return builder.build();
    }

    private GenerateOptions buildOptions() {
        GenerateOptions.Builder builder = GenerateOptions.builder();
        if (temperature != null) {
            builder.temperature(temperature);
        }
        if (maxCompletionTokens != null) {
            // 用 max_completion_tokens 而不是 max_tokens：
            // MiniMax 官方文档已把 max_tokens 标为 deprecated，要求改用 max_completion_tokens。
            //
            // 实测（javap 反编译 agentscope-extensions-model-openai 2.0.5）：
            // MiniMaxFormatter#applyMaxTokens → applyMiniMaxMaxTokens(request, maxCompletionTokens, maxTokens)
            // 逻辑是「maxCompletionTokens 优先，否则回退 maxTokens」，**两者最终都写到请求体的
            // max_completion_tokens 字段**。所以这里设哪个字段，线上发出的请求是一样的 ——
            // 是 Formatter 层做的转换，不是我们在 ModelConfig 里决定的。
            //
            // 顺带记下同一个 Formatter 的 removeUnsupported：它会把 thinkingBudget / reasoningEffort /
            // frequencyPenalty / presencePenalty / parallelToolCalls / responseFormat / seed 全部置 null ——
            // 这些参数 MiniMax 不支持，框架直接不发（不报错）。
            builder.maxCompletionTokens(maxCompletionTokens);
        }
        return builder.build();
    }
}
