package com.example.mvp.config;

import com.example.mvp.model.SwappableModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型 Bean 装配。
 *
 * <p><b>站 0 的边界判定</b>：
 * <ul>
 *   <li>框架<b>直接给</b>：{@code Model} SPI、{@code OpenAIChatModel} 实现、{@code MiniMaxFormatter}、
 *       {@code GenerateOptions} —— 我们一行都不用写；</li>
 *   <li>我们<b>必须自建</b>：{@code @ConfigurationProperties} 绑定、端点/密钥的解析与兜底
 *       （框架的 {@code MiniMaxModelProvider} 走注册表路径，不适合「显式构造一个实例再注入」的用法）。</li>
 * </ul>
 *
 * <p><b>站 2 的升级</b>：这个 Bean 现在返回的是 {@link SwappableModel}（自建装饰器），
 * 而不是裸的 {@code OpenAIChatModel}。
 * <b>外部注入点签名不变</b>（仍然是 {@code Model}），
 * 但底层模型可以在运行期热替换 —— 这正是 customer_work
 * {@code MutableDelegatingModel#swap} 的形状。
 *
 * <p><b>框架为什么不给这个</b>：core 里<b>一个 Model 装饰器基类都没有</b>
 * （没有 RetryModel / FallbackModel / DelegatingModel），
 * 只有 {@code Model} 这个 2 抽象方法的接口。所以「热替换」只能自己写。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ModelConfig.class, MvpProperties.class, AgentProperties.class})
public class ModelConfiguration {

    /**
     * 唯一的 Model Bean。
     *
     * <p><b>返回类型声明为具体类 {@link SwappableModel}</b>，而不是接口 {@code Model} ——
     * 这是个刻意的取舍：
     * <ul>
     *   <li>如果声明成 {@code Model}，再单独暴露一个 {@code SwappableModel} Bean，
     *       容器里就<b>有两个 Model 类型的候选</b>，
     *       注入 {@code Model} 时直接抛 {@code NoUniqueBeanDefinitionException}；</li>
     *   <li>而 {@code SwappableModel implements Model}，
     *       只暴露它一个，注入 {@code Model} 仍然唯一命中（子类型可注入父类型），</li>
     *   <li>同时需要 {@code swap} 的调用方可以直接注入 {@code SwappableModel}，
     *       不用 instanceof 向下转型。</li>
     * </ul>
     * 对照 customer_work：那边就是靠「唯一 Model Bean + 装饰器包在中间」这件事，
     * 让「热替换模型」不需要改任何调用方。
     */
    @Bean
    public SwappableModel model(ModelConfig config) {
        return new SwappableModel(config.build());
    }
}
