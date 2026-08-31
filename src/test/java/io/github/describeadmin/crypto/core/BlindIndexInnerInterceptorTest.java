package io.github.describeadmin.crypto.core;

import com.baomidou.mybatisplus.core.toolkit.Constants;
import io.github.describeadmin.crypto.api.BlindIndex;
import org.apache.ibatis.binding.MapperMethod;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BlindIndexInnerInterceptor")
class BlindIndexInnerInterceptorTest {

    static class TestEntity {
        private String idCard;

        @BlindIndex(source = "idCard")
        private String idCardIdx;

        String getIdCard() {
            return idCard;
        }

        void setIdCard(String idCard) {
            this.idCard = idCard;
        }

        String getIdCardIdx() {
            return idCardIdx;
        }
    }

    static class NoBlindIndexEntity {
        private String plainField = "x";
    }

    private CryptoDispatcher dispatcher;
    private BlindIndexInnerInterceptor interceptor;
    private Executor executor;
    private MappedStatement updateStatement;
    private MappedStatement deleteStatement;

    @BeforeEach
    void setUp() {
        AesCryptoProvider aes = new AesCryptoProvider(
                "raX1kbnki6EOTZuDwNPmyKvyH19v0T/7584NOnEFQXM=",
                "VLfqdPd1waoKazQ5ARsoljLG1FOBoxtQg50A+t0xlFA=");
        dispatcher = new CryptoDispatcher(List.of(aes), aes.algorithm());
        interceptor = new BlindIndexInnerInterceptor(dispatcher);

        executor = mock(Executor.class);
        updateStatement = mock(MappedStatement.class);
        when(updateStatement.getSqlCommandType()).thenReturn(SqlCommandType.UPDATE);
        deleteStatement = mock(MappedStatement.class);
        when(deleteStatement.getSqlCommandType()).thenReturn(SqlCommandType.DELETE);
    }

    @Nested
    @DisplayName("核心保护：源字段为 null 时跳过")
    class NullSourceProtection {

        @Test
        @DisplayName("源字段（idCard）为 null 时，不写入 idCardIdx——不能把索引列覆盖成基于 null 算出来的值")
        void skipsWhenSourceFieldIsNull() throws Exception {
            TestEntity entity = new TestEntity(); // idCard 未设置，为 null
            interceptor.beforeUpdate(executor, updateStatement, entity);
            assertThat(entity.getIdCardIdx()).isNull();
        }

        @Test
        @DisplayName("源字段非 null 时，正确计算并写入盲索引")
        void fillsWhenSourceFieldIsPresent() throws Exception {
            TestEntity entity = new TestEntity();
            entity.setIdCard("440101199001011234");

            interceptor.beforeUpdate(executor, updateStatement, entity);

            assertThat(entity.getIdCardIdx()).isEqualTo(dispatcher.blindIndex("440101199001011234"));
        }
    }

    @Nested
    @DisplayName("入参形态解析")
    class ParameterResolution {

        @Test
        @DisplayName("单参数方法（insert(T)/updateById(T)）：parameter 本身就是实体")
        void bareEntityParameter() throws Exception {
            TestEntity entity = new TestEntity();
            entity.setIdCard("13800001234");
            interceptor.beforeUpdate(executor, updateStatement, entity);
            assertThat(entity.getIdCardIdx()).isNotNull();
        }

        @Test
        @DisplayName("多参数方法（update(T, Wrapper)）：MyBatis 包装成 Map，键为 Constants.ENTITY(\"et\")")
        void paramMapParameter() throws Exception {
            TestEntity entity = new TestEntity();
            entity.setIdCard("13800001234");
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put(Constants.ENTITY, entity);
            paramMap.put("ew", "占位，不是实体");

            interceptor.beforeUpdate(executor, updateStatement, paramMap);

            assertThat(entity.getIdCardIdx()).isNotNull();
        }

        @Test
        @DisplayName("非实体参数（如 deleteById(Long) 的 Long）安全空转，不抛异常")
        void nonEntityParameterIsSafeNoop() throws Exception {
            interceptor.beforeUpdate(executor, updateStatement, 123L);
            // 未抛异常即通过
        }

        @Test
        @DisplayName("Map 参数里没有 Constants.ENTITY 键时安全空转")
        void mapWithoutEntityKeyIsSafeNoop() throws Exception {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("something-else", "value");
            interceptor.beforeUpdate(executor, updateStatement, paramMap);
            // 未抛异常即通过
        }

        @Test
        @DisplayName("MyBatis 的 ParamMap（无 \"et\" 键）安全空转——它的 get() 键缺失时抛 BindingException，必须先 containsKey")
        void mybatisParamMapWithoutEntityKeyIsSafeNoop() throws Exception {
            // 自定义 @Update("... where id in (#{roleIds})") 这类多命名参数方法，
            // MyBatis 包装成 MapperMethod.ParamMap，没有 "et" 键。普通 HashMap.get()
            // 返回 null，但 ParamMap.get() 覆写成键不存在即抛 BindingException。
            MapperMethod.ParamMap<Object> paramMap = new MapperMethod.ParamMap<>();
            paramMap.put("roleIds", List.of(1L, 2L));
            paramMap.put("userId", 100L);
            paramMap.put("param1", List.of(1L, 2L));
            paramMap.put("param2", 100L);
            interceptor.beforeUpdate(executor, updateStatement, paramMap);
            // 未抛异常即通过（回归：修复前这里抛 BindingException: Parameter 'et' not found）
        }

        @Test
        @DisplayName("DELETE 语句直接跳过，不扫描不填充")
        void deleteCommandSkipsEntirely() throws Exception {
            TestEntity entity = new TestEntity();
            entity.setIdCard("13800001234");
            interceptor.beforeUpdate(executor, deleteStatement, entity);
            assertThat(entity.getIdCardIdx()).isNull();
        }

        @Test
        @DisplayName("没有 @BlindIndex 字段的实体类安全空转")
        void entityWithoutBlindIndexFieldIsSafeNoop() throws Exception {
            interceptor.beforeUpdate(executor, updateStatement, new NoBlindIndexEntity());
            // 未抛异常即通过
        }
    }

    @Nested
    @DisplayName("映射扫描缓存")
    class MappingCache {

        @Test
        @DisplayName("同一个实体类第二次调用不重新扫描——缓存里只有一条记录")
        void scansOnceAndCaches() throws Exception {
            TestEntity first = new TestEntity();
            first.setIdCard("13800001234");
            interceptor.beforeUpdate(executor, updateStatement, first);

            TestEntity second = new TestEntity();
            second.setIdCard("13900005678");
            interceptor.beforeUpdate(executor, updateStatement, second);

            assertThat(first.getIdCardIdx()).isEqualTo(dispatcher.blindIndex("13800001234"));
            assertThat(second.getIdCardIdx()).isEqualTo(dispatcher.blindIndex("13900005678"));

            Field cacheField = BlindIndexInnerInterceptor.class.getDeclaredField("mappingCache");
            cacheField.setAccessible(true);
            Map<?, ?> cache = (Map<?, ?>) cacheField.get(interceptor);
            assertThat(cache).hasSize(1);
            assertThat(cache.containsKey(TestEntity.class)).isTrue();
        }
    }
}
