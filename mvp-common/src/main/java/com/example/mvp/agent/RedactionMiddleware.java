package com.example.mvp.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <h3>站 4 挖出的真实漏洞：治理能力只接在了一条路上</h3>
 * <p>本类<b>初版只改写 {@link AgentResultEvent}</b>（最终答复），完全没碰
 * {@link TextBlockDeltaEvent}（流式正文分片）。结果是：</p>
 * <ul>
 *   <li>同步出口 {@code POST /api/chat/sync} → 走的是 {@code AgentResultEvent} → <b>已脱敏</b>；</li>
 *   <li>流式出口（WebSocket {@code chat_chunk} / SSE {@code chat_stream}）→ 走的是 delta 分片
 *       → <b>原文 13800138000 直接吐给用户</b>。</li>
 * </ul>
 * <p>也就是说：<b>同一套治理，在两条出口上行为不一致</b>。这不是「流式脱敏比较麻烦所以没做」，
 * 而是典型的「能力只接在了被测试的那条路上」——
 * <b>站 3 的测试全部走同步出口（{@code agent.call()}），所以这个洞从没被测出来。</b>
 * 站 4 把它挖出来了。</p>
 *
 * <h3>修法：按 text block 缓冲，块结束时整段脱敏后放唯一安全 delta</h3>
 * <p>不能「每来一个 delta 就脱敏一次然后放出去」—— 手机号是 11 位数字，
 * <b>极可能被切在两个 delta 里</b>（探针实测框架就是逐字吐 delta），
 * 任何单片都匹配不上 {@code 1[3-9]\d{9}}，于是原文原样出站。
 * 也没法「保留尾部 N 个字符等一等」—— 正则长度无界，
 * <b>任何有限窗口都无法证明某个前缀永远不会在后续 chunk 里拼成手机号</b>。</p>
 * <p>所以本实现选择<b>隐私保证优先于逐字延迟</b>：</p>
 * <ol>
 *   <li>{@link TextBlockDeltaEvent} → 按块缓冲，<b>不放行</b>；</li>
 *   <li>{@link TextBlockEndEvent} → 整段脱敏，放出<b>唯一一个</b>安全 delta，再放块结束事件；</li>
 *   <li>流结束兜底 {@link #flushRemaining} → 上游漏发块结束时仍要把已缓冲内容脱敏后放出，
 *       <b>绝不吐原文</b>（fail-closed）。</li>
 * </ol>
 * <p>代价是<b>打字机效果消失</b>（正文整块到达）—— 这是刻意的取舍，不是缺陷。
 * 对照 customer_work 的 {@code MaskingMiddleware}：同一套语义。</p>
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

    /** 单个文本块的缓冲上限：防止异常模型生成无限正文占满堆；超限块 fail-closed，不释放已缓存原文。 */
    private static final int MAX_BLOCK_CHARS = 1_048_576;

    private final int order;

    /** 被改写过的答复条数（真机观测用）。 */
    private final List<String> maskedReplies = java.util.Collections.synchronizedList(new ArrayList<>());

    /** 被脱敏过的流式块数（真机观测用）。 */
    private final List<String> maskedBlocks = java.util.Collections.synchronizedList(new ArrayList<>());

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
        //
        // 缓冲状态 StreamState 也必须在这里新建：
        // 它是【一次调用一份】，不是【一个中间件一份】。
        // 共享一份会让并发调用互相偷对方的正文。
        return Flux.defer(() -> {
            StreamState state = new StreamState();
            return next.apply(input)
                    .concatMap(event -> redactEvent(event, state))
                    // 流末兜底：上游异常地没发块结束时，已缓冲的正文仍必须脱敏后放出。
                    .concatWith(Flux.defer(() -> flushRemaining(state)));
        });
    }

    /**
     * 改写出站事件：<b>最终答复</b>与<b>流式正文分片</b>两条路都管。
     *
     * <p>这是站 4 修掉的漏洞所在 —— 初版只管第一条路，
     * 于是流式出口把未脱敏的手机号原样吐给了用户。</p>
     */
    private Flux<AgentEvent> redactEvent(AgentEvent event, StreamState state) {
        try {
            // —— 流式正文分片：缓冲，不放行 ——
            if (event instanceof TextBlockDeltaEvent delta) {
                TextBlockBuffer buffer = state.blocks.computeIfAbsent(
                        blockKey(delta.getSource(), delta.getBlockId()),
                        key -> new TextBlockBuffer(delta.getSource(), delta.getReplyId(), delta.getBlockId()));
                if (!buffer.overflowed) {
                    if (buffer.text.length() + delta.getDelta().length() > MAX_BLOCK_CHARS) {
                        buffer.overflowed = true;
                        buffer.text.setLength(0);
                        log.error("[redaction] outbound text block exceeds limit and was dropped, blockId={}",
                                delta.getBlockId());
                    } else {
                        buffer.text.append(delta.getDelta());
                    }
                }
                return Flux.empty();
            }

            // —— 文本块结束：整段脱敏后放出唯一安全 delta ——
            if (event instanceof TextBlockEndEvent end) {
                TextBlockBuffer buffer = state.blocks.remove(blockKey(end.getSource(), end.getBlockId()));
                return buffer == null ? Flux.just(end) : flushBlock(buffer, end);
            }

            // —— 最终答复：改写结果消息 ——
            if (event instanceof AgentResultEvent result) {
                Msg original = result.getResult();
                Msg masked = redact(original);
                if (masked == null) {
                    return Flux.just(event);
                }
                maskedReplies.add(masked.getTextContent());
                log.info("[redaction] outbound reply redacted");
                return Flux.just(new AgentResultEvent(masked));
            }

            return Flux.just(event);
        } catch (Exception e) {
            // 流式正文的原文不能因脱敏器异常而旁路出站（fail-closed）。
            log.error("[redaction] outbound stream masking failed, event dropped, code=REDACT_STREAM_ERROR", e);
            return event instanceof TextBlockDeltaEvent ? Flux.empty() : Flux.just(event);
        }
    }

    /** 块结束时把缓冲整段脱敏，放出一个安全 delta + 原块结束事件。 */
    private Flux<AgentEvent> flushBlock(TextBlockBuffer buffer, TextBlockEndEvent end) {
        if (buffer.overflowed || buffer.text.length() == 0) {
            return Flux.just(end);
        }
        String original = buffer.text.toString();
        String masked = mask(original);
        if (!original.equals(masked)) {
            maskedBlocks.add(masked);
            log.info("[redaction] outbound stream block redacted, blockId={}", buffer.blockId);
        }
        return Flux.just(buffer.delta(masked), end);
    }

    /**
     * 流结束兜底：上游漏发 {@code TextBlockEndEvent} 时，把已缓冲内容脱敏后放出。
     * <b>fail-closed：绝不吐原文，也绝不静默吞掉尾巴。</b>
     */
    private Flux<AgentEvent> flushRemaining(StreamState state) {
        List<AgentEvent> remaining = new ArrayList<>();
        for (TextBlockBuffer buffer : state.blocks.values()) {
            if (buffer.overflowed || buffer.text.length() == 0) {
                continue;
            }
            String masked = mask(buffer.text.toString());
            if (!masked.equals(buffer.text.toString())) {
                maskedBlocks.add(masked);
            }
            remaining.add(buffer.delta(masked));
        }
        state.blocks.clear();
        return Flux.fromIterable(remaining);
    }

    /**
     * 文本块在本次调用里的缓冲键。
     *
     * <p>框架给每个文本块的 blockId 都是常量 {@code "text"}，
     * 所以只按 blockId 分会把不同来源的正文混进同一份缓冲。
     * 子智能体转发进来的事件带 {@code getSource()}，按来源另起一份。</p>
     */
    private static String blockKey(String source, String blockId) {
        return source == null ? blockId : source + "|" + blockId;
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

    /** 被脱敏过的流式正文块（真机观测用）。 */
    public List<String> maskedBlocks() {
        return List.copyOf(maskedBlocks);
    }

    /** 一次调用一份的缓冲状态 —— 不是中间件级共享。 */
    private static final class StreamState {
        private final Map<String, TextBlockBuffer> blocks = new LinkedHashMap<>();
    }

    /** 一个文本块的整块缓冲。 */
    private static final class TextBlockBuffer {
        private final String source;
        private final String replyId;
        private final String blockId;
        private final StringBuilder text = new StringBuilder();
        private boolean overflowed;

        private TextBlockBuffer(String source, String replyId, String blockId) {
            this.source = source;
            this.replyId = replyId;
            this.blockId = blockId;
        }

        /** 放出的安全增量：来源与原事件一致（三参构造不带 source，改写一次就「洗白」成主智能体的正文）。 */
        private TextBlockDeltaEvent delta(String masked) {
            TextBlockDeltaEvent delta = new TextBlockDeltaEvent(replyId, blockId, masked);
            delta.withSource(source);
            return delta;
        }
    }
}
