package com.yongsen.training;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

@Configuration
public class SecurityConfig {
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public TextEncryptor textEncryptor(
            Environment environment,
            @Value("${TRAINING_DATA_KEY:local-only-data-key-change-before-production}")
                    String key) {
        boolean local =
                Arrays.stream(environment.getActiveProfiles())
                                .anyMatch(p -> p.equals("local") || p.equals("test"))
                        || environment.getActiveProfiles().length == 0;
        if (!local && (key.startsWith("local-only") || key.isBlank() || key.length() < 32)) {
            throw new IllegalStateException("TRAINING_DATA_KEY required outside local/test");
        }
        return Encryptors.delux(key, "94d08cf63128b7e2");
    }

    @Bean
    public CorsFilter corsFilter(Environment environment) {
        CorsConfiguration config = new CorsConfiguration();
        boolean local = environment.matchesProfiles("local", "test");
        String defaults =
                local
                        ? "http://localhost:5173,http://127.0.0.1:5173,http://localhost:15173,http://127.0.0.1:15173"
                        : "";
        String configured = environment.getProperty("TRAINING_ALLOWED_ORIGINS", defaults);
        java.util.List<String> origins =
                Arrays.stream(configured.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toList();
        if (origins.stream()
                .anyMatch(
                        value ->
                                value.contains("*") || (!local && !value.startsWith("https://")))) {
            throw new IllegalStateException(
                    "Allowed origins must be exact HTTPS origins outside local/test");
        }
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(
                java.util.List.of("GET", "POST", "PUT", "PATCH", "OPTIONS", "HEAD"));
        config.setAllowedHeaders(
                java.util.List.of("Authorization", "Content-Type", "Idempotency-Key", "Range"));
        config.setExposedHeaders(
                java.util.List.of("Content-Range", "Accept-Ranges", "Content-Disposition"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
