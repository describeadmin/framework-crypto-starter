package io.github.describeadmin.crypto.core;

import io.github.describeadmin.crypto.api.CryptoProvider;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * AES-256-GCM，JDK 原生 {@code SunJCE} 提供，零额外依赖——这是本插件默认必定可用的算法，
 * 不依赖 Bouncy Castle 或其他第三方 JCE Provider。
 *
 * <p>密钥与盲索引密钥各自独立配置、互不复用——盲索引密钥泄露不应该等价于加密密钥泄露，
 * 这是基本的密钥分离实践。
 */
public class AesCryptoProvider implements CryptoProvider {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final int GCM_IV_LENGTH_BYTES = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int KEY_LENGTH_BYTES = 32; // 256 位

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec blindIndexKey;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param encryptionKeyBase64  Base64 编码的 32 字节（256 位）密钥
     * @param blindIndexKeyBase64  Base64 编码的 HMAC 密钥，长度不强制，但必须与加密密钥不同
     */
    public AesCryptoProvider(String encryptionKeyBase64, String blindIndexKeyBase64) {
        this.encryptionKey = decodeKey("describeadmin.crypto.aes.key", encryptionKeyBase64, KEY_LENGTH_BYTES, "AES");
        this.blindIndexKey = decodeKey("describeadmin.crypto.aes.blind-index-key", blindIndexKeyBase64, -1, "HmacSHA256");
    }

    @Override
    public String algorithm() {
        return ALGORITHM_AES256_GCM;
    }

    @Override
    public String encryptRaw(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] cipherTextAndTag = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherTextAndTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherTextAndTag, 0, combined, iv.length, cipherTextAndTag.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-256-GCM 加密失败", e);
        }
    }

    @Override
    public String decryptRaw(String payloadBase64) {
        try {
            byte[] combined = Base64.getDecoder().decode(payloadBase64);
            if (combined.length < GCM_IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("密文长度不足，不是合法的 AES256-GCM 密文");
            }
            byte[] iv = new byte[GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES);
            byte[] cipherTextAndTag = new byte[combined.length - GCM_IV_LENGTH_BYTES];
            System.arraycopy(combined, GCM_IV_LENGTH_BYTES, cipherTextAndTag, 0, cipherTextAndTag.length);

            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] plainBytes = cipher.doFinal(cipherTextAndTag);
            return new String(plainBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // GCM 认证标签校验失败（密文被篡改）也会落到这里（AEADBadTagException 是它的子类）——
            // 必须显式失败，绝不能吞掉异常返回垃圾明文。
            throw new IllegalStateException("AES-256-GCM 解密失败，密文可能已被篡改或密钥不匹配", e);
        }
    }

    @Override
    public String blindIndex(String plainText) {
        try {
            Mac mac = Mac.getInstance(MAC_ALGORITHM);
            mac.init(blindIndexKey);
            byte[] digest = mac.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 计算盲索引失败", e);
        }
    }

    private static SecretKeySpec decodeKey(String propertyName, String base64, int requiredLengthBytes, String jcaAlgorithm) {
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException("配置项 " + propertyName + " 未设置，AesCryptoProvider 无法初始化");
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
