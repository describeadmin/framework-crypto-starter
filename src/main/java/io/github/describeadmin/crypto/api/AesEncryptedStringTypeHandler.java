package io.github.describeadmin.crypto.api;

import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

/** 用 AES-256-GCM 加密的字段：{@code @TableField(typeHandler = AesEncryptedStringTypeHandler.class)}。 */
@MappedTypes(String.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class AesEncryptedStringTypeHandler extends EncryptedStringTypeHandler {

    @Override
    protected String algorithm() {
        return CryptoProvider.ALGORITHM_AES256_GCM;
    }
}
