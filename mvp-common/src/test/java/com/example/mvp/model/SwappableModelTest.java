package com.example.mvp.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 站 2：<b>自建 Model 装饰器</b>的成本与能力。
 *
 * <p><b>边界判定</b>：框架 core 里<b>一个 Model 装饰器基类都没有</b>
 * （没有 RetryModel / FallbackModel / DelegatingModel / AttributedModel）。
 * 能拿到的只有 {@code Model} 这个接口 —— 6 个方法里<b>只有 2 个是抽象的</b>。
 *
 * <p>→ <b>写一个装饰器只要实现 2 个方法</b>，成本极低；
 * → 但<b>命名、计数、归因口径要自己想</b>，框架不管。
 *
 * <p>对照 customer_work：那边自建了 5 个装饰器
 * （热替换 / 归因 / 多候选主备+熔断 / 重试 / 分级路由），
 * 而框架实际只提供了「单候选 fallback」与「重试+超时」两件。
 */
class SwappableModelTest {

    /** 只会数自己被调了几次的假模型。 */
    private static final class CountingModel implements Model {
        private final String name;
        private final AtomicInteger calls = new AtomicInteger();

        CountingModel(String name) {
            this.name = name;
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools,
                                         GenerateOptions options) {
            calls.incrementAndGet();
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text(name).build()))
                    .build());
        }

        @Override
        public String getModelName() {
            return name;
        }
    }

    @Test
    @DisplayName("站2-11：热替换生效 —— 换掉底层模型后，后续调用打到新模型")
    void swapRedirectsSubsequentCalls() {
        CountingModel first = new CountingModel("model-A");
        CountingModel second = new CountingModel("model-B");
        SwappableModel swappable = new SwappableModel(first);

        swappable.stream(List.of(), List.of(), null).blockLast();
        swappable.swap(second);
        swappable.stream(List.of(), List.of(), null).blockLast();

        assertEquals(1, first.calls.get(), "替换前应打到 model-A");
        assertEquals(1, second.calls.get(), "替换后应打到 model-B");
        assertEquals("model-B", swappable.getModelName(),
                "getModelName 应反映当前生效的模型（框架的 fallback 包装器就是靠它认人的）");
    }

    @Test
    @DisplayName("站2-12：归因计数与命中模型名 —— 这两样框架不提供，自己记")
    void attributionIsSelfMade() {
        CountingModel first = new CountingModel("model-A");
        CountingModel second = new CountingModel("model-B");
        SwappableModel swappable = new SwappableModel(first);

        swappable.stream(List.of(), List.of(), null).blockLast();
        assertEquals(1, swappable.calls(), "调用次数应被记下来");
        assertEquals("model-A", swappable.lastAttributedModel(), "命中的模型名应被记下来");

        swappable.swap(second);
        swappable.stream(List.of(), List.of(), null).blockLast();
        assertEquals(2, swappable.calls(), "调用次数应累计");
        assertEquals("model-B", swappable.lastAttributedModel(), "归因应更新到新模型");
    }

    @Test
    @DisplayName("站2-13：装饰器只实现 2 个抽象方法就够 —— 框架的 Model 接口对装饰器很友好")
    void decoratorNeedsOnlyTwoMethods() {
        // CountingModel 只实现了 stream 与 getModelName（getContextWindowSize 等 4 个都是 default）
        Model model = new CountingModel("only-two-methods");
        assertEquals("only-two-methods", model.getModelName());
    }
}
