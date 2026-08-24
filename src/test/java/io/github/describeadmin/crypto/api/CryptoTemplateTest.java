package io.github.describeadmin.crypto.api;

import io.github.describeadmin.crypto.core.AesCryptoProvider;
import io.github.describeadmin.crypto.core.CryptoDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CryptoTemplate")
class CryptoTemplateTest {

    private static final String KEY = "raX1kbnki6EOTZuDwNPmyKvyH19v0T/7584NOnEFQXM=";
    private static final String BLIND_INDEX_KEY = "VLfqdPd1waoKazQ5ARsoljLG1FOBoxtQg50A+t0xlFA=";

    private final AesCryptoProvider aesProvider = new AesCryptoProvider(KEY, BLIND_INDEX_KEY);
    private final CryptoDispatcher dispatcher =
            new CryptoDispatcher(List.of(aesProvider), CryptoProvider.ALGORITHM_AES256_GCM);
    private final CryptoTemplate template = new CryptoTemplate(dispatcher);

    @Test
    @DisplayName("五个方法都正确委托给 CryptoDispatcher，加解密往返一致")
    void delegatesToDispatcher() {
        String cipherText = template.encrypt("13800001234");
        assertThat(cipherText).startsWith(CryptoProvider.ALGORITHM_AES256_GCM + ":");
        assertThat(template.decrypt(cipherText)).isEqualTo("13800001234");

        String cipherTextWithAlgorithm = template.encrypt(CryptoProvider.ALGORITHM_AES256_GCM, "13800001234");
        assertThat(template.decrypt(cipherTextWithAlgorithm)).isEqualTo("13800001234");

        assertThat(template.blindIndex("13800001234"))
                .isEqualTo(template.blindIndex(CryptoProvider.ALGORITHM_AES256_GCM, "13800001234"));
    }

    @Test
    @DisplayName("构造时完成 EncryptedStringTypeHandler 的静态桥接初始化——TypeHandler 能正常工作而不抛\"尚未初始化\"")
    void initializesTypeHandlerBridge() {
        AesEncryptedStringTypeHandler handler = new AesEncryptedStringTypeHandler();
        // 只要不抛 IllegalStateException("尚未初始化") 就说明桥接生效；
        // TypeHandler 的完整 JDBC 往返由 MySQL Testcontainers 集成测试覆盖。
        assertThat(handler).isNotNull();
    }
}
