package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CloudCredentialsTest {
    @Test
    void requiresExplicitConfigurationAndNeverIncludesValuesInDiagnostics() {
        MockEnvironment environment = new MockEnvironment();
        CloudCredentials credentials = new CloudCredentials(environment);
        assertThrows(IllegalStateException.class, credentials::current);
        environment.setProperty("TRAINING_TENCENT_SECRET_ID", "test-id");
        environment.setProperty("TRAINING_TENCENT_SECRET_KEY", "test-private-value");
        environment.setProperty("TRAINING_TENCENT_SESSION_TOKEN", "test-session-value");
        CloudCredentials.Value value = credentials.current();
        assertEquals("test-id", value.secretId());
        assertEquals("test-session-value", value.sessionToken());
        assertFalse(value.toString().contains("test-"));
        environment.setProperty("TRAINING_TENCENT_SECRET_KEY", "");
        IllegalStateException error =
                assertThrows(IllegalStateException.class, credentials::current);
        assertFalse(error.getMessage().contains("test-"));
    }
}
