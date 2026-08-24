package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Sm4CryptoProvider")
class Sm4CryptoProviderTest {

    private static final String KEY = "zRCKIYwcbURwVLg+ZVfcGw=="; // 16 字节
    private static final String BLIND_INDEX_KEY = "uGwr7OVo8uvfJBo8bB1YQAIah4TDcjWWwOgkjaZtRtI=";

    private final Sm4CryptoProvider provider = new Sm4CryptoProvider(KEY, BLIND_INDEX_KEY);

    @Test
    @DisplayName("algorithm() 返回 SM4-GCM")
    void algorithmIsSm4Gcm() {
        assertThat(provider.algorithm()).isEqualTo(CryptoProvider.ALGORITHM_SM4_GCM);
    }

    @Nested
    @DisplayName("加解密")
    class EncryptDecrypt {

        @Test
        @DisplayName("加密后解密能拿回原始明文")
        void roundTrip() {
            String plain = "440101199001011234";
            String cipher = provider.encryptRaw(plain);
            assertThat(provider.decryptRaw(cipher)).isEqualTo(plain);
        }

        @Test
        @DisplayName("中文明文往返不丢字符集信息")
        void chineseRoundTrip() {
            String plain = "超级管理员";
            assertThat(provider.decryptRaw(provider.encryptRaw(plain))).isEqualTo(plain);
        }

        @Test
        @DisplayName("同一明文两次加密，密文不同——IV 随机化生效")
        void sameInputProducesDifferentCipherText() {
            String plain = "13800001234";
            assertThat(provider.encryptRaw(plain)).isNotEqualTo(provider.encryptRaw(plain));
        }

        @Test
        @DisplayName("篡改密文任意一字节，解密时因 GCM 认证标签校验失败而抛异常")
        void tamperedCipherTextFailsAuthentication() {
            String cipher = provider.encryptRaw("13800001234");
            byte[] bytes = Base64.getDecoder().decode(cipher);
            bytes[bytes.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(bytes);

            assertThatThrownBy(() -> provider.decryptRaw(tampered))
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * 交叉验证：不能只用 {@link Sm4CryptoProvider#decryptRaw(String)} 自证自己——
         * 那样即便实现内部逻辑自洽也测不出"密文是否真的是 SM4-GCM 语义"这件事。
         * 这里独立用 Bouncy Castle 原生 API 解密一遍，验证产出的密文格式（IV || 密文+Tag）
         * 与算法（SM4/GCM/NoPadding）确实符合预期。
         */
        @Test
        @DisplayName("产出的密文能被独立的 BC 原生 API 正确解密——交叉验证密文语义")
        void cipherTextIsDecryptableByRawBouncyCastleApi() throws Exception {
            String plain = "交叉验证-13900001234";
            String cipherText = provider.encryptRaw(plain);

            byte[] combined = Base64.getDecoder().decode(cipherText);
            byte[] iv = new byte[12];
            System.arraycopy(combined, 0, iv, 0, 12);
            byte[] cipherAndTag = new byte[combined.length - 12];
            System.arraycopy(combined, 12, cipherAndTag, 0, cipherAndTag.length);

            Cipher cipher = Cipher.getInstance("SM4/GCM/NoPadding", new BouncyCastleProvider());
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(Base64.getDecoder().decode(KEY), "SM4"),
                    new GCMParameterSpec(128, iv));
            byte[] plainBytes = cipher.doFinal(cipherAndTag);

            assertThat(new String(plainBytes, StandardCharsets.UTF_8)).isEqualTo(plain);
        }
    }

    @Nested
    @DisplayName("盲索引")
    class BlindIndex {

        @Test
        @DisplayName("同一明文永远得到同一个盲索引值（确定性）")
        void deterministic() {
            assertThat(provider.blindIndex("13800001234")).isEqualTo(provider.blindIndex("13800001234"));
        }

        @Test
        @DisplayName("明文相差一个字符，盲索引值完全不同（雪崩性）")
        void avalanche() {
            assertThat(provider.blindIndex("13800001234")).isNotEqualTo(provider.blindIndex("13800001235"));
        }

        @Test
        @DisplayName("盲索引是定长十六进制字符串（HmacSM3 摘要 = 32 字节 = 64 个十六进制字符）")
        void fixedLengthHex() {
            assertThat(provider.blindIndex("x")).hasSize(64).matches("^[0-9a-f]{64}$");
        }
    }

    @Nested
    @DisplayName("密钥校验")
    class KeyValidation {

        @Test
        @DisplayName("加密密钥长度不是 16 字节时，构造函数直接失败")
        void wrongLengthKeyFailsFast() {
            String wrongKey = Base64.getEncoder().encodeToString(new byte[32]);
            assertThatThrownBy(() -> new Sm4CryptoProvider(wrongKey, BLIND_INDEX_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("16");
        }
    }
}
