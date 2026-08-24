package io.github.describeadmin.crypto.api;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/**
 * 用 SM4-GCM 加密的字段：{@code @TableField(typeHandler = Sm4EncryptedStringTypeHandler.class)}。
 *
 * <p>使用本 TypeHandler 前提是业务方已引入 Bouncy Castle（{@code bcprov-jdk18on}），否则
 * {@code Sm4CryptoProvider} 不会被装配，加密时会在 {@code CryptoDispatcher} 里得到
 * "未注册的加密算法" 的 {@link IllegalArgumentException}。
 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class Sm4EncryptedStringTypeHandler extends EncryptedStringTypeHandler {

    @Override
    protected String algorithm() {
        return CryptoProvider.ALGORITHM_SM4_GCM;
    }
}
