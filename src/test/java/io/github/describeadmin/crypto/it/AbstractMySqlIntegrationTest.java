package io.github.describeadmin.crypto.it;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类：拉起真实 MySQL 容器，风格照抄 framework 仓 framework-it 的同名类。
 *
 * <pre>
 *   mvn test                              # 默认 mysql:5.7
 *   mvn test -Dmysql.image=mysql:8.4      # 8.4-LTS
 * </pre>
 *
 * 默认取最严格的 5.7——如果默认给 8.4，"本地跑通、CI 才在 5.7 上挂"的情况会反复发生。
 */
@SpringBootTest(classes = CryptoTestApplication.class)
public abstract class AbstractMySqlIntegrationTest {

    private static final String IMAGE = System.getProperty("mysql.image", "mysql:5.7");

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(
            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("describeadmin_crypto_test")
            .withUsername("app")
            .withPassword("app")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_general_ci")
            .withReuse(false);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);

        registry.add("spring.sql.init.mode", () -> "always");
        registry.add("spring.sql.init.schema-locations", () -> "classpath:db/schema-crypto-test.sql");
        // ⚠️ 必须显式指定，否则用平台默认编码读脚本，中文 Windows 上是 GBK（CLAUDE.md 3.6）。
        registry.add("spring.sql.init.encoding", () -> "UTF-8");

        registry.add("describeadmin.crypto.aes.key", () -> "raX1kbnki6EOTZuDwNPmyKvyH19v0T/7584NOnEFQXM=");
        registry.add("describeadmin.crypto.aes.blind-index-key", () -> "VLfqdPd1waoKazQ5ARsoljLG1FOBoxtQg50A+t0xlFA=");
        registry.add("describeadmin.crypto.sm4.key", () -> "zRCKIYwcbURwVLg+ZVfcGw==");
        registry.add("describeadmin.crypto.sm4.blind-index-key", () -> "uGwr7OVo8uvfJBo8bB1YQAIah4TDcjWWwOgkjaZtRtI=");
    }
}
