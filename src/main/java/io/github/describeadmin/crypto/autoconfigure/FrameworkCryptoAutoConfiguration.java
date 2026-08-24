package io.github.describeadmin.crypto.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.crypto.api.CryptoProvider;
import io.github.describeadmin.crypto.api.CryptoTemplate;
import io.github.describeadmin.crypto.core.AesCryptoProvider;
import io.github.describeadmin.crypto.core.BlindIndexInnerInterceptor;
import io.github.describeadmin.crypto.core.CryptoDispatcher;
import io.github.describeadmin.crypto.core.Sm4CryptoProvider;
import io.github.describeadmin.mybatis.autoconfigure.FrameworkMybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;

/**
 * framework-crypto-starter 的自动配置。
 *
 * <p><b>装配模型</b>：{@link CryptoProvider} 是多实现共存模型（同核心 {@code NotifyChannel}），
 * 不是 {@code CacheProvider}/{@code TokenStore} 那种单一默认实现整体替换模型。因此
 * {@link #aesCryptoProvider}/{@link Sm4Configuration#sm4CryptoProvider} 的
 * {@code @ConditionalOnMissingBean} <b>都绑定到具体实现类</b>（{@code AesCryptoProvider.class}/
 * {@code Sm4CryptoProvider.class}），<b>不能</b>绑定 {@code CryptoProvider.class} 接口——
 * 否则接口级条件会被先注册的那个实现挡住，且启动无报错（{@code NotifyChannel}/
 * {@code LogNotifyChannel} 已验证过这条陷阱）。{@link #cryptoDispatcher}/{@link #cryptoTemplate}
 * 靠 {@code List<CryptoProvider>} 类型收集，不需要 {@code before=}/{@code afterName=} 排序——
 * Spring 按类型收集不受 Bean 定义顺序影响。
 *
 * <p>{@code after = FrameworkMybatisAutoConfiguration.class} 纯粹是文档性的（不是必需的顺序
 * 约束）：{@link #blindIndexInnerInterceptor} 返回的 {@code InnerInterceptor} 靠
 * {@code FrameworkMybatisAutoConfiguration.mybatisPlusInterceptor()} 已有的
 * {@code ObjectProvider<InnerInterceptor>} 收集，那是在 Bean <b>实例化</b>时才去查找容器里
 * 已注册的 BeanDefinition，不受两个 {@code @AutoConfiguration} 类谁先被解析影响——
 * 这与"{@code @ConditionalOnMissingBean} 条件求值时序敏感"是完全不同的机制，不要混淆。
 */
@AutoConfiguration(after = FrameworkMybatisAutoConfiguration.class)
@ConditionalOnProperty(prefix = "describeadmin.crypto", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(FrameworkCryptoProperties.class)
public class FrameworkCryptoAutoConfiguration {

    /** 本插件要求的最低框架版本：用到 0.2.0 才开放给外部模块的 {@code ObjectProvider<InnerInterceptor>} 扩展点。 */
    public static final String REQUIRED_FRAMEWORK_VERSION = "0.2.0";

    public FrameworkCryptoAutoConfiguration() {
        // 放在构造函数里：条件都满足、真的要装配本插件时才检查，插件未激活时不因版本不匹配把应用打死。
        FrameworkVersion.requireCompatible("framework-crypto-starter", REQUIRED_FRAMEWORK_VERSION);
    }

    @Bean
    @ConditionalOnProperty(prefix = "describeadmin.crypto.aes", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(AesCryptoProvider.class)
    public AesCryptoProvider aesCryptoProvider(FrameworkCryptoProperties properties) {
        return new AesCryptoProvider(properties.getAes().getKey(), properties.getAes().getBlindIndexKey());
    }

    /**
     * 引用 {@code BouncyCastleProvider}/{@code Sm4CryptoProvider} 类型的 {@code @Bean} 方法必须放进
     * 带 {@code @ConditionalOnClass} 的<b>嵌套配置类</b>——条件在类上时 Spring 用 ASM 读注解，
     * 不会提前触发方法签名里的类加载；写在外层方法签名上，BC 不在 classpath 时会直接类加载失败，
     * 而不是被条件优雅地跳过（registry.md 准入规范第 3 条对应的写法）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.bouncycastle.jce.provider.BouncyCastleProvider")
    static class Sm4Configuration {

        @Bean
        @ConditionalOnProperty(prefix = "describeadmin.crypto.sm4", name = "enabled", havingValue = "true", matchIfMissing = true)
        @ConditionalOnMissingBean(Sm4CryptoProvider.class)
        public Sm4CryptoProvider sm4CryptoProvider(FrameworkCryptoProperties properties) {
            return new Sm4CryptoProvider(properties.getSm4().getKey(), properties.getSm4().getBlindIndexKey());
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public CryptoDispatcher cryptoDispatcher(List<CryptoProvider> providers, FrameworkCryptoProperties properties) {
        return new CryptoDispatcher(providers, properties.getDefaultAlgorithm());
    }

    @Bean
    @ConditionalOnMissingBean
    public CryptoTemplate cryptoTemplate(CryptoDispatcher dispatcher) {
        // 构造函数内联完成 EncryptedStringTypeHandler 的静态桥接初始化，见 CryptoTemplate 类注释。
        return new CryptoTemplate(dispatcher);
    }

    /**
     * 盲索引自动填充。单独一个开关，关掉不影响加解密本身。挂进
     * {@code FrameworkMybatisAutoConfiguration.mybatisPlusInterceptor()} 已有的收集点，
     * 不需要改动 framework-mybatis-starter 一行代码。{@code @Order(100)}：排在核心数据权限
     * 拦截器（{@code @Order(0)}）之后，纯粹为了显式可读——本拦截器只改字段值不改写 SQL，
     * 与分页/数据权限那类"必须先于分页"的顺序约束无关。
     */
    @Bean
    @ConditionalOnProperty(prefix = "describeadmin.crypto.blind-index", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(BlindIndexInnerInterceptor.class)
    @Order(100)
    public InnerInterceptor blindIndexInnerInterceptor(CryptoDispatcher dispatcher) {
        return new BlindIndexInnerInterceptor(dispatcher);
    }
}
