package com.yongsen.training;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JSON boundary helpers. Domain validations remain at service boundaries. */
public final class Data {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private Data() {}

    public static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    public static String str(Map<String, Object> value, String key) {
        return value.get(key) == null ? "" : String.valueOf(value.get(key));
    }

    public static String required(Map<String, Object> value, String key) {
        String result = str(value, key).trim();
        if (result.isEmpty() || result.length() > 4096) {
            throw new BusinessException("INVALID_INPUT", key + " is required or too long", 400);
        }
        return result;
    }

    public static long num(Map<String, Object> value, String key, long fallback) {
        Object raw = value.get(key);
        if (raw == null) {
            return fallback;
        }
        if (!(raw instanceof Number)) {
            throw new BusinessException("INVALID_INPUT", key + " must be numeric", 400);
        }
        return ((Number) raw).longValue();
    }

    public static double decimal(Map<String, Object> value, String key, double fallback) {
        Object raw = value.get(key);
        return raw instanceof Number ? ((Number) raw).doubleValue() : fallback;
    }

    public static boolean bool(Map<String, Object> value, String key) {
        return Boolean.TRUE.equals(value.get(key));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object value) {
        return value instanceof List ? (List<Object>) value : new ArrayList<>();
    }

    public static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize application data", exception);
        }
    }

    public static String contentHash(Object value) {
        return hash(json(canonical(value)));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?>) {
            Map<String, Object> sorted = new java.util.TreeMap<>();
            obj(value).forEach((key, item) -> sorted.put(key, canonical(item)));
            return sorted;
        }
        if (value instanceof List<?>) {
            return list(value).stream().map(Data::canonical).toList();
        }
        return value;
    }

    public static Object readAny(String value) {
        try {
            return MAPPER.readValue(value, Object.class);
        } catch (JsonProcessingException exception) {
            throw new BusinessException("INVALID_JSON", "Invalid JSON document", 400);
        }
    }

    public static Map<String, Object> parse(String value) {
        try {
            return MAPPER.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new BusinessException("INVALID_JSON", "Invalid JSON document", 400);
        }
    }

    public static String id() {
        return UUID.randomUUID().toString();
    }

    public static String token() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) {
        return hash(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public static String now() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
    }

    public static void require(boolean condition, String code, String message) {
        if (!condition) {
            throw new BusinessException(code, message, 422);
        }
    }
}
