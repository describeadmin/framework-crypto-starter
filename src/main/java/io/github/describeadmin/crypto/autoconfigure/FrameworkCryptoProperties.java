package io.github.describeadmin.crypto.autoconfigure;

import io.github.describeadmin.crypto.api.CryptoProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/** {@code describeadmin.crypto} 前缀下的全部配置项。 */
@ConfigurationProperties(prefix = "describeadmin.crypto")
public class FrameworkCryptoProperties {

    /** 顶层运行时开关，关掉后等同于没引这个 jar。 */
    private boolean enabled = true;

    /** {@link io.github.describeadmin.crypto.api.CryptoTemplate} 无参方法使用的算法。 */
    private String defaultAlgorithm = CryptoProvider.ALGORITHM_AES256_GCM;

    @NestedConfigurationProperty
    private final Aes aes = new Aes();

    @NestedConfigurationProperty
    private final Sm4 sm4 = new Sm4();

    @NestedConfigurationProperty
    private final BlindIndexConfig blindIndex = new BlindIndexConfig();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultAlgorithm() {
        return defaultAlgorithm;
    }

    public void setDefaultAlgorithm(String defaultAlgorithm) {
        this.defaultAlgorithm = defaultAlgorithm;
    }

    public Aes getAes() {
        return aes;
    }

    public Sm4 getSm4() {
        return sm4;
    }

    public BlindIndexConfig getBlindIndex() {
        return blindIndex;
    }

    /** AES-256-GCM，JDK 原生、默认必定可用。 */
    public static class Aes {

        private boolean enabled = true;

        /** Base64 编码，解码后必须是 32 字节（256 位）。未设置时 Bean 创建期直接失败。 */
        private String key;

        /** Base64 编码，HMAC-SHA256 密钥，不与 {@link #key} 复用。 */
        private String blindIndexKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getBlindIndexKey() {
            return blindIndexKey;
        }

        public void setBlindIndexKey(String blindIndexKey) {
            this.blindIndexKey = blindIndexKey;
        }
    }

    /** SM4-GCM + HmacSM3，仅当 classpath 上有 Bouncy Castle 时才会真正装配。 */
    public static class Sm4 {

        private boolean enabled = true;

        /** Base64 编码，解码后必须是 16 字节（128 位）。 */
        private String key;

        /** Base64 编码，HmacSM3 密钥，不与 {@link #key} 复用。 */
        private String blindIndexKey;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getBlindIndexKey() {
            return blindIndexKey;
        }

        public void setBlindIndexKey(String blindIndexKey) {
            this.blindIndexKey = blindIndexKey;
        }
    }

    /** 盲索引自动填充（{@code @BlindIndex}），可以单独关闭而不影响加解密本身。 */
    public static class BlindIndexConfig {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
