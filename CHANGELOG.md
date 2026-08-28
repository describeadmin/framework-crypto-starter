# Changelog

## [Unreleased]

### New Features

- 新增 `CryptoProvider` SPI（多实现共存模型，仿 `NotifyChannel`/`NotifyDispatcher`）+
  `CryptoDispatcher`（按 `algorithm()` 路由，构造期查重复标识）。
- 内置 `AesCryptoProvider`（AES-256-GCM，JDK 原生，零额外依赖，默认可用）与
  `Sm4CryptoProvider`（SM4-GCM + HmacSM3，国密，需业务方额外引入 Bouncy Castle，`optional` 依赖）。
- 密文自描述算法前缀（`<算法>:<Base64 payload>`），支持换算法不迁移存量数据、同列新旧算法密文共存。
- `EncryptedStringTypeHandler`（+ `AesEncryptedStringTypeHandler`/`Sm4EncryptedStringTypeHandler`）：
  MyBatis-Plus 字段级透明加解密。
- `CryptoTemplate`：手动加解密门面，覆盖自定义 Mapper/`JdbcTemplate`/批量脚本等 TypeHandler
  覆盖不到的场景。
- `@BlindIndex` + `BlindIndexInnerInterceptor`：盲索引自动填充，支持按明文精确查询加密字段；
  挂进 `framework-mybatis-starter` 已开放的 `ObjectProvider<InnerInterceptor>` 扩展点，零框架核心改动。
- `FrameworkCryptoAutoConfiguration`：顶层与各算法/盲索引独立开关，`FrameworkVersion` 启动期
  兼容性自检。

### Bug Fixes

- `BlindIndexInnerInterceptor.resolveEntity`：多命名参数、又无 `"et"` 键的自定义 mapper 方法
  （`@Update("... where id in (#{ids})")` 这类）经过时不再抛
  `BindingException: Parameter 'et' not found`。根因是 MyBatis 的 `MapperMethod.ParamMap`
  覆写了 `get()`——键缺失时抛异常而非返回 `null`，原实现直接 `get("et")`。改为先 `containsKey`
  再取。**即使实体上一个 `@BlindIndex` 都没有**，本拦截器仍会在每条 UPDATE 前被调到，
  因此这个崩溃会命中任何有自定义 `@Update`/`@Insert` 命名参数方法的消费方。
  回归测试 `mybatisParamMapWithoutEntityKeyIsSafeNoop`。
