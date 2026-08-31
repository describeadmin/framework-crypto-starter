# 更新日志

本文件遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 的组织方式，
版本号遵循 [SemVer](https://semver.org/lang/zh-CN/)。

每个版本固定分 **Breaking Changes / New Features / Bug Fixes** 三类
（见组织编码规范第 5 节）。没有内容的类别保留标题并写「无」，
这样使用者不必怀疑是遗漏还是确实没有。

> **本插件的版本线与框架独立**。插件发 1.3.0 完全可能仍然只要求框架 1.0.0，
> 因此每个版本都必须写明适配的框架版本，见下方各条目的「框架要求」。

## 0.2.0 (2026-08-31)

describeadmin 的敏感字段加密入库插件：字段级透明加解密 + 盲索引精确查询。
首个发布版本，版本号与 framework 0.2.0 对齐（本轮四个插件统一按框架号发布）。

**框架要求：0.2.0 及以上**（挂进 framework-mybatis-starter 0.2.0 开放的
`ObjectProvider<InnerInterceptor>` 扩展点；启动期用 `FrameworkVersion.requireCompatible` 自检）

### Breaking Changes

无（首个版本）。

### New Features

- `CryptoProvider` SPI（多实现共存模型，仿 `NotifyChannel`/`NotifyDispatcher`）+
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
- 运行时开关关掉后行为与「没引这个 jar」完全一致。

### Bug Fixes

- `BlindIndexInnerInterceptor.resolveEntity`：多命名参数、又无 `"et"` 键的自定义 mapper 方法
  （`@Update("... where id in (#{ids})")` 这类）经过时不再抛
  `BindingException: Parameter 'et' not found`。根因是 MyBatis 的 `MapperMethod.ParamMap`
  覆写了 `get()`——键缺失时抛异常而非返回 `null`，原实现直接 `get("et")`。改为先 `containsKey`
  再取。**即使实体上一个 `@BlindIndex` 都没有**，本拦截器仍会在每条 UPDATE 前被调到，
  因此这个崩溃会命中任何有自定义 `@Update`/`@Insert` 命名参数方法的消费方。
  回归测试 `mybatisParamMapWithoutEntityKeyIsSafeNoop`。

### 仓库

- 独立成仓、独立版本线、独立发布（原方案 3.1.1 的既定拓扑）。
- POM **不继承 `framework-parent`**，改为 `import framework-bom`——这正是业务方消费框架的
  姿势，插件用同一套姿势才能提前暴露业务方会遇到的问题。CI 按框架版本矩阵跑完整测试。
- 发布晚于 framework 0.2.0：`import` 的 `framework-bom` 必须是一个真实存在的已发布版本，
  因此本插件的首发版本直接从 0.2.0 起。
