package io.github.describeadmin.crypto.api;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 透明加解密 {@code TypeHandler} 的抽象基类。业务方在实体与字段上声明：
 *
 * <pre>{@code
 * @TableName(value = "sys_member", autoResultMap = true)   // ⚠️ 见下方"必须"说明
 * public class Member {
 *     @TableField(typeHandler = AesEncryptedStringTypeHandler.class)
 *     private String idCard;
 * }
 * }</pre>
 *
 * 加密/解密对业务代码完全透明——getter/setter 全程只看到明文。
 *
 * <p><b>⚠️ {@code @TableName(autoResultMap = true)} 不是可选项，是必须项</b>——这是本插件
 * 落地时最容易被漏掉、且后果最隐蔽的一条，已用真实 MySQL 集成测试验证过其必要性。
 * {@code @TableField(typeHandler = ...)} 对 INSERT/UPDATE 天然生效（typeHandler 内联在
 * MyBatis-Plus 生成的 SQL 参数占位符里，不依赖 ResultMap），<b>但 SELECT 不会自动生效</b>——
 * 没有 {@code autoResultMap = true}，MyBatis-Plus 不会为该实体生成携带 typeHandler 信息的
 * ResultMap，查询退回普通的自动结果映射，直接把密文列的原始字符串当作字段值返回。
 * <b>症状是不报错</b>：{@code selectById}/{@code selectList} 能正常跑完，只是拿到的
 * {@code idCard} 是一串 {@code "AES256-GCM:xxx"} 而不是明文——与 CLAUDE.md §3.6
 * 字符集那条坑是同一类"看起来正常、实际全错"。忘记加这个属性不会在任何一层报错，
 * 只会在业务代码里看到密文，务必在建实体时就检查这一条。
 *
 * <p><b>为什么需要一个静态持有者（service-locator）</b>：MyBatis-Plus 在
 * {@code TableInfoHelper} 初始化时通过反射直接 {@code new} 出 {@code TypeHandler} 实例，
 * <b>不会</b>走 Spring 依赖注入——这是 MyBatis 生态里众所周知的限制，不是本插件独有的坑。
 * {@link CryptoTemplate} 的构造函数会调用 {@link #initialize(CryptoTemplate)} 完成桥接；
 * 只要该 Bean 非 {@code @Lazy}，Spring 单例预实例化阶段（整体发生在
 * {@code context.refresh()} 返回之前）就会跑完这一步，而任何会真正执行 SQL 的时机
 * （Controller 处理请求、{@code CommandLineRunner}）严格发生在 {@code refresh()} 返回之后，
 * 因此正常使用下不会有时序问题。
 *
 * <p><b>唯一需要知道的边界</b>：如果业务方自己写的某个 Bean，在<b>它自己的</b>构造函数/
 * {@code @PostConstruct}（即 Spring 单例实例化阶段<b>内部</b>）里就去查询一张带加密列的表，
 * 而这个 Bean 恰好比 {@code CryptoTemplate} 更早被实例化，会在此刻拿到
 * "尚未初始化"的 {@link IllegalStateException}。应避免在 Bean 构造期查加密列。
 */
public abstract class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    private static volatile CryptoTemplate template;

    /** 由 {@link CryptoTemplate} 的构造函数调用，业务代码不需要、也不应该直接调用本方法。 */
    static void initialize(CryptoTemplate cryptoTemplate) {
        template = cryptoTemplate;
    }

    /** 本 TypeHandler 加密时使用的算法标识，解密时不需要——密文前缀会自动路由到正确的算法。 */
    protected abstract String algorithm();

    private static CryptoTemplate template() {
        CryptoTemplate t = template;
        if (t == null) {
            throw new IllegalStateException("EncryptedStringTypeHandler 尚未初始化：请确认已引入 "
                    + "framework-crypto-starter 且 describeadmin.crypto.enabled=true——TypeHandler 由 "
                    + "MyBatis 反射创建、不受 Spring 管理，必须等 CryptoTemplate Bean 构造完成才能使用，"
                    + "见类注释里的边界说明。");
        }
        return t;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int columnIndex, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(columnIndex, template().encrypt(algorithm(), parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return decryptOrNull(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return decryptOrNull(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return decryptOrNull(cs.getString(columnIndex));
    }

    private static String decryptOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        // 解密走密文前缀自动路由，不依赖"当前这个 TypeHandler 实例配的是哪种算法"——
        // 这正是同一列可以新旧算法密文共存、换算法不用一次性迁移存量数据的机制所在。
        return template().decrypt(raw);
    }
}
