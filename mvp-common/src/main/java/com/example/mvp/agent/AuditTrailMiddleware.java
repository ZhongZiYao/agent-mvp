package com.example.mvp.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 站 3：自建治理中间件 —— <b>审计留痕</b>。
 *
 * <p><b>它在链上的位置</b>：{@link MvpMiddlewareOrders#AUDIT}（150）——
 * 本 demo 里<b>最外</b>的自建治理件（框架自动注入的
 * {@code GracefulShutdownMiddleware} 除外，它硬编码在链首且不参与 order 协商）。</p>
 *
 * <p><b>为什么审计必须比脱敏更外</b>：出站方向内层先执行。
 * 脱敏（125）在内、审计（150）在外 ⇒ 脱敏先改写答复，审计后看到的是<b>改写后</b>的版本，
 * 于是留痕里落的是掩码后的手机号。</p>
 *
 * <p><b>把两个 order 值对调会怎样</b>：审计变成内层，脱敏变成外层 ——
 * 脱敏会把最终答复改写掉，而审计看到的是<b>改写前</b>的原文，
 * 留痕里就出现了未脱敏的手机号。<b>系统不会报任何错</b>，只是某天有人在审计表里
 * 看到了一串真实手机号。这正是「顺序契约」必须写成常量并用测试钉住的全部理由。</p>
 */
public class AuditTrailMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(AuditTrailMiddleware.class);

    private final int order;

    /** 已留痕的最终答复（真机观测用，也是站 3 顺序语义的证据）。 */
    private final List<String> audited = java.util.Collections.synchronizedList(new ArrayList<>());

    public AuditTrailMiddleware() {
        this(MvpMiddlewareOrders.AUDIT);
    }

    public AuditTrailMiddleware(int order) {
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(() -> next.apply(input)
                .concatMap(event -> {
                    if (event instanceof AgentResultEvent result && result.getResult() != null) {
                        String text = result.getResult().getTextContent();
                        audited.add(text);
                        // 演示里把留痕内容直接打出来 —— 真机证据就在这行日志里：
                        // 它显示的必须是【脱敏后】的手机号。
                        // 生产实现应把这条写进审计表，而不是打到日志。
                        log.info("[audit] final reply recorded: {}", text);
                    }
                    return Flux.just(event);
                }));
    }

    /** 已留痕的最终答复。 */
    public List<String> audited() {
        return List.copyOf(audited);
    }
}
