package com.yongsen.training;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class WxProviderTest {
    private static final String TEST_APP_ID = "wx1234567890abcdef";
    private static final String TEST_TOKEN = "only-a-unit-test-callback-token";
    private static final String TEST_KEY =
            Base64.getEncoder()
                    .withoutPadding()
                    .encodeToString(
                            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));

    @Test
    void mockIdentityIsExplicitAndUnavailableInProduction() {
        WxProvider local = provider("local", "mock");
        assertEquals(local.login("mock:alice"), local.login("mock:alice"));
        assertFalse(local.login("mock:alice").equals(local.login("mock:bob")));
        assertThrows(BusinessException.class, () -> local.login("a-real-looking-code"));
        assertThrows(IllegalStateException.class, () -> provider("prod", "mock"));
    }

    @Test
    void realProviderCannotReuseAnOldMockTemplate() {
        WxProvider real = provider("prod", "real");
        assertFalse(real.templateConfigured(null));
        assertFalse(real.templateConfigured(""));
        assertFalse(real.templateConfigured("  "));
        assertFalse(real.templateConfigured("mock-training-template"));
        assertFalse(real.templateConfigured("mock-"));
        assertTrue(real.templateConfigured("approved-template-id"));
    }

    @Test
    void mockProviderRecognizesOnlyNonBlankTemplates() {
        WxProvider local = provider("local", "mock");
        assertFalse(local.templateConfigured(null));
        assertFalse(local.templateConfigured(""));
        assertFalse(local.templateConfigured("  "));
        assertTrue(local.templateConfigured("mock-training-template"));
        assertTrue(local.templateConfigured("template-1"));
    }

    @Test
    void aMockSendIsLabelledAndHasAnIndependentProviderId() {
        WxProvider local = provider("local", "mock");
        WxProvider.SendResult sent =
                local.send("local-openid", "template", Map.of(), "pages/tasks/index");
        assertEquals(0, sent.errcode());
        assertEquals("LOCAL_SIMULATION", sent.errmsg());
        assertTrue(sent.msgid().startsWith("mock-"));
        assertFalse(sent.unknown());
    }

    @Test
    void officialEncryptedEnvelopeAuthenticatesAndPreservesMessageId() throws Exception {
        String xml =
                "<xml><Event>subscribe_msg_sent_event</Event><FromUserName>person-openid</FromUserName>"
                    + "<SubscribeMsgSentEvent><List><MsgID>12345678901234567890</MsgID><ErrorCode>0</ErrorCode>"
                    + "</List></SubscribeMsgSentEvent></xml>";
        String encrypted = encrypt(xml, TEST_APP_ID);
        Map<String, Object> decoded =
                provider("prod", "real").decodeCallback(query(encrypted), wrap(encrypted));
        assertEquals("subscribe_msg_sent_event", decoded.get("Event"));
        assertEquals(
                "12345678901234567890",
                Data.obj(Data.obj(decoded.get("SubscribeMsgSentEvent")).get("List")).get("MsgID"));
    }

    @Test
    void wrongApplicationCannotReuseAnOtherwiseValidCallback() throws Exception {
        String encrypted =
                encrypt(
                        "<xml><Event>subscribe_msg_sent_event</Event></xml>",
                        "wx-other-application");
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () ->
                                provider("prod", "real")
                                        .decodeCallback(query(encrypted), wrap(encrypted)));
        assertEquals("CALLBACK_DECRYPT_FAILED", exception.code());
    }

    @Test
    void invalidSignatureDoesNotPermitParsingOrStateChanges() throws Exception {
        String encrypted =
                encrypt("<xml><Event>subscribe_msg_sent_event</Event></xml>", TEST_APP_ID);
        Map<String, String> tampered =
                Map.of("timestamp", "1700000000", "nonce", "test", "msg_signature", "0".repeat(40));
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () -> provider("prod", "real").decodeCallback(tampered, wrap(encrypted)));
        assertEquals("CALLBACK_SIGNATURE_INVALID", exception.code());
    }

    @Test
    void repeatedListItemsRemainAListAndSingletonRemainsAnObject() throws Exception {
        String xml =
                "<xml><Event>subscribe_msg_popup_event</Event><SubscribeMsgPopupEvent>"
                    + "<List><TemplateId>one</TemplateId></List><List><TemplateId>two</TemplateId></List>"
                    + "</SubscribeMsgPopupEvent></xml>";
        String encrypted = encrypt(xml, TEST_APP_ID);
        Map<String, Object> decoded =
                provider("prod", "real").decodeCallback(query(encrypted), wrap(encrypted));
        Object items = Data.obj(decoded.get("SubscribeMsgPopupEvent")).get("List");
        assertTrue(items instanceof List<?>);
        assertEquals(2, ((List<?>) items).size());
    }

    @Test
    void doctypeAndExternalEntitiesAreRejectedEvenWithValidSignature() throws Exception {
        String xml =
                "<!DOCTYPE xml [<!ENTITY unsafe SYSTEM"
                        + " 'file:///etc/passwd'>]><xml><Event>&unsafe;</Event></xml>";
        String encrypted = encrypt(xml, TEST_APP_ID);
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () ->
                                provider("prod", "real")
                                        .decodeCallback(query(encrypted), wrap(encrypted)));
        assertEquals("CALLBACK_INVALID_XML", exception.code());
    }

    @Test
    void realCallbackCannotSilentlyFallBackToPlaintext() throws Exception {
        Map<String, String> signed = query(null);
        BusinessException exception =
                assertThrows(
                        BusinessException.class,
                        () ->
                                provider("prod", "real")
                                        .decodeCallback(
                                                signed,
                                                "<xml><Event>subscribe_msg_sent_event</Event></xml>"));
        assertEquals("CALLBACK_ENCRYPTION_REQUIRED", exception.code());
    }

    @Test
    void echoValidationUsesTheConfiguredToken() throws Exception {
        Map<String, String> signed = new java.util.HashMap<>(query(null));
        signed.put("echostr", "verification-value");
        assertEquals("verification-value", provider("local", "mock").verifyEcho(signed));
        signed.put("signature", "f".repeat(40));
        assertThrows(BusinessException.class, () -> provider("local", "mock").verifyEcho(signed));
    }

    private WxProvider provider(String profile, String mode) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return new WxProvider(
                environment, mode, TEST_APP_ID, "unit-test-secret", TEST_TOKEN, TEST_KEY);
    }

    private String wrap(String encrypted) {
        return "<xml><Encrypt><![CDATA[" + encrypted + "]]></Encrypt></xml>";
    }

    private Map<String, String> query(String encrypted) throws Exception {
        String timestamp = "1700000000";
        String nonce = "test";
        String[] fields =
                encrypted == null
                        ? new String[] {TEST_TOKEN, timestamp, nonce}
                        : new String[] {TEST_TOKEN, timestamp, nonce, encrypted};
        Arrays.sort(fields);
        String signature =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-1")
                                        .digest(
                                                String.join("", fields)
                                                        .getBytes(StandardCharsets.UTF_8)));
        return Map.of(
                "timestamp",
                timestamp,
                "nonce",
                nonce,
                encrypted == null ? "signature" : "msg_signature",
                signature);
    }

    /** Builds a provider-protocol fixture using the standard JCE implementation. */
    private String encrypt(String text, String appid) throws Exception {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        byte[] application = appid.getBytes(StandardCharsets.UTF_8);
        int rawLength = 20 + content.length + application.length;
        int padding = 32 - rawLength % 32;
        byte[] envelope = new byte[rawLength + padding];
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        System.arraycopy(random, 0, envelope, 0, 16);
        ByteBuffer.wrap(envelope, 16, 4).putInt(content.length);
        System.arraycopy(content, 0, envelope, 20, content.length);
        System.arraycopy(application, 0, envelope, 20 + content.length, application.length);
        Arrays.fill(envelope, rawLength, envelope.length, (byte) padding);
        byte[] key = Base64.getDecoder().decode(TEST_KEY + "=");
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(key, "AES"),
                new IvParameterSpec(Arrays.copyOf(key, 16)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(envelope));
    }
}
