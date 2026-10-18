package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.scf.v20180416.models.InvokeResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ScfBackgroundInvokerTest {
    private MockEnvironment configured() {
        return new MockEnvironment()
                .withProperty("training.background.mode", "scf")
                .withProperty("TRAINING_SCF_REGION", "ap-shanghai")
                .withProperty("TRAINING_SCF_WORKER_FUNCTION", "training-worker")
                .withProperty("TRAINING_TENCENT_SECRET_ID", "test-id")
                .withProperty("TRAINING_TENCENT_SECRET_KEY", "test-key");
    }

    @Test
    void usesOfficialEventInvocationWithOnlyJobIdAndBoundedTimeouts() {
        MockEnvironment environment = configured();
        AtomicInteger invoked = new AtomicInteger();
        CloudCredentials credentials = mock(CloudCredentials.class);
        when(credentials.current()).thenReturn(new CloudCredentials.Value("id", "key", "token"));
        ScfBackgroundInvoker invoker =
                new ScfBackgroundInvoker(
                        environment,
                        credentials,
                        (client, request) -> {
                            invoked.incrementAndGet();
                            assertEquals("Event", request.getInvocationType());
                            assertEquals("training-worker", request.getFunctionName());
                            assertEquals("default", request.getNamespace());
                            assertEquals(
                                    Map.of("jobId", "durable-job"),
                                    Data.parse(request.getClientContext()));
                            assertEquals("ap-shanghai", client.getRegion());
                            assertEquals(
                                    5, client.getClientProfile().getHttpProfile().getConnTimeout());
                            assertEquals(
                                    10,
                                    client.getClientProfile().getHttpProfile().getReadTimeout());
                            assertEquals(
                                    10,
                                    client.getClientProfile().getHttpProfile().getWriteTimeout());
                            InvokeResponse response = new InvokeResponse();
                            response.setRequestId("request-1");
                            return response;
                        });
        invoker.invoke("durable-job");
        assertEquals(1, invoked.get());
        verify(credentials).current();
    }

    @Test
    void sdkTimeoutIsNotRetriedAndProviderDetailDoesNotLeak() {
        AtomicInteger invoked = new AtomicInteger();
        CloudCredentials credentials = new CloudCredentials(configured());
        ScfBackgroundInvoker invoker =
                new ScfBackgroundInvoker(
                        configured(),
                        credentials,
                        (client, request) -> {
                            invoked.incrementAndGet();
                            throw new TencentCloudSDKException("private-provider-detail");
                        });
        BusinessException error =
                assertThrows(BusinessException.class, () -> invoker.invoke("job"));
        assertEquals("SCF_DISPATCH_FAILED", error.code());
        assertFalse(error.getMessage().contains("private-provider-detail"));
        assertEquals(1, invoked.get());
    }

    @Test
    void unconfirmedAcknowledgementIsRecoverable() {
        ScfBackgroundInvoker invoker =
                new ScfBackgroundInvoker(
                        configured(),
                        new CloudCredentials(configured()),
                        (client, request) -> new InvokeResponse());
        assertEquals(
                "SCF_DISPATCH_UNKNOWN",
                assertThrows(BusinessException.class, () -> invoker.invoke("job")).code());
    }

    @Test
    void manualTestsNeverResolveCloudCredentials() {
        MockEnvironment environment =
                new MockEnvironment().withProperty("TRAINING_BACKGROUND_MODE", "manual");
        CloudCredentials credentials = mock(CloudCredentials.class);
        new ScfBackgroundInvoker(environment, credentials).invoke("job");
        verify(credentials, never()).current();
    }
}
