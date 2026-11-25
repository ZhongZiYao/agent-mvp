package com.example.mvp.agent;

import com.example.mvp.config.AgentProperties;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.middleware.MiddlewareBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 站 3：<b>中间件顺序契约门禁</b>。
 *
 * <p><b>守的是什么</b>：框架的 {@code MiddlewareBase#order()} 默认返回 {@code 1}，
 * 相同数值时保持注册顺序。一个中间件如果没显式声明 order，
 * 它就落回默认值 1 —— 而这个 1 是<b>框架自带件的取值</b>，
 * 也就是「稳定落在最内层」那个位置。于是顺序就悄悄变了，
 * 而且<b>不会报任何错</b>。</p>
 *
 * <p><b>三条纪律</b>（照搬 customer_work 的 {@code MiddlewareOrderContractTest}）：</p>
 * <ol>
 *   <li>每个中间件都必须<b>显式</b>声明 order，不许留默认值；</li>
 *   <li>取值不得重复（重复 = 退回按注册顺序 = 回到这套契约要解决的问题本身）；</li>
 *   <li>关键相对次序必须成立（排错了不报错，只会在某天以「未脱敏内容进了审计」的形式出现）。</li>
 * </ol>
 */
class MiddlewareOrderContractTest {

    @Test
    @DisplayName("站3-6：每个中间件都必须显式声明 order，不得留框架默认值")
    void everyMiddlewareDeclaresOrder() throws IOException {
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        try (Stream<Path> stream = Files.walk(sourceRoot("src/main/java"))) {
            for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("implements MiddlewareBase")) {
                    continue;
                }
                checked++;
                String name = file.getFileName().toString().replace(".java", "");
                if (!source.contains("public int order()")) {
                    offenders.add(name);
                }
            }
        }

        assertTrue(checked >= 4,
                "应至少扫描到 4 个中间件，实际 " + checked + " —— 扫描路径可能失效了");
        if (!offenders.isEmpty()) {
            fail("以下中间件没有声明 order()，会落到框架默认值 1：\n"
                    + String.join("\n", offenders)
                    + "\n修法：在 MvpMiddlewareOrders 里定一个值并说明它为什么在那一层，然后覆写 order()。");
        }
    }

    @Test
    @DisplayName("站3-7：顺序取值不得重复")
    void orderValuesAreDistinct() throws IllegalAccessException {
        Map<Integer, String> seen = new HashMap<>();
        List<String> conflicts = new ArrayList<>();

        for (Field f : MvpMiddlewareOrders.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != int.class) {
                continue;
            }
            int value = (int) f.get(null);
            String previous = seen.put(value, f.getName());
            if (previous != null) {
                conflicts.add(previous + " 与 " + f.getName() + " 都取 " + value);
            }
        }

        assertTrue(seen.size() >= 3, "顺序常量数量异常，实际 " + seen.size());
        if (!conflicts.isEmpty()) {
            fail("顺序取值重复，这两者会退回按注册顺序：\n" + String.join("\n", conflicts));
        }
    }

    @Test
    @DisplayName("站3-8：关键相对次序成立 —— 审计比脱敏外，脱敏比拦截外")
    void criticalOrderingHolds() {
        assertTrue(MvpMiddlewareOrders.AUDIT > MvpMiddlewareOrders.REDACTION,
                "审计必须比脱敏更外 —— 排反了，审计留痕里记的是未脱敏的手机号");
        assertTrue(MvpMiddlewareOrders.REDACTION > MvpMiddlewareOrders.GUARD,
                "脱敏必须比输入拦截更外 —— 拦截件自己生成的兜底答复也要过脱敏");
        assertTrue(MvpMiddlewareOrders.AUDIT > MvpMiddlewareOrders.GUARD,
                "审计必须比输入拦截更外 —— 拦截发生时也要留痕");
    }

    @Test
    @DisplayName("站3-9：真实装配出来的生效链满足契约 —— 按 order 降序，且审计在脱敏之外")
    void effectiveChainSatisfiesContract() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        MvpAgentFactory factory = new MvpAgentFactory(new MvpTestFixtures.CapturingModel(),
                properties, governance, new InMemoryAgentStateStore());

        ReActAgent agent = factory.create("u1", "u1-s");
        List<MiddlewareBase> effective = agent.getMiddlewares();

        // 第 0 项是框架硬塞的链首（GracefulShutdownMiddleware，order=1，不参与协商）
        // 从第 1 项起才谈得上按 order 降序
        for (int i = 2; i < effective.size(); i++) {
            assertTrue(effective.get(i - 1).order() >= effective.get(i).order(),
                    "生效链必须按 order 降序，实际链: " + effective);
        }

        int auditPos = positionOf(effective, AuditTrailMiddleware.class);
        int redactionPos = positionOf(effective, RedactionMiddleware.class);
        assertTrue(auditPos >= 0 && redactionPos >= 0, "审计与脱敏都应装上，实际链: " + effective);
        assertTrue(auditPos < redactionPos,
                "审计必须在脱敏【之外】（列表里更靠前 = 更外），实际: 审计@" + auditPos
                        + " 脱敏@" + redactionPos);
    }

    @Test
    @DisplayName("站3-10：框架自带件 order=1，稳定落在自建治理件（120~150）之内")
    void frameworkProvidedMiddlewareStaysInnermost() {
        AgentProperties properties = MvpTestFixtures.props();
        MvpGovernanceAssembler governance = MvpTestFixtures.governance(properties);
        MvpAgentFactory factory = new MvpAgentFactory(new MvpTestFixtures.CapturingModel(),
                properties, governance, new InMemoryAgentStateStore());

        ReActAgent agent = factory.create("u1", "u1-s");
        List<MiddlewareBase> effective = agent.getMiddlewares();

        int finalAnswerPos = positionOf(effective,
                io.agentscope.core.middleware.FinalAnswerFilterMiddleware.class);
        int guardPos = positionOf(effective, InputGuardMiddleware.class);

        assertTrue(finalAnswerPos >= 0, "框架自带件应装上，实际链: " + effective);
        assertTrue(finalAnswerPos > guardPos,
                "FinalAnswerFilterMiddleware 的 order 是框架默认值 1，比治理件小 ⇒ 必须更靠内，"
                        + "实际: 框架件@" + finalAnswerPos + " 治理件@" + guardPos);
    }

    /** 某个类型的中间件在生效链里的位置；找不到返回 -1。 */
    private static int positionOf(List<MiddlewareBase> chain, Class<?> type) {
        for (int i = 0; i < chain.size(); i++) {
            if (type.isInstance(chain.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** 源码根：优先当前模块下的路径，测试从仓库根跑时退回 ../&lt;module&gt;/… 。 */
    private Path sourceRoot(String moduleRelative) {
        Path fromModuleRoot = Paths.get(moduleRelative);
        if (Files.exists(fromModuleRoot)) {
            return fromModuleRoot;
        }
        return Paths.get("..").resolve(moduleRelative);
    }
}
