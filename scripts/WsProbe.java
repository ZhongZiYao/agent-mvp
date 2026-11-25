import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.CompletionStage;

/**
 * 站 1 的 WebSocket 探测客户端（零依赖，用 JDK 自带的 java.net.http.WebSocket）。
 *
 * <p>用法：
 * <pre>
 *   java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 scripts\WsProbe.java &lt;token&gt; [消息]
 * </pre>
 *
 * <p>它做的事：连上 {@code ws://localhost:8088/ws/user?token=...}，
 * 发一条 {@code chat} 帧，然后把收到的所有帧打出来。
 * 用来肉眼确认「握手鉴权 → 受理回执 → 正文增量 → 结束帧」这条链是否成立。
 */
public class WsProbe {

    public static void main(String[] args) throws Exception {
        String token = args.length > 0 ? args[0] : "";
        String message = args.length > 1 ? args[1] : "我的订单 SO202611250001 到哪了？";
        String base = System.getProperty("ws.base", "ws://localhost:8088/ws/user");

        System.out.println("[PROBE] 连接 " + base + "（token 长度 " + token.length() + "）");

        HttpClient client = HttpClient.newHttpClient();
        WebSocket ws = client.newWebSocketBuilder()
                .buildAsync(URI.create(base + "?token=" + token), new Listener())
                .join();

        String frame = "{\"type\":\"chat\",\"data\":{\"clientMsgId\":\"cm-probe-1\",\"message\":\""
                + message + "\"}}";
        System.out.println("[PROBE] 发送 " + frame);
        ws.sendText(frame, true);

        // 重复投递同一条（验证幂等：应回受理号但不再触发模型）
        Thread.sleep(1500);
        System.out.println("[PROBE] 重复投递同 clientMsgId（验证幂等）");
        ws.sendText(frame, true);

        Thread.sleep(6000);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "probe done").join();
        System.out.println("[PROBE] 结束");
    }

    static class Listener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            System.out.println("[PROBE] 已连接");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("[RECV] " + data);
            webSocket.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("[ERR ] " + error);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            System.out.println("[PROBE] 已关闭 status=" + statusCode + " reason=" + reason);
            return null;
        }
    }
}
