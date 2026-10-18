package com.yongsen.training;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.net.URI;

/** Fail before application initialization when a serverless instance has unsafe defaults. */
@Configuration
@Profile("serverless")
public class ServerlessConfiguration {
    public ServerlessConfiguration(Environment environment, CloudCredentials credentials) {
        require(
                !environment.matchesProfiles("local", "test"),
                "Serverless cannot be combined with local or test profiles");
        require(
                "real".equals(environment.getProperty("training.wechat-mode")),
                "Serverless requires real WeChat configuration");
        require(
                "cos".equals(environment.getProperty("training.storage.mode")),
                "Serverless requires private COS storage");
        require(
                "scf".equals(environment.getProperty("training.background.mode")),
                "Serverless requires event-based background work");
        require(
                "org.springframework.jdbc.datasource.DriverManagerDataSource"
                        .equals(environment.getProperty("spring.datasource.type")),
                "Serverless requires connections to close without an idle eviction thread");
        require(
                "never".equals(environment.getProperty("spring.sql.init.mode")),
                "Production schema must be initialized separately");
        String password = environment.getProperty("training.admin-password", "");
        require(
                password.length() >= 12 && !"LocalTrain123!".equals(password),
                "A non-default administrator password of at least 12 characters is required");
        requireHttpsBase(environment.getProperty("training.public-base", ""));
        String jdbc = environment.getProperty("spring.datasource.url", "");
        require(jdbc.startsWith("jdbc:mysql://"), "Serverless requires cloud MySQL");
        URI database;
        try {
            database = URI.create(jdbc.substring("jdbc:".length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid cloud MySQL address");
        }
        String host = database.getHost();
        require(
                host != null
                        && !host.equalsIgnoreCase("localhost")
                        && !host.startsWith("127.")
                        && !host.equals("[::1]")
                        && !host.equals("::1")
                        && !host.equals("0.0.0.0"),
                "Serverless requires a non-loopback cloud MySQL address");
        credentials.current();
    }

    private static void requireHttpsBase(String value) {
        URI address;
        try {
            address = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("A valid public HTTPS API address is required");
        }
        require(
                "https".equals(address.getScheme())
                        && address.getHost() != null
                        && address.getRawUserInfo() == null
                        && address.getRawQuery() == null
                        && address.getRawFragment() == null
                        && (address.getPath().isEmpty() || "/".equals(address.getPath())),
                "A public HTTPS API origin without credentials or query parameters is required");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
