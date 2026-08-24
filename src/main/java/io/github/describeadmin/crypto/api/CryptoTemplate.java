package io.github.describeadmin.crypto.api;

import io.github.describeadmin.crypto.core.CryptoDispatcher;

/**
 * 手动加解密门面，供业务方在 {@link EncryptedStringTypeHandler} 自动生效不了的场景下直接注入使用。
 *
 * <p><b>什么时候必须用它</b>：{@code TypeHandler} 只在 MyBatis-Plus 走标准实体映射
 * （{@code selectById}/{@code updateById}/{@code selectList} 等）时自动生效，或者自定义
 * {@code resultMap}/{@code @Results} 显式重新声明了同一个 {@code typeHandler}。以下场景都会
 * <b>绕开</b>自动加解密、且<b>不会报错</b>——拿到的是密文当明文用：
 *
 * <ul>
 *   <li>自定义 {@code @Select} 返回 {@code Map<String,Object>} 或轻量 DTO（报表/统计类查询）</li>
 *   <li>自定义 XML/{@code @Select} 查询即便返回实体类，只要没有重新显式声明
 *       {@code typeHandler}，也不会自动生效</li>
 *   <li>{@code JdbcTemplate}/原生 {@code DataSource} 查询</li>
 *   <li>批量导入/导出脚本、数据迁移工具</li>
 * </ul>
 *
 * <p>典型用法：
 *
 * <pre>{@code
 * List<Map<String, Object>> rows = mapper.selectRawByDept(deptId);
 * rows.forEach(row -> row.put("mobile", cryptoTemplate.decrypt((String) row.get("mobile"))));
 *
 * // 按明文条件在自定义查询里做等值匹配，用 blindIndex 而不是直接拿明文去比密文列
 * mapper.selectByMobileIdx(cryptoTemplate.blindIndex(mobile));
 * }</pre>
 */
public class CryptoTemplate {

    private final CryptoDispatcher dispatcher;

    public CryptoTemplate(CryptoDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        // TypeHandler 由 MyBatis 反射创建、不受 Spring 管理，需要一个静态持有者桥接——
        // 详见 EncryptedStringTypeHandler 的类注释。这里在构造函数里完成初始化：
        // Spring 单例预实例化阶段整体先于任何请求处理完成，只要本 Bean 非 @Lazy 就没有时序问题。
        EncryptedStringTypeHandler.initialize(this);
    }

    /** 用默认算法加密（{@code describeadmin.crypto.default-algorithm}）。 */
    public String encrypt(String plainText) {
        return dispatcher.encrypt(plainText);
    }

    /** 用指定算法加密，algorithm 见 {@link CryptoProvider#ALGORITHM_AES256_GCM} 等常量。 */
    public String encrypt(String algorithm, String plainText) {
        return dispatcher.encrypt(algorithm, plainText);
    }

    /** 解密，自动识别密文的算法前缀，调用方不需要记得当年用的是哪种算法。 */
    public String decrypt(String cipherText) {
        return dispatcher.decrypt(cipherText);
    }

    /** 用默认算法计算盲索引，供自定义查询拼等值条件用。 */
    public String blindIndex(String plainText) {
        return dispatcher.blindIndex(plainText);
    }

    /** 用指定算法计算盲索引。 */
    public String blindIndex(String algorithm, String plainText) {
        return dispatcher.blindIndex(algorithm, plainText);
    }
}
