package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.env.MockEnvironment;

class ServerlessConfigurationTest {
    private MockEnvironment production() {
        MockEnvironment environment =
                new MockEnvironment()
                        .withProperty("training.wechat-mode", "real")
                        .withProperty("training.storage.mode", "cos")
                        .withProperty("training.background.mode", "scf")
                        .withProperty(
                                "spring.datasource.type",
                                "org.springframework.jdbc.datasource.DriverManagerDataSource")
                        .withProperty("spring.sql.init.mode", "never")
                        .withProperty("training.admin-password", "test-only-administrator")
                        .withProperty("training.public-base", "https://api.example.com")
                        .withProperty(
                                "spring.datasource.url", "jdbc:mysql://10.0.0.5:3306/training")
                        .withProperty("TRAINING_TENCENT_SECRET_ID", "test-id")
                        .withProperty("TRAINING_TENCENT_SECRET_KEY", "test-key");
        environment.setActiveProfiles("serverless");
        return environment;
    }

    @Test
    void acceptsExplicitProductionSettingsAndRejectsMixedLocalProfile() {
        MockEnvironment environment = production();
        assertDoesNotThrow(
                () -> new ServerlessConfiguration(environment, new CloudCredentials(environment)));
        environment.setActiveProfiles("local", "serverless");
        assertThrows(
                IllegalStateException.class,
                () -> new ServerlessConfiguration(environment, new CloudCredentials(environment)));
    }

    @ParameterizedTest
    @CsvSource({
        "training.wechat-mode,mock",
        "training.storage.mode,local",
        "training.background.mode,local",
        "spring.datasource.type,com.zaxxer.hikari.HikariDataSource",
        "spring.sql.init.mode,always",
        "training.admin-password,LocalTrain123!",
        "training.admin-password,short",
        "training.public-base,http://api.example.com",
        "training.public-base,https://user:pass@api.example.com",
        "training.public-base,https://api.example.com?token=test",
        "training.public-base,https://api.example.com/api/v1",
        "spring.datasource.url,jdbc:h2:mem:local",
        "spring.datasource.url,jdbc:mysql://localhost/training",
        "spring.datasource.url,jdbc:mysql://127.0.0.1/training",
        "spring.datasource.url,jdbc:mysql://[::1]/training"
    })
    void rejectsConfigurationThatWouldDefeatCloudIsolationOrIdlePause(String key, String value) {
        MockEnvironment environment = production().withProperty(key, value);
        assertThrows(
                IllegalStateException.class,
                () -> new ServerlessConfiguration(environment, new CloudCredentials(environment)));
    }
}
