package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class AuthServiceWechatTest {
    private Store store;
    private WxProvider provider;
    private TextEncryptor encryptor;
    private MockEnvironment environment;
    private AuthService service;

    @BeforeEach
    void setup() {
        DriverManagerDataSource datasource =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + Data.id()
                                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(datasource);
        store = new Store(new JdbcTemplate(datasource));
        provider = mock(WxProvider.class);
        when(provider.appid()).thenReturn("wx-unit-test-app");
        when(provider.mode()).thenReturn("real");
        encryptor = Encryptors.delux("unit-test-encryption-password", "1234567890abcdef");
        environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        service = new AuthService(store, provider, encryptor, environment, "LocalTrain123!");
    }

    @Test
    void loginCreatesAnIndependentServerSessionAndNeverTrustsAClientOpenid() {
        when(provider.login("one-use-code")).thenReturn("provider-openid");
        Map<String, Object> result =
                service.wechatLogin(
                        Map.of(
                                "code",
                                "one-use-code",
                                "openid",
                                "attacker-openid",
                                "role",
                                "ADMIN"),
                        "127.0.0.1");
        verify(provider).login("one-use-code");
        assertEquals("LEARNER", result.get("role"));
        assertEquals("UNBOUND", result.get("bindingStatus"));
        assertNull(result.get("person"));
        String token = Data.str(result, "accessToken");
        assertTrue(token.length() >= 40);
        assertNotEquals("one-use-code", token);
        assertNotEquals("provider-openid", token);
        assertFalse(Data.json(result).contains("openid"));
        assertFalse(Data.json(result).contains("session_key"));
        assertTrue(Instant.parse(Data.str(result, "expiresAt")).isAfter(Instant.now()));
        List<StoredRecordDO> rows = store.all(Store.Table.AUTH_TOKEN);
        assertEquals(1, rows.size());
        StoredRecordDO stored = rows.get(0);
        assertEquals(Data.hash(token), stored.uniqueKey());
        Map<String, Object> persisted = stored.toBoundary();
        assertEquals(Data.hash("wx-unit-test-app:provider-openid"), persisted.get("actorId"));
        assertEquals("provider-openid", encryptor.decrypt(Data.str(persisted, "encryptedOpenid")));
        assertFalse(Data.json(persisted).contains("provider-openid"));
        assertFalse(Data.json(persisted).contains("one-use-code"));
        assertFalse(Data.json(persisted).contains(token));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        assertEquals(persisted.get("actorId"), service.actor(request).get("actorId"));
    }

    @Test
    void repeatedLoginProducesDistinctTokensForTheSameApplicationScopedIdentity() {
        when(provider.login("code-1")).thenReturn("provider-openid");
        when(provider.login("code-2")).thenReturn("provider-openid");
        Map<String, Object> first = service.wechatLogin(Map.of("code", "code-1"), "127.0.0.1");
        Map<String, Object> second = service.wechatLogin(Map.of("code", "code-2"), "127.0.0.1");
        assertNotEquals(first.get("accessToken"), second.get("accessToken"));
        List<StoredRecordDO> rows = store.all(Store.Table.AUTH_TOKEN);
        assertEquals(2, rows.size());
        assertEquals(rows.get(0).referenceId(), rows.get(1).referenceId());
        when(provider.appid()).thenReturn("wx-different-app");
        service.wechatLogin(Map.of("code", "code-2"), "127.0.0.1");
        assertEquals(
                2,
                store.all(Store.Table.AUTH_TOKEN).stream()
                        .map(StoredRecordDO::referenceId)
                        .distinct()
                        .count());
    }

    @Test
    void rejectedWechatLoginCannotCreateALocalSession() {
        when(provider.login("expired-code"))
                .thenThrow(new BusinessException("WECHAT_LOGIN_FAILED", "微信登录凭证已失效，请重新登录", 401));
        assertThrows(
                BusinessException.class,
                () -> service.wechatLogin(Map.of("code", "expired-code"), "127.0.0.1"));
        assertTrue(store.all(Store.Table.AUTH_TOKEN).isEmpty());
        assertTrue(store.all(Store.Table.WX_IDENTITY_BINDING).isEmpty());
    }

    @Test
    void realProviderInLocalProfileDoesNotCreateAFakeSubscriptionTemplate() {
        service.run(null);
        assertEquals(
                "",
                store.get(Store.Table.APP_CONFIG, "notification").toBoundary().get("templateId"));
    }

    @Test
    void actualMockProviderMayInitializeItsExplicitlyLocalTemplate() {
        when(provider.mode()).thenReturn("mock");
        service.run(null);
        assertEquals(
                "mock-training-template",
                store.get(Store.Table.APP_CONFIG, "notification").toBoundary().get("templateId"));
    }

    @Test
    void existingTemplateConfigurationIsNeverOverwrittenOnStartup() {
        store.create(
                Store.Table.APP_CONFIG,
                Data.map("id", "notification", "templateId", "configured-real-template"),
                null,
                null,
                "ACTIVE",
                "notification");
        service.run(null);
        assertEquals(
                "configured-real-template",
                store.get(Store.Table.APP_CONFIG, "notification").toBoundary().get("templateId"));
    }
}
