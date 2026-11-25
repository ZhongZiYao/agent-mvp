package com.example.mvp.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 站 3：自建治理中间件 —— <b>出站脱敏</b>。
 *
 * <p><b>它在链上的位置</b>：{@link MvpMiddlewareOrders#REDACTION}（125）——
 * 比审计（150）<b>更内</b>，比输入拦截（120）更外。</p>
 *
 * <p><b>为什么这个位置是对的（也是站 3 的核心证据）</b>：</p>
 * <ul>
 *   <li>出站方向上，<b>内层先执行</b>。所以脱敏先改写答复，审计后看到改写后的版本 ——
 *       审计留痕里落的是脱敏后的手机号，不是原文。
 *       <b>把这两个 order 值对调，审计留痕里就会出现未脱敏的手机号</b>，
 *       而系统<b>不会报任何错</b>。这就是为什么顺序必须写成契约并用测试钉住。</li>
 *   <li>比输入拦截更外：拦截件自己生成的兜底答复也要过脱敏。</li>
 * </ul>
 *
 * <p><b>改写答复消息的正确姿势</b>：用 {@link Msg#withContent(List)}，
 * <b>不要</b>用 {@code Msg.builder()} 重建。后者会把 {@code id}、{@code usage}、
 * {@code timestamp} 丢掉，元数据也得手工补 —— 而框架把「结束原因」就存在元数据里，
 * 重建会静默退回"正常结束"。{@code withContent} 只换 content，其余原样保留。</p>
 */
public class RedactionMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(RedactionMiddleware.class);

    /** 中国大陆手机号：11 位，1 开头且第二位 3-9。两侧不允许再接数字，避免误伤长数字串。 */
    private static final Pattern MOBILE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");

    private final int order;

    /** 被改写过的答复条数（真机观测用）。 */
    private final List<String> maskedReplies = java.util.Collections.synchronizedList(new ArrayList<>());

    public RedactionMiddleware() {
        this(MvpMiddlewareOrders.REDACTION);
    }

    public RedactionMiddleware(int order) {
        this.order = order;
    }

    @Override
    public int order() {
        return order;
    }

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    Function<AgentInput, Flux<AgentEvent>> next) {
        // Flux.defer：中间件的钩子是在<b>订阅时</b>才执行的，不是注册时。
        // 写成 eager 的写法会在装配阶段就执行一遍 —— 那时装配还没完成。
        return Flux.defer(() -> next.apply(input)
                .concatMap(this::redactEvent));
    }

    /**
     * 改写出站事件里的最终答复。
     *
     * <p>只改 {@link AgentResultEvent}：那才是框架最终交给调用方的结果消息。
     * 流式的 {@code TextBlockDeltaEvent} 分片本 demo 不处理 ——
     * 「按块缓冲、在块结束时整体放出」是流式脱敏的额外复杂度，
     * customer_work 那边为此专门写了 {@code ForwardedText} 与失败即丢弃的策略。</p>
     */
    private Flux<AgentEvent> redactEvent(AgentEvent event) {
        if (!(event instanceof AgentResultEvent result)) {
            return Flux.just(event);
        }
        Msg original = result.getResult();
        Msg masked = redact(original);
        if (masked == null) {
            return Flux.just(event);
        }
        maskedReplies.add(masked.getTextContent());
        log.info("[redaction] outbound reply redacted");
        return Flux.just(new AgentResultEvent(masked));
    }

    /** 返回脱敏后的新消息；无需改动则返回 {@code null}（调用方据此保留原事件）。 */
    private Msg redact(Msg original) {
        if (original == null) {
            return null;
        }
        List<ContentBlock> blocks = original.getContent();
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        boolean changed = false;
        List<ContentBlock> rebuilt = new ArrayList<>(blocks.size());
        for (ContentBlock block : blocks) {
            if (block instanceof TextBlock text) {
                String masked = mask(text.getText());
                if (!masked.equals(text.getText())) {
                    rebuilt.add(TextBlock.builder().text(masked).build());
                    changed = true;
                    continue;
                }
            }
            rebuilt.add(block);
        }
        if (!changed) {
            return null;
        }
        // withContent：只换 content，id / name / role / metadata / timestamp / usage 全部保留
        return original.withContent(rebuilt);
    }

    /** 把手机号替换成前三后四的掩码。 */
    static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = MOBILE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        matcher.reset();
        StringBuilder out = new StringBuilder(text.length());
        while (matcher.find()) {
            String phone = matcher.group();
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    phone.substring(0, 3) + "****" + phone.substring(7)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 被脱敏过的答复（真机观测用）。 */
    public List<String> maskedReplies() {
        return List.copyOf(maskedReplies);
    }
}
