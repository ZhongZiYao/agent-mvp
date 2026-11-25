package com.example.mvp.agent;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 站 2：自建 {@link AgentStateStore} 实现。
 *
 * <p><b>边界判定：框架给接口，不给实现，持久化是一个需要自己选型的决策。</b>
 * core 里的 {@code AgentStateStore} 只有 9 个方法（4 抽象 + 5 default），
 * 但「状态存到哪、怎么隔离、怎么版本化」框架一概不管 ——
 * 官方为此<b>另发了两个扩展包</b>：{@code agentscope-extensions-mysql}
 * （MysqlAgentStateStore + 4 种方言）与 {@code agentscope-extensions-redis}
 * （RedisAgentStateStore，jedis / lettuce / redisson 三种客户端）。
 *
 * <p>换句话说：<b>「多实例部署时状态要共享」这件事，框架不解决，
 * 你要么自己写实现，要么自己选一个扩展包。</b>
 *
 * <p><b>槽位语义（实测）</b>：框架定义的三段式槽位是
 * {@code (userId, sessionId, key)} —— 参数名取自官方
 * {@code MysqlAgentStateStore} 的 LocalVariableTable（javap 实测，非猜测）。
 * <b>这三个值任一不同，就是不同的状态槽位</b>。
 * customer_work 那边把这一点写进了注释：
 * 「绝不能写成常量 —— 写成常量会让所有用户共用一份对话历史」。
 *
 * <p><b>版本化</b>：框架的 default 实现是
 * {@code supportsVersioning() → false}、
 * {@code getVersioned} 固定返回版本 {@code -1}（{@code UNVERSIONED}）、
 * {@code saveIfVersion} 直接转调 {@code save} 并返回 {@code -1}。
 * 也就是说<b>不实现版本化也能跑</b>，代价是失去乐观并发控制
 * （框架的 {@code ConflictPolicy.FAIL} / {@code APPEND_MERGE} 依赖版本号）。
 * 本实现补上真正的 CAS 版本号，好让 {@code ConflictPolicy} 有意义。
 */
@Component
public class InMemoryAgentStateStore implements AgentStateStore {

    /** 槽位键的分隔符：用不可能出现在 id 里的 NUL，避免 "a|b" + "c" 与 "a" + "b|c" 撞车。 */
    private static final char SEP = '\u0000';

    /** 槽位键 → 状态列表。 */
    private final Map<String, List<State>> slots = new ConcurrentHashMap<>();

    /** 槽位键 → 当前版本号（乐观并发）。 */
    private final Map<String, AtomicLong> versions = new ConcurrentHashMap<>();

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public void save(String userId, String sessionId, String key, State state) {
        if (state == null) {
            return;
        }
        String slot = slotKey(userId, sessionId, key);
        List<State> bucket = slots.computeIfAbsent(slot, k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.clear();
            bucket.add(state);
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        String slot = slotKey(userId, sessionId, key);
        List<State> bucket = slots.computeIfAbsent(slot, k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.clear();
            bucket.addAll(states);
        }
    }

    @Override
    public <T extends State> Optional<T> get(String userId, String sessionId, String key, Class<T> type) {
        List<State> bucket = slots.get(slotKey(userId, sessionId, key));
        if (bucket == null) {
            return Optional.empty();
        }
        synchronized (bucket) {
            for (State s : bucket) {
                if (type.isInstance(s)) {
                    return Optional.of(type.cast(s));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public <T extends State> List<T> getList(String userId, String sessionId, String key, Class<T> type) {
        List<State> bucket = slots.get(slotKey(userId, sessionId, key));
        if (bucket == null) {
            return List.of();
        }
        List<T> out = new ArrayList<>();
        synchronized (bucket) {
            for (State s : bucket) {
                if (type.isInstance(s)) {
                    out.add(type.cast(s));
                }
            }
        }
        return out;
    }

    @Override
    public <T extends State> io.agentscope.core.state.VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        String slot = slotKey(userId, sessionId, key);
        Optional<T> value = get(userId, sessionId, key, type);
        long version = versions.getOrDefault(slot, new AtomicLong(0)).get();
        return new io.agentscope.core.state.VersionedState<>(value.orElse(null), value.isPresent() ? version : UNVERSIONED);
    }

    @Override
    public long saveIfVersion(String userId, String sessionId, String key, State state, long expectedVersion) {
        String slot = slotKey(userId, sessionId, key);
        AtomicLong counter = versions.computeIfAbsent(slot, k -> new AtomicLong(0));
        synchronized (counter) {
            long current = counter.get();
            // CAS：只有版本号与调用方读到的一致时才写入。
            // 不一致说明别人先写了 —— 返回 -1 表示「没写上」，由框架决定怎么处理冲突。
            if (current != expectedVersion) {
                return UNVERSIONED;
            }
            save(userId, sessionId, key, state);
            return counter.incrementAndGet();
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String prefix = prefix(userId, sessionId);
        return slots.keySet().stream().anyMatch(k -> k.startsWith(prefix));
    }

    @Override
    public void delete(String userId, String sessionId) {
        String prefix = prefix(userId, sessionId);
        slots.keySet().removeIf(k -> k.startsWith(prefix));
        versions.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String prefix = userId + SEP;
        Set<String> out = new LinkedHashSet<>();
        for (String slot : slots.keySet()) {
            if (slot.startsWith(prefix)) {
                String rest = slot.substring(prefix.length());
                int idx = rest.indexOf(SEP);
                if (idx > 0) {
                    out.add(rest.substring(0, idx));
                }
            }
        }
        return out;
    }

    /** 已占用的槽位数（测试用）。 */
    public int slotCount() {
        return slots.size();
    }

    /** 槽位键：(userId, sessionId, key)。 */
    private static String slotKey(String userId, String sessionId, String key) {
        return userId + SEP + sessionId + SEP + key;
    }

    /** 某个 (userId, sessionId) 下所有 key 的前缀。 */
    private static String prefix(String userId, String sessionId) {
        return userId + SEP + sessionId + SEP;
    }
}
