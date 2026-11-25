package com.example.mvp.agent;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.shutdown.GracefulShutdownMiddleware;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 3：<b>中间件链的执行顺序 —— 运行时实证</b>。
 *
 * <p><b>这一站最不能靠注释的地方就是 order() 的语义。</b>
 * 「数值越大越靠外」这句话如果理解反了，整套治理顺序就全反了：
 * 审计会排到脱敏里面、留痕里出现未脱敏的手机号，
 * 而<b>系统不会报任何错</b>。所以这里用运行时行为把它钉死。</p>
 *
 * <p><b>钉死的方式</b>：注册几个只做「记账」的探针中间件，分别给不同的 order，
 * 让它们记录自己<b>入站</b>与<b>出站</b>的时刻（记的是 order 值本身），
 * 然后断言：</p>
 * <ul>
 *   <li>入站顺序 = order <b>降序</b>（大的先执行 ⇒ 更靠外）；</li>
 *   <li>出站顺序 = 入站的<b>逆序</b>（内层先收尾 ⇒ 大的后收尾 ⇒ 更靠外）。</li>
 * </ul>
 */
class MiddlewareChainOrderTest {

    /** 只做记账的探针中间件：记录入站与出站，记的是 order 值。 */
    private static final class ProbeMiddleware implements MiddlewareBase {

        private final int order;
        private final List<String> trace;

        ProbeMiddleware(int order, List<String> trace) {
            this.order = order;
            this.trace = trace;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                        Function<AgentInput, Flux<AgentEvent>> next) {
            // defer：钩子体在【订阅时】执行，不是在装配时。
            return Flux.defer(() -> {
                trace.add("in:" + order);
                return next.apply(input)
                        .doFinally(signal -> trace.add("out:" + order));
            });
        }
    }

    /** 探针链：Agent + 记账本。 */
    private record Probe(ReActAgent agent, List<String> trace) {
    }

    /** 造一条探针链。 */
    private static Probe probeChain(int... orders) {
        List<String> trace = new ArrayList<>();
        MvpTestFixtures.CapturingModel model = new MvpTestFixtures.CapturingModel();
        ReActAgent.Builder builder = ReActAgent.builder()
                .name("chain-probe")
                .description("链序探针")
                .sysPrompt("test")
                .model(model)
                .maxIters(1);
        for (int order : orders) {
            builder.middleware(new ProbeMiddleware(order, trace));
        }
        return new Probe(builder.build(), trace);
    }

    private static void run(Probe probe) {
        probe.agent().call("你好",
                RuntimeContext.builder().userId("u1").sessionId("s1").build()).block();
    }

    private static List<String> inbound(List<String> trace) {
        return trace.stream().filter(s -> s.startsWith("in:")).toList();
    }

    private static List<String> outbound(List<String> trace) {
        return trace.stream().filter(s -> s.startsWith("out:")).toList();
    }

    @Test
    @DisplayName("站3-1：入站顺序 = order 降序 —— 数值越大越靠外，先执行")
    void inboundOrderIsDescending() {
        // 故意乱序注册：200 / 50 / 120
        Probe probe = probeChain(200, 50, 120);
        run(probe);

        assertEquals(List.of("in:200", "in:120", "in:50"), inbound(probe.trace()),
                "入站必须是 order 降序（200 最外先进，50 最后进），实际: " + probe.trace());
    }

    @Test
    @DisplayName("站3-2：出站顺序 = 入站的逆序 —— 内层先收尾，外层最后收尾")
    void outboundOrderIsReverseOfInbound() {
        Probe probe = probeChain(200, 50, 120);
        run(probe);

        assertEquals(List.of("out:50", "out:120", "out:200"), outbound(probe.trace()),
                "出站必须是入站的逆序，实际: " + probe.trace());
    }

    @Test
    @DisplayName("站3-3：注册顺序不影响执行顺序 —— 框架在 build() 里按 order 降序重排")
    void registrationOrderDoesNotMatter() {
        Probe first = probeChain(200, 50, 120);
        Probe second = probeChain(50, 120, 200);
        run(first);
        run(second);

        // 两条链的 order 集合相同、注册顺序不同 —— 生效顺序必须一致。
        assertEquals(inbound(first.trace()), inbound(second.trace()),
                "注册顺序换了，生效顺序不该变（框架在 build() 里按 order 降序重排），实际: "
                        + first.trace() + " vs " + second.trace());
    }

    @Test
    @DisplayName("站3-4：生效链里框架自动注入的 GracefulShutdownMiddleware 硬编码在链首，不参与 order 协商")
    void frameworkInjectedMiddlewareIsHardWiredAtChainHead() {
        Probe probe = probeChain(999, 50);
        List<MiddlewareBase> effective = probe.agent().getMiddlewares();

        assertInstanceOf(GracefulShutdownMiddleware.class, effective.get(0),
                "链首必须是框架自动注入的 GracefulShutdownMiddleware —— 它在 ReActAgent 构造函数里直接 add，"
                        + "排序发生在构造函数之前，所以任何 order 都压不过它");
        assertEquals(1, effective.get(0).order(),
                "它的 order 是框架默认值 1 —— 正因为不参与协商，才会带着 1 排在 order=999 的前面");
        assertTrue(effective.size() > 1 && effective.get(1).order() == 999,
                "紧挨着链首的应当是 order 最大的那个，实际: " + effective.get(1).order());
    }

    @Test
    @DisplayName("站3-5：生效链（除链首硬编码项外）严格按 order 降序")
    void effectiveChainIsDescendingByOrder() {
        Probe probe = probeChain(200, 50, 120, 77);
        List<MiddlewareBase> effective = probe.agent().getMiddlewares();

        // 第 0 项是框架硬塞的链首（order=1），从第 1 项开始才谈得上「按 order 降序」
        for (int i = 2; i < effective.size(); i++) {
            int previous = effective.get(i - 1).order();
            int current = effective.get(i).order();
            assertTrue(previous >= current,
                    "生效链必须按 order 降序，第 " + i + " 项 order=" + current
                            + " 反而大于前一项 " + previous + "，实际链: " + effective);
        }
    }
}
