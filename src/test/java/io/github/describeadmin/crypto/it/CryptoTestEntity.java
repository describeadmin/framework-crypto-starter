package io.github.describeadmin.crypto.it;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.github.describeadmin.crypto.api.AesEncryptedStringTypeHandler;
import io.github.describeadmin.crypto.api.BlindIndex;

/**
 * 用 AES-256-GCM 加密 idCard 的测试实体。
 *
 * <p>{@code autoResultMap = true} <b>不是可选项</b>——{@code @TableField(typeHandler = ...)}
 * 只靠这个属性才会被 MyBatis-Plus 用来生成带 typeHandler 信息的 ResultMap 供 SELECT 使用；
 * 不开启时，INSERT/UPDATE 仍会正确调用 typeHandler 加密（那部分是内联在生成的 SQL 参数占位符里的，
 * 不依赖 ResultMap），但 SELECT 会退回普通的自动结果映射，直接把密文当字符串返回——不报错，
 * 只是拿到的是密文当明文用。这条必须在插件文档里显著标注。
 */
@TableName(value = "crypto_test_entity", autoResultMap = true)
public class CryptoTestEntity {

    @TableId
    private Long id;

    // 与 idCardIdx 同一个道理：partial update 不带 idCard 时不应该把密文列覆盖成 NULL。
    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String idCard;

    @BlindIndex(source = "idCard")
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
