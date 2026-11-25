package com.example.mvp.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 站 2：<b>自建</b> Model 装饰器 —— 热替换 + 调用归因。
 *
 * <p><b>边界判定：框架给 Model 接口，不给任何装饰器基类。</b>
 * {@code io.agentscope.core.model.Model} 一共 6 个方法，
 * 其中<b>只有 2 个是抽象的</b>（{@code stream} 与 {@code getModelName}），
 * 其余 4 个（{@code supportsNativeStructuredOutput} / {@code ...WithTools} /
 * {@code supportsToolChoiceSpecific} / {@code getContextWindowSize}）都是 default。
 *
 * <p>→ <b>写一个装饰器只要实现 2 个方法，成本极低</b>；
 * → 但框架<b>不提供</b>任何现成的装饰器：没有 RetryModel、没有 FallbackModel、
 * 没有 DelegatingModel、没有 AttributedModel。
 *
 * <p><b>对照 customer_work</b>：那边自建了 5 个装饰器 ——
 * {@code MutableDelegatingModel}（热替换）、{@code AttributedModel}（归因）、
 * {@code FailoverModel}（主备有序候选 + 熔断记忆）、
 * {@code ResilientChatModel}（重试 + 退避）、{@code TieredRoutingModel}（分级路由）。
 * 而框架 2.0.5 实际只提供了其中两件：
 * <ul>
 *   <li><b>兜底模型</b>：{@code Builder.fallbackModel(Model)} + {@code failoverListener} ——
 *       但只有<b>单个</b>兜底候选，没有候选表、没有熔断器、没有「按部署健康度剔除候选」；</li>
 *   <li><b>重试 + 超时</b>：{@code Builder.maxRetries(int)} 与
 *       {@code Builder.modelExecutionConfig(ExecutionConfig)} ——
 *       比 customer_work 自建的 {@code ResilientChatModel} <b>还全</b>
 *       （多了 timeout / maxBackoff / backoffMultiplier / retryOn 谓词）。</li>
 * </ul>
 * → <b>热替换、归因、多候选主备 + 熔断、分级路由，这四件框架完全没给，必须自建。</b>
 *
 * <p>本类实现 customer_work 的「热替换 + 归因」两件，用来说明：
 * <b>装饰器自建成本 = 2 个方法</b>，但<b>命名、计数、归因口径要自己想</b>。
 */
public class SwappableModel implements Model {

    /** 当前生效的委托模型；{@link #swap} 换掉它。 */
    private final AtomicReference<Model> delegate;

    /** 累计调用次数（归因用）。 */
    private final AtomicLong calls = new AtomicLong();

    /** 最近一次调用实际命中的模型名（归因用）。 */
    private final AtomicReference<String> lastAttributedModel = new AtomicReference<>();

    public SwappableModel(Model delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("委托模型不能为空");
        }
        this.delegate = new AtomicReference<>(delegate);
    }

    /**
     * 热替换：换掉底层模型，<b>不需要重建 Agent</b>。
     *
     * <p>这正是框架没给、而 customer_work 自建 {@code MutableDelegatingModel#swap} 的原因。
     */
    public void swap(Model next) {
        if (next == null) {
            throw new IllegalArgumentException("替换后的模型不能为空");
        }
        delegate.set(next);
    }

    /** 当前生效的委托模型。 */
    public Model current() {
        return delegate.get();
    }

    /** 累计调用次数。 */
    public long calls() {
        return calls.get();
    }

    /** 最近一次调用命中的模型名。 */
    public String lastAttributedModel() {
        return lastAttributedModel.get();
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> msgs, List<ToolSchema> tools, GenerateOptions options) {
        // 关键：每次调用都读一次当前的委托 —— 这就是「热替换」能生效的原因。
        // 如果在构造时把 delegate 抓进局部变量，热替换就名存实亡了。
        Model target = delegate.get();
        calls.incrementAndGet();
        lastAttributedModel.set(target.getModelName());
        return target.stream(msgs, tools, options);
    }

    @Override
    public String getModelName() {
        return delegate.get().getModelName();
    }
}
