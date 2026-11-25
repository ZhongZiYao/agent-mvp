package com.example.mvp.agent;

/**
 * 站 3：治理中间件的<b>顺序契约</b>。
 *
 * <h3>框架的 order() 语义（实测，非猜测）</h3>
 * <p><b>数值越大 = 越靠外</b>；靠外意味着<b>入站时先执行、出站时后执行</b>。
 * 同一数值时保持<b>注册顺序</b>。默认值是 {@code 1}。</p>
 *
 * <p><b>证据链（全部来自框架字节码，可复现）</b>：</p>
 * <ol>
 *   <li>{@code ReActAgent$Builder#build()} 的倒数第二步：
 *       {@code middlewares.sort(Comparator.comparingInt(MiddlewareBase::order).reversed())}
 *       —— 排序键是 {@code order()}（BootstrapMethods #2 指向
 *       {@code REF_invokeInterface MiddlewareBase.order:()I}），
 *       <b>{@code .reversed()} 意味着降序</b> ⇒ <b>order 越大越靠列表前部</b>；</li>
 *   <li>{@code MiddlewareChain.build(...)} 的循环是<b>倒序</b>的：
 *       {@code for (int i = size - 1; i >= 0; i--) chain = wrap(middlewares.get(i), chain)}
 *       —— 列表<b>最后一个</b>先包住终端（最内层），列表<b>第一个</b>最后包（最外层）
 *       ⇒ <b>列表第 0 个 = 最外层</b>；</li>
 *   <li>两条合起来：<b>order 越大 ⇒ 列表越靠前 ⇒ 越靠外</b>。</li>
 * </ol>
 *
 * <h3>两个容易踩的坑</h3>
 * <ul>
 *   <li><b>自己预排序是无效的</b>：框架在 {@code build()} 里会再排一次降序，
 *       你的升序预排序会被整体覆盖。它唯一的作用是在<b>同值</b>时影响相对次序 ——
 *       而同值时「按 order 排」本身没有意义。所以正确做法是<b>只管声明 order，别管排序</b>。</li>
 *   <li><b>框架自动注入的 {@code GracefulShutdownMiddleware} 不参与 order 协商</b>：
 *       它在 {@code ReActAgent} 的<b>构造函数</b>里被直接 {@code add} 到列表首位
 *       （{@code all.add(new GracefulShutdownMiddleware(...)); all.addAll(builder.middlewares);}），
 *       而排序发生在构造函数<b>之前</b>。所以它<b>永远在最外层</b>，
 *       哪怕你注册一个 order=999 的中间件也压不过它。</li>
 * </ul>
 *
 * <h3>对照 customer_work</h3>
 * <p>那边是 {@code MiddlewareOrders}：<b>27 个常量 ↔ 27 个实现类</b>，取值 50~200，
 * 并且有 {@code MiddlewareOrderContractTest} 守着「必须显式声明 / 取值不重复 / 关键相对次序成立」三条。
 * 本 demo 只保留 3 个治理件，但<b>契约的三条纪律完全照搬</b>。</p>
 */
public final class MvpMiddlewareOrders {

    // ---------- 审计（最外） ----------

    /**
     * 审计留痕：出站方向最后执行，记录的应当是<b>最终真正发出的内容</b>。
     *
     * <p>所以它必须比脱敏<b>更外</b>：出站时脱敏先改写内容，审计后看到改写后的版本。
     * 排反了，审计留痕里就会出现未脱敏的手机号 —— 而且<b>不会报任何错</b>。</p>
     */
    public static final int AUDIT = 150;

    // ---------- 出站过滤 ----------

    /**
     * PII 脱敏：出站方向把个人信息挡在后续所有留痕之前。
     *
     * <p>比审计<b>更内</b>，比输入拦截<b>更外</b>。
     * 「更外于输入拦截」是为了让脱敏也能盖住<b>治理件自己生成的答复</b>
     * （比如拦截提示里如果带上了用户手机号，同样要脱敏）。</p>
     */
    public static final int REDACTION = 125;

    // ---------- 入站防护 ----------

    /**
     * 输入拦截：恶意/越界输入越早挡住越好。
     *
     * <p>命中时<b>直接短路</b>，不调用模型 —— 这既省 token，也避免越界内容进入任何下游链路。</p>
     */
    public static final int GUARD = 120;

    private MvpMiddlewareOrders() {
    }
}
