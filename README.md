# framework-crypto-starter

给业务方自己实体里的敏感字段（身份证号、手机号等）提供开箱即用的加密入库能力：
MyBatis-Plus 透明加解密（`TypeHandler`）+ 盲索引自动填充（精确查询）+ 手动加解密门面
（`TypeHandler` 覆盖不到的场景）。内置 **AES-256-GCM**（JDK 原生，零额外依赖，默认可用）与
**SM4-GCM + HmacSM3**（国密，面向政务/信创场景，需业务方额外引入 Bouncy Castle）两种算法，
可同时启用、按字段各自选择，互不影响。

**不修改框架任何功能**，`sys_user.mobile`/`sys_user.email` 等核心字段维持明文现状——
本插件只服务业务方自己的实体，框架核心零改动即可挂上（见下方"零框架核心改动"）。

适配框架最低版本：**0.2.0**（见 `FrameworkCryptoAutoConfiguration.REQUIRED_FRAMEWORK_VERSION`，
用到了 0.2.0 才开放给外部模块的 `ObjectProvider<InnerInterceptor>` 扩展点）。

## 引入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-crypto-starter</artifactId>
  <version>0.2.0</version> <!-- framework-bom 不仲裁插件版本，需显式写版本号 -->
</dependency>

<!-- 只用 AES 到此为止即可。要用 SM4-GCM，额外加这一条——不加则 SM4 相关能力不装配，
     AES 不受任何影响，等同没引这部分能力。版本以
     https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/maven-metadata.xml 为准核实。 -->
<dependency>
  <groupId>org.bouncycastle</groupId>
  <artifactId>bcprov-jdk18on</artifactId>
  <version>1.85.2</version>
</dependency>
```

## 必须配置的密钥

```yaml
describeadmin:
  crypto:
    default-algorithm: AES256-GCM   # CryptoTemplate 无参方法、@BlindIndex 未指定 algorithm 时使用
    aes:
      key: ${CRYPTO_AES_KEY}                 # Base64，解码后必须是 32 字节（256 位）
      blind-index-key: ${CRYPTO_AES_IDX_KEY} # Base64，与 key 不同的独立密钥
    sm4:                                      # 只用 AES 时这一段可以整体不配
      key: ${CRYPTO_SM4_KEY}                 # Base64，解码后必须是 16 字节（128 位）
      blind-index-key: ${CRYPTO_SM4_IDX_KEY}
```

密钥缺失或长度不对时，应用**启动期直接失败**，不会拖到第一次加密调用才报错。生成密钥示例：

```bash
openssl rand -base64 32   # AES 加密密钥 / 各类 blind-index-key
openssl rand -base64 16   # SM4 加密密钥
```

**v1 的密钥管理边界**：密钥来自配置项（环境变量/配置中心），本插件不做 KMS 托管、不做密钥轮换。
生产环境的密钥分发与轮换责任在业务方；未来如有真实需求，会做成独立的 KMS 对接插件
（同一套 `CryptoProvider` 契约，替换默认实现即可，不需要改业务实体代码）。

## 用法

```java
@TableName(value = "sys_member", autoResultMap = true)   // ⚠️ 见下方"两条必须知道的边界"第一条
public class Member extends BaseEntity {

    @TableField(typeHandler = AesEncryptedStringTypeHandler.class, updateStrategy = FieldStrategy.NOT_NULL)
    private String idCard;

