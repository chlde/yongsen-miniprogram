package com.yongsen.training;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Explicit server-side CAM credentials; never derive cloud authority from HTTP headers. */
@Component
public class CloudCredentials {
    private final Environment environment;

    public CloudCredentials(Environment environment) {
        this.environment = environment;
    }

    public Value current() {
        String secretId = environment.getProperty("TRAINING_TENCENT_SECRET_ID", "");
        String secretKey = environment.getProperty("TRAINING_TENCENT_SECRET_KEY", "");
        String sessionToken = environment.getProperty("TRAINING_TENCENT_SESSION_TOKEN", "");
        if (secretId.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("Explicit server-side CAM credentials are required");
        }
        return new Value(secretId, secretKey, sessionToken);
    }

    public record Value(String secretId, String secretKey, String sessionToken) {
        @Override
        public String toString() {
            return "CloudCredentials.Value[redacted]";
        }
    }
}
