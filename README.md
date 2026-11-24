# framework-crypto-starter

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./assets/logo-text.svg">
  <img alt="Describe Admin" src="./assets/logo-text-light.svg">
</picture>

describeadmin 的可选插件。业务实体敏感字段的加密存储：MyBatis-Plus 透明加解密、盲索引精确查询、手动加解密门面。内置 AES-256-GCM 与国密 SM4-GCM。

**文档：[https://describeadmin.github.io/docs/how-to/crypto](https://describeadmin.github.io/docs/how-to/crypto)**，全部配置项见 [插件配置](https://describeadmin.github.io/docs/reference/configuration/plugins)。

## 引入

```xml
<dependency>
  <groupId>io.github.describeadmin</groupId>
  <artifactId>framework-crypto-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

版本号要显式写：`framework-bom` 不管插件的版本，插件有自己的版本线。

```yaml
describeadmin:
  crypto:
    aes:
      key: ${CRYPTO_AES_KEY}                  # Base64，解码后 32 字节
      blind-index-key: ${CRYPTO_AES_IDX_KEY}  # Base64，与 key 不同
```

用 SM4 要另外引入 Bouncy Castle（`org.bouncycastle:bcprov-jdk18on`）。实体上必须写 `@TableName(autoResultMap = true)`，
否则查询不会解密。**密钥丢了数据就解不开了**，密钥管理见文档。

运行时开关是 `describeadmin.crypto.enabled`（默认 `true`），关掉后与没有引入这个 jar 完全一样。

## 兼容性

| 插件版本 | 最低框架版本 |
|---|---|
| 1.0.0 | 1.0.0 |

插件启动时检查框架版本：框架比上表要求的旧、或主版本不同（0.x 与 1.x 不互通），应用直接拒绝启动并说明原因。
CI 对表里的每个框架版本各跑一遍完整测试。版本对应关系的总表见 [插件目录](https://describeadmin.github.io/docs/reference/plugins)。

## 开发

```bash
mvn clean test                              # 集成测试用 Testcontainers 起 MySQL 5.7，需要 Docker
mvn clean test -Dmysql.image=mysql:8.4      # 切到 MySQL 8.4
mvn clean test -Ddescribeadmin.version=1.0.0
```

端到端测试绕开 ORM 直接查裸列，确认库里存的是密文；SM4 用 Bouncy Castle 原生 API 交叉验证，不只用自己的实现自证。

构建只要求 JDK ≥ 17（enforcer 把关，产物固定为 Java 17 字节码）；用 `mvn -v` 看 Maven 实际用的 JDK。
框架有尚未发布到 Central 的版本时，先 `mvn -f ../framework/pom.xml clean install -DskipTests -Dgpg.skip=true` 装到本地仓库。

本插件不继承 `framework-parent`，而是 import `framework-bom`，与业务方的用法相同。插件开发规范见
[编写插件](https://describeadmin.github.io/docs/dev/plugin-dev)，发布照 [发布手册](https://describeadmin.github.io/docs/dev/release) 执行。

## 变更记录与许可证

[CHANGELOG.md](./CHANGELOG.md) · [Apache-2.0](./LICENSE)
