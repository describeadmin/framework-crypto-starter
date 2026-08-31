package io.github.describeadmin.crypto.core;

import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import io.github.describeadmin.crypto.api.BlindIndex;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 盲索引自动填充：给 {@code @BlindIndex} 字段自动算出并写入 HMAC，业务方不需要手写任何同步逻辑。
 *
 * <p><b>零框架核心改动</b>——挂进 {@code framework-mybatis-starter} 已经开放、面向外部模块的
 * {@code ObjectProvider<InnerInterceptor>} 扩展点（{@code FrameworkMybatisAutoConfiguration}
 * 的 javadoc 明确写了"把自己的 InnerInterceptor 注册成 Bean 即可"并给出了示例）。之所以不用
 * {@code MetaObjectHandler}（{@code BaseEntity} 的 {@code createTime}/{@code updateTime}
 * 用的那套）：MyBatis-Plus 全局只允许注册一个 {@code MetaObjectHandler} Bean，核心的
 * {@code AuditMetaObjectHandler} 已经占用了这个位置，本插件再注册一个会产生 Bean 冲突。
 *
 * <p><b>时序</b>：{@code InnerInterceptor.beforeUpdate(Executor, MappedStatement, Object)}
 * 拿到的是原始实体对象引用，且严格早于 {@code MetaObjectHandler} 填充、早于
 * {@code FieldStrategy} 判定"字段是否参与这条 SQL"、早于 {@code TypeHandler.setParameter}
 * （即加密发生的时刻）。因此本类在这里读到的源字段值必然仍是明文，写回 {@code _idx}
 * 字段的值也必然赶得上后续的 SQL 参数绑定。
 *
 * <p><b>核心保护：partial update 时源字段为 {@code null} 要跳过，不写入</b>——否则会把
 * {@code _idx} 列覆盖成基于 {@code null} 算出来的错误值。这个保护要与业务实体上
 * {@code @TableField(updateStrategy = FieldStrategy.NOT_NULL)}（{@link BlindIndex} 的
 * javadoc 已经要求）配合：本类跳过不碰这个 Java 字段，{@code NOT_NULL} 策略负责把它排除出
 * UPDATE 的 SET 子句——两处配合缺一不可，与 {@code SysUser.password} 字段的处理方式一致。
 */
public class BlindIndexInnerInterceptor implements InnerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(BlindIndexInnerInterceptor.class);
    private static final List<Mapping> NO_MAPPING = List.of();

    private final CryptoDispatcher dispatcher;
    private final Map<Class<?>, List<Mapping>> mappingCache = new ConcurrentHashMap<>();

    public BlindIndexInnerInterceptor(CryptoDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void beforeUpdate(Executor executor, MappedStatement ms, Object parameter) throws SQLException {
        if (ms.getSqlCommandType() == SqlCommandType.DELETE) {
            return;
        }
        Object entity = resolveEntity(parameter);
        if (entity == null) {
            return;
        }
        List<Mapping> mappings = mappingCache.computeIfAbsent(entity.getClass(), this::scan);
        if (mappings.isEmpty()) {
            return;
        }
        for (Mapping mapping : mappings) {
            fill(entity, mapping);
        }
    }

    private void fill(Object entity, Mapping mapping) {
        Object sourceValue;
        try {
            sourceValue = mapping.sourceField.get(entity);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("读取 " + mapping.sourceField + " 失败", e);
        }
        // ⚠️ 核心保护：partial update 未带上源字段（值为 null）时跳过，不写 HMAC(null)。
        // 见类注释与 BlindIndex 注解的 javadoc。
        if (sourceValue == null) {
            return;
        }
        String algorithm = mapping.algorithm.isEmpty() ? dispatcher.defaultAlgorithm() : mapping.algorithm;
        String value = dispatcher.blindIndex(algorithm, String.valueOf(sourceValue));
        try {
            mapping.idxField.set(entity, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("写入 " + mapping.idxField + " 失败", e);
        }
    }

    /**
     * @return {@code parameter} 是 {@code Map}（MyBatis 对多参数方法的包装，键为
     *         {@link Constants#ENTITY}，即 {@code "et"}）时取出真正的实体；单参数方法
     *         （{@code insert(T)}/{@code updateById(T)}）直接透传，{@code parameter} 本身
     *         就是实体；既不是 Map 也不含 "et" 键（如 {@code deleteById(Long)}、或
     *         {@code @Update("... where id in (#{ids})")} 这类多命名参数的自定义方法）
     *         时返回 {@code null}，安全空转
     *
     * <p>⚠️ 必须先 {@code containsKey} 再 {@code get}：MyBatis 的
     * {@code MapperMethod.ParamMap} 覆写了 {@code get()}，键不存在时<b>抛
     * {@code BindingException}</b> 而不是返回 {@code null}。任何带命名参数、又没有 "et"
     * 键的自定义 mapper 方法（{@code updateXxx(@Param("a") ..., @Param("b") ...)}）
     * 走到这里，直接 {@code get("et")} 会把整个应用打挂——即便实体上一个
     * {@code @BlindIndex} 都没有，本拦截器也会在每条 UPDATE 前被调到。
     */
    private static Object resolveEntity(Object parameter) {
        if (parameter instanceof Map<?, ?> map) {
            return map.containsKey(Constants.ENTITY) ? map.get(Constants.ENTITY) : null;
        }
        return parameter;
    }

    private List<Mapping> scan(Class<?> clazz) {
        List<Mapping> result = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                BlindIndex annotation = field.getAnnotation(BlindIndex.class);
                if (annotation == null) {
                    continue;
                }
                Field sourceField = findField(clazz, annotation.source());
                if (sourceField == null) {
                    throw new IllegalStateException("@BlindIndex(source = \"" + annotation.source() + "\") 在 "
                            + clazz.getName() + " 上找不到对应的源字段，请确认字段名拼写正确");
                }
                field.setAccessible(true);
                sourceField.setAccessible(true);
                result.add(new Mapping(field, sourceField, annotation.algorithm()));
            }
            current = current.getSuperclass();
        }
        if (result.isEmpty()) {
            return NO_MAPPING;
        }
        log.debug("扫描到 {} 个 @BlindIndex 字段: {}", result.size(), clazz.getName());
        return List.copyOf(result);
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private record Mapping(Field idxField, Field sourceField, String algorithm) {
    }
}
