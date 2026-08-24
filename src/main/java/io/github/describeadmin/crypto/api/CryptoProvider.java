package io.github.describeadmin.crypto.api;

/**
 * 敏感字段加解密算法的 SPI。
 *
 * <p><b>多实现共存模型，不是"单一默认实现整体替换"模型</b>——与核心的
 * {@code NotifyChannel} 是同一类设计，不是 {@code CacheProvider}/{@code TokenStore} 那种
 * {@code @ConditionalOnMissingBean} 整体替换模型。同一个 Spring 容器里可以同时存在
 * {@code AES256-GCM} 与 {@code SM4-GCM} 两个 Provider，分别服务不同字段——业务方按需
 * 在字段的 {@code TypeHandler} 上选择用哪一种，不是装了新算法插件就顶替旧的。
 *
 * <p>{@link #algorithm()} 是同一容器内的唯一标识，{@code CryptoDispatcher} 会在构造期
 * 检测重复标识并直接拒绝启动（同 {@code NotifyDispatcher} 对 {@code channel()} 的处理）。
 * 这个标识同时是密文的前缀（见 {@code CryptoDispatcher} 的密文格式），使得同一列在换算法后
 * 新旧密文仍能共存、都能正确解密，不需要一次性迁移存量数据。
 *
 * <p>本接口位于 {@code api} 包下，属于兼容性承诺范围。
 */
public interface CryptoProvider {

    /** JDK 原生 AES-256-GCM 的算法标识。 */
    String ALGORITHM_AES256_GCM = "AES256-GCM";

    /** 依赖 Bouncy Castle 的国密 SM4-GCM 的算法标识。 */
    String ALGORITHM_SM4_GCM = "SM4-GCM";

    /**
     * 算法标识，同时也是密文前缀的一部分。同一容器内不允许重复
     * （由 {@code CryptoDispatcher} 的构造函数负责检测并拒绝）。
     */
    String algorithm();

    /**
     * 加密。
     *
     * @param plainText 明文，不为 {@code null}
     * @return Base64 编码的 {@code IV || 密文+Tag}（GCM 等认证加密模式下密文与 Tag
     *         由底层 {@code Cipher} 一次性产出，不需要分开处理）——<b>不含算法前缀</b>，
     *         前缀由 {@code CryptoDispatcher} 统一拼装。不要在实现里自己加前缀，
     *         密文格式是插件唯一契约，只能有一处产出/解析。
     */
    String encryptRaw(String plainText);

    /**
     * 解密，入参是已经被 {@code CryptoDispatcher} 剥离算法前缀之后的 payload。
     *
     * @param payloadBase64 {@link #encryptRaw(String)} 的返回值
     * @return 明文
     * @throws RuntimeException 密文被篡改（GCM 认证标签校验失败）或格式不合法时抛出，
     *         不允许静默返回垃圾明文
     */
    String decryptRaw(String payloadBase64);

    /**
     * 计算盲索引：一个确定性的 MAC（如 HMAC），供等值查询用。
     *
     * <p>同一明文 + 同一密钥永远得到同一个值（这是它区别于 {@link #encryptRaw(String)}
     * 的关键——后者必须随机化）；不可逆，不能从返回值反推明文。
     *
     * <p>只支持等值查询，不支持模糊/范围查询——MAC 输出与输入之间没有"接近关系"，
     * 这是使用盲索引前必须知道的边界，业务方不应尝试用它做 {@code LIKE} 查询。
     *
     * @param plainText 明文
     * @return 定长、可安全存库/建索引的确定性摘要
     */
    String blindIndex(String plainText);
}
