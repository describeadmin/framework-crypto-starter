package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AesCryptoProvider")
class AesCryptoProviderTest {

    private static final String KEY = "raX1kbnki6EOTZuDwNPmyKvyH19v0T/7584NOnEFQXM="; // 32 字节
    private static final String BLIND_INDEX_KEY = "VLfqdPd1waoKazQ5ARsoljLG1FOBoxtQg50A+t0xlFA=";

    private final AesCryptoProvider provider = new AesCryptoProvider(KEY, BLIND_INDEX_KEY);

    @Test
    @DisplayName("algorithm() 返回 AES256-GCM")
    void algorithmIsAes256Gcm() {
        assertThat(provider.algorithm()).isEqualTo(CryptoProvider.ALGORITHM_AES256_GCM);
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
        @DisplayName("中文明文往返不丢字符集信息（CLAUDE.md 3.6 断言比对具体值的纪律）")
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
        @DisplayName("密文不含算法前缀——前缀只由 CryptoDispatcher 拼装")
        void rawCipherTextHasNoAlgorithmPrefix() {
            assertThat(provider.encryptRaw("abc")).doesNotContain(":");
        }

        @Test
        @DisplayName("篡改密文任意一字节，解密时因 GCM 认证标签校验失败而抛异常，不返回垃圾明文")
        void tamperedCipherTextFailsAuthentication() {
            String cipher = provider.encryptRaw("13800001234");
            byte[] bytes = Base64.getDecoder().decode(cipher);
            bytes[bytes.length - 1] ^= 0x01; // 翻转最后一个字节（落在 GCM Tag 区域内）
            String tampered = Base64.getEncoder().encodeToString(bytes);

            assertThatThrownBy(() -> provider.decryptRaw(tampered))
                    .isInstanceOf(IllegalStateException.class);
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
        @DisplayName("盲索引是定长十六进制字符串（HmacSHA256 = 64 个十六进制字符）")
        void fixedLengthHex() {
            assertThat(provider.blindIndex("x")).hasSize(64).matches("^[0-9a-f]{64}$");
        }
    }

    @Nested
    @DisplayName("密钥校验")
    class KeyValidation {

        @Test
        @DisplayName("加密密钥未设置时，构造函数直接失败")
        void missingKeyFailsFast() {
            assertThatThrownBy(() -> new AesCryptoProvider(null, BLIND_INDEX_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("describeadmin.crypto.aes.key");
        }

        @Test
        @DisplayName("加密密钥长度不是 32 字节时，构造函数直接失败")
        void wrongLengthKeyFailsFast() {
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> new AesCryptoProvider(shortKey, BLIND_INDEX_KEY))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32");
        }

        @Test
        @DisplayName("密钥不是合法 Base64 时，构造函数直接失败")
        void invalidBase64FailsFast() {
            assertThatThrownBy(() -> new AesCryptoProvider("not-base64!!!", BLIND_INDEX_KEY))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
