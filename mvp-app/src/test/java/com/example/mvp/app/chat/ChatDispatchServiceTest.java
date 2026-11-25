package com.example.mvp.app.chat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 1-C 测试：受理、幂等、会话归属。
 *
 * <p><b>幂等为什么必须做</b>：customer_work 的 {@code WsFrame} 注释说得很直白 ——
 * 「同一句话到两次就是两次完整的对话轮——双份 token，还可能提交两张退款工单」。
 * 幂等键 = {@code userId + clientMsgId}，重复投递返回<b>同一个受理号</b>，
 * 且 <b>不再触发模型调用</b>。
 */
class ChatDispatchServiceTest {

    private ChatDispatchService service;

    @BeforeEach
    void setUp() {
        service = new ChatDispatchService();
    }

    @Test
    @DisplayName("站1-C1：首次投递被受理，且分配了受理号与会话号")
    void firstDeliveryIsAccepted() {
        ChatDispatchService.Acceptance acc = service.accept("u1001", "cm-1", null, "你好");

        assertEquals(ChatDispatchService.WsFrameAcceptance.ACCEPTED, acc.status());
        assertTrue(acc.shouldDispatch(), "首次投递应当触发模型调用");
        assertTrue(acc.acceptanceId() != null && !acc.acceptanceId().isBlank());
        assertTrue(acc.sessionId().startsWith("u1001-"),
                "会话号必须带用户前缀，实际是: " + acc.sessionId());
    }

    @Test
    @DisplayName("站1-C2：同一 clientMsgId 重复投递 → 同一受理号、不再触发模型")
    void duplicateDeliveryReturnsSameAcceptance() {
        ChatDispatchService.Acceptance first = service.accept("u1001", "cm-1", null, "你好");
        ChatDispatchService.Acceptance second = service.accept("u1001", "cm-1", null, "你好");

        assertEquals(ChatDispatchService.WsFrameAcceptance.DUPLICATE, second.status());
        assertEquals(first.acceptanceId(), second.acceptanceId(),
                "重复投递必须返回首次的受理号，客户端据此核对");
        assertFalse(second.shouldDispatch(), "重复投递不得再触发模型调用（省 token，也避免重复副作用）");
        assertEquals(1, service.acceptedCount());
    }

    @Test
    @DisplayName("站1-C2b：幂等命中时复用首次的会话号（重发不能落到别的会话）")
    void duplicateReusesSameSessionId() {
        ChatDispatchService.Acceptance first = service.accept("u1001", "cm-1", null, "你好");
        ChatDispatchService.Acceptance second = service.accept("u1001", "cm-1", null, "你好");

        assertEquals(first.sessionId(), second.sessionId(),
                "重发必须落回同一个会话 —— 否则前端看到同一个 clientMsgId 却是两个 sessionId，历史对不上");
    }

    @Test
    @DisplayName("站1-C3：不同 clientMsgId 是两条独立投递")
    void differentClientMsgIdIsNewDelivery() {
        ChatDispatchService.Acceptance a = service.accept("u1001", "cm-1", null, "你好");
        ChatDispatchService.Acceptance b = service.accept("u1001", "cm-2", null, "你好");

        assertNotEquals(a.acceptanceId(), b.acceptanceId());
        assertTrue(b.shouldDispatch());
        assertEquals(2, service.acceptedCount());
    }

    @Test
    @DisplayName("站1-C4：幂等键按用户隔离 —— 别人的同 clientMsgId 不受影响")
    void idempotencyIsScopedPerUser() {
        service.accept("u1001", "cm-1", null, "你好");
        ChatDispatchService.Acceptance other = service.accept("u1002", "cm-1", null, "你好");

        assertTrue(other.shouldDispatch(),
                "幂等键含 userId —— 两个用户用同一个 clientMsgId 不该互相吞消息");
    }

    @Test
    @DisplayName("站1-C5：会话号不属于当前用户 → 拒绝受理")
    void foreignSessionIsRejected() {
        ChatDispatchService.Acceptance acc = service.accept("u1001", "cm-9", "u2002-abc", "你好");

        assertEquals(ChatDispatchService.WsFrameAcceptance.REJECTED, acc.status());
        assertFalse(acc.shouldDispatch());
        assertEquals(0, service.acceptedCount(), "被拒绝的投递不应进入幂等表");
    }

    @Test
    @DisplayName("站1-C6：空消息 → 拒绝受理")
    void emptyMessageIsRejected() {
        ChatDispatchService.Acceptance acc = service.accept("u1001", "cm-10", null, "   ");

        assertEquals(ChatDispatchService.WsFrameAcceptance.REJECTED, acc.status());
    }
}
