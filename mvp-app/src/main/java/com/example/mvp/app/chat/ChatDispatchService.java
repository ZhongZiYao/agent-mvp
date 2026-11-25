package com.example.mvp.app.chat;

import com.example.mvp.app.SessionIds;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 站 1-C：受理 + 幂等 + 会话归属。
 *
 * <p><b>框架给 0 个东西</b>。AgentScope core 不知道「消息」是什么、
 * 不知道「重复投递」是什么、也不知道「这个会话归谁」。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code ChatDispatchService#onUserMessage}，
 * 核心是「受理号 + 幂等」。本 demo 把同一套语义做小：
 * <ul>
 *   <li><b>受理号</b>：每条被接收的消息分配一个确定性 ID，客户端拿它去核对「我那条到底进没进」；</li>
 *   <li><b>幂等</b>：幂等键 = {@code userId + clientMsgId}，重复投递返回<b>同一个受理号</b>，
 *       不再触发第二次模型调用；</li>
 *   <li><b>归属</b>：会话号必须以 {@code userId + "-"} 开头，否则拒绝。</li>
 * </ul>
 *
 * <p><b>为什么幂等是「必须」而不是「优化」</b>——customer_work 的 {@code WsFrame} 注释说得很清楚：
 * <blockquote>
 * WebSocket 在移动网络下断连是常态、客户端重发是必须的，
 * 而同一句话到两次就是两次完整的对话轮——双份 token，还可能提交两张退款工单。
 * </blockquote>
 * 这条在 demo 里同样成立，而且<b>危害与金额成正比</b>：一次退款工具调用 = 一次真实写操作。
 */
@Service
public class ChatDispatchService {

    /** 幂等表：userId + "|" + clientMsgId -> 首次受理的记录。 */
    private final Map<String, AcceptedRecord> accepted = new ConcurrentHashMap<>();

    private final AtomicLong acceptanceSeq = new AtomicLong();

    /**
     * 受理一条客户端消息。
     *
     * @param userId       已鉴权的用户 id
     * @param clientMsgId  客户端消息标识（重发时沿用同一个值）
     * @param sessionId    会话号，可为 null（则新建）
     * @param message      用户消息正文
     * @return 受理结果
     */
    public Acceptance accept(String userId, String clientMsgId, String sessionId, String message) {
        if (message == null || message.isBlank()) {
            return new Acceptance(null, null, WsFrameAcceptance.REJECTED, "消息正文为空");
        }

        String effectiveSessionId;
        try {
            effectiveSessionId = SessionIds.resolve(sessionId, userId);
        } catch (IllegalArgumentException e) {
            return new Acceptance(null, sessionId, WsFrameAcceptance.REJECTED, e.getMessage());
        }

        // 没有 clientMsgId 的老客户端按首次处理（去重是增益，不是安全边界）
        if (clientMsgId == null || clientMsgId.isBlank()) {
            return new Acceptance(newAcceptanceId(), effectiveSessionId, WsFrameAcceptance.ACCEPTED, null);
        }

        String idempotencyKey = userId + "|" + clientMsgId;
        AcceptedRecord existing = accepted.get(idempotencyKey);
        if (existing != null) {
            // 幂等命中：返回首次的受理号 <b>与首次的会话号</b>，调用方据此跳过模型调用。
            // ⚠️ 会话号必须一起复用 —— 否则重发会落到另一个会话上，
            // 前端看到「同一个 clientMsgId 却是另一个 sessionId」，历史就对不上了。
            return new Acceptance(existing.acceptanceId(), existing.sessionId(),
                    WsFrameAcceptance.DUPLICATE, null);
        }

        AcceptedRecord record = new AcceptedRecord(newAcceptanceId(), effectiveSessionId);
        AcceptedRecord previous = accepted.putIfAbsent(idempotencyKey, record);
        if (previous != null) {
            // 并发下另一个线程先写入了：以先写入的为准
            return new Acceptance(previous.acceptanceId(), previous.sessionId(),
                    WsFrameAcceptance.DUPLICATE, null);
        }
        return new Acceptance(record.acceptanceId(), record.sessionId(), WsFrameAcceptance.ACCEPTED, null);
    }

    /** 首次受理的记录：受理号 + 会话号（两者都要在重发时原样复用）。 */
    private record AcceptedRecord(String acceptanceId, String sessionId) {
    }

    /** 已受理条数（测试用）。 */
    public int acceptedCount() {
        return accepted.size();
    }

    private String newAcceptanceId() {
        return "ACC-" + acceptanceSeq.incrementAndGet();
    }

    /** 受理结果。 */
    public record Acceptance(String acceptanceId, String sessionId, String status, String reason) {

        /** 是否需要真正调用模型（受理成功且非重复）。 */
        public boolean shouldDispatch() {
            return WsFrameAcceptance.ACCEPTED.equals(status);
        }
    }

    /** 受理状态常量（与帧协议里的 ACCEPTANCE_* 对齐）。 */
    public static final class WsFrameAcceptance {
        public static final String ACCEPTED = "ACCEPTED";
        public static final String DUPLICATE = "DUPLICATE";
        public static final String REJECTED = "REJECTED";

        private WsFrameAcceptance() {
        }
    }
}
