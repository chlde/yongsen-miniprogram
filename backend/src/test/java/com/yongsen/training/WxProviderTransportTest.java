package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class WxProviderTransportTest {
    private static final String APPLICATION_ID = "wx-transport-test";
    private static final String TEST_SECRET = "synthetic-unit-test-secret";
    private static final String PAGE =
            "pages/task/index?assignmentId=assignment-1&notificationId=notice-1";
    private static final Map<String, Object> CONTENT =
            Map.of("thing1", Map.of("value", "安全培训"), "time2", Map.of("value", "2026-10-18 17:00"));
    private final Queue<Object> outcomes = new ConcurrentLinkedQueue<>();
    private final List<HttpRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private Clock clock;
    private WxProvider provider;

    @BeforeEach
    void setup() throws Exception {
        HttpClient client = mock(HttpClient.class);
        clock = mock(Clock.class);
        when(clock.millis()).thenReturn(1800000000000L);
        when(client.send(
                        any(HttpRequest.class),
                        org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenAnswer(
                        invocation -> {
                            requests.add(invocation.getArgument(0));
                            Object outcome = outcomes.remove();
                            if (outcome instanceof Exception exception) {
                                throw exception;
                            }
                            return outcome;
                        });
        MockEnvironment environment =
                new MockEnvironment().withProperty("WECHAT_MINIPROGRAM_STATE", "trial");
        environment.setActiveProfiles("prod");
        provider =
                new WxProvider(
                        environment, "real", APPLICATION_ID, TEST_SECRET, "", "", client, clock);
    }

    @Test
    void loginUsesOnlyOfficialCodeExchangeAndDiscardsProviderSessionKey() {
        respond(
                200,
                "{\"openid\":\"person-openid\",\"session_key\":\"provider-secret\",\"unionid\":\"union-id\"}");
        assertEquals("person-openid", provider.login("code+with&symbols"));
        HttpRequest request = requests.get(0);
        assertEquals("GET", request.method());
        assertEquals("https", request.uri().getScheme());
        assertEquals("api.weixin.qq.com", request.uri().getHost());
        assertEquals("/sns/jscode2session", request.uri().getPath());
        assertTrue(request.uri().getRawQuery().contains("js_code=code%2Bwith%26symbols"));
        assertTrue(request.uri().getRawQuery().contains("grant_type=authorization_code"));
        assertEquals(10, request.timeout().orElseThrow().toSeconds());
    }

    @Test
    void providerLoginErrorsHaveUsefulStatusWithoutRawProviderErrorText() {
        int[][] cases = {
            {40029, 401}, {40163, 401}, {40226, 403}, {45011, 429}, {-1, 503}, {40125, 503}
        };
        for (int[] item : cases) {
            respond(200, "{\"errcode\":" + item[0] + ",\"errmsg\":\"" + TEST_SECRET + "\"}");
            BusinessException error =
                    assertThrows(BusinessException.class, () -> provider.login("temporary-code"));
            assertEquals(item[1], error.status());
            assertEquals(item[0], error.details().get("providerErrcode"));
            assertFalse(error.toString().contains(TEST_SECRET));
            assertNull(error.getCause());
        }
        assertEquals(cases.length, requests.size());
    }

    @Test
    void malformedLoginResponsesFailClosedInsteadOfCoercingErrorCodeToSuccess() {
        for (String body :
                List.of(
                        "",
                        "null",
                        "[]",
                        "{\"errcode\":\"0\",\"openid\":\"person\"}",
                        "{\"errcode\":0,\"openid\":3}",
                        "{\"errcode\":null,\"openid\":\"person\"}")) {
            respond(200, body);
            BusinessException error =
                    assertThrows(BusinessException.class, () -> provider.login("temporary-code"));
            assertEquals(503, error.status());
        }
    }

    @Test
    void timeoutDoesNotRetryAOneUseLoginCodeOrLeakCredentialBearingException() {
        outcomes.add(new HttpTimeoutException("URI includes secret=" + TEST_SECRET));
        BusinessException error =
                assertThrows(BusinessException.class, () -> provider.login("temporary-code"));
        assertEquals(503, error.status());
        assertNull(error.getCause());
        assertFalse(error.toString().contains(TEST_SECRET));
        assertEquals(1, requests.size());
    }

    @Test
    void realLoginRejectsLocalIdentityBeforeNetworkAccess() {
        assertThrows(BusinessException.class, () -> provider.login("mock:alice"));
        assertThrows(BusinessException.class, () -> provider.login(" "));
        assertTrue(requests.isEmpty());
    }

    @Test
    void stableTokenUsesPostCachesCredentialAndDoesNotInventDeliveryId() throws Exception {
        token("token-1", 7200);
        respond(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        respond(200, "{\"errcode\":0,\"errmsg\":\"ok\"}");
        WxProvider.SendResult first = send();
        assertEquals(0, first.errcode());
        assertEquals("", first.msgid());
        assertFalse(first.unknown());
        assertEquals(0, send().errcode());
        assertEquals(3, requests.size());
        HttpRequest credential = requests.get(0);
        assertEquals("POST", credential.method());
        assertEquals("https://api.weixin.qq.com/cgi-bin/stable_token", credential.uri().toString());
        Map<String, Object> tokenBody = body(credential);
        assertEquals("client_credential", tokenBody.get("grant_type"));
        assertEquals(APPLICATION_ID, tokenBody.get("appid"));
        assertEquals(TEST_SECRET, tokenBody.get("secret"));
        assertEquals(false, tokenBody.get("force_refresh"));
        Map<String, Object> message = body(requests.get(1));
        assertEquals("trial", message.get("miniprogram_state"));
        assertEquals("zh_CN", message.get("lang"));
        assertEquals(PAGE, message.get("page"));
        assertEquals(CONTENT, message.get("data"));
    }

    @Test
    void explicitInvalidTokenRefreshesNormallyBeforeOneSafeResend() throws Exception {
        token("old-token", 7200);
        respond(200, "{\"errcode\":40014}");
        token("new-token", 7200);
        respond(200, "{\"errcode\":0}");
        assertEquals(0, send().errcode());
        assertEquals(4, requests.size());
        assertEquals(false, body(requests.get(2)).get("force_refresh"));
        assertTrue(requests.get(3).uri().getQuery().contains("access_token=new-token"));
    }

    @Test
    void forceRefreshIsUsedOnlyWhenOrdinaryRefreshReturnsTheRejectedTokenAndIsRateLimited()
            throws Exception {
        token("old-token", 7200);
        respond(200, "{\"errcode\":40001}");
        token("old-token", 7200);
        token("new-token", 7200);
        respond(200, "{\"errcode\":0}");
        assertEquals(0, send().errcode());
        assertEquals(false, body(requests.get(2)).get("force_refresh"));
        assertEquals(true, body(requests.get(3)).get("force_refresh"));
        respond(200, "{\"errcode\":42001}");
        token("new-token", 7200);
        WxProvider.SendResult limited = send();
        assertEquals(-1, limited.errcode());
        assertFalse(limited.unknown());
        assertEquals(7, requests.size());
    }

    @Test
    void normalCacheExpiryUsesActualRemainingLifetimeWithoutForce() throws Exception {
        token("token-1", 100);
        respond(200, "{\"errcode\":0}");
        send();
        when(clock.millis()).thenReturn(1800000091000L);
        token("token-2", 7200);
        respond(200, "{\"errcode\":0}");
        send();
        assertEquals(false, body(requests.get(2)).get("force_refresh"));
        assertTrue(requests.get(3).uri().getQuery().contains("access_token=token-2"));
    }

    @Test
    void uncertainSendOutcomesAreNeverRetried() {
        token("token-1", 7200);
        outcomes.add(new HttpTimeoutException("secret transport URI"));
        assertTrue(send().unknown());
        outcomes.add(new IOException("connection reset after body sent"));
        assertTrue(send().unknown());
        respond(200, "{\"errcode\":\"0\"}");
        assertTrue(send().unknown());
        respond(200, "null");
        assertTrue(send().unknown());
        respond(503, "error");
        assertTrue(send().unknown());
        assertEquals(6, requests.size());
    }

    @Test
    void connectTimeoutAndExplicitRejectionAreKnownFailuresAndErrorTextIsSanitized() {
        token("token-1", 7200);
        outcomes.add(new HttpConnectTimeoutException("not connected"));
        assertFalse(send().unknown());
        respond(200, "{\"errcode\":43101,\"errmsg\":\"" + TEST_SECRET + "\"}");
        WxProvider.SendResult rejected = send();
        assertEquals(43101, rejected.errcode());
        assertEquals("WECHAT_ERROR_43101", rejected.errmsg());
        assertFalse(rejected.unknown());
        assertEquals(3, requests.size());
    }

    @Test
    void invalidCredentialResponseCannotCauseASubscriptionRequest() {
        for (String result :
                List.of(
                        "{\"errcode\":40164,\"errmsg\":\"" + TEST_SECRET + "\"}",
                        "{\"access_token\":\"token-1\",\"expires_in\":\"7200\"}",
                        "{\"access_token\":\"token-1\",\"expires_in\":0}")) {
            respond(200, result);
            WxProvider.SendResult failure = send();
            assertEquals(-1, failure.errcode());
            assertFalse(failure.unknown());
            assertFalse(failure.errmsg().contains(TEST_SECRET));
        }
        assertEquals(3, requests.size());
        assertTrue(
                requests.stream()
                        .allMatch(
                                request ->
                                        request.uri().getPath().equals("/cgi-bin/stable_token")));
    }

    @Test
    void malformedTemplateAndExternalPageFailBeforeAnyHttpRequest() {
        for (Map<String, Object> invalid :
                List.of(
                        Map.<String, Object>of(),
                        Map.<String, Object>of("thing1", Map.of("value", "培".repeat(21))),
                        Map.<String, Object>of("thing1", Map.of("value", "培训\n提醒")),
                        Map.<String, Object>of("time2", Map.of("value", "2026-02-30 12:00")),
                        Map.<String, Object>of("name1", Map.of("value", "培训")))) {
            assertEquals(47003, provider.send("openid", "template", invalid, PAGE).errcode());
        }
        assertEquals(
                47003,
                provider.send("openid", "template", CONTENT, "https://example.com").errcode());
        assertEquals(
                47003,
                provider.send("openid", "template", CONTENT, "javascript:alert(1)").errcode());
        assertTrue(requests.isEmpty());
    }

    @Test
    void concurrentFirstSendsShareOneOrdinaryTokenFetch() throws Exception {
        token("token-shared", 7200);
        respond(200, "{\"errcode\":0}");
        respond(200, "{\"errcode\":0}");
        CountDownLatch start = new CountDownLatch(1);
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        2,
                        2,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(2),
                        runnable -> new Thread(runnable, "wechat-transport-unit-test"),
                        new ThreadPoolExecutor.AbortPolicy());
        try {
            Future<WxProvider.SendResult> first =
                    executor.submit(
                            () -> {
                                start.await();
                                return send();
                            });
            Future<WxProvider.SendResult> second =
                    executor.submit(
                            () -> {
                                start.await();
                                return send();
                            });
            start.countDown();
            assertEquals(0, first.get(3, TimeUnit.SECONDS).errcode());
            assertEquals(0, second.get(3, TimeUnit.SECONDS).errcode());
            assertEquals(
                    1,
                    requests.stream()
                            .filter(
                                    request ->
                                            request.uri().getPath().equals("/cgi-bin/stable_token"))
                            .count());
            assertEquals(3, requests.size());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptedSendRestoresThreadFlagAndPreservesUnknownOutcome() {
        token("token-1", 7200);
        outcomes.add(new InterruptedException("interrupted after request was dispatched"));
        try {
            assertTrue(send().unknown());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(2, requests.size());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void optionalProviderMessageIdPreservesFullIntegerPrecision() {
        token("token-1", 7200);
        respond(200, "{\"errcode\":0,\"MsgID\":1700827132819554304}");
        assertEquals("1700827132819554304", send().msgid());
        respond(200, "{\"errcode\":0,\"msgid\":\"1864323726461255680\"}");
        assertEquals("1864323726461255680", send().msgid());
    }

    @Test
    void callbackRemainsFailClosedWhenOnlyCoreLoginIsConfigured() {
        assertFalse(provider.callbackConfigured());
        assertThrows(
                BusinessException.class, () -> provider.verifyEcho(Map.of("echostr", "anything")));
        assertThrows(
                BusinessException.class,
                () -> provider.decodeCallback(Map.of(), "<xml><Encrypt>x</Encrypt></xml>"));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThrows(
                IllegalStateException.class,
                () ->
                        new WxProvider(
                                environment, "real", APPLICATION_ID, TEST_SECRET, "token", ""));
        assertThrows(
                IllegalStateException.class,
                () ->
                        new WxProvider(
                                environment,
                                "real",
                                APPLICATION_ID,
                                TEST_SECRET,
                                "token",
                                "!".repeat(43)));
    }

    private WxProvider.SendResult send() {
        return provider.send("person-openid", "template-1", CONTENT, PAGE);
    }

    private void token(String value, int seconds) {
        respond(200, "{\"access_token\":\"" + value + "\",\"expires_in\":" + seconds + "}");
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, String text) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(text);
        outcomes.add(response);
    }

    private Map<String, Object> body(HttpRequest request) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new Flow.Subscriber<ByteBuffer>() {
                            @Override
                            public void onSubscribe(Flow.Subscription subscription) {
                                subscription.request(Long.MAX_VALUE);
                            }

                            @Override
                            public void onNext(ByteBuffer item) {
                                byte[] chunk = new byte[item.remaining()];
                                item.get(chunk);
                                bytes.writeBytes(chunk);
                            }

                            @Override
                            public void onError(Throwable error) {
                                complete.completeExceptionally(error);
                            }

                            @Override
                            public void onComplete() {
                                complete.complete(null);
                            }
                        });
        complete.get(1, TimeUnit.SECONDS);
        return Data.parse(bytes.toString(StandardCharsets.UTF_8));
    }
}
