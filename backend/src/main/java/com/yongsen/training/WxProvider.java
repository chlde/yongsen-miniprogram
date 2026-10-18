package com.yongsen.training;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** WeChat's transport and callback protocol. No business completion state is changed here. */
@Service
public class WxProvider {
    private static final int MAX_CALLBACK_BYTES = 256 * 1024;
    private static final int MAX_TOKEN_LIFETIME_SECONDS = 7200;
    private static final long FORCE_REFRESH_INTERVAL_MS = 30000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String STABLE_TOKEN_ENDPOINT =
            "https://api.weixin.qq.com/cgi-bin/stable_token";
    private final String providerMode;
    private final String applicationId;
    private final String applicationSecret;
    private final String callbackToken;
    private final String callbackAesKey;
    private final String miniProgramState;
    private final HttpClient http;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private String accessToken = "";
    private long tokenExpiresAt;
    private long nextForcedRefreshAt;

    public record SendResult(int errcode, String errmsg, String msgid, boolean unknown) {}

    @Autowired
    public WxProvider(
            Environment environment,
            @Value("${training.wechat-mode}") String mode,
            @Value("${training.appid}") String appid,
            @Value("${training.app-secret:}") String secret,
            @Value("${training.callback-token:}") String token,
            @Value("${training.callback-aes-key:}") String aesKey) {
        this(
                environment,
                mode,
                appid,
                secret,
                token,
                aesKey,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC());
    }

    WxProvider(
            Environment environment,
            String mode,
            String appid,
            String secret,
            String token,
            String aesKey,
            HttpClient http,
            Clock clock) {
        this.providerMode = mode;
        this.applicationId = appid;
        this.applicationSecret = secret;
        this.callbackToken = token;
        this.callbackAesKey = aesKey;
        this.miniProgramState = environment.getProperty("WECHAT_MINIPROGRAM_STATE", "formal");
        if (!List.of("formal", "trial", "developer").contains(miniProgramState)) {
            throw new IllegalStateException(
                    "WECHAT_MINIPROGRAM_STATE must be formal, trial or developer");
        }
        this.http = http;
        this.clock = clock;
        if (!List.of("mock", "real").contains(mode)) {
            throw new IllegalStateException("WECHAT_MODE must be mock or real");
        }
        if ("mock".equals(mode) && !environment.matchesProfiles("local", "test")) {
            throw new IllegalStateException("Mock WeChat is restricted to local/test profiles");
        }
        if ("real".equals(mode) && (appid.isBlank() || secret.isBlank())) {
            throw new IllegalStateException("Real WeChat requires AppID and secret");
        }
        if (!token.isBlank() || !aesKey.isBlank()) {
            if (token.isBlank() || !aesKey.matches("[A-Za-z0-9+/]{43}")) {
                throw new IllegalStateException(
                        "WeChat callbacks require token and a valid AES key together");
            }
        }
    }

    public String mode() {
        return providerMode;
    }

    public String appid() {
        return applicationId;
    }

    public boolean callbackConfigured() {
        return !callbackToken.isBlank() && !callbackAesKey.isBlank();
    }

    public boolean templateConfigured(String id) {
        return id != null
                && !id.isBlank()
                && (!"real".equals(providerMode) || !id.startsWith("mock-"));
    }

