package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 按 {@link CryptoProvider#algorithm()} 路由的多实现共存收集器，仿照核心
 * {@code NotifyDispatcher} 的设计：构造期用 {@code putIfAbsent} 检测重复标识，
 * 重复即 {@link IllegalStateException} 拒绝启动——"谁生效不确定"必须在启动期暴露，
 * 不能拖到调用期才发现。
 *
 * <p>额外负责密文的算法前缀拼装/解析：{@code <算法标识>:<Base64 payload>}。这是插件唯一的
 * 密文格式契约，只在这一处产出/解析——{@link CryptoProvider} 的实现类不感知这个前缀。
 * 好处有二：{@code decrypt()} 不需要调用方记得当年用的是哪种算法；换算法不需要一次性迁移
 * 存量数据，同一列里新旧算法的密文可以共存，只要旧算法对应的 Provider Bean 还注册着，
 * 历史数据就永远能解密。
 */
public class CryptoDispatcher {

    private final Map<String, CryptoProvider> providers;
    private final String defaultAlgorithm;

    public CryptoDispatcher(List<CryptoProvider> providers, String defaultAlgorithm) {
        Map<String, CryptoProvider> map = new LinkedHashMap<>();
        for (CryptoProvider provider : providers) {
            String key = provider.algorithm();
            if (key == null || key.isBlank()) {
                throw new IllegalStateException("CryptoProvider.algorithm() 不能为空: " + provider.getClass());
            }
            CryptoProvider existing = map.putIfAbsent(key, provider);
            if (existing != null) {
                throw new IllegalStateException(
                        "重复的加密算法标识 \"" + key + "\": " + existing.getClass() + " 与 " + provider.getClass());
            }
        }
        this.providers = Map.copyOf(map);

        // default-algorithm 必须命中已注册的某个 Provider，且在构造期（启动期）就校验——
        // 典型误配：default-algorithm 写了 SM4-GCM 但业务方没引 Bouncy Castle，
        // 这种情况应该在启动时直接失败，而不是拖到第一次 encrypt() 调用才发现。
        if (defaultAlgorithm == null || defaultAlgorithm.isBlank()) {
            throw new IllegalStateException("describeadmin.crypto.default-algorithm 不能为空");
        }
        if (!this.providers.containsKey(defaultAlgorithm)) {
            throw new IllegalStateException("describeadmin.crypto.default-algorithm=\"" + defaultAlgorithm
                    + "\" 未注册，已注册的算法: " + this.providers.keySet()
                    + "。若指向 SM4-GCM，请确认业务方已引入 Bouncy Castle（bcprov-jdk18on）。");
        }
        this.defaultAlgorithm = defaultAlgorithm;
    }

    /** 用默认算法加密，返回带前缀的完整密文。 */
    public String encrypt(String plainText) {
        return encrypt(defaultAlgorithm, plainText);
    }

    /** 用指定算法加密，返回带前缀的完整密文。 */
    public String encrypt(String algorithm, String plainText) {
        if (plainText == null) {
            return null;
        }
        return algorithm + ":" + require(algorithm).encryptRaw(plainText);
    }

    /**
     * 解密，自动按密文前缀路由到对应算法，调用方不需要记得当年用的是哪种算法。
     *
     * @throws IllegalArgumentException 密文没有 {@code <算法>:} 前缀（很可能是一个从未被
     *         本插件加密过的原始值，当作密文来解会得到误导性的结果，因此直接拒绝而不是
     *         当作明文放行）
     */
    public String decrypt(String cipherText) {
        if (cipherText == null) {
            return null;
        }
        int i = cipherText.indexOf(':');
        if (i < 0) {
            throw new IllegalArgumentException(
                    "不是本插件产出的密文格式（缺少 \"<算法>:\" 前缀），该值可能从未被加密过，长度=" + cipherText.length());
        }
        String algorithm = cipherText.substring(0, i);
        String payload = cipherText.substring(i + 1);
        return require(algorithm).decryptRaw(payload);
    }

    /** 用默认算法计算盲索引。 */
    public String blindIndex(String plainText) {
        return blindIndex(defaultAlgorithm, plainText);
    }

    /** 用指定算法计算盲索引。 */
    public String blindIndex(String algorithm, String plainText) {
        if (plainText == null) {
            return null;
        }
        return require(algorithm).blindIndex(plainText);
    }

    public String defaultAlgorithm() {
        return defaultAlgorithm;
    }

    /** 已注册的全部算法标识，供装配测试与诊断使用。 */
    public Set<String> algorithms() {
        return providers.keySet();
    }

    private CryptoProvider require(String algorithm) {
        CryptoProvider provider = providers.get(algorithm);
        if (provider == null) {
            throw new IllegalArgumentException("未注册的加密算法: \"" + algorithm + "\"，已注册: " + providers.keySet());
        }
        return provider;
    }
}
