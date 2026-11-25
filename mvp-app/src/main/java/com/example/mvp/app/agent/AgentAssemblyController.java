package com.example.mvp.app.agent;

import com.example.mvp.agent.MvpAgentFactory;
import io.agentscope.core.ReActAgent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 站 2：<b>装配快照出口</b> —— 把「这个 Agent 身上到底装了什么」摊开给人看。
 *
 * <p><b>为什么需要这个端点</b>：装配层的错误<b>几乎全部是不报错的静默错误</b>：
 * <ul>
 *   <li>中间件漏挂 → 治理能力在某条路径上失效，没有异常，只是没拦住；</li>
 *   <li>权限规则写错 → 工具该拦没拦 / 不该拦拦了，同样不报错；</li>
 *   <li>{@code maxRetries} 与 {@code modelExecutionConfig} 同时设 → <b>后者赢</b>，
 *       前者被静默丢弃，也不报错。</li>
 * </ul>
 * 唯一的发现手段就是<b>在运行期把装配结果读出来看</b>。
 * customer_work 那边对应的是 admin 的「Agent 配置预览」与诊断链路。
 *
 * <p>用法：先调 {@code POST /api/auth/token} 拿 token，再
 * {@code GET /api/agent/assembly?sessionId=xxx}。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentAssemblyController {

    private final MvpAgentFactory agentFactory;

    public AgentAssemblyController(MvpAgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * 展示装配快照。
     *
     * @param userId    用户 id（默认取演示账号）
     * @param sessionId 会话号（默认新建一个）
     */
    @GetMapping("/assembly")
    public Map<String, Object> assembly(@RequestParam(required = false) String userId,
                                        @RequestParam(required = false) String sessionId) {
        String uid = (userId == null || userId.isBlank()) ? "u1001" : userId.trim();
        String sid = (sessionId == null || sessionId.isBlank())
                ? uid + "-assembly-demo" : sessionId.trim();

        ReActAgent agent = agentFactory.create(uid, sid);
        Map<String, Object> out = new LinkedHashMap<>(agentFactory.describe(agent));
        out.put("userId", uid);
        out.put("sessionId", sid);
        out.put("systemPrompt", agentFactory.systemPrompt());
        return out;
    }
}
