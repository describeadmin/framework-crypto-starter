package io.github.describeadmin.crypto.it;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.crypto.api.BlindIndex;
import io.github.describeadmin.crypto.api.Sm4EncryptedStringTypeHandler;

/**
 * 用 SM4-GCM 加密 idCard 的测试实体，与 {@link CryptoTestEntity} 映射<b>同一张表</b>——
 * 用来验证"同一列里 AES/SM4 两种算法的密文可以共存，且都能被任意一个实体的
 * TypeHandler 正确解密（解密走密文前缀路由，不依赖当前 TypeHandler 配的是哪种算法）"。
 */
@TableName(value = "crypto_test_entity", autoResultMap = true)
public class CryptoTestEntitySm4 {

    @TableId
    private Long id;

    @TableField(typeHandler = Sm4EncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String idCard;

    @BlindIndex(source = "idCard", algorithm = "SM4-GCM")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String idCardIdx;

    private String otherField;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getIdCard() {
        return idCard;
    }

    public void setIdCard(String idCard) {
        this.idCard = idCard;
    }

    public String getIdCardIdx() {
        return idCardIdx;
    }

    public String getOtherField() {
        return otherField;
    }

    public void setOtherField(String otherField) {
        this.otherField = otherField;
    }
}
