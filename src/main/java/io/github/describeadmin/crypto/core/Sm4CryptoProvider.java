package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Provider;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * SM4-GCM + HmacSM3，国密算法，面向政务/信创场景的密评合规需求。依赖 Bouncy Castle
 * 提供 JCE 实现——标准 OpenJDK 不自带 SM4/SM3，这也是本插件把 Bouncy Castle 声明为
 * {@code optional} 依赖、只在业务方引入时才装配这个 Provider 的原因（见
 * {@code FrameworkCryptoAutoConfiguration.Sm4Configuration}）。
 *
 * <p><b>盲索引也用 HmacSM3，不用 HmacSHA256</b>——如果加密用国密、索引却用国际算法，
 * "全链路国密合规"这个诉求就不成立，密评通不过。这不是两个独立算法的自由组合，
 * 是同一个 Provider 内部要自成一套国密闭环。
 *
 * <p>持有一个 {@link BouncyCastleProvider} <b>实例</b>直接传给 {@code Cipher.getInstance}/
 * {@code Mac.getInstance}，不调用 {@code Security.addProvider()}——避免污染 JVM 全局
 * Provider 列表，与业务方自己也可能注册 BC 的场景互不干扰。
 */
public class Sm4CryptoProvider implements CryptoProvider {

    private static final String CIPHER_TRANSFORMATION = "SM4/GCM/NoPadding";
    private static final String MAC_ALGORITHM = "HmacSM3";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 16; // SM4 固定 128 位密钥

    private final Provider bcProvider = new BouncyCastleProvider();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec blindIndexKey;
    private final SecureRandom random = new SecureRandom();

    public Sm4CryptoProvider(String encryptionKeyBase64, String blindIndexKeyBase64) {
        this.encryptionKey = decodeKey("describeadmin.crypto.sm4.key", encryptionKeyBase64, KEY_LENGTH_BYTES, "SM4");
        this.blindIndexKey = decodeKey("describeadmin.crypto.sm4.blind-index-key", blindIndexKeyBase64, -1, "HmacSM3");
    }

    @Override
    public String algorithm() {
        return ALGORITHM_SM4_GCM;
    }

    @Override
    public String encryptRaw(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION, bcProvider);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherTextAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherTextAndTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherTextAndTag, 0, combined, iv.length, cipherTextAndTag.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SM4-GCM 加密失败", e);
        }
    }

    @Override
    public String decryptRaw(String payloadBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(payloadBase64);
            if (combined.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("密文长度不足，不是合法的 SM4-GCM 密文");
            }
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES);
            byte[] cipherTextAndTag = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherTextAndTag, 0, cipherTextAndTag.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION, bcProvider);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherTextAndTag);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SM4-GCM 解密失败，密文可能已被篡改或密钥不匹配", e);
        }
    }

    @Override
    public String blindIndex(String plainText) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM, bcProvider);
            mac.init(blindIndexKey);
            byte[] digest = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSM3 计算盲索引失败", e);
        }
    }

    private static SecretKeySpec decodeKey(String propertyName, String base64, int requiredLengthBytes, String jcaAlgorithm) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("配置项 " + propertyName + " 未设置，Sm4CryptoProvider 无法初始化");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("配置项 " + propertyName + " 不是合法的 Base64 编码", e);
        }
        if (requiredLengthBytes > 0 && bytes.length != requiredLengthBytes) {
            throw new IllegalStateException("配置项 " + propertyName + " 解码后长度应为 " + requiredLengthBytes
                    + " 字节，实际 " + bytes.length + " 字节");
        }
        return new SecretKeySpec(bytes, jcaAlgorithm);
    }
}
