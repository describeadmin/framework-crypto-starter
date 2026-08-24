package io.github.describeadmin.crypto.it;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.describeadmin.crypto.api.CryptoTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端到端集成测试：验证 TypeHandler 透明加解密 + 盲索引自动填充在真实 JDBC 往返里确实生效，
 * 而不只是"单元测试里自己加密自己解密"这种自证自己的验证。
 */
@DisplayName("加密插件端到端集成测试")
class CryptoEndToEndIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private CryptoTestMapper mapper;

    @Autowired
    private CryptoTestMapperSm4 mapperSm4;

    @Autowired
    private CryptoTemplate cryptoTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("① 绕开 ORM 直接查裸列：库里存的是带前缀的密文，不是明文——最容易假阳性的一环")
    void rawColumnStoresCipherTextNotPlainText() {
        String plain = "440101199001011234";
        CryptoTestEntity entity = new CryptoTestEntity();
        entity.setIdCard(plain);
        entity.setOtherField("x");
        mapper.insert(entity);

        String raw = jdbcTemplate.queryForObject(
                "select id_card from crypto_test_entity where id = ?", String.class, entity.getId());

        assertThat(raw).isNotEqualTo(plain);
        assertThat(raw).startsWith("AES256-GCM:");
    }

    @Test
    @DisplayName("② selectById 自动解密——业务代码全程只看到明文")
    void selectByIdAutoDecrypts() {
        String plain = "440101199001011234";
        CryptoTestEntity entity = new CryptoTestEntity();
        entity.setIdCard(plain);
        mapper.insert(entity);

        CryptoTestEntity loaded = mapper.selectById(entity.getId());
        assertThat(loaded.getIdCard()).isEqualTo(plain);
    }

    @Test
    @DisplayName("③ 同一列 AES/SM4 两种算法密文共存，任意一方的 TypeHandler 都能正确解密对方写入的行"
            + "——验证密文自描述前缀路由，不依赖当前 TypeHandler 配的是哪种算法")
    void aesAndSm4CipherTextsCoexistAndCrossDecryptCorrectly() {
        CryptoTestEntity aesEntity = new CryptoTestEntity();
        aesEntity.setIdCard("AES-明文-13800001234");
        mapper.insert(aesEntity);

        CryptoTestEntitySm4 sm4Entity = new CryptoTestEntitySm4();
        sm4Entity.setIdCard("SM4-明文-13900005678");
        mapperSm4.insert(sm4Entity);

        // 正常路径：各自的 mapper 读各自写入的行
        assertThat(mapper.selectById(aesEntity.getId()).getIdCard()).isEqualTo("AES-明文-13800001234");
        assertThat(mapperSm4.selectById(sm4Entity.getId()).getIdCard()).isEqualTo("SM4-明文-13900005678");

        // 交叉路径：用 SM4 的 TypeHandler 读 AES 写入的行，反之亦然——两边都必须能正确解密
        assertThat(mapperSm4.selectById(aesEntity.getId()).getIdCard()).isEqualTo("AES-明文-13800001234");
        assertThat(mapper.selectById(sm4Entity.getId()).getIdCard()).isEqualTo("SM4-明文-13900005678");
    }

    @Test
    @DisplayName("④ 篡改数据库里的密文后再读取，抛异常而不是返回垃圾明文——GCM 完整性在真实 JDBC 往返里同样生效")
    void tamperedCipherTextThrowsOnRead() {
        CryptoTestEntity entity = new CryptoTestEntity();
        entity.setIdCard("13800001234");
        mapper.insert(entity);

        String stored = jdbcTemplate.queryForObject(
                "select id_card from crypto_test_entity where id = ?", String.class, entity.getId());
        int colon = stored.indexOf(':');
        String prefix = stored.substring(0, colon + 1);
        byte[] payloadBytes = Base64.getDecoder().decode(stored.substring(colon + 1));
        payloadBytes[payloadBytes.length - 1] ^= 0x01; // 翻转最后一个字节，落在 GCM Tag 区域内
        String tampered = prefix + Base64.getEncoder().encodeToString(payloadBytes);

        jdbcTemplate.update("update crypto_test_entity set id_card = ? where id = ?", tampered, entity.getId());

        assertThatThrownBy(() -> mapper.selectById(entity.getId())).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("⑤ 用 CryptoTemplate.blindIndex() 拼查询条件，能按明文精确匹配到对应行——支撑\"按手机号反查用户\"这类场景")
    void blindIndexEqualityQueryFindsExactMatch() {
        String plain = "13800001234";
        CryptoTestEntity entity = new CryptoTestEntity();
        entity.setIdCard(plain);
        mapper.insert(entity);

        List<CryptoTestEntity> found = mapper.selectList(
                new QueryWrapper<CryptoTestEntity>().eq("id_card_idx", cryptoTemplate.blindIndex(plain)));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getId()).isEqualTo(entity.getId());
        assertThat(found.get(0).getIdCard()).isEqualTo(plain);
    }

    @Test
    @DisplayName("⑥ partial update 不触碰源字段（idCard）时，id_card 与 id_card_idx 两列均保持不变"
            + "——核心保护（源字段 null 时跳过 + updateStrategy=NOT_NULL）的端到端证据")
    void partialUpdateDoesNotTouchEncryptedColumnsOrBlindIndex() {
        CryptoTestEntity entity = new CryptoTestEntity();
        entity.setIdCard("13800001234");
        entity.setOtherField("before");
        mapper.insert(entity);

        String idCardBefore = jdbcTemplate.queryForObject(
                "select id_card from crypto_test_entity where id = ?", String.class, entity.getId());
        String idCardIdxBefore = jdbcTemplate.queryForObject(
                "select id_card_idx from crypto_test_entity where id = ?", String.class, entity.getId());

        // 模拟 partial update：只改 otherField，idCard 在这个 Java 对象上是 null（没有被设置）
        CryptoTestEntity partial = new CryptoTestEntity();
        partial.setId(entity.getId());
        partial.setOtherField("after");
        mapper.updateById(partial);

        String idCardAfter = jdbcTemplate.queryForObject(
                "select id_card from crypto_test_entity where id = ?", String.class, entity.getId());
        String idCardIdxAfter = jdbcTemplate.queryForObject(
                "select id_card_idx from crypto_test_entity where id = ?", String.class, entity.getId());
        String otherFieldAfter = jdbcTemplate.queryForObject(
                "select other_field from crypto_test_entity where id = ?", String.class, entity.getId());

        assertThat(idCardAfter).isEqualTo(idCardBefore);
        assertThat(idCardIdxAfter).isEqualTo(idCardIdxBefore);
        assertThat(otherFieldAfter).isEqualTo("after");
    }
}
