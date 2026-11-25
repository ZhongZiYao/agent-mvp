package com.example.mvp.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 站 2：<b>装配对齐守卫</b> —— 扫源码断言「所有建 Agent 的点都走了同一个装配入口」。
 *
 * <p><b>这条测试防的是什么</b>：中间件漏挂、权限没生效、治理能力在某条路径上失效 ——
 * <b>全都不报错</b>。唯一的结构性防线就是这条断言。
 *
 * <p><b>为什么扫源码而不是反射</b>：反射只能看到「最终那个 Agent 挂了什么」，
 * 看不到「<b>有多少个</b>建 Agent 的入口、每个入口有没有走装配」。
 * 而「能力只接在一条路上」这个缺陷，恰恰是<b>入口数量</b>的问题。
 *
 * <p><b>按文件判定还是按调用点判定</b>：customer_work 那边踩过这个坑 ——
 * 「门禁按文件判定会漏掉同文件的第二个调用点」。
 * 所以本测试采用<b>按调用点计数</b>：同一文件里
 * {@code ReActAgent.builder()} 出现 N 次，装配标记 {@code applyTo} 就必须出现 ≥ N 次。
 *
 * <p><b>⚠️ 第一版栽在这条上，值得单独记一笔</b>：
 * 最初用朴素的字符串计数，结果<b>注释里的 {@code ReActAgent.builder()}
 * 也被算成了调用点</b> —— 文件里有 1 处真实调用 + 2 处 javadoc 提及，
 * 计数变成 3，断言红了。
 * <b>修法是「先剥注释再计数」</b>（见 {@link #stripComments}），
 * 而不是去改注释凑数字。
 * 这条与 customer_work「变异测试用 {@code mv} 还原源码会保留旧 mtime」的教训同类：
 * <b>测试基础设施自己出错时，红的可能是测试而不是代码。</b>
 *
 * <p><b>防「假绿」</b>：如果源码路径写错、扫不到任何文件，
 * 「每个含 builder 的文件都合格」会<b>空集成立</b> → 测试绿但毫无意义。
 * 所以额外断言<b>至少扫到 1 个建 Agent 的调用点</b>。
 */
class AgentAssemblyAlignmentTest {

    /** 建 Agent 的标记。 */
    private static final String BUILD_MARKER = "ReActAgent.builder()";

    /** 走装配入口的标记。 */
    private static final String APPLY_MARKER = ".applyTo(";

    @Test
    @DisplayName("站2-14：每个建 Agent 的调用点都经过了治理装配（唯一入口）")
    void everyAgentCreationGoesThroughTheAssembler() throws IOException {
        List<Path> roots = sourceRoots();
        assertTrue(!roots.isEmpty(), "没找到任何源码目录，user.dir=" + System.getProperty("user.dir"));

        int totalBuildCalls = 0;
        List<String> violations = new ArrayList<>();

        for (Path root : roots) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    // 关键：先剥注释，否则 javadoc 里的字面量会被当成调用点
                    String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                    int builds = count(code, BUILD_MARKER);
                    int applies = count(code, APPLY_MARKER);
                    if (builds == 0) {
                        continue;
                    }
                    totalBuildCalls += builds;
                    if (applies < builds) {
                        violations.add(relativize(file) + "：建 Agent " + builds + " 处，装配标记 " + applies + " 处");
                    }
                }
            }
        }

        assertTrue(totalBuildCalls >= 1,
                "一个建 Agent 的调用点都没扫到 —— 源码路径写错了，这条测试会变成假绿。"
                        + "user.dir=" + System.getProperty("user.dir") + " roots=" + roots);
        assertTrue(violations.isEmpty(),
                "以下文件建了 Agent 却没走装配入口（治理能力会静默失效）：\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("站2-15：装配入口只有一个实现类 —— 不允许多处各写一份治理装配")
    void thereIsExactlyOneAssemblerImplementation() throws IOException {
        int assemblerClasses = 0;
        List<String> names = new ArrayList<>();
        for (Path root : sourceRoots()) {
            try (Stream<Path> stream = Files.walk(root)) {
                for (Path file : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                    if (code.contains("void applyTo(ReActAgent.Builder")) {
                        assemblerClasses++;
                        names.add(relativize(file));
                    }
                }
            }
        }
        assertEquals(1, assemblerClasses,
                "治理装配应当只有一个实现类，实际找到: " + names);
    }

    /**
     * 剥掉注释，保留字符串字面量。
     *
     * <p>只做三件事：跳过 {@code /* ... *}{@code /} 块注释、跳过 {@code //} 行注释、
     * 原样保留字符串与字符字面量（否则 URL 里的 {@code //} 会把后半行吃掉）。
     */
    static String stripComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        char[] chars = source.toCharArray();
        boolean inBlock = false;
        boolean inLine = false;
        boolean inString = false;
        boolean inChar = false;

        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            char next = (i + 1 < chars.length) ? chars[i + 1] : '\0';

            if (inBlock) {
                if (c == '*' && next == '/') {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < chars.length) {
                    out.append(chars[++i]);
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\' && i + 1 < chars.length) {
                    out.append(chars[++i]);
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }

            // 文本块 """..."""：整体跳过内容，避免里面的引号把状态机带偏
            if (c == '"' && next == '"' && i + 2 < chars.length && chars[i + 2] == '"') {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = (end < 0) ? source.length() : end + 3;
                out.append(source, i, stop);
                i = stop - 1;
                continue;
            }
            if (c == '/' && next == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '/' && next == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
                continue;
            }
            if (c == '\'') {
                inChar = true;
                out.append(c);
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 找出所有生产源码目录。 */
    private static List<Path> sourceRoots() {
        Path moduleDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        for (Path candidate : List.of(
                moduleDir.resolve("src/main/java"),
                moduleDir.resolve("../mvp-app/src/main/java"))) {
            Path normalized = candidate.normalize();
            if (Files.isDirectory(normalized)) {
                roots.add(normalized);
            }
        }
        return roots;
    }

    private static String relativize(Path file) {
        Path moduleDir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        try {
            return moduleDir.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            n++;
            idx += needle.length();
        }
        return n;
    }
}
