package io.github.describeadmin.crypto.it;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** 仅供集成测试使用的最小 Spring Boot 应用：拉起真实 DataSource + MyBatis-Plus + 本插件的自动配置。 */
@SpringBootApplication
@MapperScan("io.github.describeadmin.crypto.it")
class CryptoTestApplication {
}
