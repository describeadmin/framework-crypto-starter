package io.github.describeadmin.crypto.autoconfigure;

import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.github.describeadmin.common.api.FrameworkVersion;
import io.github.describeadmin.crypto.api.CryptoProvider;
import io.github.describeadmin.crypto.api.CryptoTemplate;
import io.github.describeadmin.crypto.core.AesCryptoProvider;
import io.github.describeadmin.crypto.core.BlindIndexInnerInterceptor;
import io.github.describeadmin.crypto.core.CryptoDispatcher;
import io.github.describeadmin.crypto.core.Sm4CryptoProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 插件装配机制的验证——回答"把这个 jar 放进 classpath，它到底有没有接管"，
 * 不是"AES/SM4 算法本身对不对"（那由 {@code AesCryptoProviderTest}/{@code Sm4CryptoProviderTest} 负责）。
 *
 * <p>⚠️ 一条与 {@code framework-auth-email-starter} 的关键差异：Bouncy Castle 是<b>本模块自己</b>
 * 的 optional 依赖（用于 SM4 单测的交叉验证），因此在本模块自己的测试运行时 BC <b>始终</b>在
 * classpath 上——不像 {@code spring-boot-starter-mail} 那样可以用 {@code @ConditionalOnBean}
 * 区分"类在但未配置"的中间态。本类因此测不出"业务方完全不引 BC → Sm4CryptoProvider 缺席"这条
 * 路径本身（那需要一个不依赖 BC 的下游消费模块才能验证），只能验证"BC 在场时
 * {@code @ConditionalOnClass} 分支确实生效" + "sm4.enabled=false 能独立关掉 SM4，不影响 AES"，
 * 两者结合已经覆盖了装配逻辑的正确性，只是覆盖角度与"classpath 缺失"略有不同。
 */
@DisplayName("插件装配")
class FrameworkCryptoAutoConfigurationTest {

    private static final String AES_KEY = "raX1kbnki6EOTZuDwNPmyKvyH19v0T/7584NOnEFQXM=";
    private static final String AES_BLIND_INDEX_KEY = "VLfqdPd1waoKazQ5ARsoljLG1FOBoxtQg50A+t0xlFA=";
    private static final String SM4_KEY = "zRCKIYwcbURwVLg+ZVfcGw==";
    private static final String SM4_BLIND_INDEX_KEY = "uGwr7OVo8uvfJBo8bB1YQAIah4TDcjWWwOgkjaZtRtI=";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FrameworkCryptoAutoConfiguration.class))
            .withPropertyValues(
                    "describeadmin.crypto.aes.key=" + AES_KEY,
                    "describeadmin.crypto.aes.blind-index-key=" + AES_BLIND_INDEX_KEY,
                    "describeadmin.crypto.sm4.key=" + SM4_KEY,
                    "describeadmin.crypto.sm4.blind-index-key=" + SM4_BLIND_INDEX_KEY);

    @Test
    @DisplayName("插件声明的最低框架版本，与它实际构建所依赖的框架是自洽的")
    void declaredRequirementIsSatisfiedByTheFrameworkItBuildsAgainst() {
        assertThat(FrameworkVersion.current()).isNotEqualTo(FrameworkVersion.UNKNOWN);
        assertThatNoException().isThrownBy(() -> FrameworkVersion.requireCompatible(
                "framework-crypto-starter", FrameworkCryptoAutoConfiguration.REQUIRED_FRAMEWORK_VERSION));
    }

    @Test
    @DisplayName("框架比插件要求的旧时，启动即失败而不是等到运行期报错")
    void incompatibleFrameworkFailsFast() {
        assertThatThrownBy(() -> FrameworkVersion.requireCompatible("framework-crypto-starter", "99.0.0"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("framework-crypto-starter");
    }

    @Test
    @DisplayName("不引本插件时，不会注册任何相关 Bean（等同没引这个 jar）")
    void withoutPluginNoBeansRegistered() {
        new ApplicationContextRunner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CryptoDispatcher.class);
            assertThat(context).doesNotHaveBean(CryptoTemplate.class);
        });
    }

    @Test
    @DisplayName("describeadmin.crypto.enabled=false 时完全不装配，等同没引这个 jar")
    void disabledFallsBackToAbsent() {
        runner.withPropertyValues("describeadmin.crypto.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CryptoDispatcher.class);
            assertThat(context).doesNotHaveBean(AesCryptoProvider.class);
        });
    }

    @Test
    @DisplayName("默认配置下，AES 与 SM4 两个 Provider 共存，CryptoDispatcher 同时收集到两种算法")
    void aesAndSm4Coexist() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AesCryptoProvider.class);
            assertThat(context).hasSingleBean(Sm4CryptoProvider.class);
            assertThat(context.getBean(CryptoDispatcher.class).algorithms())
                    .containsExactlyInAnyOrder(CryptoProvider.ALGORITHM_AES256_GCM, CryptoProvider.ALGORITHM_SM4_GCM);
        });
    }

    @Test
    @DisplayName("describeadmin.crypto.sm4.enabled=false 时只关掉 SM4，不影响 AES——多实现独立开关")
    void sm4CanBeDisabledIndependently() {
        runner.withPropertyValues("describeadmin.crypto.sm4.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(AesCryptoProvider.class);
            assertThat(context).doesNotHaveBean(Sm4CryptoProvider.class);
            assertThat(context.getBean(CryptoDispatcher.class).algorithms())
                    .containsExactly(CryptoProvider.ALGORITHM_AES256_GCM);
        });
    }

    @Test
    @DisplayName("default-algorithm 指向未注册的算法时，启动失败")
    void unknownDefaultAlgorithmFailsStartup() {
        runner.withPropertyValues(
                        "describeadmin.crypto.sm4.enabled=false",
                        "describeadmin.crypto.default-algorithm=" + CryptoProvider.ALGORITHM_SM4_GCM)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("盲索引自动填充默认随插件一起启用")
    void blindIndexEnabledByDefault() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(InnerInterceptor.class);
            assertThat(context.getBean(InnerInterceptor.class)).isInstanceOf(BlindIndexInnerInterceptor.class);
        });
    }

    @Test
    @DisplayName("可以只关掉盲索引自动填充，不影响加解密本身")
    void blindIndexCanBeDisabledIndependently() {
        runner.withPropertyValues("describeadmin.crypto.blind-index.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(InnerInterceptor.class);
            assertThat(context).hasSingleBean(CryptoDispatcher.class);
            assertThat(context).hasSingleBean(CryptoTemplate.class);
        });
    }

    @Test
    @DisplayName("AES 加密密钥未配置时，启动失败——不留到第一次加密调用才报错")
    void missingAesKeyFailsStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FrameworkCryptoAutoConfiguration.class))
                .withPropertyValues("describeadmin.crypto.sm4.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }
}
