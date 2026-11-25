package com.example.mvp.app.ws;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站 1-B：WebSocket 帧协议（统一信封 {@code {"type": "...", "data": ...}}）。
 *
 * <p><b>对照 customer_work</b>：那边是 {@code infra/ws/WsFrame}，帧类型有 10 种
 * （chat / chat_accepted / chat_chunk / chat_done / ticket_event / ticket_new / system / error / ping / pong）。
 * 本 demo 只保留对话主链路的 6 种 —— <b>刻意不抄工单类帧</b>，
 * 因为工单是业务域（站 6 的事），不是接入层机制。
 *
 * <p><b>为什么帧类型是字符串常量而不是枚举</b>：这些 type 是<b>前后端契约</b>，
 * 拼错一个字母前端就静默收不到消息。集中定义 + 静态工厂构帧，
 * 避免各处手拼字面量 —— 这是 customer_work 的做法，这里照抄。
 */
public record WsFrame(String type, Object data) {

    /** 客户端发来的对话请求。 */
    public static final String TYPE_CHAT = "chat";
    /** 服务端已受理（不代表 AI 已完成，只代表已接收、已去重）。 */
    public static final String TYPE_CHAT_ACCEPTED = "chat_accepted";
    /** AI 流式正文增量。 */
    public static final String TYPE_CHAT_CHUNK = "chat_chunk";
    /** AI 流式结束。 */
    public static final String TYPE_CHAT_DONE = "chat_done";
    /** 错误帧。 */
    public static final String TYPE_ERROR = "error";
    /** 心跳请求 / 响应。 */
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";

    public static final String KEY_TYPE = "type";
    public static final String KEY_DATA = "data";
    public static final String KEY_CONTENT = "content";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_SESSION_ID = "sessionId";
    public static final String KEY_CLIENT_MSG_ID = "clientMsgId";
    public static final String KEY_ACCEPTANCE = "acceptance";
    /** 受理状态（ACCEPTED / DUPLICATE）。 */
    public static final String KEY_STATUS = "status";
    public static final String KEY_CODE = "code";

    /** 受理状态：已受理。 */
    public static final String ACCEPTANCE_ACCEPTED = "ACCEPTED";
    /** 受理状态：重复投递（幂等命中，返回首次的受理号）。 */
    public static final String ACCEPTANCE_DUPLICATE = "DUPLICATE";
    /** 受理状态：被拒绝（如会话归属不匹配）。 */
    public static final String ACCEPTANCE_REJECTED = "REJECTED";

    /** 对话请求帧。 */
    public static WsFrame chat(Object data) {
        return new WsFrame(TYPE_CHAT, data);
    }

    /**
     * 受理回执。
     *
     * @param status {@link #ACCEPTANCE_ACCEPTED} 首次受理 /
     *               {@link #ACCEPTANCE_DUPLICATE} 重复投递（受理号与会话号都是首次的）
     */
    public static WsFrame chatAccepted(String clientMsgId, String acceptanceId, String sessionId, String status) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        data.put(KEY_ACCEPTANCE, acceptanceId);
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_STATUS, status);
        return new WsFrame(TYPE_CHAT_ACCEPTED, data);
    }

    /** 正文增量帧。 */
    public static WsFrame chatChunk(String content, String sessionId, String clientMsgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CONTENT, content);
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        return new WsFrame(TYPE_CHAT_CHUNK, data);
    }

    /** 流式结束帧。 */
    public static WsFrame chatDone(String sessionId, String clientMsgId, String fullText) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        data.put(KEY_CONTENT, fullText);
        return new WsFrame(TYPE_CHAT_DONE, data);
    }

    /** 错误帧。 */
    public static WsFrame error(String code, String message, String sessionId, String clientMsgId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(KEY_CODE, code);
        data.put(KEY_MESSAGE, message);
        data.put(KEY_SESSION_ID, sessionId);
        data.put(KEY_CLIENT_MSG_ID, clientMsgId);
        return new WsFrame(TYPE_ERROR, data);
    }

    /** 心跳响应。 */
    public static WsFrame pong() {
        return new WsFrame(TYPE_PONG, null);
    }
}