    public String login(String code) {
        Data.require(
                code != null && !code.isBlank() && code.length() <= 4096,
                "INVALID_WECHAT_CODE",
                "微信登录凭证无效，请重新登录");
        if ("mock".equals(providerMode)) {
            Data.require(
                    code.matches("mock:[A-Za-z0-9_-]{1,100}"),
                    "LOCAL_IDENTITY_REQUIRED",
                    "本地模式请使用 mock:姓名标识 登录");
            return "local_" + Data.hash(code);
        }
        Data.require(!code.startsWith("mock:"), "INVALID_WECHAT_CODE", "真实微信模式不接受本地身份");
        String query =
                "appid="
                        + encode(applicationId)
                        + "&secret="
                        + encode(applicationSecret)
                        + "&js_code="
                        + encode(code)
                        + "&grant_type=authorization_code";
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create("https://api.weixin.qq.com/sns/jscode2session?" + query))
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
        // The protocol requires credentials in this query. Never log the request, code, or provider
        // body.
        JsonNode result = exchangeJson(request);
        Integer error = errorCode(result, false);
        if (error == null) {
            throw unavailable();
        }
        if (error != 0) {
            throw loginFailure(error);
        }
        JsonNode openid = result.path("openid");
        if (!openid.isTextual() || !openid.asText().matches("[A-Za-z0-9_-]{1,128}")) {
            throw unavailable();
        }
        // Session keys and unionids are intentionally not returned to the application or client.
        return openid.asText();
    }

    public SendResult send(
            String openid, String templateId, Map<String, Object> data, String page) {
        if ("mock".equals(providerMode)) {
            return new SendResult(0, "LOCAL_SIMULATION", "mock-" + Data.id(), false);
        }
        if (!validMessage(openid, templateId, data, page)) {
            return new SendResult(47003, "NOTIFICATION_CONFIG_INVALID", "", false);
        }
        String token;
        try {
            token = token(null);
        } catch (BusinessException exception) {
            return tokenFailure(exception);
        }
        SendResult result = sendOnce(openid, templateId, data, page, token);
        // A retry is safe only after an explicit rejection of the credential, never after UNKNOWN.
        if (!result.unknown() && List.of(40001, 40014, 42001).contains(result.errcode())) {
            try {
                token = token(token);
            } catch (BusinessException exception) {
                return tokenFailure(exception);
            }
            result = sendOnce(openid, templateId, data, page, token);
        }
        return result;
    }

    private SendResult sendOnce(
            String openid, String templateId, Map<String, Object> data, String page, String token) {
        String body =
                Data.json(
                        Data.map(
                                "touser",
                                openid,
                                "template_id",
                                templateId,
                                "page",
                                page,
                                "miniprogram_state",
                                miniProgramState,
                                "lang",
                                "zh_CN",
                                "data",
                                data));
        HttpRequest request =
                HttpRequest.newBuilder(
                                URI.create(
                                        "https://api.weixin.qq.com/cgi-bin/message/subscribe/send?access_token="
                                                + encode(token)))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > MAX_CALLBACK_BYTES) {
                return new SendResult(-1, "HTTP_RESULT_UNKNOWN", "", true);
            }
            JsonNode result = mapper.readTree(response.body());
            Integer error = errorCode(result, true);
            if (error == null) {
                return new SendResult(-1, "PROVIDER_RESULT_UNKNOWN", "", true);
            }
            // The documented success response has no msgid. Do not manufacture a delivery key.
            JsonNode messageId = result.has("msgid") ? result.path("msgid") : result.path("MsgID");
            String correlatedId =
                    messageId.isTextual() || messageId.isIntegralNumber() ? messageId.asText() : "";
            if (correlatedId.length() > 128) {
                correlatedId = "";
            }
            return new SendResult(
                    error, error == 0 ? "ok" : "WECHAT_ERROR_" + error, correlatedId, false);
        } catch (HttpConnectTimeoutException exception) {
            return new SendResult(-1, "CONNECT_TIMEOUT", "", false);
        } catch (IOException exception) {
            return new SendResult(-1, "TRANSPORT_RESULT_UNKNOWN", "", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new SendResult(-1, "INTERRUPTED_RESULT_UNKNOWN", "", true);
        }
    }

    private synchronized String token(String rejectedToken) {
        long now = clock.millis();
        if (!accessToken.isEmpty() && !accessToken.equals(rejectedToken) && now < tokenExpiresAt) {
            return accessToken;
        }
        if (accessToken.equals(rejectedToken)) {
            tokenExpiresAt = 0;
        }
        JsonNode result = requestToken(false);
        String value = result.path("access_token").asText();
        if (value.equals(rejectedToken)) {
            // Ordinary mode is preferred. Force only when WeChat reissues an explicitly rejected
            // token.
            if (now < nextForcedRefreshAt) {
                throw new BusinessException("WECHAT_TOKEN_REFRESH_LIMITED", "微信凭据刷新频繁，请稍后重试", 503);
            }
            nextForcedRefreshAt = now + FORCE_REFRESH_INTERVAL_MS;
            result = requestToken(true);
            value = result.path("access_token").asText();
            if (value.equals(rejectedToken)) {
                throw new BusinessException("WECHAT_TOKEN_UNAVAILABLE", "微信凭据暂不可用", 503);
            }
        }
        long remaining = result.path("expires_in").asLong();
        accessToken = value;
        tokenExpiresAt = clock.millis() + (remaining - Math.min(120, remaining / 10)) * 1000;
        return value;
    }

    private JsonNode requestToken(boolean forceRefresh) {
        String body =
                Data.json(
                        Data.map(
                                "grant_type", "client_credential",
                                "appid", applicationId,
                                "secret", applicationSecret,
                                "force_refresh", forceRefresh));
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(STABLE_TOKEN_ENDPOINT))
                        .timeout(REQUEST_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        JsonNode result = exchangeJson(request);
        Integer error = errorCode(result, false);
        JsonNode value = result.path("access_token");
        JsonNode lifetime = result.path("expires_in");
        if (error == null
                || error != 0
                || !value.isTextual()
                || value.asText().isBlank()
                || !lifetime.isIntegralNumber()
                || !lifetime.canConvertToInt()
                || lifetime.asInt() <= 0
                || lifetime.asInt() > MAX_TOKEN_LIFETIME_SECONDS) {
            throw new BusinessException(
                    "WECHAT_TOKEN_UNAVAILABLE",
                    "微信凭据暂不可用",
                    503,
                    error == null ? Map.of() : Map.of("providerErrcode", error));
        }
        return result;
    }

    private JsonNode exchangeJson(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > MAX_CALLBACK_BYTES) {
                throw unavailable();
            }
            JsonNode result = mapper.readTree(response.body());
            if (result == null || !result.isObject()) {
                throw unavailable();
            }
            return result;
        } catch (IOException exception) {
            // Provider exception text can contain the credential-bearing request URI; discard the
            // cause.
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException("WECHAT_UNAVAILABLE", "微信请求已中断", 503);
        }
    }

    private Integer errorCode(JsonNode result, boolean required) {
        if (result == null || !result.isObject()) {
            return null;
        }
        JsonNode error = result.get("errcode");
        if (error == null) {
            return required ? null : 0;
        }
        return error.isIntegralNumber() && error.canConvertToInt() ? error.asInt() : null;
    }

    private BusinessException loginFailure(int code) {
        Map<String, Object> details = Map.of("providerErrcode", code);
        return switch (code) {
            case 40029, 40163 ->
                    new BusinessException("WECHAT_LOGIN_FAILED", "微信登录凭证已失效，请重新登录", 401, details);
            case 40226 ->
                    new BusinessException("WECHAT_LOGIN_BLOCKED", "微信暂时限制此账号登录", 403, details);
            case 45011, 45009 ->
                    new BusinessException("WECHAT_RATE_LIMITED", "微信登录繁忙，请稍后重试", 429, details);
            default -> new BusinessException("WECHAT_UNAVAILABLE", "微信登录服务暂不可用", 503, details);
        };
    }

    private BusinessException unavailable() {
        return new BusinessException("WECHAT_UNAVAILABLE", "微信服务暂不可用", 503);
    }

    private SendResult tokenFailure(BusinessException exception) {
        Object code = exception.details().get("providerErrcode");
        String reason = code instanceof Integer ? "TOKEN_UNAVAILABLE_" + code : "TOKEN_UNAVAILABLE";
        return new SendResult(-1, reason, "", false);
    }

    private boolean validMessage(
            String openid, String templateId, Map<String, Object> data, String page) {
        if (openid == null
                || !openid.matches("[A-Za-z0-9_-]{1,128}")
                || templateId == null
                || !templateId.matches("[A-Za-z0-9_-]{1,256}")
                || page == null
                || page.length() > 1024
                || !page.matches("pages/[A-Za-z0-9_/-]+(?:\\?[A-Za-z0-9_=&%.-]+)?")
                || data == null
                || data.isEmpty()
                || data.size() > 5) {
            return false;
        }
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> content)
                    || content.size() != 1
                    || !(content.get("value") instanceof String value)
                    || value.isBlank()
                    || value.codePoints().anyMatch(Character::isISOControl)) {
                return false;
            }
            String key = entry.getKey();
            if (key == null) {
                return false;
            }
            // The MVP maps a task title and an optional deadline; no arbitrary template types are
            // emitted.
            if (key.matches("thing[0-9]+")) {
                if (value.codePointCount(0, value.length()) > 20) {
                    return false;
                }
            } else if (key.matches("time[0-9]+")) {
                if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}")) {
                    return false;
                }
                try {
                    java.time.LocalDateTime.parse(
                            value,
                            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
                                    .withResolverStyle(java.time.format.ResolverStyle.STRICT));
                } catch (java.time.format.DateTimeParseException exception) {
                    return false;
                }
            } else {
                return false;
            }
        }
        return true;
    }

    public String verifyEcho(Map<String, String> query) {
        String echo = query.getOrDefault("echostr", "");
        if (query.containsKey("msg_signature")) {
            verifySignature(query, echo);
            return decrypt(echo);
        }
        verifySignature(query, null);
        return echo;
    }

    public Map<String, Object> decodeCallback(Map<String, String> query, String body) {
        Data.require(
                body != null && body.getBytes(StandardCharsets.UTF_8).length <= MAX_CALLBACK_BYTES,
                "CALLBACK_INVALID",
                "回调内容无效");
        Map<String, Object> envelope = parsePayload(body);
        String encrypted = Data.str(envelope, "Encrypt");
        if (!encrypted.isEmpty()) {
            verifySignature(query, encrypted);
            return parsePayload(decrypt(encrypted));
        }
        // Real deployments use the encrypted callback configured for this mini-program.
        Data.require(!"real".equals(providerMode), "CALLBACK_ENCRYPTION_REQUIRED", "需要加密回调");
        verifySignature(query, null);
        return envelope;
    }

    private void verifySignature(Map<String, String> query, String encrypted) {
        String timestamp = query.getOrDefault("timestamp", "");
        String nonce = query.getOrDefault("nonce", "");
        String supplied = query.getOrDefault(encrypted == null ? "signature" : "msg_signature", "");
        if (callbackToken.isBlank()
                || !timestamp.matches("[0-9]{1,15}")
                || nonce.isEmpty()
                || nonce.length() > 256
                || !supplied.matches("[a-fA-F0-9]{40}")) {
            throw new BusinessException("CALLBACK_SIGNATURE_INVALID", "回调校验失败", 403);
        }
        try {
            String[] parts =
                    encrypted == null
                            ? new String[] {callbackToken, timestamp, nonce}
                            : new String[] {callbackToken, timestamp, nonce, encrypted};
            Arrays.sort(parts);
            // SHA-1 is mandated by the WeChat wire protocol, not used for stored credentials.
            byte[] digest =
                    MessageDigest.getInstance("SHA-1")
                            .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(digest, HexFormat.of().parseHex(supplied))) {
                throw new BusinessException("CALLBACK_SIGNATURE_INVALID", "回调校验失败", 403);
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("WeChat signature algorithm unavailable", exception);
        }
    }

    private String decrypt(String encrypted) {
        try {
            byte[] key = Base64.getDecoder().decode(callbackAesKey + "=");
            if (key.length != 32) {
                throw new GeneralSecurityException("Invalid key length");
            }
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            // The provider protocol defines this IV; this code only decrypts provider messages.
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(Arrays.copyOf(key, 16)));
            byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            if (padded.length < 32) {
                throw new GeneralSecurityException("Invalid envelope");
            }
            int padding = padded[padded.length - 1] & 0xff;
            if (padding < 1 || padding > 32 || padding > padded.length) {
                throw new GeneralSecurityException("Invalid padding");
            }
            for (int index = padded.length - padding; index < padded.length; index++) {
                if ((padded[index] & 0xff) != padding) {
                    throw new GeneralSecurityException("Invalid padding");
                }
            }
            int limit = padded.length - padding;
            int length = ByteBuffer.wrap(padded, 16, 4).getInt();
            if (length < 0 || length > limit - 20) {
                throw new GeneralSecurityException("Invalid message length");
            }
            String receiver =
                    new String(padded, 20 + length, limit - 20 - length, StandardCharsets.UTF_8);
            if (!applicationId.equals(receiver)) {
                throw new GeneralSecurityException("Wrong receiving application");
            }
            return new String(padded, 20, length, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BusinessException("CALLBACK_DECRYPT_FAILED", "回调解密校验失败", 403);
        }
    }

    private Map<String, Object> parsePayload(String body) {
        if (body.stripLeading().startsWith("{")) {
            return Data.parse(body);
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return elementMap(
                    factory.newDocumentBuilder()
                            .parse(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                            .getDocumentElement());
        } catch (Exception exception) {
            throw new BusinessException("CALLBACK_INVALID_XML", "回调格式无效", 400);
        }
    }

    private Map<String, Object> elementMap(Element parent) {
        Map<String, Object> result = new LinkedHashMap<>();
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (!(child instanceof Element element)) {
                continue;
            }
            boolean nested = false;
            for (int childIndex = 0;
                    childIndex < element.getChildNodes().getLength();
                    childIndex++) {
                nested |= element.getChildNodes().item(childIndex) instanceof Element;
            }
            Object value = nested ? elementMap(element) : element.getTextContent();
            String name = element.getTagName();
            if (result.containsKey(name)) {
                Object previous = result.get(name);
                List<Object> values =
                        previous instanceof List<?> list
                                ? new ArrayList<>(list)
                                : new ArrayList<>(List.of(previous));
                values.add(value);
                result.put(name, values);
            } else {
                result.put(name, value);
            }
        }
        return result;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