    // 需要按 idCard 精确查询（如后台反查会员）才需要这两行；只存不查的字段可以不加
    @BlindIndex(source = "idCard")
    @TableField(updateStrategy = FieldStrategy.NOT_NULL)
    private String idCardIdx;
}
```

对应 DDL（业务方自己的表，插件不接管表结构）：

```sql
CREATE TABLE sys_member (
  ...
  id_card     VARCHAR(255) NULL,   -- 密文比明文长得多，按 Base64 膨胀预留列宽
  id_card_idx VARCHAR(64)  NULL,
  KEY idx_id_card_idx (id_card_idx)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
```

业务代码全程只用明文 getter/setter，插入/查询/更新都无感知——`idCardIdx` 也不需要手写任何
同步逻辑，`BlindIndexInnerInterceptor` 会在 insert/update 时自动从 `idCard` 算出并填入。

要用 SM4，把 `typeHandler` 换成 `Sm4EncryptedStringTypeHandler`，`@BlindIndex` 加
`algorithm = "SM4-GCM"`（不加则用 `default-algorithm`）即可，其余代码零改动。

## 两条必须知道的边界

### 1. `@TableName(autoResultMap = true)` 不是可选项

`@TableField(typeHandler = ...)` 对 **INSERT/UPDATE 天然生效**（typeHandler 内联在
MyBatis-Plus 生成的 SQL 参数占位符里，加密照常发生），**但 SELECT 不会自动生效**——没有
`autoResultMap = true`，MyBatis-Plus 不会为该实体生成携带 typeHandler 信息的 ResultMap，
查询退回普通的自动结果映射，把密文列的原始字符串直接当字段值返回。

**症状是完全不报错**：`selectById`/`selectList` 正常跑完，只是拿到的 `idCard` 是一串
`"AES256-GCM:xxx"` 而不是明文——与 CLAUDE.md §3.6 字符集那条坑是同一类"看起来正常、
实际全错"。这条已用真实 MySQL 集成测试验证过其必要性，建实体时务必对照检查。

### 2. `TypeHandler` 只在标准 MyBatis-Plus 映射路径生效，绕开的场景必须用 `CryptoTemplate`

以下场景都会绕开自动加解密，且同样**不报错**，拿到的是密文当明文用：

- 自定义 `@Select` 返回 `Map<String,Object>` 或轻量 DTO（报表/统计类查询很常见）
- 自定义 XML/`@Select` 查询即便返回实体类，只要没有重新显式声明同一个 `typeHandler`，也不会自动生效
- `JdbcTemplate`/原生 `DataSource` 查询
- 批量导入/导出脚本、数据迁移工具

这些场景注入 `CryptoTemplate` 手动处理：

```java
@Autowired
private CryptoTemplate cryptoTemplate;

// 自定义查询返回的裸 Map，手动解密
rows.forEach(row -> row.put("idCard", cryptoTemplate.decrypt((String) row.get("idCard"))));

// 按明文条件精确匹配，用 blindIndex 拼查询参数，不要拿明文去比密文列
mapper.selectList(new QueryWrapper<Member>().eq("id_card_idx", cryptoTemplate.blindIndex(idCard)));
```

### 盲索引只支持等值查询

`@BlindIndex` 解决的是"按明文精确查一行"（如按身份证号反查会员），**不支持模糊/范围查询**
——HMAC 输出与输入之间没有"接近关系"，`LIKE`/区间查询在这个机制下做不到。只在业务上真的
需要精确查询的字段才加 `@BlindIndex`，不需要查询的加密字段直接省掉这两行即可。

## 密文格式与换算法

密文格式：`<算法标识>:<Base64(IV || 密文+Tag)>`，例如 `AES256-GCM:xJ3k9F...`。解密时按前缀
自动路由到对应算法，调用方不需要记得当年用的是哪种算法。这个设计使得**换算法不需要一次性
迁移存量数据**——同一列里新旧算法的密文可以共存，只要旧算法对应的 Provider Bean 还注册着
（即配置了对应的密钥），历史数据永远能解密；新写入按字段当前声明的 `TypeHandler` 用新算法。

## 零框架核心改动

盲索引自动填充没有走 MyBatis-Plus 的 `MetaObjectHandler`（`BaseEntity` 的
`createTime`/`updateTime` 用的那套）——MyBatis-Plus 全局只允许注册一个 `MetaObjectHandler`
Bean，核心的 `AuditMetaObjectHandler` 已经占用了这个位置。本插件改用
`framework-mybatis-starter` 已经开放、面向外部模块的 `ObjectProvider<InnerInterceptor>`
扩展点（`FrameworkMybatisAutoConfiguration` 的 javadoc 明确写了"把自己的 InnerInterceptor
注册成 Bean 即可"），因此**引入本插件不需要框架做任何配合改动**。

## 配置项（前缀 `describeadmin.crypto`）

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 顶层运行时开关，关闭后等同于未引入本插件 |
| `default-algorithm` | `AES256-GCM` | `CryptoTemplate` 无参方法、`@BlindIndex` 未指定 `algorithm` 时使用 |
| `aes.enabled` | `true` | AES-256-GCM 开关 |
| `aes.key` | 无 | Base64，32 字节 |
| `aes.blind-index-key` | 无 | Base64，独立密钥 |
| `sm4.enabled` | `true` | SM4-GCM 开关（仅当 classpath 上有 Bouncy Castle 才会真正装配） |
| `sm4.key` | 无 | Base64，16 字节 |
| `sm4.blind-index-key` | 无 | Base64，独立密钥 |
| `blind-index.enabled` | `true` | `@BlindIndex` 自动填充开关，关闭不影响加解密本身 |

## 明确不覆盖的范围

- SM2（非对称/信封加密）、格式保留加密 FPE——见 `docs/registry.md` "规划中"
- DES/3DES（过时不安全）、ChaCha20（无合规驱动）——不计划支持
- KMS/密钥托管对接——见上方"必须配置的密钥"一节

## 测试

```bash
mvn test                              # 默认 MySQL 5.7
mvn test -Dmysql.image=mysql:8.4      # 8.4-LTS
```

- `AesCryptoProviderTest`/`Sm4CryptoProviderTest`：加解密往返、GCM 篡改检测、盲索引确定性；
  SM4 额外用 Bouncy Castle 原生 API 交叉验证密文语义，不只用自己的 `decryptRaw` 自证自己。
- `CryptoDispatcherTest`：多算法路由、构造期查重、未注册算法/无前缀密文拒绝。
- `BlindIndexInnerInterceptorTest`：映射扫描与缓存、源字段为 `null` 时跳过（partial update 保护）、
  单/多参数入参形态解析。
- `FrameworkCryptoAutoConfigurationTest`：装配机制——多算法共存、独立开关、`default-algorithm`
  误配启动即失败，覆盖 `docs/registry.md` 准入规范第 8 条。
- `CryptoEndToEndIntegrationTest`（Testcontainers 真实 MySQL）：绕开 ORM 直接查裸列确认存的
  是密文、`selectById` 自动解密、AES/SM4 同列密文共存且互相能正确解密、篡改密文触发异常、
  盲索引精确查询、partial update 不覆盖已有密文/索引列——这几条都是"单元测试自己加密自己
  解密"测不出来的真实 JDBC 往返行为，包括 `autoResultMap = true` 这条边界本身就是在这一层
  被发现并验证的。


## 相关文档

- 插件准入规范与目录：docs 仓 `registry.md`
- 编码规范：docs 仓 `CLAUDE.md`（各子仓不再放副本，开发时把本仓与 `docs` 仓并列检出）
- 发布步骤：docs 仓 `RELEASE.md`——发到 Maven Central 的版本不可撤回、不可覆盖

## License

Apache License 2.0
