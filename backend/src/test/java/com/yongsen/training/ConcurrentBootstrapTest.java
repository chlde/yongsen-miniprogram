package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class ConcurrentBootstrapTest {
    @Test
    void simultaneousColdStartsCreateOneAdminWithoutOverwritingConfiguration() throws Exception {
        BackendFixture fixture = new BackendFixture();
        Store shared = spy(fixture.store);
        CyclicBarrier bothReady = new CyclicBarrier(2);
        doAnswer(
                        invocation -> {
                            bothReady.await(5, TimeUnit.SECONDS);
                            return invocation.callRealMethod();
                        })
                .when(shared)
                .create(eq(Store.Table.ADMIN_USER), any(), any(), any(), any(), any());
        WxProvider provider = mock(WxProvider.class);
        when(provider.mode()).thenReturn("real");
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AuthService first =
                new AuthService(
                        shared,
                        provider,
                        mock(TextEncryptor.class),
                        environment,
                        "first-start-password");
        AuthService second =
                new AuthService(
                        shared,
                        provider,
                        mock(TextEncryptor.class),
                        environment,
                        "second-start-password");
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(2),
                        runnable -> new Thread(runnable, "bootstrap-test"),
                        new ThreadPoolExecutor.AbortPolicy());
        try {
            Future<?> firstResult = executor.submit(() -> first.run(null));
            Future<?> secondResult = executor.submit(() -> second.run(null));
            firstResult.get(10, TimeUnit.SECONDS);
            secondResult.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(1, shared.all(Store.Table.ADMIN_USER).size());
        Map<String, Object> admin = shared.byKey(Store.Table.ADMIN_USER, "admin").toBoundary();
        assertEquals("ACTIVE", admin.get("status"));
        assertNotEquals("first-start-password", admin.get("passwordHash"));
        assertEquals(
                "template-1",
                shared.get(Store.Table.APP_CONFIG, "notification").toBoundary().get("templateId"));
    }
}
