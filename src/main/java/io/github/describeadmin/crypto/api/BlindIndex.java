package io.github.describeadmin.crypto.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在盲索引字段上，声明它的明文来源与使用的算法。
 *
 * <p>典型用法：
 *
 * <pre>{@code
 * @TableName(value = "sys_member", autoResultMap = true)  // 见 EncryptedStringTypeHandler 的说明
 * public class Member {
 *     @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
 *     private String idCard;
 *
 *     @BlindIndex(source = "idCard")
 *     @TableField(updateStrategy = FieldStrategy.NOT_NULL)
 *     private String idCardIdx;
 * }
 * }</pre>
 *
 * <p>业务方只需要声明这两个字段（以及对应的 DDL 列），插入/更新时插件的
 * {@code BlindIndexInnerInterceptor} 会自动从 {@link #source()} 指向的明文字段算出盲索引
 * 写入本字段，不需要手写任何同步逻辑。
 *
 * <p><b>{@code idCardIdx} 字段必须加</b> {@code @TableField(updateStrategy = FieldStrategy.NOT_NULL)}
 * <b>——这条不是可选项</b>。partial update 时如果这次调用没有带上 {@code idCard}（源字段为
 * {@code null}），拦截器会跳过、不写入这个字段，但如果实体上没有这条
 * {@code updateStrategy}，MyBatis-Plus 默认行为仍会把 {@code null} 显式写进 UPDATE 语句，
 * 把已有的索引值覆盖成 NULL。两处配合缺一不可，与 {@code SysUser.password} 字段的处理方式
 * 是同一个模式。
 *
 * <p><b>{@code idCard} 本身同样建议加</b> {@code @TableField(updateStrategy = FieldStrategy.NOT_NULL)}
 * ——原因相同：partial update 没带上这个字段时，若没有这条策略，密文列也会被显式置 NULL。
 * 这不是本注解强制要求的（{@code BlindIndexInnerInterceptor} 只负责保护 {@code idCardIdx}），
 * 但几乎总是加密字段该有的配置，示例里一并给出。
 *
 * <p>本注解位于 {@code api} 包下，属于兼容性承诺范围。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface BlindIndex {

    /**
     * 同一实体内、持有明文的源字段的 <b>Java 字段名</b>（不是数据库列名），如 {@code "idCard"}。
     */
    String source();

    /**
     * 使用的算法标识（{@link CryptoProvider#algorithm()}）。留空表示使用
     * {@code CryptoDispatcher} 的 {@code default-algorithm}。
     */
    String algorithm() default "";
}
