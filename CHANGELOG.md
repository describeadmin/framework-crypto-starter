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
