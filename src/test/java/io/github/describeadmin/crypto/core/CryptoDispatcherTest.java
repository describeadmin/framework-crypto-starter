package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CryptoDispatcher")
class CryptoDispatcherTest {

    /** 最简单的伪实现：加密就是加前缀 "ENC[...]"，方便断言前缀路由是否正确，不涉及真实密码学。 */
    private static class FakeProvider implements CryptoProvider {
        private final String algorithm;

        FakeProvider(String algorithm) {
            this.algorithm = algorithm;
        }

        @Override
        public String algorithm() {
            return algorithm;
        }

        @Override
        public String encryptRaw(String plainText) {
            return "ENC[" + algorithm + "|" + plainText + "]";
        }

        @Override
        public String decryptRaw(String payloadBase64) {
            return payloadBase64.substring(("ENC[" + algorithm + "|").length(), payloadBase64.length() - 1);
        }

        @Override
        public String blindIndex(String plainText) {
            return "IDX[" + algorithm + "|" + plainText + "]";
        }
    }

    private final FakeProvider aes = new FakeProvider("FAKE-A");
    private final FakeProvider sm4 = new FakeProvider("FAKE-B");

    @Test
    @DisplayName("构造期发现重复的 algorithm() 标识，直接拒绝启动")
    void duplicateAlgorithmFailsFast() {
        assertThatThrownBy(() -> new CryptoDispatcher(
                List.of(aes, new FakeProvider("FAKE-A")), "FAKE-A"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FAKE-A");
    }

    @Test
    @DisplayName("default-algorithm 未命中任何已注册 Provider 时，构造期直接失败")
    void unknownDefaultAlgorithmFailsFast() {
        assertThatThrownBy(() -> new CryptoDispatcher(List.of(aes), "NOT-REGISTERED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT-REGISTERED");
    }

    @Test
    @DisplayName("加密结果带 <算法>: 前缀，解密时按前缀自动路由到正确的 Provider")
    void encryptDecryptRoundTripWithPrefixRouting() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes, sm4), "FAKE-A");

        String cipherText = dispatcher.encrypt("FAKE-B", "hello");
        assertThat(cipherText).startsWith("FAKE-B:");
        assertThat(dispatcher.decrypt(cipherText)).isEqualTo("hello");
    }

    @Test
    @DisplayName("不传算法时用 default-algorithm")
    void usesDefaultAlgorithmWhenNotSpecified() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes, sm4), "FAKE-B");
        assertThat(dispatcher.encrypt("hello")).startsWith("FAKE-B:");
        assertThat(dispatcher.blindIndex("hello")).isEqualTo("IDX[FAKE-B|hello]");
    }

    @Test
    @DisplayName("解密一个没有 <算法>: 前缀的字符串——拒绝当明文放行，直接抛异常")
    void decryptWithoutPrefixThrows() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes), "FAKE-A");
        assertThatThrownBy(() -> dispatcher.decrypt("这不是密文"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("解密一个前缀指向未注册算法的密文，异常信息里列出已注册的算法")
    void decryptWithUnregisteredAlgorithmPrefixThrows() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes), "FAKE-A");
        assertThatThrownBy(() -> dispatcher.decrypt("FAKE-Z:whatever"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FAKE-Z")
                .hasMessageContaining("FAKE-A");
    }

    @Test
    @DisplayName("algorithms() 返回全部已注册的算法标识")
    void algorithmsReturnsAllRegistered() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes, sm4), "FAKE-A");
        assertThat(dispatcher.algorithms()).containsExactlyInAnyOrder("FAKE-A", "FAKE-B");
    }

    @Test
    @DisplayName("encrypt/decrypt/blindIndex 对 null 输入返回 null，而不是抛异常")
    void nullInputReturnsNull() {
        CryptoDispatcher dispatcher = new CryptoDispatcher(List.of(aes), "FAKE-A");
        assertThat(dispatcher.encrypt(null)).isNull();
        assertThat(dispatcher.decrypt(null)).isNull();
        assertThat(dispatcher.blindIndex(null)).isNull();
    }
}
