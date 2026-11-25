package com.example.mvp.config;

import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.ConflictPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 站 2：装配层参数（对应 customer_work 的 {@code AgentProperties}，刻意做小）。
 *
 * <p><b>这一组参数本身就是「装配层」概念的证据</b>：它们全部只影响
 * {@code ReActAgent.Builder} 上有哪些格子可填，而<b>每一个格子框架都给了</b>。
 * customer_work 那边对应的是 {@code customer-work.agent.*} 一整段配置
 * （max-iters / state-conflict-policy / meta-tool-enabled / final-answer-filter-enabled …）。
 *
 * @param maxIters               ReAct 迭代上限（工具调用轮数的硬顶）
 * @param maxRetries             模型调用级重试的「总尝试次数」（注意：不是额外重试次数）
 * @param modelMaxAttempts       {@code modelExecutionConfig} 里的尝试次数（与 maxRetries 分开配，用来观察谁生效）
 * @param modelTimeoutSeconds    单次模型调用超时（秒）
 * @param modelInitialBackoffMillis 首次重试前的退避基数（毫秒）
 * @param modelBackoffMultiplier 退避倍数
 * @param useModelExecutionConfig 是否用框架的 {@code modelExecutionConfig} 覆盖整块执行参数
 * @param conflictPolicy         会话状态并发写策略：OVERWRITE / FAIL / APPEND_MERGE
 * @param permissionMode         工具权限模式：DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK
 * @param deniedTools            禁止调用的工具名清单
 */
@ConfigurationProperties(prefix = "mvp.agent")
public record AgentProperties(
        Integer maxIters,
        Integer maxRetries,
        Integer modelMaxAttempts,
        Integer modelTimeoutSeconds,
        Integer modelInitialBackoffMillis,
        Double modelBackoffMultiplier,
        Boolean useModelExecutionConfig,
        String conflictPolicy,
        String permissionMode,
        List<String> deniedTools) {

    /** 缺省迭代上限。 */
    private static final int DEFAULT_MAX_ITERS = 5;

    /**
     * 缺省重试次数。
     *
     * <p><b>为什么缺省也是 3</b>：框架的 {@code assembleModelConfig} 在
     * {@code Builder.maxRetries} 没设时直接填 3 —— <b>框架默认就重试</b>，
     * 不是「不设就不重试」。这条很容易踩：以为「没配 = 不重试」，
     * 实际线上每个模型调用都在悄悄重试两次。
     */
    private static final int DEFAULT_MAX_RETRIES = 3;

    public int resolvedMaxIters() {
        return maxIters == null || maxIters <= 0 ? DEFAULT_MAX_ITERS : maxIters;
    }

    public int resolvedMaxRetries() {
        return maxRetries == null ? DEFAULT_MAX_RETRIES : maxRetries;
    }

    /**
     * {@code modelExecutionConfig} 里的尝试次数。
     *
     * <p><b>刻意与 {@code maxRetries} 分开</b>：两者同时设时只有后者生效，
     * 配置成不同值才能在真机上一眼看出「到底是谁赢了」。
     * 缺省跟随 {@code maxRetries}（这样不配也说得通）。
     */
    public int resolvedModelMaxAttempts() {
        return modelMaxAttempts == null ? resolvedMaxRetries() : modelMaxAttempts;
    }

    public int resolvedModelTimeoutSeconds() {
        return modelTimeoutSeconds == null ? 60 : modelTimeoutSeconds;
    }

    public long resolvedModelInitialBackoffMillis() {
        return modelInitialBackoffMillis == null ? 500L : modelInitialBackoffMillis;
    }

    public double resolvedModelBackoffMultiplier() {
        return modelBackoffMultiplier == null ? 2.0d : modelBackoffMultiplier;
    }

    /**
     * 是否用 {@code modelExecutionConfig} 覆盖整块执行参数。
     *
     * <p><b>这个开关是站 2 挖出来的坑的开关</b>：
     * {@code maxRetries} 与 {@code modelExecutionConfig} <b>不能同时生效</b> ——
     * 后者会把整个 {@code executionConfig} 字段替换掉，前者被丢弃。
     * 留着这个开关，是为了在真机上把两种行为都跑一遍。
     */
    public boolean resolvedUseModelExecutionConfig() {
        return useModelExecutionConfig == null || useModelExecutionConfig;
    }

    public ConflictPolicy resolvedConflictPolicy() {
        if (conflictPolicy == null || conflictPolicy.isBlank()) {
            return ConflictPolicy.OVERWRITE;
        }
        try {
            return ConflictPolicy.valueOf(conflictPolicy.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "mvp.agent.conflict-policy 取值非法：" + conflictPolicy
                            + "（可选 OVERWRITE / FAIL / APPEND_MERGE）", e);
        }
    }

    public PermissionMode resolvedPermissionMode() {
        if (permissionMode == null || permissionMode.isBlank()) {
            return PermissionMode.DEFAULT;
        }
        try {
            return PermissionMode.fromString(permissionMode.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "mvp.agent.permission-mode 取值非法：" + permissionMode
                            + "（可选 DEFAULT / ACCEPT_EDITS / EXPLORE / BYPASS / DONT_ASK）", e);
        }
    }

    public List<String> resolvedDeniedTools() {
        return deniedTools == null ? List.of() : deniedTools;
    }
}
